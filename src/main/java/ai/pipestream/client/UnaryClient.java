package ai.pipestream.client;

import ai.pipestream.tourney.unary.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.jboss.logging.Logger;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Unary client implementation demonstrating the "painful" polling approach.
 */
public class UnaryClient {
    
    private static final Logger LOG = Logger.getLogger(UnaryClient.class);
    private final ManagedChannel channel;
    private final UnaryArenaGrpc.UnaryArenaBlockingStub blockingStub;
    private final Random random;
    private final String languageName;
    private final String prngAlgorithm;
    
    public UnaryClient(String host, int port, String languageName, String prngAlgorithm) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build();
        this.blockingStub = UnaryArenaGrpc.newBlockingStub(channel);
        this.random = new Random();
        this.languageName = languageName;
        this.prngAlgorithm = prngAlgorithm;
    }
    
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
    
    public void play() {
        LOG.infof("Unary Client starting: %s (%s)", languageName, prngAlgorithm);
        
        // Step 1: Register
        RegistrationResponse regResponse = blockingStub.register(
            RegistrationRequest.newBuilder()
                .setLanguageName(languageName)
                .setPrngAlgorithm(prngAlgorithm)
                .build()
        );
        
        String matchId = regResponse.getMatchId();
        LOG.infof("Registered with matchId: %s, Status: %s", matchId, regResponse.getStatus());
        
        // Wait for opponent if needed
        if ("WAITING_FOR_OPPONENT".equals(regResponse.getStatus())) {
            LOG.info("Waiting for opponent...");
            try {
                Thread.sleep(2000); // Give time for opponent to join
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        
        LOG.info("Starting match...");
        
        // Play rounds
        for (int round = 1; round <= 1000; round++) {
            // Step 2: Submit move
            int move = random.nextInt(3); // 0=Rock, 1=Paper, 2=Scissors
            
            MoveResponse moveResponse = blockingStub.submitMove(
                MoveRequest.newBuilder()
                    .setMatchId(matchId)
                    .setRoundNumber(round)
                    .setMove(move)
                    .build()
            );
            
            if ("GAME_OVER".equals(moveResponse.getStatus())) {
                LOG.info("Game is over");
                break;
            }
            
            if (!"ACCEPTED".equals(moveResponse.getStatus())) {
                LOG.warnf("Move not accepted: %s", moveResponse.getStatus());
                continue;
            }
            
            // Step 3: Poll for result (THE PAINFUL PART)
            ResultResponse result = null;
            int pollAttempts = 0;
            while (result == null || "PENDING".equals(result.getStatus())) {
                pollAttempts++;
                result = blockingStub.checkRoundResult(
                    ResultRequest.newBuilder()
                        .setMatchId(matchId)
                        .setRoundNumber(round)
                        .build()
                );
                
                if ("PENDING".equals(result.getStatus())) {
                    try {
                        Thread.sleep(10); // Polling delay
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            
            if (round % 100 == 0) {
                LOG.infof("Round %d: %s (Polls: %d)", round, result.getOutcome(), pollAttempts);
            }
        }
        
        LOG.info("Match completed!");
    }
    
    public static void main(String[] args) {
        String host = System.getProperty("arena.host", "localhost");
        int port = Integer.parseInt(System.getProperty("arena.port", "9000"));
        String languageName = System.getProperty("language.name", "Java-21");
        String prngAlgorithm = System.getProperty("prng.algorithm", "java.util.Random");
        
        UnaryClient client = new UnaryClient(host, port, languageName, prngAlgorithm);
        try {
            client.play();
        } finally {
            try {
                client.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
