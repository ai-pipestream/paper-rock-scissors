package com.rickert.arena.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entity representing a single round in a Unary match.
 * Stores individual moves and results to demonstrate database IOPS overhead.
 */
@Entity
@Table(name = "unary_round", indexes = {
    @Index(name = "idx_match_round", columnList = "matchId,roundNumber")
})
public class UnaryRound extends PanacheEntity {
    
    @Column(nullable = false)
    public String matchId;
    
    @Column(nullable = false)
    public int roundNumber;
    
    public Integer playerOneMove;  // 0=Rock, 1=Paper, 2=Scissors
    public Integer playerTwoMove;
    
    @Enumerated(EnumType.STRING)
    public RoundStatus status;
    
    public String outcome; // "PLAYER_ONE_WIN", "PLAYER_TWO_WIN", "TIE"
    
    public Instant createdAt;
    public Instant completedAt;
    
    public enum RoundStatus {
        WAITING_PLAYER_ONE,
        WAITING_PLAYER_TWO,
        COMPLETE
    }
    
    public static UnaryRound findByMatchAndRound(String matchId, int roundNumber) {
        return find("matchId = ?1 and roundNumber = ?2", matchId, roundNumber).firstResult();
    }
}
