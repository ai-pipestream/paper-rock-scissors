# Lesson 6: Virtual Threads vs Reactive — The Same Arena, Two Ways

[Lesson 1](./01-mutiny-reactive.md) toured seven threading models and ended on a claim: virtual threads (Project Loom) "aim to make reactive programming unnecessary for I/O-bound workloads." This lesson puts that claim on trial. We built the **exact same arena twice** so you can read both and decide for yourself:

| Module | Concurrency model | Persistence | Java |
|---|---|---|---|
| [`mutiny-server`](../../mutiny-server) | Reactive (Mutiny `Uni`/`Multi`) | Hibernate **Reactive** Panache | 21 |
| [`vt-server`](../../vt-server) | **Virtual threads** (`@RunOnVirtualThread`) | Hibernate **ORM** Panache (blocking JDBC) | 21 |

Both implement the same `.proto` contracts (shared from [`common`](../../common)), both pass the same clients, both start a PostgreSQL container via Dev Services. The *only* thing that differs is how they get work done while waiting on the database.

> **The short version.** The virtual-threads version is meaningfully easier to write, read, test, and debug, and for this workload it is just as fast. That is why this project now leads with it. The reactive version is kept as a first-class citizen because reactive is still the right tool in some situations — we'll be honest about which.

---

## The core difference in one method

Here is `register` — a client joins the arena, and we either pair them with a waiting opponent or park them as a new waiting match. Same logic, same SQL, both variants.

### Reactive (`mutiny-server`)

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
                return match.persist().replaceWith(              // Uni<Void> -> Uni<RegisterResponse>
                    RegisterResponse.newBuilder()
                        .setMatchId(match.matchId)
                        .setStatus("READY")
                        .build());
            } else {
                UnaryMatch newMatch = new UnaryMatch();
                // ... populate ...
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
    List<UnaryMatch> waitingMatches = UnaryMatch.findWaitingMatches();  // just a List

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
    // ... populate ...
    newMatch.persist();
    return RegisterResponse.newBuilder()
        .setStatus("WAITING_FOR_OPPONENT")
        .build();
}
```

Read them side by side. The reactive version threads its logic through `.chain(...)`, `.replaceWith(...)`, and a lambda whose two branches must each *return a `Uni`*. The virtual-threads version is the code you would write on day one of learning Java: call the database, look at the result, `return` a response. There is no `Uni`, no `chain`, no "every branch must produce a publisher." A stack trace from the blocking version points at the exact line; a failure in the reactive chain points into Mutiny's operators.

**Where did the async go?** It didn't disappear — it moved into the runtime. `findWaitingMatches()` and `persist()` are ordinary blocking JDBC calls, but because the whole method runs on a **virtual thread**, when it blocks on the database the JVM unmounts it from its carrier (OS) thread and runs something else. You wrote thread-per-request code; you got event-loop scalability. That is the whole pitch of Loom, and [Lesson 1 §6](./01-mutiny-reactive.md#6-virtual-threads-project-loom--green-threads) explains the mechanism.

---

## How `vt-server` is wired

Three small pieces make the blocking style work under gRPC.

### 1. `@RunOnVirtualThread` on the gRPC method

Quarkus generates a Mutiny-flavored service interface, so the method signature still returns `Uni`. The trick is that with `@RunOnVirtualThread`, Quarkus runs the **method body on a virtual thread** — so inside it you may block freely, then wrap the finished result:

```java
@GrpcService
@Singleton
public class UnaryArenaServiceImpl implements UnaryArenaService {

    @Inject ArenaRepository arena;

    @Override
    @RunOnVirtualThread
    public Uni<RegisterResponse> register(RegisterRequest request) {
        return Uni.createFrom().item(arena.register(request));  // arena.register() blocks — on a VT
    }
}
```

The gRPC layer is a three-line adapter. All the real work is in `ArenaRepository`, which is plain blocking Java (the method shown above). Contrast this with the reactive server, where the `Uni` plumbing and the business logic are interleaved in the same method.

> **Why the split into a repository?** Keeping the blocking, `@Transactional` logic in an `@ApplicationScoped` bean (returning plain types) means it can be unit-tested with no gRPC and no reactive test harness — you call a method and assert on the returned object. It also sidesteps mixing JTA `@Transactional` with a reactive return type.

### 2. Blocking Hibernate ORM instead of Hibernate Reactive

The two servers use **different persistence stacks**, and they cannot share a module because of it:

```gradle
// mutiny-server
implementation 'io.quarkus:quarkus-hibernate-reactive-panache'
implementation 'io.quarkus:quarkus-reactive-pg-client'

