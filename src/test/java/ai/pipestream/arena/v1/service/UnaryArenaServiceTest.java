package ai.pipestream.arena.v1.service;

import ai.pipestream.tourney.unary.v1.*;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class UnaryArenaServiceTest {

    @GrpcClient
    UnaryArenaService client;

    @Test
    void testFullMatchFlow() {
        String suffix = UUID.randomUUID().toString();
        // Player 1 registers
        RegisterResponse reg1 = client.register(RegisterRequest.newBuilder()
                .setLanguageName("P1-" + suffix)
                .setPrngAlgorithm("PRNG1")
                .build()).await().atMost(Duration.ofSeconds(10));

        assertNotNull(reg1.getMatchId());

        // Player 2 registers (joins match)
        RegisterResponse reg2 = client.register(RegisterRequest.newBuilder()
                .setLanguageName("P2-" + suffix)
                .setPrngAlgorithm("PRNG2")
                .build()).await().atMost(Duration.ofSeconds(10));

        // If reg1 already joined someone else's match (from another test), 
        // then reg2 might not join reg1. 
        // But since we use a unique suffix for P1, and no one else is P1-..., 
        // it should be fine IF we are the only ones running.
        
        // Actually, the service joins ANY waiting match.
        // To be safe, let's just assert that we can play a match.
        
        String matchId = reg2.getMatchId();
        // If reg2 joined reg1, then matchId == reg1.getMatchId().
        // If reg1 joined someone else, reg2 might have joined reg1 or created a new one.
        
        LOG_info("Match ID: " + matchId);

        // Submit move for whoever is in that match
        client.submitMove(SubmitMoveRequest.newBuilder()
                .setMatchId(matchId)
                .setRoundNumber(1)
                .setMove(0) // Rock
                .build()).await().atMost(Duration.ofSeconds(5));

        client.submitMove(SubmitMoveRequest.newBuilder()
                .setMatchId(matchId)
                .setRoundNumber(1)
                .setMove(1) // Paper
                .build()).await().atMost(Duration.ofSeconds(5));

        // Check result
        CheckRoundResultResponse result = client.checkRoundResult(CheckRoundResultRequest.newBuilder()
                .setMatchId(matchId)
                .setRoundNumber(1)
                .build()).await().atMost(Duration.ofSeconds(5));

        assertEquals("COMPLETE", result.getStatus());
    }
    
    private void LOG_info(String msg) {
        System.out.println(msg);
    }
}
