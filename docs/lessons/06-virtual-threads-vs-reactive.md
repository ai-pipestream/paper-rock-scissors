# Lesson 6: Virtual Threads vs Reactive — The Same Arena, Two Ways

**Goal:** Read two complete implementations of the same gRPC contract and decide which concurrency model fits your team — without framework marketing.

> **Prerequisites:** [Lesson 1](./01-mutiny-reactive.md) or [Appendix A](./appendix-a-threading-models.md), plus [Lesson 2](./02-grpc-patterns.md)

[Lesson 1](./01-mutiny-reactive.md) explained how `mutiny-server` uses Mutiny. This lesson puts that approach **on trial** against `vt-server` — same `.proto`, same PostgreSQL schema, same clients, different way of waiting.

| Module | Concurrency | Persistence | Code shape |
|---|---|---|---|
| [`mutiny-server`](../../mutiny-server) | Mutiny `Uni`/`Multi` | Hibernate **Reactive** | Chained async pipelines |
| [`vt-server`](../../vt-server) | `@RunOnVirtualThread` | Hibernate **ORM** (JDBC) | Plain blocking Java |

Both start PostgreSQL via Dev Services. Go/Python/Java clients cannot tell them apart on the wire.

---

## The core difference in one method

`register` — a client joins; we pair them with a waiting opponent or create a new waiting match.

### Reactive (`mutiny-server`)

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
                return match.persist().replaceWith(
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

### Virtual threads (`vt-server`)

```java
@Transactional
public RegisterResponse register(RegisterRequest request) {
    List<UnaryMatch> waitingMatches = UnaryMatch.findWaitingMatches();

    if (!waitingMatches.isEmpty()) {
        UnaryMatch match = waitingMatches.get(0);
        match.playerTwoName = request.getLanguageName();
        match.status = UnaryMatch.MatchStatus.READY;
        match.startedAt = Instant.now();
        match.persist();
        return RegisterResponse.newBuilder()
            .setMatchId(match.matchId)
            .setStatus("READY")
            .build();
    }

    UnaryMatch newMatch = new UnaryMatch();
    newMatch.matchId = UUID.randomUUID().toString();
    newMatch.playerOneName = request.getLanguageName();
    newMatch.status = UnaryMatch.MatchStatus.WAITING_FOR_OPPONENT;
    newMatch.persist();
    return RegisterResponse.newBuilder()
        .setStatus("WAITING_FOR_OPPONENT")
        .build();
}
```

Read them side by side:

- Reactive: every branch returns a `Uni`; logic lives inside `.chain()` lambdas.
- Virtual threads: ordinary `if/else`, `return`, stack traces point at **your line numbers**.

**Where did the async go?** Into the JVM. `findWaitingMatches()` blocks on JDBC, but the method runs on a **virtual thread**. When it blocks, the JVM unmounts it from the carrier OS thread and schedules other work. You wrote thread-per-request code; you got event-loop scalability.

---

## How `vt-server` is wired

Three small pieces connect blocking Java to gRPC.

### 1. `@RunOnVirtualThread` on the gRPC method

Quarkus still generates Mutiny stubs, so the signature returns `Uni`. The adapter is thin:

```java
@GrpcService
@Singleton
public class UnaryArenaServiceImpl implements UnaryArenaService {

    @Inject ArenaRepository arena;

    @Override
    @RunOnVirtualThread
    public Uni<RegisterResponse> register(RegisterRequest request) {
        return Uni.createFrom().item(arena.register(request));
    }
}
```

Inside `@RunOnVirtualThread`, blocking is allowed. The gRPC layer wraps the finished result in `Uni.createFrom().item(...)`.

### 2. Repository split — testable blocking logic

Business logic lives in `ArenaRepository` — plain `@ApplicationScoped` methods returning plain types. Unit-test without gRPC or Mutiny:

```java
@Inject ArenaRepository arena;
RegisterResponse resp = arena.register(request);
assertEquals("READY", resp.getStatus());
```

### 3. Different Gradle dependencies

```gradle
// mutiny-server
implementation 'io.quarkus:quarkus-hibernate-reactive-panache'
implementation 'io.quarkus:quarkus-reactive-pg-client'

// vt-server
implementation 'io.quarkus:quarkus-hibernate-orm-panache'
implementation 'io.quarkus:quarkus-jdbc-postgresql'
```

Entities are field-identical; only the Panache import and return types differ (`Uni<T>` vs `T`).

---

## Streaming: where the gap shrinks

Bidirectional gRPC is `Multi → Multi` **in both variants**. Match orchestration is already synchronous Java over in-memory state — see [Lesson 2](./02-grpc-patterns.md).

Open `StreamingArenaServiceImpl` in both modules: pairing, rounds, and push logic are nearly identical.

The divergence is the end-of-match stats write:

```java
// mutiny-server
return Panache.withTransaction(stats::persist).replaceWithVoid();

// vt-server — fire-and-forget on a virtual thread so JDBC never blocks the emitter
Thread.ofVirtual().start(() -> streamStats.save(stats));
```

**Takeaway:** virtual threads help proportional to **blocking I/O per request**. Unary arena = many DB round-trips = big win. Streaming arena = one write at end = small win.

---

## Pinning and JDK 24+

Early virtual-thread adopters hit **pinning**: `synchronized` blocks pinned the carrier OS thread, silently reintroducing pool starvation.

**[JEP 491](https://openjdk.org/jeps/491) (JDK 24)** fixed `synchronized` pinning. This project runs **JDK 25**. The historical reason to prefer reactive *just* to avoid pinning is gone for typical service code.

Native/JNI frames can still pin — rare in ordinary CRUD services.

---

## Decision table

| | Reactive (`mutiny-server`) | Virtual threads (`vt-server`) |
|---|---|---|
| Code shape | `Uni`/`Multi` chains | Plain blocking Java |
| Learning curve | Steep (operators, lazy execution) | Normal Java |
| Debugging | Stack traces into Mutiny operators | Your own line numbers |
| Stream operators / backpressure | Built into `Multi` | Manual |
| Best for | Heavy streaming fan-in/out, operator-rich pipelines | I/O-bound request/response |
| This repo's default for new I/O work | Honest alternative | **Preferred** |

Neither is wrong. `mutiny-server` exists in full — not as a straw man — because teams fluent in reactive should not rewrite working systems on hype.

---

## Try it yourself

```bash
# Terminal 1 — reactive
./run-server.sh mutiny

# Terminal 2 — same clients, same game (two needed to form a match)
./run-streaming-client.sh "Reactive-1" "SplittableRandom" &
./run-streaming-client.sh "Reactive-2" "L64X128MixRandom"

# Stop, switch server
./run-server.sh vt
./run-streaming-client.sh "VT-1" "SplittableRandom" &
./run-streaming-client.sh "VT-2" "L64X128MixRandom"
```

Side-by-side benchmark (different HTTP ports):

```bash
./gradlew :vt-server:quarkusDev -Dquarkus.http.port=8081 &
./run-server.sh mutiny   # port 8080
# point clients at each port, compare rounds/sec in MatchStatistics
```

**Exercise:** `diff -u mutiny-server/src/main/java/ai/pipestream/arena/v1/service/UnaryArenaServiceImpl.java vt-server/src/main/java/ai/pipestream/arena/v1/service/UnaryArenaServiceImpl.java` — see the adapter vs logic split.

---

## What's next

You can implement the contract two ways on Quarkus. What about **no framework at all** — and clients in three languages?

**[Lesson 7: Deploy anywhere, clients in any language](./07-deployment-and-polyglot.md)**