// vt-server
implementation 'io.quarkus:quarkus-hibernate-orm-panache'
implementation 'io.quarkus:quarkus-jdbc-postgresql'
```

The entities are line-for-line identical except for the base class — `PanacheEntity` from `hibernate.reactive.panache` vs `hibernate.orm.panache` — which flips the finder return types:

```java
// mutiny-server: reactive finders
public static Uni<List<UnaryMatch>> findWaitingMatches() { return list("status", WAITING_FOR_OPPONENT); }

// vt-server: blocking finders
public static List<UnaryMatch> findWaitingMatches() { return list("status", WAITING_FOR_OPPONENT); }
```

### 3. `@Transactional` instead of `@WithTransaction`

Blocking JTA (`jakarta.transaction.Transactional`) replaces the reactive `@WithTransaction`. Because the method runs on a virtual thread, the blocking transaction never pins a platform thread.

---

## Streaming: where the difference nearly vanishes

The bidirectional `battle` stream is a useful reality check on the hype. A gRPC bidi call is `Multi<Request> -> Multi<Response>` **at the wire in both variants** — gRPC's streaming model is inherently event-driven, and virtual threads don't change it. Open [`StreamingArenaServiceImpl`](../../vt-server/src/main/java/ai/pipestream/arena/v1/service/StreamingArenaServiceImpl.java) in each module and you'll find the match orchestration is nearly identical, because it was already plain synchronous Java operating on in-memory state (the connection *is* the state — see [Lesson 2](./02-grpc-patterns.md)).

The one place they diverge is the single database write per match:

```java
// mutiny-server: reactive transaction, returns a Uni that gets subscribed
return Panache.withTransaction(stats::persist).replaceWithVoid();

// vt-server: plain @Transactional call, offloaded to a virtual thread so the
// blocking JDBC write never lands on the reactive emitter thread
Thread.ofVirtual().start(() -> streamStats.save(stats));
```

**Takeaway:** virtual threads pay off in proportion to how much *blocking I/O* a request does. The unary arena is a chain of database round-trips per move — big win. The streaming arena is mostly in-memory with one write at the end — small win. Reach for VT where the work is I/O-bound; don't expect magic where it isn't.

---

## When reactive is still the right call

Virtual threads are not a universal replacement. Keep reactive (`mutiny-server`) in mind when:

- **You need streaming operators and backpressure.** `Multi` gives you `filter`, `group`, `onOverflow`, and demand-driven backpressure for free. Hand-rolling those over blocking iterators is real work.
- **Third-party code pins.** A virtual thread that enters a `synchronized` block or calls native/JNI code **pins** its carrier thread, silently reintroducing thread-pool starvation. Old JDBC drivers and libraries are the usual offenders (`ReentrantLock` avoids it; migrating a dependency tree does not). If your hot path pins, reactive's non-blocking I/O sidesteps the problem entirely. Profile before you assume.
- **`ThreadLocal`-heavy libraries.** Caches keyed on `ThreadLocal` can allocate one instance per virtual thread — and there can be millions of them.

And keep virtual threads in mind — the default for new I/O-bound services here — when you want blocking-style code that scales, readable stack traces, and a mental model your whole team already has.

| | Reactive (`mutiny-server`) | Virtual threads (`vt-server`) |
|---|---|---|
| Code shape | `Uni`/`Multi` chains | plain blocking Java |
| Learning curve | steep (operators, "colored" functions) | none beyond normal Java |
| Stack traces | into Mutiny operators | your own line numbers |
| Backpressure / stream ops | built in | manual |
| Failure mode | never blocks | pinning (`synchronized`/JNI) |
| Best for | streaming, heavy fan-in/out, pinning-prone deps | I/O-bound request/response, teams new to reactive |

---

## Try it

```bash
# Reactive arena
./run-server.sh mutiny        #  == ./gradlew :mutiny-server:quarkusDev

# Virtual-threads arena (same clients, same contracts)
./run-server.sh vt            #  == ./gradlew :vt-server:quarkusDev
```

Point the Go or Python client (see the [README](../../README.md)) at either one — they can't tell the difference from the outside. That's the point: **same behavior on the wire, very different developer experience behind it.**
