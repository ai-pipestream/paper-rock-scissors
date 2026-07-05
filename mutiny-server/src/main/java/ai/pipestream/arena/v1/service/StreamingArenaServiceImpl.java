package ai.pipestream.arena.v1.service;

import ai.pipestream.arena.v1.model.MatchStatistics;
import ai.pipestream.arena.v1.util.GameLogic;
import ai.pipestream.tourney.stream.v1.*;
import io.quarkus.grpc.GrpcService;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streaming gRPC Service implementation.
 * Fully reactive using Mutiny and Hibernate Reactive.
 */
@GrpcService
@Singleton
public class StreamingArenaServiceImpl implements StreamingArenaService {
    
    private static final Logger LOG = Logger.getLogger(StreamingArenaServiceImpl.class);
    private static final int TOTAL_ROUNDS = 1000;
    
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
        // Look for a waiting player
        StreamPlayer opponent = null;
        synchronized (waitingPlayers) {
            if (!waitingPlayers.isEmpty()) {
                // Get first waiting player
                String waitingId = waitingPlayers.keySet().iterator().next();
                opponent = waitingPlayers.remove(waitingId);
            } else {
                // No opponent available, add to waiting
                waitingPlayers.put(player.connectionId, player);
                return;
            }
        }
        
        // Create match
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
        
        // Notify both players
        playerOne.processor.onNext(BattleResponse.newBuilder()
            .setStatus("OPPONENT_FOUND: " + playerTwo.languageName)
            .build());
        
        playerTwo.processor.onNext(BattleResponse.newBuilder()
            .setStatus("OPPONENT_FOUND: " + playerOne.languageName)
            .build());
        
        // Start the first round
        startNextRound(match);
    }
    
    private void startNextRound(StreamMatch match) {
        if (match.currentRound > TOTAL_ROUNDS) {
            completeMatch(match);
            return;
        }
        
        match.playerOneMoveReceived = false;
        match.playerTwoMoveReceived = false;
        match.playerOneMove = -1;
        match.playerTwoMove = -1;
        
        // Send "Pulse" to both players requesting a move
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
        
        // Record the move
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
            
            // Check if both moves received
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
        // Determine winner
        String outcome = GameLogic.determineWinner(match.playerOneMove, match.playerTwoMove);
        
        // Update statistics
        if ("PLAYER_ONE_WIN".equals(outcome)) {
            match.stats.playerOneStats.wins++;
        } else if ("PLAYER_TWO_WIN".equals(outcome)) {
            match.stats.playerTwoStats.wins++;
        } else {
            match.stats.ties++;
        }
        
        // Send results to both players
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
        
        match.playerOne.processor.onNext(BattleResponse.newBuilder()
            .setResult(resultP1)
            .build());
        
        match.playerTwo.processor.onNext(BattleResponse.newBuilder()
            .setResult(resultP2)
            .build());
        
        // Move to next round
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
        
        // Send completion message
        match.playerOne.processor.onNext(BattleResponse.newBuilder()
            .setStatus("MATCH_COMPLETE")
            .build());
        
        match.playerTwo.processor.onNext(BattleResponse.newBuilder()
            .setStatus("MATCH_COMPLETE")
            .build());
        
        // Save statistics to database (Reactive)
        saveStreamingStatistics(match, durationMillis).subscribe().with(
            v -> LOG.info("Streaming match stats saved successfully"),
            e -> LOG.errorf("Failed to save statistics: %s", e.getMessage())
        );
        
        // Cleanup
        activeMatches.remove(match.matchId);
        match.playerOne.processor.onComplete();
        match.playerTwo.processor.onComplete();
    }
    
    private Uni<Void> saveStreamingStatistics(StreamMatch match, long durationMillis) {
        MatchStatistics stats = new MatchStatistics();
        stats.matchId = match.matchId;
        stats.matchType = "STREAMING";
        stats.playerOneName = match.playerOne.languageName + " (" + match.playerOne.prngAlgorithm + ")";
        stats.playerTwoName = match.playerTwo.languageName + " (" + match.playerTwo.prngAlgorithm + ")";
        
        stats.playerOneRocks = match.stats.playerOneStats.rocks;
        stats.playerOnePapers = match.stats.playerOneStats.papers;
        stats.playerOneScissors = match.stats.playerOneStats.scissors;
        stats.playerOneWins = match.stats.playerOneStats.wins;
        
        stats.playerTwoRocks = match.stats.playerTwoStats.rocks;
        stats.playerTwoPapers = match.stats.playerTwoStats.papers;
        stats.playerTwoScissors = match.stats.playerTwoStats.scissors;
        stats.playerTwoWins = match.stats.playerTwoStats.wins;
        
        stats.ties = match.stats.ties;
        stats.totalRounds = TOTAL_ROUNDS;
        stats.durationMillis = durationMillis;
        stats.roundsPerSecond = (TOTAL_ROUNDS * 1000.0) / durationMillis;
        stats.databaseIops = 1L; // Only one write for the entire match
        stats.createdAt = Instant.now();
        
        stats.calculateDistributions();
        
        // Detect seed collision (identical sequences)
        stats.seedCollisionDetected = detectSeedCollision(match);
        
        LOG.infof("Streaming match stats saving: RPS=%.2f, P1 Bias=%.2f%%, P2 Bias=%.2f%%",
            stats.roundsPerSecond, stats.playerOneBias, stats.playerTwoBias);
        
        return Panache.withTransaction(stats::persist).replaceWithVoid();
    }
    
    private boolean detectSeedCollision(StreamMatch match) {
        // Simple heuristic: if move distributions are too similar, might indicate collision
        PlayerStats p1 = match.stats.playerOneStats;
        PlayerStats p2 = match.stats.playerTwoStats;
        
        int rockDiff = Math.abs(p1.rocks - p2.rocks);
        int paperDiff = Math.abs(p1.papers - p2.papers);
        int scissorsDiff = Math.abs(p1.scissors - p2.scissors);
        
        // If all differences are very small (< 5), possible collision
        return rockDiff < 5 && paperDiff < 5 && scissorsDiff < 5;
    }
    
    private void cleanupPlayer(StreamPlayer player) {
        waitingPlayers.remove(player.connectionId);
        
        if (player.matchId != null) {
            StreamMatch match = activeMatches.get(player.matchId);
            if (match != null) {
                // Player disconnected mid-match
                LOG.warnf("Player %s disconnected from match %s", 
                    player.connectionId, player.matchId);
                activeMatches.remove(player.matchId);
                
                // Notify opponent
                StreamPlayer opponent = (player == match.playerOne) ? 
                    match.playerTwo : match.playerOne;
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