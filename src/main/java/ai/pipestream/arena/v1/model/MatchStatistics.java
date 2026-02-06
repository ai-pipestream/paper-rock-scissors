package ai.pipestream.arena.v1.model;

import io.quarkus.hibernate.reactive.panache.PanacheEntity;
import jakarta.persistence.Entity;
import java.time.Instant;

@Entity
public class MatchStatistics extends PanacheEntity {
    public String matchId;
    public String matchType; // "STREAMING" or "UNARY"
    public String playerOneName;
    public String playerTwoName;
    
    public int playerOneRocks;
    public int playerOnePapers;
    public int playerOneScissors;
    public int playerOneWins;
    
    public int playerTwoRocks;
    public int playerTwoPapers;
    public int playerTwoScissors;
    public int playerTwoWins;
    
    public int ties;
    public int totalRounds;
    
    public long durationMillis;
    public double roundsPerSecond;
    public long databaseIops;
    
    public double playerOneBias; // Percentage of most frequent move
    public double playerTwoBias;
    public boolean seedCollisionDetected;
    
    public Instant createdAt;

    public void calculateDistributions() {
        if (totalRounds == 0) return;
        
        int p1Max = Math.max(playerOneRocks, Math.max(playerOnePapers, playerOneScissors));
        this.playerOneBias = (p1Max * 100.0) / totalRounds;
        
        int p2Max = Math.max(playerTwoRocks, Math.max(playerTwoPapers, playerTwoScissors));
        this.playerTwoBias = (p2Max * 100.0) / totalRounds;
    }
}