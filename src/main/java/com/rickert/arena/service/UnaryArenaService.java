package com.rickert.arena.service;

import com.rickert.arena.model.UnaryMatch;
import com.rickert.arena.model.UnaryRound;
import com.rickert.arena.model.MatchStatistics;
import com.rickert.arena.util.GameLogic;
import com.rickert.tourney.unary.*;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unary gRPC Service implementation.
 * Demonstrates the "painful" polling approach with database state management.
 */
@GrpcService
@Singleton
public class UnaryArenaService implements UnaryArena {
    
    private static final Logger LOG = Logger.getLogger(UnaryArenaService.class);
    private final AtomicLong dbIopsCounter = new AtomicLong(0);
    
    @Override
    public Uni<RegistrationResponse> register(RegistrationRequest request) {
        return Uni.createFrom().item(() -> {
            LOG.infof("Registration request from: %s (%s)", 
                request.getLanguageName(), request.getPrngAlgorithm());
            
            return registerBlocking(request);
        });
    }
    
    @Transactional
    RegistrationResponse registerBlocking(RegistrationRequest request) {
        dbIopsCounter.incrementAndGet(); // SELECT for waiting matches
        List<UnaryMatch> waitingMatches = UnaryMatch.findWaitingMatches();
        
        if (!waitingMatches.isEmpty()) {
            // Join an existing match
            UnaryMatch match = waitingMatches.get(0);
            match.playerTwoName = request.getLanguageName();
            match.playerTwoPrng = request.getPrngAlgorithm();
            match.status = UnaryMatch.MatchStatus.READY;
            match.startedAt = Instant.now();
            
            dbIopsCounter.incrementAndGet(); // UPDATE match
            match.persist();
            
            return RegistrationResponse.newBuilder()
                .setMatchId(match.matchId)
                .setOpponentName(match.playerOneName)
                .setStatus("READY")
                .build();
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
            newMatch.persist();
            
            return RegistrationResponse.newBuilder()
                .setMatchId(newMatch.matchId)
                .setOpponentName("")
                .setStatus("WAITING_FOR_OPPONENT")
                .build();
        }
    }
    
    @Override
    public Uni<MoveResponse> submitMove(MoveRequest request) {
        return Uni.createFrom().item(() -> submitMoveBlocking(request));
    }
    
    @Transactional
    MoveResponse submitMoveBlocking(MoveRequest request) {
        dbIopsCounter.incrementAndGet(); // SELECT match
        UnaryMatch match = UnaryMatch.findByMatchId(request.getMatchId());
        
        if (match == null) {
            throw new IllegalArgumentException("Match not found");
        }
        
        if (match.status == UnaryMatch.MatchStatus.COMPLETED) {
            return MoveResponse.newBuilder()
                .setStatus("GAME_OVER")
                .build();
        }
        
        if (request.getRoundNumber() != match.currentRound) {
            return MoveResponse.newBuilder()
                .setStatus("INVALID_TURN")
                .build();
        }
        
        if (!GameLogic.isValidMove(request.getMove())) {
            return MoveResponse.newBuilder()
                .setStatus("INVALID_TURN")
                .build();
        }
        
        dbIopsCounter.incrementAndGet(); // SELECT round
        UnaryRound round = UnaryRound.findByMatchAndRound(request.getMatchId(), request.getRoundNumber());
        
        if (round == null) {
            // Create new round
            round = new UnaryRound();
            round.matchId = request.getMatchId();
            round.roundNumber = request.getRoundNumber();
            round.createdAt = Instant.now();
            round.status = UnaryRound.RoundStatus.WAITING_PLAYER_TWO;
            round.playerOneMove = request.getMove();
            
            dbIopsCounter.incrementAndGet(); // INSERT round
            round.persist();
        } else if (round.playerOneMove != null && round.playerTwoMove == null) {
            // Second player's move
            round.playerTwoMove = request.getMove();
            round.status = UnaryRound.RoundStatus.COMPLETE;
            round.completedAt = Instant.now();
            
            // Calculate outcome
            round.outcome = GameLogic.determineWinner(round.playerOneMove, round.playerTwoMove);
            
            // Update match statistics
            updateMatchStats(match, round);
            
            dbIopsCounter.incrementAndGet(); // UPDATE round
            round.persist();
        }
        
        return MoveResponse.newBuilder()
            .setStatus("ACCEPTED")
            .build();
    }
    
    @Override
    public Uni<ResultResponse> checkRoundResult(ResultRequest request) {
        return Uni.createFrom().item(() -> {
            dbIopsCounter.incrementAndGet(); // SELECT round
            UnaryRound round = UnaryRound.findByMatchAndRound(request.getMatchId(), request.getRoundNumber());
            
            if (round == null || round.status != UnaryRound.RoundStatus.COMPLETE) {
                return ResultResponse.newBuilder()
                    .setStatus("PENDING")
                    .setOpponentMove(-1)
                    .setOutcome("")
                    .build();
            }
            
            return ResultResponse.newBuilder()
                .setStatus("COMPLETE")
                .setOpponentMove(round.playerTwoMove != null ? round.playerTwoMove : round.playerOneMove)
                .setOutcome(round.outcome)
                .build();
        });
    }
    
    private void updateMatchStats(UnaryMatch match, UnaryRound round) {
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
            saveMatchStatistics(match);
        } else {
            match.currentRound++;
        }
        
        dbIopsCounter.incrementAndGet(); // UPDATE match
        match.persist();
    }
    
    @Transactional
    void saveMatchStatistics(UnaryMatch match) {
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
        stats.persist();
    }
}
