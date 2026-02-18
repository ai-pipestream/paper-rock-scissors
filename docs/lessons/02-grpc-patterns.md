# Lesson 2: gRPC Patterns — Unary vs Streaming

This project demonstrates two fundamental gRPC communication patterns through a paper-rock-scissors arena. Both services live in the same Quarkus application, share the same port, and use Mutiny-flavored stubs — but their architectures couldn't be more different.

The goal isn't to declare a winner (spoiler: streaming wins for this use case). It's to understand *why* the patterns behave the way they do, and to see the trade-offs play out in real code.

## The Proto Contracts

Before diving into the implementations, look at how differently each service defines its API.

### Unary: Three Separate RPCs

```proto
service UnaryArenaService {
  rpc Register (RegisterRequest) returns (RegisterResponse);
  rpc SubmitMove (SubmitMoveRequest) returns (SubmitMoveResponse);
  rpc CheckRoundResult (CheckRoundResultRequest) returns (CheckRoundResultResponse);
}
```

Three distinct operations. The client drives every interaction — register, submit, poll. Each call is independent, so every request must carry enough context for the server to figure out what's going on.

```proto
message SubmitMoveRequest {
  string match_id = 1;      // "Which match am I in?"
  int32 round_number = 2;   // "What round is it?"
  int32 move = 3;           // The actual payload
}
```

Two out of three fields exist purely to re-establish context that the server already knew a moment ago but has since forgotten.

### Streaming: One Bidirectional RPC

```proto
service StreamingArenaService {
  rpc Battle (stream BattleRequest) returns (stream BattleResponse);
}
```

One RPC. Both sides send and receive freely. The connection *is* the match — no IDs needed.

```proto
message BattleRequest {
  oneof payload {
    Handshake handshake = 1;  // Once, at the start
    Move move = 2;            // Just the move — no context required
  }
}
```

The `Move` message has a single field. The server knows which match this belongs to because it arrived on a specific stream.

## The Unary Service: Database as Memory

`UnaryArenaServiceImpl` is annotated with `@GrpcService` and returns `Uni<T>` from every method. Quarkus's gRPC integration subscribes to these `Uni` instances automatically — you never call `.subscribe()` yourself in server-side code.

### Registration: Find or Create

```java
@Override
@WithTransaction
public Uni<RegisterResponse> register(RegisterRequest request) {
    return UnaryMatch.findWaitingMatches()
        .chain(waitingMatches -> {
            if (!waitingMatches.isEmpty()) {
                UnaryMatch match = waitingMatches.get(0);
                match.playerTwoName = request.getLanguageName();
                match.status = UnaryMatch.MatchStatus.READY;
                match.startedAt = Instant.now();
                return match.persist().replaceWith(/* READY response */);
            } else {
                UnaryMatch newMatch = new UnaryMatch();
                newMatch.matchId = UUID.randomUUID().toString();
                newMatch.playerOneName = request.getLanguageName();
                newMatch.status = UnaryMatch.MatchStatus.WAITING_FOR_OPPONENT;
                return newMatch.persist().replaceWith(/* WAITING response */);
            }
        });
}
```

`@WithTransaction` opens a reactive transaction that wraps the entire method. The `.chain()` operator sequences two async steps: query the database, then either update the found match or insert a new one. At no point does a thread block — Hibernate Reactive handles the I/O, and Mutiny threads the result through the pipeline.

### Submitting a Move: Context Reconstruction

Every time a client submits a move, the server has to reconstruct the world from scratch:

```java
@Override
@WithTransaction
public Uni<SubmitMoveResponse> submitMove(SubmitMoveRequest request) {
    return UnaryMatch.findByMatchId(request.getMatchId())    // SELECT match
        .chain(match -> {
            // Validate match exists, isn't completed, round number matches...
            return UnaryRound.findByMatchAndRound(             // SELECT round
                    request.getMatchId(), request.getRoundNumber())
                .chain(round -> {
                    if (round == null) {
                        // First player to move: INSERT a new round
                        UnaryRound newRound = new UnaryRound();
                        newRound.playerOneMove = request.getMove();
                        newRound.status = UnaryRound.RoundStatus.WAITING_PLAYER_TWO;
                        return newRound.persist().replaceWith(/* ACCEPTED */);
                    } else if (round.playerTwoMove == null) {
                        // Second player: complete the round
                        round.playerTwoMove = request.getMove();
                        round.outcome = GameLogic.determineWinner(
                            round.playerOneMove, round.playerTwoMove);
                        return updateMatchStats(match, round)
                            .replaceWith(/* ACCEPTED */);
                    }
                });
        });
}
```

Two database reads before the server even knows what to do. Then an insert or update, plus match statistics updates. That's the cost of statelessness: every request starts from zero.

