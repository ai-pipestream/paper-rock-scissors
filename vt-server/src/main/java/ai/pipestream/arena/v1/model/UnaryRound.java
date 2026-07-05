package ai.pipestream.arena.v1.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** Blocking Hibernate ORM version of the unary round entity. See {@link UnaryMatch}. */
// One round per (match, roundNumber) — defends the invariant submitMove's
// pessimistic match lock enforces.
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"matchId", "roundNumber"}))
public class UnaryRound extends PanacheEntity {
    public String matchId;
    public int roundNumber;

    public Integer playerOneMove;
    public Integer playerTwoMove;

    public String outcome;
    public RoundStatus status;

    public Instant createdAt;
    public Instant completedAt;

    public enum RoundStatus {
        WAITING_PLAYER_TWO,
        COMPLETE
    }

    public static UnaryRound findByMatchAndRound(String matchId, int roundNumber) {
        return find("matchId = ?1 and roundNumber = ?2", matchId, roundNumber).firstResult();
    }
}
