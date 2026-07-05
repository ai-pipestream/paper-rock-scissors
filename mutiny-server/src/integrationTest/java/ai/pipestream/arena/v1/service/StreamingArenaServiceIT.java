package ai.pipestream.arena.v1.service;

import ai.pipestream.tourney.stream.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusIntegrationTest
public class StreamingArenaServiceIT {

    @TestHTTPResource
    URL url;

    ManagedChannel channel;
    MutinyStreamingArenaServiceGrpc.MutinyStreamingArenaServiceStub client;

    @BeforeEach
    void init() {
        channel = ManagedChannelBuilder.forAddress(url.getHost(), url.getPort()).usePlaintext().build();
        client = MutinyStreamingArenaServiceGrpc.newMutinyStub(channel);
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testStreamingBattle() {
        BroadcastProcessor<BattleRequest> p1Request = BroadcastProcessor.create();
        BroadcastProcessor<BattleRequest> p2Request = BroadcastProcessor.create();

        List<BattleResponse> p1Responses = new CopyOnWriteArrayList<>();
        List<BattleResponse> p2Responses = new CopyOnWriteArrayList<>();

        client.battle(p1Request).subscribe().with(p1Responses::add);
        client.battle(p2Request).subscribe().with(p2Responses::add);

        // Send handshakes
        p1Request.onNext(BattleRequest.newBuilder()
                .setHandshake(Handshake.newBuilder()
                        .setLanguageName("P1-IT")
                        .setPrngAlgorithm("A")
                        .build())
                .build());

        p2Request.onNext(BattleRequest.newBuilder()
                .setHandshake(Handshake.newBuilder()
                        .setLanguageName("P2-IT")
                        .setPrngAlgorithm("B")
                        .build())
                .build());

        // Wait for match creation status
        long start = System.currentTimeMillis();
        boolean matchCreated = false;
        while (System.currentTimeMillis() - start < 10000) {
            if (p1Responses.stream().anyMatch(r -> r.hasStatus() && r.getStatus().startsWith("OPPONENT_FOUND"))) {
                matchCreated = true;
                break;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) {}
        }

        assertTrue(matchCreated, "Match should be created within 10 seconds");
    }
}