### Polling: The Waiting Game

The client has no way to know when both moves are in. It guesses, by asking repeatedly:

```java
@Override
@WithTransaction
public Uni<CheckRoundResultResponse> checkRoundResult(CheckRoundResultRequest request) {
    return UnaryRound.findByMatchAndRound(request.getMatchId(), request.getRoundNumber())
        .map(round -> {
            if (round == null || round.status != UnaryRound.RoundStatus.COMPLETE) {
                return CheckRoundResultResponse.newBuilder()
                    .setStatus("PENDING").build();
            }
            return CheckRoundResultResponse.newBuilder()
                .setStatus("COMPLETE")
                .setOpponentMove(round.playerTwoMove)
                .setOutcome(round.outcome).build();
        });
}
```

Simple enough on the server side — it's just a SELECT. But the client calls this in a loop:

```java
private CheckRoundResultResponse pollForResult(String matchId, int round) {
    return Multi.createBy().repeating()
        .uni(() -> mutinyStub.checkRoundResult(
            CheckRoundResultRequest.newBuilder()
                .setMatchId(matchId)
                .setRoundNumber(round).build()
        ))
        .until(res -> "COMPLETE".equals(res.getStatus()))
        .collect().last()
        .await().atMost(Duration.ofSeconds(30));
}
```

This is Mutiny's `repeating().uni().until()` pattern — it fires the RPC over and over until the predicate is satisfied. Elegant code, brutal on the database. Every poll is a round-trip: client to server, server to database, database to server, server to client. Multiply by however many polls it takes, times 1,000 rounds, times two players.

**The IOPS math**: ~6 database operations per round, 1,000 rounds = ~6,000 database IOPS per match. That's the tax you pay for polling.

## The Streaming Service: The Connection Is the Context

`StreamingArenaServiceImpl` takes a completely different approach. No database for game state. No polling. The server drives everything.

### Opening the Stream

```java
@Override
public Multi<BattleResponse> battle(Multi<BattleRequest> request) {
    BroadcastProcessor<BattleResponse> processor = BroadcastProcessor.create();
    StreamPlayer player = new StreamPlayer(connectionId, processor);

    request.subscribe().with(
        message -> handleClientMessage(player, message),
        failure -> cleanupPlayer(player),
        () -> cleanupPlayer(player)
    );

    return processor;
}
```

This is the heart of bidirectional streaming in Mutiny. The method receives a `Multi<BattleRequest>` (the client's outbound stream) and returns a `Multi<BattleResponse>` (the server's outbound stream). The `BroadcastProcessor` acts as a programmatic emitter — anywhere in the service, you can call `processor.onNext(...)` to push a message to that specific client.

The `request.subscribe().with(...)` sets up three handlers: one for each incoming message, one for errors, one for stream completion. No polling — messages arrive when the client sends them.

### Player Matching: In-Memory Handshake

```java
private void tryMatchPlayers(StreamPlayer player) {
    synchronized (waitingPlayers) {
        if (!waitingPlayers.isEmpty()) {
            String waitingId = waitingPlayers.keySet().iterator().next();
            StreamPlayer opponent = waitingPlayers.remove(waitingId);
        } else {
            waitingPlayers.put(player.connectionId, player);
            return;
        }
    }
    if (opponent != null) {
        createMatch(player, opponent);
    }
}
```

A `ConcurrentHashMap` of waiting players. First one in waits; second one triggers a match. No database involved. The `synchronized` block is narrow — just long enough to atomically check-and-remove or add.

### The Pulse: Server-Driven Rounds

```java
private void startNextRound(StreamMatch match) {
    if (match.currentRound > TOTAL_ROUNDS) {
        completeMatch(match);
        return;
    }

    RequestMove trigger = RequestMove.newBuilder()
        .setRoundId(match.currentRound).build();

    match.playerOne.processor.onNext(
        BattleResponse.newBuilder().setTrigger(trigger).build());
    match.playerTwo.processor.onNext(
        BattleResponse.newBuilder().setTrigger(trigger).build());
}
```

The server tells the clients when to move. Not the other way around. This is the "Pulse" — a `RequestMove` message that says "I need your move for round N." The client just responds:

```java
// Client-side: respond to the trigger immediately
} else if (update.hasTrigger()) {
    int move = random.nextInt(3);
    requestProcessor.onNext(BattleRequest.newBuilder()
        .setMove(Move.newBuilder().setMove(move).build())
        .build());
}
```

Three lines. No match ID, no round number, no context lookup. The client is genuinely dumb — it generates a random number when asked and sends it back. All the intelligence lives on the server.

### Processing a Round: Both Moves In

