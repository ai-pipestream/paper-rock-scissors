package ai.pipestream.arena.v1.model;

import io.quarkus.hibernate.reactive.panache.PanacheEntity;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.Entity;
import java.time.Instant;

@Entity
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

    public static Uni<UnaryRound> findByMatchAndRound(String matchId, int roundNumber) {
        return find("matchId = ?1 and roundNumber = ?2", matchId, roundNumber).firstResult();
    }
}