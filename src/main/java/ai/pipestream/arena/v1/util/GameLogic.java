package ai.pipestream.arena.v1.util;

public class GameLogic {
    public static final int ROCK = 0;
    public static final int PAPER = 1;
    public static final int SCISSORS = 2;

    public static boolean isValidMove(int move) {
        return move >= 0 && move <= 2;
    }

    /**
     * Determines the winner of a round.
     * Returns "PLAYER_ONE_WIN", "PLAYER_TWO_WIN", or "TIE".
     */
    public static String determineWinner(int p1Move, int p2Move) {
        if (p1Move == p2Move) {
            return "TIE";
        }
        
        if ((p1Move == ROCK && p2Move == SCISSORS) ||
            (p1Move == PAPER && p2Move == ROCK) ||
            (p1Move == SCISSORS && p2Move == PAPER)) {
            return "PLAYER_ONE_WIN";
        }
        
        return "PLAYER_TWO_WIN";
    }

    public static String outcomeForPlayer(String matchOutcome, boolean isPlayerOne) {
        if ("TIE".equals(matchOutcome)) return "TIE";
        
        if (isPlayerOne) {
            return "PLAYER_ONE_WIN".equals(matchOutcome) ? "WIN" : "LOSS";
        } else {
            return "PLAYER_TWO_WIN".equals(matchOutcome) ? "WIN" : "LOSS";
        }
    }
}