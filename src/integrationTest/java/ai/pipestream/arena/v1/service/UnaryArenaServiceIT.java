package ai.pipestream.arena.v1.service;

import ai.pipestream.tourney.unary.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusIntegrationTest
public class UnaryArenaServiceIT {

    @TestHTTPResource
    URL url;

    ManagedChannel channel;
    MutinyUnaryArenaServiceGrpc.MutinyUnaryArenaServiceStub client;

    @BeforeEach
    void init() {
        // Use the port from the injected URL
        channel = ManagedChannelBuilder.forAddress(url.getHost(), url.getPort()).usePlaintext().build();
        client = MutinyUnaryArenaServiceGrpc.newMutinyStub(channel);
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testRegister() {
        String suffix = UUID.randomUUID().toString();
        RegisterResponse response = client.register(RegisterRequest.newBuilder()
                .setLanguageName("Java-IT-" + suffix)
                .setPrngAlgorithm("IT-PRNG")
                .build())
                .await().atMost(Duration.ofSeconds(10));

        assertNotNull(response);
        assertNotNull(response.getMatchId());
    }
}
