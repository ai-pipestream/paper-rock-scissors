package ai.pipestream.arena.netty;

import ai.pipestream.arena.v1.util.GameLogic;
import ai.pipestream.tourney.stream.v1.ArenaResultsRequest;
import ai.pipestream.tourney.stream.v1.ArenaResultsResponse;
import ai.pipestream.tourney.stream.v1.BattleRequest;
import ai.pipestream.tourney.stream.v1.BattleResponse;
import ai.pipestream.tourney.stream.v1.Handshake;
import ai.pipestream.tourney.stream.v1.LanguageResult;
import ai.pipestream.tourney.stream.v1.Move;
import ai.pipestream.tourney.stream.v1.RequestMove;
import ai.pipestream.tourney.stream.v1.RoundResult;
import ai.pipestream.tourney.stream.v1.StreamingArenaServiceGrpc;
import io.grpc.stub.StreamObserver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The exact same streaming arena as {@code mutiny-server} / {@code vt-server}, but on
 * the raw grpc-java API — a hand-written {@link StreamObserver} instead of Mutiny
 * {@code Multi} or {@code @RunOnVirtualThread}. The matchmaking + round logic is a
 * line-for-line port of the reactive version so the wire behaviour is identical.
 *
 * <p>Two deliberate simplifications versus the Quarkus builds, both to keep this a clean
 * transport benchmark with zero external dependencies:
 * <ul>
 *   <li>the language leaderboard is aggregated <b>in memory</b> (a per-language counter)
 *       rather than persisted to PostgreSQL — so {@code GetArenaResults} still works, but
 *       there is no DB write per match. Run the Quarkus servers to measure the DB cost.</li>
 *   <li>no round-creation persistence, so the class is inherently free of the
 *       round-race that needed a pessimistic lock in the DB-backed servers.</li>
 * </ul>
 */
public class StreamingArenaServiceImpl extends StreamingArenaServiceGrpc.StreamingArenaServiceImplBase {

    private final int totalRounds;

    private final ConcurrentHashMap<String, StreamMatch> activeMatches = new ConcurrentHashMap<>();
    private final Deque<StreamPlayer> waiting = new ArrayDeque<>();
    private final Object waitingLock = new Object();

    // Replaces the database the Quarkus servers use. key = language name ->
    // [matches, roundWins, roundLosses, ties, rocks, papers, scissors].
    private final ConcurrentHashMap<String, long[]> leaderboard = new ConcurrentHashMap<>();
    private final AtomicLong totalMatches = new AtomicLong();

    public StreamingArenaServiceImpl(int totalRounds) {
        this.totalRounds = totalRounds;
    }

    @Override
    public StreamObserver<BattleRequest> battle(StreamObserver<BattleResponse> responseObserver) {
        StreamPlayer player = new StreamPlayer(UUID.randomUUID().toString(), responseObserver);
        return new StreamObserver<>() {
            @Override
            public void onNext(BattleRequest msg) {
                if (msg.hasHandshake()) {
                    handleHandshake(player, msg.getHandshake());
                } else if (msg.hasMove()) {
                    handleMove(player, msg.getMove());
                }
            }

            @Override
            public void onError(Throwable t) {
                cleanup(player);
            }

            @Override
            public void onCompleted() {
                cleanup(player);
            }
        };
    }