```java
private void handleMove(StreamPlayer player, Move move) {
    StreamMatch match = activeMatches.get(player.matchId);

    synchronized (match) {
        if (player == match.playerOne) {
            match.playerOneMove = move.getMove();
            match.playerOneMoveReceived = true;
        } else {
            match.playerTwoMove = move.getMove();
            match.playerTwoMoveReceived = true;
        }

        if (match.playerOneMoveReceived && match.playerTwoMoveReceived) {
            processRound(match);
        }
    }
}
```

The server knows which player sent the move because it has a direct reference — `player == match.playerOne` is a simple identity check. When both moves arrive, `processRound` fires immediately. No polling, no database query.

### Sending Results: Push, Not Pull

```java
private void processRound(StreamMatch match) {
    String outcome = GameLogic.determineWinner(match.playerOneMove, match.playerTwoMove);

    match.playerOne.processor.onNext(BattleResponse.newBuilder()
        .setResult(RoundResult.newBuilder()
            .setRoundId(match.currentRound)
            .setOpponentMove(match.playerTwoMove)
            .setOutcome(GameLogic.outcomeForPlayer(outcome, true)))
        .build());

    match.playerTwo.processor.onNext(BattleResponse.newBuilder()
        .setResult(RoundResult.newBuilder()
            .setRoundId(match.currentRound)
            .setOpponentMove(match.playerOneMove)
            .setOutcome(GameLogic.outcomeForPlayer(outcome, false)))
        .build());

    match.currentRound++;
    startNextRound(match);
}
```

Each player gets a personalized result — their own outcome and their opponent's move — pushed instantly. Then the next round starts. No waiting. The entire round lifecycle — trigger, collect moves, resolve, notify, advance — happens in microseconds, limited only by network latency between clients and server.

**The IOPS math**: 0 database operations during the match. One single INSERT at the very end to save statistics. For 1,000 rounds: 1 write.

## The Unified Server

Both services coexist in the same Quarkus application:

```yaml
quarkus:
  grpc:
    server:
      use-separate-server: false
```

This tells Quarkus to host gRPC on the main HTTP server rather than a separate port. Both `UnaryArenaService` and `StreamingArenaService` share the same JVM, the same event loop threads, and the same database connection pool. Clients just need to know which service to call — the transport layer is identical.

## The Client Contrast

The difference in client complexity tells the whole story.

### Unary Client: The Driver

The unary client is in charge. It registers, loops through rounds, submits moves, and polls for results. It manages round numbers, handles `WAITING_FOR_OPPONENT`, and implements retry logic:

```java
public void play() {
    RegisterResponse regResponse = mutinyStub.register(/* ... */)
        .await().atMost(Duration.ofSeconds(5));

    for (int round = 1; round <= 1000; round++) {
        int move = random.nextInt(3);
        mutinyStub.submitMove(/* matchId, round, move */)
            .await().atMost(Duration.ofSeconds(2));
        CheckRoundResultResponse result = pollForResult(matchId, round);
    }
}
```

The client owns the flow. It decides when to move, when to poll, and when to stop. That's more power, but also more responsibility — and more ways to get out of sync with the server.

### Streaming Client: The Responder

The streaming client opens a connection and reacts to whatever the server sends:

```java
public void play() throws InterruptedException {
    BroadcastProcessor<BattleRequest> requestProcessor = BroadcastProcessor.create();
    Multi<BattleResponse> responses = mutinyStub.battle(requestProcessor);

    responses.subscribe().with(update -> {
        if (update.hasTrigger()) {
            int move = random.nextInt(3);
            requestProcessor.onNext(/* move */);
        } else if (update.hasStatus()) {
            if ("MATCH_COMPLETE".equals(update.getStatus())) finishLatch.countDown();
        }
    });

    requestProcessor.onNext(/* handshake */);
    finishLatch.await(5, TimeUnit.MINUTES);
}
```

Send a handshake, then just listen. When asked for a move, send one. When told the match is over, stop. The client doesn't even know what round it's on — the server tracks that.

## When to Use Which

Unary isn't wrong. It's the right tool for a different job.

**Use Unary when:**
- The operation is genuinely one-shot (look up a user, submit a form)
- You need horizontal scalability with stateless load balancing
- Clients connect briefly and disconnect
- Caching or CDN layers sit between client and server

**Use Streaming when:**
- Both sides need to send data at unpredictable times
- Low latency matters more than simplicity
- The interaction is stateful and long-lived
- You'd otherwise be polling (that's a code smell for "you wanted a stream")

The paper-rock-scissors arena makes the difference visceral: 6,000 database IOPS vs 1. Tens of milliseconds per round vs single-digit milliseconds. A client that drives the protocol vs a client that just shows up and plays.
