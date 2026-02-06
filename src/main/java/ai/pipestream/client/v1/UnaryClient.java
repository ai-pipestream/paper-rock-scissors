package ai.pipestream.client.v1;

import ai.pipestream.tourney.unary.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Unary client implementation using Mutiny stubs.
 */
public class UnaryClient {
    
    private static final Logger LOG = Logger.getLogger(UnaryClient.class);
    private final ManagedChannel channel;
    private final MutinyUnaryArenaServiceGrpc.MutinyUnaryArenaServiceStub mutinyStub;
    private final Random random;
    private final String languageName;
    private final String prngAlgorithm;
    
    public UnaryClient(String host, int port, String languageName, String prngAlgorithm) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build();
        this.mutinyStub = MutinyUnaryArenaServiceGrpc.newMutinyStub(channel);
        this.random = new Random();
        this.languageName = languageName;
        this.prngAlgorithm = prngAlgorithm;
    }
    
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
    
    public void play() {
        LOG.infof("Unary Client (Mutiny) starting: %s (%s)", languageName, prngAlgorithm);
        
        // Step 1: Register
        RegisterResponse regResponse = mutinyStub.register(
            RegisterRequest.newBuilder()
                .setLanguageName(languageName)
                .setPrngAlgorithm(prngAlgorithm)
                .build()
        ).await().atMost(Duration.ofSeconds(5));
        
        String matchId = regResponse.getMatchId();
        LOG.infof("Registered with matchId: %s, Status: %s", matchId, regResponse.getStatus());
        
        // Wait for opponent if needed
        if ("WAITING_FOR_OPPONENT".equals(regResponse.getStatus())) {
            LOG.info("Waiting for opponent...");
            try {
                Thread.sleep(2000); 
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        
        LOG.info("Starting match...");
        
        // Play rounds
        for (int round = 1; round <= 1000; round++) {
            // Step 2: Submit move
            int move = random.nextInt(3); 
            
            SubmitMoveResponse moveResponse = mutinyStub.submitMove(
                SubmitMoveRequest.newBuilder()
                    .setMatchId(matchId)
                    .setRoundNumber(round)
                    .setMove(move)
                    .build()
            ).await().atMost(Duration.ofSeconds(2));
            
            if ("GAME_OVER".equals(moveResponse.getStatus())) {
                LOG.info("Game is over");
                break;
            }
            
            if (!"ACCEPTED".equals(moveResponse.getStatus())) {
                LOG.warnf("Move not accepted: %s", moveResponse.getStatus());
                continue;
            }
            
            // Step 3: Poll for result
            CheckRoundResultResponse result = pollForResult(matchId, round);
            
            if (round % 100 == 0) {
                LOG.infof("Round %d: %s", round, result.getOutcome());
            }
        }
        
        LOG.info("Match completed!");
    }

    private CheckRoundResultResponse pollForResult(String matchId, int round) {
        return Multi.createBy().repeating()
            .uni(() -> mutinyStub.checkRoundResult(
                CheckRoundResultRequest.newBuilder()
                    .setMatchId(matchId)
                    .setRoundNumber(round)
                    .build()
            ))
            .until(res -> "COMPLETE".equals(res.getStatus()))
            .collect().last()
            .await().atMost(Duration.ofSeconds(30));
    }
    
    public static void main(String[] args) {
        String host = System.getProperty("arena.host", "localhost");
        int port = Integer.parseInt(System.getProperty("arena.port", "9000"));
        String languageName = System.getProperty("language.name", "Java-Mutiny-Unary");
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