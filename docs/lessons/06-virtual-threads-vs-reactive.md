# Lesson 6: Virtual Threads vs Reactive — The Same Arena, Two Ways

[Lesson 1](./01-mutiny-reactive.md) toured seven threading models and ended on a claim: virtual threads (Project Loom) "aim to make reactive programming unnecessary for I/O-bound workloads." This lesson puts that claim on trial. We built the **exact same arena twice** so you can read both and decide for yourself:

| Module | Concurrency model | Persistence | Java |
|---|---|---|---|
| [`mutiny-server`](../../mutiny-server) | Reactive (Mutiny `Uni`/`Multi`) | Hibernate **Reactive** Panache | 21 |
| [`vt-server`](../../vt-server) | **Virtual threads** (`@RunOnVirtualThread`) | Hibernate **ORM** Panache (blocking JDBC) | 21 |

Both implement the same `.proto` contracts (shared from [`common`](../../common)), both pass the same clients, both start a PostgreSQL container via Dev Services. The *only* thing that differs is how they get work done while waiting on the database.

> **The short version.** Here are both, in full, doing the same job — read them and decide where your own preference lies. Ours is virtual threads for this kind of I/O-bound request/response work: the code is ordinary blocking Java, so it's easier to write, test, and — the thing that settled it for us after time in production — *debug*. A stack trace points at your own line number, not into a chain of operators, and you can step through it in a debugger the way you already know how. The reactive version is a mature, capable approach that plenty of teams run happily; we keep it here in full, not as a straw man. Two things about virtual threads are worth knowing before you choose, and we cover them below.

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

## Two things to know before you pick virtual threads

We prefer virtual threads, but that preference comes with two honest caveats. Neither is a reason to avoid them today; both are worth knowing so you evaluate them on their current state, not their reputation.

1. **They had a rocky start.** Loom was in preview through JDK 19–20 and only went GA in **21** (September 2023). The first stretch was bumpy: profilers and debuggers were catching up, some `ThreadLocal`-heavy libraries behaved badly at scale, and — the headline gotcha — `synchronized` *pinned* (see below). If you tried virtual threads early and bounced off, that's fair; a lot has been smoothed out since, and the tooling is now solid.

2. **Pinning is fixed.** The one that actually bit people: a virtual thread that entered a `synchronized` block pinned its carrier (OS) thread for the duration, so a lock around a blocking call could quietly reintroduce thread-pool starvation. The old advice was "audit your whole dependency tree for `synchronized`." **[JEP 491](https://openjdk.org/jeps/491) (JDK 24, March 2025) removed it** — `synchronized` no longer pins, and `Object.wait()` is fixed too. On JDK 24+ (this project runs **25**) the biggest reason teams reached for reactive *to avoid pinning* is gone. Native/foreign (JNI) frames can still pin, but that's rare in ordinary service code.

So the fairest way to read this lesson: on a modern JDK, virtual threads deliver the scalability without the sharp edges that defined their debut. Where you land is still your call.

## Where reactive still fits

Virtual threads aren't a universal replacement, and this isn't a eulogy for reactive — `mutiny-server` is here, complete, because it's a legitimate choice. Reach for it when:

- **You need streaming operators and real backpressure.** `Multi` gives you `filter`, `group`, `onOverflow`, and demand-driven backpressure out of the box. Hand-rolling those over blocking iterators is genuine work, and this is where reactive is strongest.
- **The codebase (or team) is already fluent in reactive.** Consistency has value; a shop that thinks in `Uni`/`Multi` and has the operational muscle memory for it isn't wrong to stay there.
- **`ThreadLocal`-heavy libraries are in the hot path.** Caches keyed on `ThreadLocal` can allocate one instance per virtual thread, and there can be a great many of them — worth measuring.

Historically "third-party code pins" belonged on this list; JEP 491 took it off. If you last evaluated the trade-off before JDK 24, it's worth a fresh look.

| | Reactive (`mutiny-server`) | Virtual threads (`vt-server`) |
|---|---|---|
| Code shape | `Uni`/`Multi` chains | plain blocking Java |
| Learning curve | steep (operators, "colored" functions) | none beyond normal Java |
| Stack traces & debugging | into Mutiny operators | your own line numbers; step-through works |
| Backpressure / stream ops | built in | manual |
| `synchronized` pinning | n/a (never blocks) | fixed in JDK 24 (JEP 491); native/JNI can still pin |
| Best for | streaming, heavy fan-in/out, backpressure | I/O-bound request/response |
| Our preference | kept as a full, honest alternative | **default for new I/O-bound services** |

---

## Try it

```bash
# Reactive arena
./run-server.sh mutiny        #  == ./gradlew :mutiny-server:quarkusDev

# Virtual-threads arena (same clients, same contracts)
./run-server.sh vt            #  == ./gradlew :vt-server:quarkusDev
```

Point the Go or Python client (see the [README](../../README.md)) at either one — they can't tell the difference from the outside. That's the point: **same behavior on the wire, very different developer experience behind it.**
