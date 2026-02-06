package ai.pipestream.arena.util;

/**
 * Utility class for Rock-Paper-Scissors game logic.
 */
public class GameLogic {
    
    public static final int ROCK = 0;
    public static final int PAPER = 1;
    public static final int SCISSORS = 2;
    
    /**
     * Determines the winner of a round.
     * @param moveOne First player's move (0=Rock, 1=Paper, 2=Scissors)
     * @param moveTwo Second player's move
     * @return "PLAYER_ONE_WIN", "PLAYER_TWO_WIN", or "TIE"
     */
    public static String determineWinner(int moveOne, int moveTwo) {
        if (moveOne == moveTwo) {
            return "TIE";
        }
        
        if ((moveOne == ROCK && moveTwo == SCISSORS) ||
            (moveOne == PAPER && moveTwo == ROCK) ||
            (moveOne == SCISSORS && moveTwo == PAPER)) {
            return "PLAYER_ONE_WIN";
        }
        
        return "PLAYER_TWO_WIN";
    }
    
    /**
     * Converts outcome to player-specific result.
     * @param outcome The general outcome
     * @param isPlayerOne Whether this is for player one
     * @return "WIN", "LOSS", or "TIE"
     */
    public static String outcomeForPlayer(String outcome, boolean isPlayerOne) {
        if ("TIE".equals(outcome)) {
            return "TIE";
        }
        
        if (isPlayerOne) {
            return "PLAYER_ONE_WIN".equals(outcome) ? "WIN" : "LOSS";
        } else {
            return "PLAYER_TWO_WIN".equals(outcome) ? "WIN" : "LOSS";
        }
    }
    
    /**
     * Validates a move.
     * @param move The move to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidMove(int move) {
        return move >= 0 && move <= 2;
    }
    
    /**
     * Converts move to string representation.
     */
    public static String moveToString(int move) {
        switch (move) {
            case ROCK: return "ROCK";
            case PAPER: return "PAPER";
            case SCISSORS: return "SCISSORS";
            default: return "UNKNOWN";
        }
    }
}
