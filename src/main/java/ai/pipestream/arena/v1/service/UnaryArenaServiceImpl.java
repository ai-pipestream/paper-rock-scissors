package ai.pipestream.arena.v1.service;

import ai.pipestream.arena.v1.model.UnaryMatch;
import ai.pipestream.arena.v1.model.UnaryRound;
import ai.pipestream.arena.v1.model.MatchStatistics;
import ai.pipestream.arena.v1.util.GameLogic;
import ai.pipestream.tourney.unary.v1.*;
import io.quarkus.grpc.GrpcService;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unary gRPC Service implementation.
 * Fully reactive using Mutiny and Hibernate Reactive.
 */
@GrpcService
@Singleton
public class UnaryArenaServiceImpl implements UnaryArenaService {
    
    private static final Logger LOG = Logger.getLogger(UnaryArenaServiceImpl.class);
    private final AtomicLong dbIopsCounter = new AtomicLong(0);
    
    @Override
    @WithTransaction
    public Uni<RegisterResponse> register(RegisterRequest request) {
        LOG.infof("Registration request from: %s (%s)", 
            request.getLanguageName(), request.getPrngAlgorithm());
        
        dbIopsCounter.incrementAndGet(); // SELECT for waiting matches
        return UnaryMatch.findWaitingMatches()
            .chain(waitingMatches -> {
                LOG.infof("Found %d waiting matches", waitingMatches.size());
                if (!waitingMatches.isEmpty()) {
                    // Join an existing match
                    UnaryMatch match = waitingMatches.get(0);
                    match.playerTwoName = request.getLanguageName();
                    match.playerTwoPrng = request.getPrngAlgorithm();
                    match.status = UnaryMatch.MatchStatus.READY;
                    match.startedAt = Instant.now();
                    
                    dbIopsCounter.incrementAndGet(); // UPDATE match
                    return match.persist().replaceWith(
                        RegisterResponse.newBuilder()
                            .setMatchId(match.matchId)
                            .setOpponentName(match.playerOneName)
                            .setStatus("READY")
                            .build()
                    );
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
                    return newMatch.persist().replaceWith(
                        RegisterResponse.newBuilder()
                            .setMatchId(newMatch.matchId)
                            .setOpponentName("")
                            .setStatus("WAITING_FOR_OPPONENT")
                            .build()
                    );
                }
            });
    }
    
    @Override
    @WithTransaction
    public Uni<SubmitMoveResponse> submitMove(SubmitMoveRequest request) {
        dbIopsCounter.incrementAndGet(); // SELECT match
        return UnaryMatch.findByMatchId(request.getMatchId())
            .chain(match -> {
                if (match == null) {
                    return Uni.createFrom().failure(new IllegalArgumentException("Match not found"));
                }
                
                if (match.status == UnaryMatch.MatchStatus.COMPLETED) {
                    return Uni.createFrom().item(SubmitMoveResponse.newBuilder()
                        .setStatus("GAME_OVER")
                        .build());
                }
                
                if (request.getRoundNumber() != match.currentRound) {
                    return Uni.createFrom().item(SubmitMoveResponse.newBuilder()
                        .setStatus("INVALID_TURN")
                        .build());
                }
                
                if (!GameLogic.isValidMove(request.getMove())) {
                    return Uni.createFrom().item(SubmitMoveResponse.newBuilder()
                        .setStatus("INVALID_TURN")
                        .build());
                }
                
                dbIopsCounter.incrementAndGet(); // SELECT round
                return UnaryRound.findByMatchAndRound(request.getMatchId(), request.getRoundNumber())
                    .chain(round -> {
                        if (round == null) {
                            // Create new round
                            UnaryRound newRound = new UnaryRound();
                            newRound.matchId = request.getMatchId();
                            newRound.roundNumber = request.getRoundNumber();
                            newRound.createdAt = Instant.now();
                            newRound.status = UnaryRound.RoundStatus.WAITING_PLAYER_TWO;
                            newRound.playerOneMove = request.getMove();
                            
                            dbIopsCounter.incrementAndGet(); // INSERT round
                            return newRound.persist().replaceWith(
                                SubmitMoveResponse.newBuilder()
                                    .setStatus("ACCEPTED")
                                    .build()
                            );
                        } else if (round.playerOneMove != null && round.playerTwoMove == null) {
                            // Second player's move
                            round.playerTwoMove = request.getMove();
                            round.status = UnaryRound.RoundStatus.COMPLETE;
                            round.completedAt = Instant.now();
                            
                            // Calculate outcome
                            round.outcome = GameLogic.determineWinner(round.playerOneMove, round.playerTwoMove);
                            
                            // Update match statistics and persist both
                            return updateMatchStats(match, round)
                                .replaceWith(
                                    SubmitMoveResponse.newBuilder()
                                        .setStatus("ACCEPTED")
                                        .build()
                                );
                        } else {
                            return Uni.createFrom().item(SubmitMoveResponse.newBuilder()
                                .setStatus("ACCEPTED") // Already moved or something, just accept
                                .build());
                        }
                    });
            });
    }
    
    @Override
    @WithTransaction
    public Uni<CheckRoundResultResponse> checkRoundResult(CheckRoundResultRequest request) {
        dbIopsCounter.incrementAndGet(); // SELECT round
        return UnaryRound.findByMatchAndRound(request.getMatchId(), request.getRoundNumber())
            .map(round -> {
                if (round == null || round.status != UnaryRound.RoundStatus.COMPLETE) {
                    return CheckRoundResultResponse.newBuilder()
                        .setStatus("PENDING")
                        .setOpponentMove(-1)
                        .setOutcome("")
                        .build();
                }
                
                return CheckRoundResultResponse.newBuilder()
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
        
        Uni<Void> persistAction;
        
        // Check if match is complete
        if (match.currentRound >= match.totalRounds) {
            match.status = UnaryMatch.MatchStatus.COMPLETED;
            match.completedAt = Instant.now();
            
            // Create statistics record
            persistAction = saveMatchStatistics(match);
        } else {
            match.currentRound++;
            persistAction = Uni.createFrom().voidItem();
        }
        
        dbIopsCounter.incrementAndGet(); // UPDATE match
        dbIopsCounter.incrementAndGet(); // UPDATE round
        return Uni.combine().all().unis(match.persist(), round.persist(), persistAction).discardItems();
    }
    
    private Uni<Void> saveMatchStatistics(UnaryMatch match) {
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
        
        stats.calculateDistributions();
        
        LOG.infof("Match %s completed: P1=%d, P2=%d, Ties=%d, Duration=%dms, RPS=%.2f, IOPS=%d",
            match.matchId, match.playerOneWins, match.playerTwoWins, match.ties,
            stats.durationMillis, stats.roundsPerSecond, stats.databaseIops);
        
        dbIopsCounter.incrementAndGet(); // INSERT stats
        return stats.persist().replaceWithVoid();
    }
}