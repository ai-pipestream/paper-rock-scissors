package ai.pipestream.arena.v1.repository;

import ai.pipestream.arena.v1.model.MatchStatistics;
import ai.pipestream.arena.v1.model.UnaryMatch;
import ai.pipestream.arena.v1.model.UnaryRound;
import ai.pipestream.arena.v1.util.GameLogic;
import ai.pipestream.tourney.unary.v1.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Blocking business logic for the unary arena.
 *
 * This is the whole point of the virtual-threads variant: compare it to
 * :mutiny-server's {@code UnaryArenaServiceImpl}. There, every step is a
 * {@code Uni.chain(...)} link and the transaction is {@code @WithTransaction}.
 * Here, it is plain sequential Java — {@code if}/{@code else}, direct returns,
 * blocking Panache calls — wrapped in an ordinary JTA {@code @Transactional}.
 *
 * The gRPC layer ({@link ai.pipestream.arena.v1.service.UnaryArenaServiceImpl})
 * runs each call on a virtual thread, so these blocking JDBC calls never block a
 * platform/event-loop thread. Read like blocking code, scale like reactive code.
 */
@ApplicationScoped
public class ArenaRepository {

    private static final Logger LOG = Logger.getLogger(ArenaRepository.class);
    private final AtomicLong dbIopsCounter = new AtomicLong(0);

    // Rounds per match. Defaults to 1000; tests and CI override it
    // (e.g. arena.total-rounds=20) for a fast full match.
    @ConfigProperty(name = "arena.total-rounds", defaultValue = "1000")
    int totalRounds;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        LOG.infof("Registration request from: %s (%s)",
                request.getLanguageName(), request.getPrngAlgorithm());

        dbIopsCounter.incrementAndGet(); // SELECT for waiting matches
        List<UnaryMatch> waitingMatches = UnaryMatch.findWaitingMatches();
        LOG.infof("Found %d waiting matches", waitingMatches.size());

        if (!waitingMatches.isEmpty()) {
            // Join an existing match
            UnaryMatch match = waitingMatches.get(0);
            match.playerTwoName = request.getLanguageName();
            match.playerTwoPrng = request.getPrngAlgorithm();
            match.status = UnaryMatch.MatchStatus.READY;
            match.startedAt = Instant.now();

            dbIopsCounter.incrementAndGet(); // UPDATE match
            match.persist();

            return RegisterResponse.newBuilder()
                    .setMatchId(match.matchId)
                    .setOpponentName(match.playerOneName)
                    .setStatus("READY")
                    .build();
        }

        // Create a new match
        UnaryMatch newMatch = new UnaryMatch();
        newMatch.matchId = UUID.randomUUID().toString();
        newMatch.playerOneName = request.getLanguageName();
        newMatch.playerOnePrng = request.getPrngAlgorithm();
        newMatch.status = UnaryMatch.MatchStatus.WAITING_FOR_OPPONENT;
        newMatch.createdAt = Instant.now();
        newMatch.currentRound = 1;
        newMatch.totalRounds = totalRounds;

        dbIopsCounter.incrementAndGet(); // INSERT match
        newMatch.persist();

        return RegisterResponse.newBuilder()
                .setMatchId(newMatch.matchId)
                .setOpponentName("")
                .setStatus("WAITING_FOR_OPPONENT")
                .build();
    }

    @Transactional
    public SubmitMoveResponse submitMove(SubmitMoveRequest request) {
        dbIopsCounter.incrementAndGet(); // SELECT match (locked — serializes both players' submits)
        UnaryMatch match = UnaryMatch.findByMatchIdForUpdate(request.getMatchId());
        if (match == null) {
            throw new IllegalArgumentException("Match not found");
        }
        if (match.status == UnaryMatch.MatchStatus.COMPLETED) {
            return status("GAME_OVER");
        }
        if (request.getRoundNumber() != match.currentRound) {
            return status("INVALID_TURN");
        }
        if (!GameLogic.isValidMove(request.getMove())) {
            return status("INVALID_TURN");
        }

        dbIopsCounter.incrementAndGet(); // SELECT round
        UnaryRound round = UnaryRound.findByMatchAndRound(request.getMatchId(), request.getRoundNumber());

        if (round == null) {
            // First move of the round
            UnaryRound newRound = new UnaryRound();
            newRound.matchId = request.getMatchId();
            newRound.roundNumber = request.getRoundNumber();
            newRound.createdAt = Instant.now();
            newRound.status = UnaryRound.RoundStatus.WAITING_PLAYER_TWO;
            newRound.playerOneMove = request.getMove();

            dbIopsCounter.incrementAndGet(); // INSERT round
            newRound.persist();
            return status("ACCEPTED");
        }

        if (round.playerOneMove != null && round.playerTwoMove == null) {
            // Second player's move — resolve the round
            round.playerTwoMove = request.getMove();
            round.status = UnaryRound.RoundStatus.COMPLETE;
            round.completedAt = Instant.now();
            round.outcome = GameLogic.determineWinner(round.playerOneMove, round.playerTwoMove);

            updateMatchStats(match, round);
            return status("ACCEPTED");
        }

        // Already moved — idempotent accept
        return status("ACCEPTED");
    }

    @Transactional
    public CheckRoundResultResponse checkRoundResult(CheckRoundResultRequest request) {
        dbIopsCounter.incrementAndGet(); // SELECT round
        UnaryRound round = UnaryRound.findByMatchAndRound(request.getMatchId(), request.getRoundNumber());

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
    }

    private void updateMatchStats(UnaryMatch match, UnaryRound round) {
        if ("PLAYER_ONE_WIN".equals(round.outcome)) {
            match.playerOneWins++;
        } else if ("PLAYER_TWO_WIN".equals(round.outcome)) {
            match.playerTwoWins++;
        } else {
            match.ties++;
        }

        if (match.currentRound >= match.totalRounds) {
            match.status = UnaryMatch.MatchStatus.COMPLETED;
            match.completedAt = Instant.now();
            saveMatchStatistics(match);
        } else {
            match.currentRound++;
        }

        dbIopsCounter.incrementAndGet(); // UPDATE match
        dbIopsCounter.incrementAndGet(); // UPDATE round
        match.persist();
        round.persist();
    }

    private void saveMatchStatistics(UnaryMatch match) {
        MatchStatistics stats = new MatchStatistics();
        stats.matchId = match.matchId;
        stats.matchType = "UNARY";
        stats.playerOneName = match.playerOneName;
        stats.playerTwoName = match.playerTwoName;
        stats.playerOneWins = match.playerOneWins;
        stats.playerTwoWins = match.playerTwoWins;
        stats.ties = match.ties;
        stats.totalRounds = match.totalRounds;
        stats.durationMillis = Duration.between(match.startedAt, match.completedAt).toMillis();
        stats.roundsPerSecond = stats.durationMillis == 0
                ? 0.0
                : (match.totalRounds * 1000.0) / stats.durationMillis;
        stats.databaseIops = dbIopsCounter.get();
        stats.createdAt = Instant.now();
        stats.calculateDistributions();

        LOG.infof("Match %s completed: P1=%d, P2=%d, Ties=%d, Duration=%dms, RPS=%.2f, IOPS=%d",
                match.matchId, match.playerOneWins, match.playerTwoWins, match.ties,
                stats.durationMillis, stats.roundsPerSecond, stats.databaseIops);

        dbIopsCounter.incrementAndGet(); // INSERT stats
        stats.persist();
    }

    private static SubmitMoveResponse status(String status) {
        return SubmitMoveResponse.newBuilder().setStatus(status).build();
    }
}