    @Override
    public void getArenaResults(ArenaResultsRequest request,
                                StreamObserver<ArenaResultsResponse> responseObserver) {
        ArenaResultsResponse.Builder resp = ArenaResultsResponse.newBuilder()
                .setTotalMatches(totalMatches.get());

        List<LanguageResult> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : leaderboard.entrySet()) {
            long[] a = e.getValue();
            long matches, wins, losses, ties, rocks, papers, scissors;
            synchronized (a) {
                matches = a[0]; wins = a[1]; losses = a[2]; ties = a[3];
                rocks = a[4]; papers = a[5]; scissors = a[6];
            }
            long moves = rocks + papers + scissors;
            double winRate = (wins + losses) == 0 ? 0.0 : (double) wins / (wins + losses);
            double rockPct = moves == 0 ? 0.0 : 100.0 * rocks / moves;
            double paperPct = moves == 0 ? 0.0 : 100.0 * papers / moves;
            double scissorsPct = moves == 0 ? 0.0 : 100.0 * scissors / moves;
            double bias = Math.max(rockPct, Math.max(paperPct, scissorsPct)) - (100.0 / 3.0);
            rows.add(LanguageResult.newBuilder()
                    .setLanguage(e.getKey())
                    .setMatchesPlayed(matches).setWins(wins).setLosses(losses).setTies(ties)
                    .setWinRate(winRate)
                    .setRocks(rocks).setPapers(papers).setScissors(scissors)
                    .setRockPct(rockPct).setPaperPct(paperPct).setScissorsPct(scissorsPct)
                    .setMoveBiasPct(bias)
                    .build());
        }
        rows.sort((x, y) -> Double.compare(y.getWinRate(), x.getWinRate()));
        resp.addAllLanguages(rows);

        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    // ---- matchmaking ------------------------------------------------------

    private void handleHandshake(StreamPlayer player, Handshake handshake) {
        player.language = handshake.getLanguageName();
        player.prng = handshake.getPrngAlgorithm();
        player.send(BattleResponse.newBuilder().setStatus("CONNECTED").build());

        StreamPlayer opponent;
        synchronized (waitingLock) {
            if (waiting.isEmpty()) {
                waiting.add(player);
                return;
            }
            opponent = waiting.poll();
        }
        // FIFO: the player who was already waiting is player one.
        createMatch(opponent, player);
    }

    private void createMatch(StreamPlayer one, StreamPlayer two) {
        String matchId = UUID.randomUUID().toString();
        StreamMatch match = new StreamMatch(matchId, one, two);
        activeMatches.put(matchId, match);
        one.matchId = matchId;
        two.matchId = matchId;

        one.send(BattleResponse.newBuilder().setStatus("OPPONENT_FOUND: " + two.language).build());
        two.send(BattleResponse.newBuilder().setStatus("OPPONENT_FOUND: " + one.language).build());

        startNextRound(match);
    }

    private void startNextRound(StreamMatch match) {
        if (match.currentRound > totalRounds) {
            completeMatch(match);
            return;
        }
        match.oneMoveReceived = false;
        match.twoMoveReceived = false;
        match.oneMove = -1;
        match.twoMove = -1;

        RequestMove trigger = RequestMove.newBuilder().setRoundId(match.currentRound).build();
        match.one.send(BattleResponse.newBuilder().setTrigger(trigger).build());
        match.two.send(BattleResponse.newBuilder().setTrigger(trigger).build());
    }

    private void handleMove(StreamPlayer player, Move move) {
        if (player.matchId == null) {
            return;
        }
        StreamMatch match = activeMatches.get(player.matchId);
        if (match == null || !GameLogic.isValidMove(move.getMove())) {
            return;
        }

        synchronized (match) {
            if (player == match.one) {
                match.oneMove = move.getMove();
                match.oneMoveReceived = true;
                tally(match.s1, move.getMove());
            } else {
                match.twoMove = move.getMove();
                match.twoMoveReceived = true;
                tally(match.s2, move.getMove());
            }
            if (match.oneMoveReceived && match.twoMoveReceived) {
                processRound(match);
            }
        }
    }

    private static void tally(PlayerStats stats, int move) {
        switch (move) {
            case GameLogic.ROCK -> stats.rocks++;
            case GameLogic.PAPER -> stats.papers++;
            case GameLogic.SCISSORS -> stats.scissors++;
            default -> { /* unreachable: validated in handleMove */ }
        }
    }

