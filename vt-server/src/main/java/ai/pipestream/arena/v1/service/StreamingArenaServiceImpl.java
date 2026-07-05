package ai.pipestream.arena.v1.service;

import ai.pipestream.arena.v1.model.MatchStatistics;
import ai.pipestream.arena.v1.repository.StreamStatsRepository;
import ai.pipestream.arena.v1.util.GameLogic;
import ai.pipestream.tourney.stream.v1.*;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streaming gRPC service — virtual-threads variant.
 *
 * A bidirectional stream is inherently {@code Multi -> Multi} at the wire; gRPC's
 * streaming model does not change between the reactive and virtual-thread builds,
 * and neither does the in-memory match orchestration (it is plain synchronous Java
 * in both). The ONE difference from :mutiny-server is persistence: instead of a
 * reactive {@code Panache.withTransaction(...)} chain, the single per-match write is
 * a plain blocking {@code @Transactional} call ({@link StreamStatsRepository}),
 * offloaded to a virtual thread so it never blocks the reactive emitter thread.
 *
 * Takeaway for the tutorial: streaming is dominated by connection state, not the
 * database, so the reactive-vs-VT difference here is small — but where the database
 * IS touched, the VT version is ordinary blocking code.
 */
@GrpcService
@Singleton
public class StreamingArenaServiceImpl implements StreamingArenaService {

    private static final Logger LOG = Logger.getLogger(StreamingArenaServiceImpl.class);

    // Rounds per match. Default 1000; the tournament runner and CI override it
    // (arena.total-rounds) to trade sample size for wall-clock.
    @ConfigProperty(name = "arena.total-rounds", defaultValue = "1000")
    int totalRounds;

    @Inject
    StreamStatsRepository streamStats;

    // In-memory state: The connection IS the context
    private final ConcurrentHashMap<String, StreamMatch> activeMatches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamPlayer> waitingPlayers = new ConcurrentHashMap<>();

    @Override
    public Multi<BattleResponse> battle(Multi<BattleRequest> request) {
        String connectionId = UUID.randomUUID().toString();
        LOG.infof("New streaming connection: %s", connectionId);

        BroadcastProcessor<BattleResponse> processor = BroadcastProcessor.create();
        StreamPlayer player = new StreamPlayer(connectionId, processor);

        // Process incoming messages
        request.subscribe().with(
                message -> handleClientMessage(player, message),
                failure -> {
                    LOG.errorf("Stream error for %s: %s", connectionId, failure.getMessage());
                    cleanupPlayer(player);
                },
                () -> {
                    LOG.infof("Stream completed for %s", connectionId);
                    cleanupPlayer(player);
                }
        );

        return processor;
    }

    @Override
    @RunOnVirtualThread
    public Uni<ArenaResultsResponse> getArenaResults(ArenaResultsRequest request) {
        // Blocking read of every completed streaming match on a virtual thread,
        // aggregated by language. (StreamStatsRepository.allStreamingMatches is
        // a plain @Transactional query.)
        return Uni.createFrom().item(aggregate(streamStats.allStreamingMatches()));
    }

    // Accumulator per language: [matches, roundWins, roundLosses, ties, rocks, papers, scissors].
    private ArenaResultsResponse aggregate(List<MatchStatistics> all) {
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (MatchStatistics s : all) {
            credit(acc, s.playerOneLanguage, s.playerOneWins, s.playerTwoWins, s.ties,
                    s.playerOneRocks, s.playerOnePapers, s.playerOneScissors);
            credit(acc, s.playerTwoLanguage, s.playerTwoWins, s.playerOneWins, s.ties,
                    s.playerTwoRocks, s.playerTwoPapers, s.playerTwoScissors);
        }

        List<LanguageResult> results = new ArrayList<>();
        for (Map.Entry<String, long[]> e : acc.entrySet()) {
            long[] a = e.getValue();
            long matches = a[0], wins = a[1], losses = a[2], ties = a[3];
            long rocks = a[4], papers = a[5], scissors = a[6];
            long moves = rocks + papers + scissors;
            double winRate = (wins + losses) == 0 ? 0.0 : (double) wins / (wins + losses);
            double rockPct = moves == 0 ? 0.0 : 100.0 * rocks / moves;
            double paperPct = moves == 0 ? 0.0 : 100.0 * papers / moves;
            double scissorsPct = moves == 0 ? 0.0 : 100.0 * scissors / moves;
            double bias = Math.max(rockPct, Math.max(paperPct, scissorsPct)) - (100.0 / 3.0);
            results.add(LanguageResult.newBuilder()
                    .setLanguage(e.getKey())
                    .setMatchesPlayed(matches).setWins(wins).setLosses(losses).setTies(ties)
                    .setWinRate(winRate)
                    .setRocks(rocks).setPapers(papers).setScissors(scissors)
                    .setRockPct(rockPct).setPaperPct(paperPct).setScissorsPct(scissorsPct)
                    .setMoveBiasPct(bias)
                    .build());
        }
        results.sort((x, y) -> Double.compare(y.getWinRate(), x.getWinRate()));
        return ArenaResultsResponse.newBuilder()
                .setTotalMatches(all.size())
                .addAllLanguages(results)
                .build();
    }

