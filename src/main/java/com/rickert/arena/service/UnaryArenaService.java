package com.rickert.arena.service;

import com.rickert.arena.model.UnaryMatch;
import com.rickert.arena.model.UnaryRound;
import com.rickert.arena.model.MatchStatistics;
import com.rickert.arena.util.GameLogic;
import com.rickert.tourney.unary.*;
import io.quarkus.grpc.GrpcService;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unary gRPC Service implementation.
 * Demonstrates the "painful" polling approach with database state management.
 */
@GrpcService
@ApplicationScoped
public class UnaryArenaService implements UnaryArena {
    
    private static final Logger LOG = Logger.getLogger(UnaryArenaService.class);
    private final AtomicLong dbIopsCounter = new AtomicLong(0);
    
    @Override
    public Uni<RegistrationResponse> register(RegistrationRequest request) {
        LOG.infof("Registration request from: %s (%s)", 
            request.getLanguageName(), request.getPrngAlgorithm());
        
        return Panache.withTransaction(() -> {
            dbIopsCounter.incrementAndGet(); // SELECT for waiting matches
            return UnaryMatch.findWaitingMatches()
                .onItem().transformToUni(waitingMatches -> {
                    if (!waitingMatches.isEmpty()) {
                        // Join an existing match
                        UnaryMatch match = waitingMatches.get(0);
                        match.playerTwoName = request.getLanguageName();
                        match.playerTwoPrng = request.getPrngAlgorithm();
                        match.status = UnaryMatch.MatchStatus.READY;
                        match.startedAt = Instant.now();
                        
                        dbIopsCounter.incrementAndGet(); // UPDATE match
                        return match.persistAndFlush()
                            .onItem().transform(m -> RegistrationResponse.newBuilder()
                                .setMatchId(match.matchId)
                                .setOpponentName(match.playerOneName)
                                .setStatus("READY")
                                .build());
                    } else {
                        // Create a new match
                        UnaryMatch newMatch = new UnaryMatch();
                        newMatch.matchId = UUID.randomUUID().toString();
                        newMatch.playerOneName = request.getLanguageName();
                        newMatch.playerOnePrng = request.getPrngAlgorithm();
                        newMatch.status = UnaryMatch.MatchStatus.WAITING_FOR_OPPONENT;
                        newMatch.createdAt = Instant.now();
                        newMatch.currentRound = 1;
                        
                        dbIopsCounter.incrementAndGet(); // INSERT match
                        return newMatch.persistAndFlush()
                            .onItem().transform(m -> RegistrationResponse.newBuilder()
                                .setMatchId(newMatch.matchId)
                                .setOpponentName("")
                                .setStatus("WAITING_FOR_OPPONENT")
                                .build());
                    }
                });
        });
    }
    
    @Override
    public Uni<MoveResponse> submitMove(MoveRequest request) {
        return Panache.withTransaction(() -> {
            dbIopsCounter.incrementAndGet(); // SELECT match
            return UnaryMatch.findByMatchId(request.getMatchId())
                .onItem().ifNull().failWith(() -> new IllegalArgumentException("Match not found"))
                .onItem().transformToUni(match -> {
                    if (match.status == UnaryMatch.MatchStatus.COMPLETED) {
                        return Uni.createFrom().item(MoveResponse.newBuilder()
                            .setStatus("GAME_OVER")
                            .build());
                    }
                    
                    if (request.getRoundNumber() != match.currentRound) {
                        return Uni.createFrom().item(MoveResponse.newBuilder()
                            .setStatus("INVALID_TURN")
                            .build());
                    }
                    
                    if (!GameLogic.isValidMove(request.getMove())) {
                        return Uni.createFrom().item(MoveResponse.newBuilder()
                            .setStatus("INVALID_TURN")
                            .build());
                    }
                    
                    dbIopsCounter.incrementAndGet(); // SELECT round
                    return UnaryRound.findByMatchAndRound(request.getMatchId(), request.getRoundNumber())
                        .onItem().ifNull().continueWith(() -> {
                            // Create new round
                            UnaryRound round = new UnaryRound();
                            round.matchId = request.getMatchId();
                            round.roundNumber = request.getRoundNumber();
                            round.createdAt = Instant.now();
                            round.status = UnaryRound.RoundStatus.WAITING_PLAYER_TWO;
                            return round;
                        })
                        .onItem().transformToUni(round -> {
                            // Determine if this is player one or two based on existing moves
                            if (round.playerOneMove == null) {
                                round.playerOneMove = request.getMove();
                                round.status = UnaryRound.RoundStatus.WAITING_PLAYER_TWO;
                            } else if (round.playerTwoMove == null) {
                                round.playerTwoMove = request.getMove();
                                round.status = UnaryRound.RoundStatus.COMPLETE;
                                round.completedAt = Instant.now();
                                
                                // Calculate outcome
                                round.outcome = GameLogic.determineWinner(
                                    round.playerOneMove, round.playerTwoMove);
                                
                                // Update match statistics
                                return updateMatchStats(match, round)
                                    .onItem().transformToUni(v -> {
                                        dbIopsCounter.incrementAndGet(); // UPDATE round
                                        return round.persistAndFlush();
                                    });
                            }
                            
                            dbIopsCounter.incrementAndGet(); // INSERT/UPDATE round
                            return round.persistAndFlush();
                        })
                        .onItem().transform(r -> MoveResponse.newBuilder()
                            .setStatus("ACCEPTED")
                            .build());
                });
        });
    }
    
