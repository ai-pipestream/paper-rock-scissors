package ai.pipestream.arena.v1.model;

import io.quarkus.hibernate.reactive.panache.PanacheEntity;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.Entity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;

@Entity
public class UnaryMatch extends PanacheEntity {
    public String matchId;
    public String playerOneName;
    public String playerOnePrng;
    public String playerTwoName;
    public String playerTwoPrng;
    
    public int playerOneWins = 0;
    public int playerTwoWins = 0;
    public int ties = 0;
    
    public int currentRound = 1;
    public int totalRounds = 1000;
    
    public MatchStatus status;
    public Instant createdAt;
    public Instant startedAt;
    public Instant completedAt;

    public enum MatchStatus {
        WAITING_FOR_OPPONENT,
        READY,
        COMPLETED
    }

    public static Uni<List<UnaryMatch>> findWaitingMatches() {
        return list("status", MatchStatus.WAITING_FOR_OPPONENT);
    }

    public static Uni<UnaryMatch> findByMatchId(String matchId) {
        return find("matchId", matchId).firstResult();
    }

    /**
     * Find a match and take a pessimistic write lock on its row for the current
     * transaction. submitMove uses this so the two players' concurrent submits for
     * the same round are serialized — otherwise both read "round not found" and
     * each create a separate half-round that never completes (the match then hangs
     * forever in the client's poll loop).
     */
    public static Uni<UnaryMatch> findByMatchIdForUpdate(String matchId) {
        return find("matchId", matchId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
    }
}