    private static void credit(Map<String, long[]> acc, String language,
                               long wins, long losses, long ties,
                               long rocks, long papers, long scissors) {
        String key = (language == null || language.isEmpty()) ? "unknown" : language;
        long[] a = acc.computeIfAbsent(key, k -> new long[7]);
        a[0]++; a[1] += wins; a[2] += losses; a[3] += ties;
        a[4] += rocks; a[5] += papers; a[6] += scissors;
    }

    private void handleClientMessage(StreamPlayer player, BattleRequest message) {
        if (message.hasHandshake()) {
            handleHandshake(player, message.getHandshake());
        } else if (message.hasMove()) {
            handleMove(player, message.getMove());
        }
    }

    private void handleHandshake(StreamPlayer player, Handshake handshake) {
        player.languageName = handshake.getLanguageName();
        player.prngAlgorithm = handshake.getPrngAlgorithm();

        LOG.infof("Handshake from %s: %s (%s)",
                player.connectionId, player.languageName, player.prngAlgorithm);

        // Send connection confirmation
        player.processor.onNext(BattleResponse.newBuilder()
                .setStatus("CONNECTED")
                .build());

        // Try to find an opponent
        tryMatchPlayers(player);
    }

    private void tryMatchPlayers(StreamPlayer player) {
        StreamPlayer opponent = null;
        synchronized (waitingPlayers) {
            if (!waitingPlayers.isEmpty()) {
                String waitingId = waitingPlayers.keySet().iterator().next();
                opponent = waitingPlayers.remove(waitingId);
            } else {
                waitingPlayers.put(player.connectionId, player);
                return;
            }
        }

        if (opponent != null) {
            createMatch(player, opponent);
        }
    }

    private void createMatch(StreamPlayer playerOne, StreamPlayer playerTwo) {
        String matchId = UUID.randomUUID().toString();
        StreamMatch match = new StreamMatch(matchId, playerOne, playerTwo);

        activeMatches.put(matchId, match);
        playerOne.matchId = matchId;
        playerTwo.matchId = matchId;

        LOG.infof("Match created: %s - %s vs %s",
                matchId, playerOne.languageName, playerTwo.languageName);

        playerOne.processor.onNext(BattleResponse.newBuilder()
                .setStatus("OPPONENT_FOUND: " + playerTwo.languageName)
                .build());

        playerTwo.processor.onNext(BattleResponse.newBuilder()
                .setStatus("OPPONENT_FOUND: " + playerOne.languageName)
                .build());

        startNextRound(match);
    }

    private void startNextRound(StreamMatch match) {
        if (match.currentRound > totalRounds) {
            completeMatch(match);
            return;
        }

        match.playerOneMoveReceived = false;
        match.playerTwoMoveReceived = false;
        match.playerOneMove = -1;
        match.playerTwoMove = -1;

        RequestMove trigger = RequestMove.newBuilder()
                .setRoundId(match.currentRound)
                .build();

        match.playerOne.processor.onNext(BattleResponse.newBuilder()
                .setTrigger(trigger)
                .build());

        match.playerTwo.processor.onNext(BattleResponse.newBuilder()
                .setTrigger(trigger)
                .build());
    }

