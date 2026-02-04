package com.rickert.client;

import com.rickert.tourney.stream.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Streaming client implementation demonstrating the "clean" approach.
 */
public class StreamingClient {
    
    private static final Logger LOG = Logger.getLogger(StreamingClient.class);
    private final ManagedChannel channel;
    private final StreamingArenaGrpc.StreamingArenaStub asyncStub;
    private final Random random;
    private final String languageName;
    private final String prngAlgorithm;
    
    public StreamingClient(String host, int port, String languageName, String prngAlgorithm) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build();
        this.asyncStub = StreamingArenaGrpc.newStub(channel);
        this.random = new Random();
        this.languageName = languageName;
        this.prngAlgorithm = prngAlgorithm;
    }
    
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
    
    public void play() throws InterruptedException {
        LOG.infof("Streaming Client starting: %s (%s)", languageName, prngAlgorithm);
        
        CountDownLatch finishLatch = new CountDownLatch(1);
        
        // Create the bidirectional stream
        StreamObserver<ArenaMessage> requestObserver = asyncStub.battle(
            new StreamObserver<GameUpdate>() {
                private int roundsCompleted = 0;
                
                @Override
                public void onNext(GameUpdate update) {
                    if (update.hasStatus()) {
                        String status = update.getStatus();
                        LOG.info("Status: " + status);
                        
                        if (status.startsWith("OPPONENT_FOUND") || 
                            status.equals("MATCH_COMPLETE") ||
                            status.equals("OPPONENT_DISCONNECTED")) {
                            if (status.equals("MATCH_COMPLETE")) {
                                LOG.infof("Match completed! Total rounds: %d", roundsCompleted);
                                finishLatch.countDown();
                            }
                        }
                    } else if (update.hasTrigger()) {
                        // Server requesting a move - respond immediately
                        int move = random.nextInt(3);
                        requestObserver.onNext(ArenaMessage.newBuilder()
                            .setMove(Move.newBuilder().setMove(move).build())
                            .build());
                    } else if (update.hasResult()) {
                        // Round result received
                        roundsCompleted++;
                        RoundResult result = update.getResult();
                        if (roundsCompleted % 100 == 0) {
                            LOG.infof("Round %d: %s", result.getRoundId(), result.getOutcome());
                        }
                    }
                }
                
                @Override
                public void onError(Throwable t) {
                    LOG.errorf("Stream error: %s", t.getMessage());
                    finishLatch.countDown();
                }
                
                @Override
                public void onCompleted() {
                    LOG.info("Stream completed");
                    finishLatch.countDown();
                }
            }
        );
        
        // Send handshake
        requestObserver.onNext(ArenaMessage.newBuilder()
            .setHandshake(Handshake.newBuilder()
                .setLanguageName(languageName)
                .setPrngAlgorithm(prngAlgorithm)
                .build())
            .build());
        
        // Wait for completion
        if (!finishLatch.await(5, TimeUnit.MINUTES)) {
            LOG.warn("Match did not complete within timeout");
        }
    }
    
    public static void main(String[] args) {
        String host = System.getProperty("arena.host", "localhost");
        int port = Integer.parseInt(System.getProperty("arena.port", "9000"));
        String languageName = System.getProperty("language.name", "Java-21");
        String prngAlgorithm = System.getProperty("prng.algorithm", "L64X128MixRandom");
        
        StreamingClient client = new StreamingClient(host, port, languageName, prngAlgorithm);
        try {
            client.play();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                client.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
