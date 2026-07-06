# Lesson 1: Reactive Handlers with Mutiny

**Goal:** Understand how `mutiny-server` handles gRPC and database I/O without blocking threads — using types and patterns you will see throughout this repo.

> **Prerequisites:** [Lesson 0](./00-grpc-primer.md) (run the arena once).  
> **Background (optional):** [Appendix A — threading models](./appendix-a-threading-models.md)

---

## Why this lesson exists

When a client calls `Register` or `SubmitMove`, the server waits on PostgreSQL. A naive implementation blocks an OS thread for the entire round trip. Under load, you run out of threads long before you run out of CPU.

The reactive server (`mutiny-server`) solves this differently: **while the database answers, the thread goes back to work on other requests.** You express that with Mutiny's `Uni` and `Multi` types.

This lesson is about *how that server waits*. It is not a Quarkus tutorial — Quarkus just generates the stubs and wires the event loop. The ideas apply to any event-loop-based stack.

---

## The stack in one picture

```
Client ──gRPC/HTTP2──► Quarkus gRPC layer
                              │
                              ▼
                    Your service returns Uni/Multi
                              │
                              ▼
                    Mutiny (compose async steps)
                              │
                              ▼
                    Vert.x event loop (never block here)
                              │
                              ▼
                    Hibernate Reactive ──► PostgreSQL
```

Quarkus generates **Mutiny-flavored gRPC stubs** (`MutinyUnaryArenaServiceGrpc`). Your service methods return `Uni<RegisterResponse>` instead of `RegisterResponse`. The framework subscribes for you — you never call `.subscribe()` in server code.

---

## `Uni<T>` — one result, later

A `Uni` is a *lazy* promise of a single value (or a failure). Nothing runs until something subscribes.

Here is the real `register` method from `mutiny-server`:

```java
@Override
@WithTransaction
public Uni<RegisterResponse> register(RegisterRequest request) {
    return UnaryMatch.findWaitingMatches()                     // Uni<List<UnaryMatch>>
        .chain(waitingMatches -> {
            if (!waitingMatches.isEmpty()) {
                UnaryMatch match = waitingMatches.get(0);
                match.playerTwoName = request.getLanguageName();
                match.status = UnaryMatch.MatchStatus.READY;
                match.startedAt = Instant.now();
                return match.persist().replaceWith(              // Uni<Void> → Uni<RegisterResponse>
                    RegisterResponse.newBuilder()
                        .setMatchId(match.matchId)
                        .setStatus("READY")
                        .build());
            } else {
                UnaryMatch newMatch = new UnaryMatch();
                newMatch.matchId = UUID.randomUUID().toString();
                newMatch.playerOneName = request.getLanguageName();
                newMatch.status = UnaryMatch.MatchStatus.WAITING_FOR_OPPONENT;
                return newMatch.persist().replaceWith(
                    RegisterResponse.newBuilder()
                        .setStatus("WAITING_FOR_OPPONENT")
                        .build());
            }
        });
}
```

Read it as a sentence:

1. **Find waiting matches** (async DB query).
2. **Then** (`chain`) either join an existing match or create a new one.
3. **Persist** and **replace** the void result with the gRPC response.

No thread sits idle staring at PostgreSQL. When the query completes, Mutiny resumes the lambda on an event-loop thread.

### `Uni` vs `CompletableFuture`

| | `Uni` | `CompletableFuture` |
|---|---|---|
| Starts when | Subscribed | Created |
| gRPC integration | Native in Quarkus | Manual adapter |
| Composition | `chain`, `onItem().transform` | `thenCompose`, `thenApply` |
| Lazy cancellation | Built in | Awkward |

---

## `Multi<T>` — a stream of events

A `Multi` emits zero-to-many items over time. It maps directly to gRPC **streaming** RPCs.

The streaming arena's `battle` method receives the client's inbound stream and returns the server's outbound stream:

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

    return processor;   // server pushes responses via processor.onNext(...)
}
```

When the server needs a move from both players, it pushes triggers:

```java
match.playerOne.processor.onNext(
    BattleResponse.newBuilder()
        .setTrigger(RequestMove.newBuilder().setRoundId(match.currentRound).build())
        .build());