    private void handleMove(StreamPlayer player, Move move) {
        if (player.matchId == null) {
            LOG.warnf("Move received before match assignment from %s", player.connectionId);
            return;
        }

        StreamMatch match = activeMatches.get(player.matchId);
        if (match == null) {
            LOG.warnf("Match not found: %s", player.matchId);
            return;
        }

        if (!GameLogic.isValidMove(move.getMove())) {
            LOG.warnf("Invalid move from %s: %d", player.connectionId, move.getMove());
            return;
        }

        synchronized (match) {
            if (player == match.playerOne) {
                match.playerOneMove = move.getMove();
                match.playerOneMoveReceived = true;
                updateMoveStats(match.stats.playerOneStats, move.getMove());
            } else {
                match.playerTwoMove = move.getMove();
                match.playerTwoMoveReceived = true;
                updateMoveStats(match.stats.playerTwoStats, move.getMove());
            }

            if (match.playerOneMoveReceived && match.playerTwoMoveReceived) {
                processRound(match);
            }
        }
    }

    private void updateMoveStats(PlayerStats stats, int move) {
        switch (move) {
            case GameLogic.ROCK: stats.rocks++; break;
            case GameLogic.PAPER: stats.papers++; break;
            case GameLogic.SCISSORS: stats.scissors++; break;
        }
    }

    private void processRound(StreamMatch match) {
        String outcome = GameLogic.determineWinner(match.playerOneMove, match.playerTwoMove);

        if ("PLAYER_ONE_WIN".equals(outcome)) {
            match.stats.playerOneStats.wins++;
        } else if ("PLAYER_TWO_WIN".equals(outcome)) {
            match.stats.playerTwoStats.wins++;
        } else {
            match.stats.ties++;
        }

        RoundResult resultP1 = RoundResult.newBuilder()
                .setRoundId(match.currentRound)
                .setOpponentMove(match.playerTwoMove)
                .setOutcome(GameLogic.outcomeForPlayer(outcome, true))
                .build();

        RoundResult resultP2 = RoundResult.newBuilder()
                .setRoundId(match.currentRound)
                .setOpponentMove(match.playerOneMove)
                .setOutcome(GameLogic.outcomeForPlayer(outcome, false))
                .build();

        match.playerOne.processor.onNext(BattleResponse.newBuilder().setResult(resultP1).build());
        match.playerTwo.processor.onNext(BattleResponse.newBuilder().setResult(resultP2).build());

        match.currentRound++;
        startNextRound(match);
    }

    private void completeMatch(StreamMatch match) {
        match.completedAt = Instant.now();
        long durationMillis = java.time.Duration.between(match.startedAt, match.completedAt).toMillis();

        LOG.infof("Match %s completed: %s=%d, %s=%d, Ties=%d, Duration=%dms",
                match.matchId,
                match.playerOne.languageName, match.stats.playerOneStats.wins,
                match.playerTwo.languageName, match.stats.playerTwoStats.wins,
                match.stats.ties, durationMillis);

        match.playerOne.processor.onNext(BattleResponse.newBuilder().setStatus("MATCH_COMPLETE").build());
        match.playerTwo.processor.onNext(BattleResponse.newBuilder().setStatus("MATCH_COMPLETE").build());

        // Persist statistics on a virtual thread: plain blocking @Transactional JDBC,
        // kept off the reactive emitter thread. (Reactive variant uses Panache.withTransaction.)
        persistStatsOnVirtualThread(match, durationMillis);

        activeMatches.remove(match.matchId);
        match.playerOne.processor.onComplete();
        match.playerTwo.processor.onComplete();
    }

