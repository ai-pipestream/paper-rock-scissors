package ai.pipestream.arena.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entity for storing match statistics and analysis results.
 */
@Entity
@Table(name = "match_statistics")
public class MatchStatistics extends PanacheEntity {
    
    @Column(unique = true, nullable = false)
    public String matchId;
    
    public String matchType; // "UNARY" or "STREAMING"
    
    // Player 1 stats
    public String playerOneName;
    public int playerOneRocks;
    public int playerOnePapers;
    public int playerOneScissors;
    public int playerOneWins;
    
    // Player 2 stats
    public String playerTwoName;
    public int playerTwoRocks;
    public int playerTwoPapers;
    public int playerTwoScissors;
    public int playerTwoWins;
    
    public int ties;
    public int totalRounds;
    
    // Performance metrics
    public Long durationMillis;
    public Double roundsPerSecond;
    public Long databaseIops; // For Unary matches
    
    // Bias detection
    public Double playerOneBias; // Deviation from 33.33% for each move
    public Double playerTwoBias;
    public Boolean seedCollisionDetected;
    
    public Instant createdAt;
    
    public void calculateDistributions() {
        if (totalRounds > 0) {
            double expectedPercent = 33.33;
            
            // Calculate bias for player one
            double p1RockPercent = (playerOneRocks * 100.0) / totalRounds;
            double p1PaperPercent = (playerOnePapers * 100.0) / totalRounds;
            double p1ScissorsPercent = (playerOneScissors * 100.0) / totalRounds;
            playerOneBias = Math.max(
                Math.abs(p1RockPercent - expectedPercent),
                Math.max(
                    Math.abs(p1PaperPercent - expectedPercent),
                    Math.abs(p1ScissorsPercent - expectedPercent)
                )
            );
            
            // Calculate bias for player two
            double p2RockPercent = (playerTwoRocks * 100.0) / totalRounds;
            double p2PaperPercent = (playerTwoPapers * 100.0) / totalRounds;
            double p2ScissorsPercent = (playerTwoScissors * 100.0) / totalRounds;
            playerTwoBias = Math.max(
                Math.abs(p2RockPercent - expectedPercent),
                Math.max(
                    Math.abs(p2PaperPercent - expectedPercent),
                    Math.abs(p2ScissorsPercent - expectedPercent)
                )
            );
        }
    }
}