    private void processRound(StreamMatch match) {
        String outcome = GameLogic.determineWinner(match.oneMove, match.twoMove);
        if ("PLAYER_ONE_WIN".equals(outcome)) {
            match.s1.wins++;
        } else if ("PLAYER_TWO_WIN".equals(outcome)) {
            match.s2.wins++;
        } else {
            match.ties++;
        }

        match.one.send(BattleResponse.newBuilder().setResult(RoundResult.newBuilder()
                .setRoundId(match.currentRound)
                .setOpponentMove(match.twoMove)
                .setOutcome(GameLogic.outcomeForPlayer(outcome, true))
                .build()).build());
        match.two.send(BattleResponse.newBuilder().setResult(RoundResult.newBuilder()
                .setRoundId(match.currentRound)
                .setOpponentMove(match.oneMove)
                .setOutcome(GameLogic.outcomeForPlayer(outcome, false))
                .build()).build());

        match.currentRound++;
        startNextRound(match);
    }

    private void completeMatch(StreamMatch match) {
        match.one.send(BattleResponse.newBuilder().setStatus("MATCH_COMPLETE").build());
        match.two.send(BattleResponse.newBuilder().setStatus("MATCH_COMPLETE").build());

        credit(match.one.language, match.s1, match.s2, match.ties);
        credit(match.two.language, match.s2, match.s1, match.ties);
        totalMatches.incrementAndGet();

        activeMatches.remove(match.matchId);
        match.one.complete();
        match.two.complete();
    }

    /** Fold one player's match result into the leaderboard (losses = opponent's round wins). */
    private void credit(String language, PlayerStats self, PlayerStats opponent, int ties) {
        String key = (language == null || language.isEmpty()) ? "unknown" : language;
        long[] a = leaderboard.computeIfAbsent(key, k -> new long[7]);
        synchronized (a) {
            a[0]++;
            a[1] += self.wins;
            a[2] += opponent.wins;
            a[3] += ties;
            a[4] += self.rocks;
            a[5] += self.papers;
            a[6] += self.scissors;
        }
    }

    private void cleanup(StreamPlayer player) {
        synchronized (waitingLock) {
            waiting.remove(player);
        }
        if (player.matchId == null) {
            return;
        }
        StreamMatch match = activeMatches.remove(player.matchId);
        if (match == null) {
            return;
        }
        StreamPlayer opponent = (player == match.one) ? match.two : match.one;
        opponent.send(BattleResponse.newBuilder().setStatus("OPPONENT_DISCONNECTED").build());
        opponent.complete();
    }

    // ---- state ------------------------------------------------------------

    /** Wraps a client's response stream; {@code send}/{@code complete} are serialized
     *  because grpc {@link StreamObserver} is not thread-safe and both players' inbound
     *  threads can drive one observer. */
    private static final class StreamPlayer {
        final String id;
        final StreamObserver<BattleResponse> out;
        volatile String matchId;
        volatile String language;
        volatile String prng;

        StreamPlayer(String id, StreamObserver<BattleResponse> out) {
            this.id = id;
            this.out = out;
        }

        synchronized void send(BattleResponse r) {
            out.onNext(r);
        }

        synchronized void complete() {
            try {
                out.onCompleted();
            } catch (RuntimeException ignore) {
                // stream already closed by the peer — nothing to do.
            }
        }
    }

    private static final class StreamMatch {
        final String matchId;
        final StreamPlayer one;
        final StreamPlayer two;
        int currentRound = 1;
        boolean oneMoveReceived;
        boolean twoMoveReceived;
        int oneMove = -1;
        int twoMove = -1;
        final PlayerStats s1 = new PlayerStats();
        final PlayerStats s2 = new PlayerStats();
        int ties;

        StreamMatch(String matchId, StreamPlayer one, StreamPlayer two) {
            this.matchId = matchId;
            this.one = one;
            this.two = two;
        }
    }

    private static final class PlayerStats {
        int rocks;
        int papers;
        int scissors;
        int wins;
    }
}