```

That is **server push** — the opposite of unary polling. See [Lesson 2](./02-grpc-patterns.md) for the full protocol comparison.

### When to use which

| Type | Use for |
|---|---|
| `Uni` | Unary RPCs, single DB lookup, one response |
| `Multi` | Server-streaming, bidirectional streaming, event feeds |

---

## Chaining: the heart of Mutiny

The power is composition. Here is a simplified `submitMove` pipeline:

```java
return UnaryMatch.findByMatchIdForUpdate(request.getMatchId())    // lock the match row
    .chain(match -> {
        if (match == null || match.status == MatchStatus.COMPLETED) {
            return Uni.createFrom().item(/* error response */);
        }
        return UnaryRound.findByMatchAndRound(matchId, roundNumber)
            .chain(round -> {
                if (round == null) {
                    // first player this round — insert half-round
                    return newRound.persist().replaceWith(/* ACCEPTED */);
                } else {
                    // second player — complete round, update stats
                    round.playerTwoMove = request.getMove();
                    round.outcome = GameLogic.determineWinner(...);
                    return updateMatchStats(match, round).replaceWith(/* ACCEPTED */);
                }
            });
    });
```

Each `.chain()` waits for the previous `Uni` to complete before running the next step. Failures propagate to `.onFailure()` handlers if you add them.

**Critical rule:** if you call `persist()` and do not chain or subscribe to the returned `Uni`, **the write never happens**. Hibernate Reactive does not flush implicitly on a bare method return.

---

## The golden rule: never block the event loop

These will freeze the server under load:

```java
Thread.sleep(1000);                    // ❌ blocks I/O thread
result = someUni.await().indefinitely(); // ❌ in server code
jdbcConnection.prepareStatement(...);  // ❌ blocking JDBC on event loop
```

If you must run blocking code, move it explicitly:

```java
return Uni.createFrom().item(() -> legacyBlockingCall())
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
```

The arena's reactive path avoids blocking JDBC entirely — it uses Hibernate **Reactive** with the non-blocking PostgreSQL client. That pairing is [Lesson 3](./03-hibernate-reactive.md).

---

## Transactions: `@WithTransaction`

Reactive transactions wrap the whole method:

```java
@WithTransaction
public Uni<RegisterResponse> register(RegisterRequest request) { ... }
```

This:

1. Opens a reactive transaction before the method runs.
2. Flushes the session when the returned `Uni` completes successfully.
3. Rolls back automatically if the `Uni` fails.

The virtual-thread server uses blocking `@Transactional` instead — same idea, different API. Compare in [Lesson 6](./06-virtual-threads-vs-reactive.md).

---

## Client-side Mutiny (for tests and demo clients)

Server code returns `Uni`. **Client** code often *awaits* it:

```java
RegisterResponse reg = client.register(request)
    .await().atMost(Duration.ofSeconds(10));
```

That is fine in tests and CLI clients. Do not `.await()` inside server service methods — you would block the event loop you are trying to protect.

The unary client's polling loop uses another Mutiny pattern:

```java
Multi.createBy().repeating()
    .uni(() -> stub.checkRoundResult(...))
    .until(res -> "COMPLETE".equals(res.getStatus()))
    .collect().last()
    .await().atMost(Duration.ofSeconds(30));
```

Elegant on the client. Expensive on the database. That tension is the point of [Lesson 2](./02-grpc-patterns.md).

---

## Context propagation (why Quarkus + Mutiny)

Raw thread pools lose request context — security identity, tracing IDs, transaction boundaries. Quarkus propagates the Vert.x **duplicated context** through Mutiny operators automatically. Your `@WithTransaction` scope and logging MDC follow the `chain()` calls without manual `ThreadLocal` hacks.

---

## Try it yourself

```bash
# Start the reactive server
./run-server.sh mutiny

# In another terminal — watch the logs while clients play
./run-streaming-client.sh "Mutiny-1" "SplittableRandom" &
./run-streaming-client.sh "Mutiny-2" "L64X128MixRandom"
```

**Exercise:** Add a temporary `Thread.sleep(5000)` inside `register` in `UnaryArenaServiceImpl`. Restart, run two unary clients, watch concurrent requests stall. Remove it. That is the event loop penalty in miniature.

---

## Is reactive still the right default?

Not always. This repo implements the **same arena again** with virtual threads (`vt-server`) — blocking Java that scales because the JVM unmounts virtual threads during I/O. Read the side-by-side in **[Lesson 6](./06-virtual-threads-vs-reactive.md)** before choosing a stack for your own service.

For the historical context behind event loops and thread pools, see **[Appendix A](./appendix-a-threading-models.md)**.

---

## What's next

**[Lesson 2: Unary vs streaming](./02-grpc-patterns.md)** — the gRPC design decision that matters more than any framework choice.