    private void persistStatsOnVirtualThread(StreamMatch match, long durationMillis) {
        MatchStatistics stats = new MatchStatistics();
        stats.matchId = match.matchId;
        stats.matchType = "STREAMING";
        stats.playerOneName = match.playerOne.languageName + " (" + match.playerOne.prngAlgorithm + ")";
        stats.playerTwoName = match.playerTwo.languageName + " (" + match.playerTwo.prngAlgorithm + ")";
        stats.playerOneLanguage = match.playerOne.languageName;
        stats.playerTwoLanguage = match.playerTwo.languageName;

        stats.playerOneRocks = match.stats.playerOneStats.rocks;
        stats.playerOnePapers = match.stats.playerOneStats.papers;
        stats.playerOneScissors = match.stats.playerOneStats.scissors;
        stats.playerOneWins = match.stats.playerOneStats.wins;

        stats.playerTwoRocks = match.stats.playerTwoStats.rocks;
        stats.playerTwoPapers = match.stats.playerTwoStats.papers;
        stats.playerTwoScissors = match.stats.playerTwoStats.scissors;
        stats.playerTwoWins = match.stats.playerTwoStats.wins;

        stats.ties = match.stats.ties;
        stats.totalRounds = totalRounds;
        stats.durationMillis = durationMillis;
        stats.roundsPerSecond = durationMillis == 0 ? 0.0 : (totalRounds * 1000.0) / durationMillis;
        stats.databaseIops = 1L; // Only one write for the entire match
        stats.createdAt = Instant.now();
        stats.calculateDistributions();
        stats.seedCollisionDetected = detectSeedCollision(match);

        LOG.infof("Streaming match stats saving: RPS=%.2f, P1 Bias=%.2f%%, P2 Bias=%.2f%%",
                stats.roundsPerSecond, stats.playerOneBias, stats.playerTwoBias);

        Thread.ofVirtual().name("stream-stats-" + match.matchId).start(() -> {
            try {
                streamStats.save(stats);
                LOG.info("Streaming match stats saved successfully");
            } catch (Exception e) {
                LOG.errorf("Failed to save statistics: %s", e.getMessage());
            }
        });
    }

    private boolean detectSeedCollision(StreamMatch match) {
        PlayerStats p1 = match.stats.playerOneStats;
        PlayerStats p2 = match.stats.playerTwoStats;

        int rockDiff = Math.abs(p1.rocks - p2.rocks);
        int paperDiff = Math.abs(p1.papers - p2.papers);
        int scissorsDiff = Math.abs(p1.scissors - p2.scissors);

        return rockDiff < 5 && paperDiff < 5 && scissorsDiff < 5;
    }

    private void cleanupPlayer(StreamPlayer player) {
        waitingPlayers.remove(player.connectionId);

        if (player.matchId != null) {
            StreamMatch match = activeMatches.get(player.matchId);
            if (match != null) {
                LOG.warnf("Player %s disconnected from match %s",
                        player.connectionId, player.matchId);
                activeMatches.remove(player.matchId);

                StreamPlayer opponent = (player == match.playerOne) ? match.playerTwo : match.playerOne;
                opponent.processor.onNext(BattleResponse.newBuilder()
                        .setStatus("OPPONENT_DISCONNECTED")
                        .build());
                opponent.processor.onComplete();
            }
        }
    }

    // Inner classes for match state
    private static class StreamMatch {
        final String matchId;
        final StreamPlayer playerOne;
        final StreamPlayer playerTwo;
        final Instant startedAt;
        Instant completedAt;

        int currentRound = 1;
        boolean playerOneMoveReceived = false;
        boolean playerTwoMoveReceived = false;
        int playerOneMove = -1;
        int playerTwoMove = -1;

        final MatchStats stats = new MatchStats();

        StreamMatch(String matchId, StreamPlayer playerOne, StreamPlayer playerTwo) {
            this.matchId = matchId;
            this.playerOne = playerOne;
            this.playerTwo = playerTwo;
            this.startedAt = Instant.now();
        }
    }

    private static class StreamPlayer {
        final String connectionId;
        final BroadcastProcessor<BattleResponse> processor;
        String matchId;
        String languageName;
        String prngAlgorithm;

        StreamPlayer(String connectionId, BroadcastProcessor<BattleResponse> processor) {
            this.connectionId = connectionId;
            this.processor = processor;
        }
    }

    private static class MatchStats {
        final PlayerStats playerOneStats = new PlayerStats();
        final PlayerStats playerTwoStats = new PlayerStats();
        int ties = 0;
    }

    private static class PlayerStats {
        int rocks = 0;
        int papers = 0;
        int scissors = 0;
        int wins = 0;
    }
}
