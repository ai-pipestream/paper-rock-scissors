package ai.pipestream.client.v1;

import ai.pipestream.tourney.stream.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Streaming client implementation using Mutiny stubs.
 */
public class StreamingClient {
    
    private static final Logger LOG = Logger.getLogger(StreamingClient.class);
    private final ManagedChannel channel;
    private final MutinyStreamingArenaServiceGrpc.MutinyStreamingArenaServiceStub mutinyStub;
    private final Random random;
    private final String languageName;
    private final String prngAlgorithm;
    
    public StreamingClient(String host, int port, String languageName, String prngAlgorithm) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build();
        this.mutinyStub = MutinyStreamingArenaServiceGrpc.newMutinyStub(channel);
        this.random = new Random();
        this.languageName = languageName;
        this.prngAlgorithm = prngAlgorithm;
    }
    
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
    
    public void play() throws InterruptedException {
        play(false);
    }

    public void play(boolean verbose) throws InterruptedException {
        CountDownLatch finishLatch = new CountDownLatch(1);
        BroadcastProcessor<BattleRequest> requestProcessor = BroadcastProcessor.create();
        
        // Use the bidirectional stream
        Multi<BattleResponse> responses = mutinyStub.battle(requestProcessor);
        
        responses.subscribe().with(
            update -> {
                if (update.hasStatus()) {
                    String status = update.getStatus();
                    if (verbose) LOG.info("Status: " + status);

                    if (status.equals("MATCH_COMPLETE") || status.equals("OPPONENT_DISCONNECTED")) {
                        finishLatch.countDown();
                    }
                } else if (update.hasTrigger()) {
                    // Server requesting a move
                    int move = random.nextInt(3);
                    requestProcessor.onNext(BattleRequest.newBuilder()
                        .setMove(Move.newBuilder().setMove(move).build())
                        .build());
                } else if (update.hasResult() && verbose) {
                    RoundResult result = update.getResult();
                    if (result.getRoundId() % 100 == 0) {
                        LOG.infof("Round %d: %s", result.getRoundId(), result.getOutcome());
                    }
                }
            },
            failure -> {
                LOG.errorf("Stream error: %s", failure.getMessage());
                finishLatch.countDown();
            },
            () -> {
                LOG.info("Stream completed");
                finishLatch.countDown();
            }
        );
        
        // Send handshake
        requestProcessor.onNext(BattleRequest.newBuilder()
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
        String languageName = System.getProperty("language.name", "Java");
        String prngAlgorithm = System.getProperty("prng.algorithm", "java.util.Random");
        int matches = Integer.parseInt(System.getProperty("matches", "1"));
        boolean verbose = Boolean.parseBoolean(System.getProperty("verbose", "false"));

        StreamingClient client = new StreamingClient(host, port, languageName, prngAlgorithm);
        System.out.printf("%s (%s): playing %d match(es)...%n", languageName, prngAlgorithm, matches);
        try {
            int step = Math.max(1, matches / 10);
            for (int i = 0; i < matches; i++) {
                client.play(verbose);
                if (matches > 1 && (i + 1) % step == 0) {
                    System.out.printf("  %s: %d/%d matches%n", languageName, i + 1, matches);
                }
            }
            System.out.printf("%s done: %d matches%n", languageName, matches);
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