    @Override
    public Uni<ResultResponse> checkRoundResult(ResultRequest request) {
        dbIopsCounter.incrementAndGet(); // SELECT round
        return UnaryRound.findByMatchAndRound(request.getMatchId(), request.getRoundNumber())
            .onItem().ifNull().continueWith(() -> {
                // Round doesn't exist yet
                return ResultResponse.newBuilder()
                    .setStatus("PENDING")
                    .setOpponentMove(-1)
                    .setOutcome("")
                    .build();
            })
            .onItem().transform(round -> {
                if (round.status != UnaryRound.RoundStatus.COMPLETE) {
                    return ResultResponse.newBuilder()
                        .setStatus("PENDING")
                        .setOpponentMove(-1)
                        .setOutcome("")
                        .build();
                }
                
                // Determine which player is requesting and provide opponent's move
                // For simplicity, we'll return both moves and outcome
                return ResultResponse.newBuilder()
                    .setStatus("COMPLETE")
                    .setOpponentMove(round.playerTwoMove != null ? round.playerTwoMove : round.playerOneMove)
                    .setOutcome(round.outcome)
                    .build();
            });
    }
    
    private Uni<Void> updateMatchStats(UnaryMatch match, UnaryRound round) {
        // Update match statistics
        if ("PLAYER_ONE_WIN".equals(round.outcome)) {
            match.playerOneWins++;
        } else if ("PLAYER_TWO_WIN".equals(round.outcome)) {
            match.playerTwoWins++;
        } else {
            match.ties++;
        }
        
        // Check if match is complete
        if (match.currentRound >= match.totalRounds) {
            match.status = UnaryMatch.MatchStatus.COMPLETED;
            match.completedAt = Instant.now();
            
            // Create statistics record
            return saveMatchStatistics(match);
        } else {
            match.currentRound++;
        }
        
        dbIopsCounter.incrementAndGet(); // UPDATE match
        return match.persistAndFlush().replaceWithVoid();
    }
    
    private Uni<Void> saveMatchStatistics(UnaryMatch match) {
        return Panache.withTransaction(() -> {
            MatchStatistics stats = new MatchStatistics();
            stats.matchId = match.matchId;
            stats.matchType = "UNARY";
            stats.playerOneName = match.playerOneName;
            stats.playerTwoName = match.playerTwoName;
            stats.playerOneWins = match.playerOneWins;
            stats.playerTwoWins = match.playerTwoWins;
            stats.ties = match.ties;
            stats.totalRounds = match.totalRounds;
            stats.durationMillis = java.time.Duration.between(
                match.startedAt, match.completedAt).toMillis();
            stats.roundsPerSecond = (match.totalRounds * 1000.0) / stats.durationMillis;
            stats.databaseIops = dbIopsCounter.get();
            stats.createdAt = Instant.now();
            
            // Calculate move distributions (would need to query all rounds)
            stats.calculateDistributions();
            
            LOG.infof("Match %s completed: P1=%d, P2=%d, Ties=%d, Duration=%dms, RPS=%.2f, IOPS=%d",
                match.matchId, match.playerOneWins, match.playerTwoWins, match.ties,
                stats.durationMillis, stats.roundsPerSecond, stats.databaseIops);
            
            dbIopsCounter.incrementAndGet(); // INSERT stats
            return stats.persistAndFlush().replaceWithVoid();
        });
    }
}
