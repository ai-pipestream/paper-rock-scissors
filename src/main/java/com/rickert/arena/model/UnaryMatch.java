package com.rickert.arena.model;

import io.smallrye.mutiny.Uni;
import io.quarkus.hibernate.reactive.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

/**
 * Entity representing a match between two clients in the Unary approach.
 * This entity is persisted to demonstrate the "Context Tax" and database overhead.
 */
@Entity
@Table(name = "unary_match")
public class UnaryMatch extends PanacheEntity {
    
    @Column(unique = true, nullable = false)
    public String matchId;
    
    public String playerOneName;
    public String playerOnePrng;
    
    public String playerTwoName;
    public String playerTwoPrng;
    
    @Enumerated(EnumType.STRING)
    public MatchStatus status;
    
    public Instant createdAt;
    public Instant startedAt;
    public Instant completedAt;
    
    public int currentRound;
    public int totalRounds = 1000;
    
    // Statistics
    public int playerOneWins;
    public int playerTwoWins;
    public int ties;
    
    public enum MatchStatus {
        WAITING_FOR_OPPONENT,
        READY,
        IN_PROGRESS,
        COMPLETED
    }
    
    public static Uni<UnaryMatch> findByMatchId(String matchId) {
        return find("matchId", matchId).firstResult();
    }
    
    public static Uni<List<UnaryMatch>> findWaitingMatches() {
        return list("status", MatchStatus.WAITING_FOR_OPPONENT);
    }
}
