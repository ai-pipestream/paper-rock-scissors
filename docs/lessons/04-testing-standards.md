# Lesson 4: Testing the Contract

**Goal:** Test your gRPC service at the right level — fast in-process tests for logic, black-box tests for packaging, polyglot clients for the real wire.

> **Prerequisites:** [Lesson 3](./03-hibernate-reactive.md)

Frameworks change. Generated stub packages change. The **`.proto` contract** is what your users depend on. Tests should anchor there.

---

## Two test levels in Quarkus

| | `@QuarkusTest` | `@QuarkusIntegrationTest` |
|---|---|---|
| **Location** | `src/test/java` | `src/integrationTest/java` |
| **Process** | Same JVM as the app | Separate process (packaged JAR) |
| **CDI `@Inject`** | Yes | No |
| **gRPC client** | `@GrpcClient` injection | Manual `ManagedChannel` |
| **Speed** | Fast | Slower (starts real server) |
| **Catches** | Logic bugs, DB issues | Packaging, port binding, prod startup |
| **Gradle task** | `./gradlew test` | `./gradlew quarkusIntTest` |

Use **both**. Unit-level for every change. Integration before release.

---

## Level 1: `@QuarkusTest` — inject a gRPC client

The real test in this repo plays a miniature match over the unary API:

```java
@QuarkusTest
public class UnaryArenaServiceTest {

    @GrpcClient
    UnaryArenaService client;   // Mutiny stub — injected, same JVM

    @Test
    void testFullMatchFlow() {
        String suffix = UUID.randomUUID().toString();

        RegisterResponse reg1 = client.register(RegisterRequest.newBuilder()
                .setLanguageName("P1-" + suffix)
                .build()).await().atMost(Duration.ofSeconds(10));

        RegisterResponse reg2 = client.register(RegisterRequest.newBuilder()
                .setLanguageName("P2-" + suffix)
                .build()).await().atMost(Duration.ofSeconds(10));

        String matchId = reg2.getMatchId();

        client.submitMove(SubmitMoveRequest.newBuilder()
                .setMatchId(matchId).setRoundNumber(1).setMove(0).build())
            .await().atMost(Duration.ofSeconds(5));

        client.submitMove(SubmitMoveRequest.newBuilder()
                .setMatchId(matchId).setRoundNumber(1).setMove(1).build())
            .await().atMost(Duration.ofSeconds(5));

        CheckRoundResultResponse result = client.checkRoundResult(
                CheckRoundResultRequest.newBuilder()
                    .setMatchId(matchId).setRoundNumber(1).build())
            .await().atMost(Duration.ofSeconds(5));

        assertEquals("COMPLETE", result.getStatus());
    }
}
```

What this validates:

- gRPC method signatures match the `.proto`
- Registration pairs two players
- Move submission completes a round
- Result is queryable without a poll loop (both moves already in)

**Dev Services** starts PostgreSQL automatically for this test — no manual database setup.

### Tips for stable `@QuarkusTest`

1. **Unique suffixes** — `UUID` in player names avoids collisions when tests run in parallel against shared waiting-match logic.
2. **`.await().atMost(...)`** — always bound wait time; hung tests fail fast in CI.
3. **Test the contract, not internals** — no `@Inject UnaryArenaServiceImpl`; call through the stub like a real client.

---

## Level 2: `@QuarkusIntegrationTest` — black box

Integration tests cannot inject CDI beans. They discover the running server's port and build a channel manually:

```java
@QuarkusIntegrationTest
public class UnaryArenaServiceIT {

    @TestHTTPResource
    URL url;                    // dynamically assigned port

    ManagedChannel channel;
    MutinyUnaryArenaServiceGrpc.MutinyUnaryArenaServiceStub client;

    @BeforeEach
    void init() {
        channel = ManagedChannelBuilder
            .forAddress(url.getHost(), url.getPort())
            .usePlaintext()
            .build();
        client = MutinyUnaryArenaServiceGrpc.newMutinyStub(channel);
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void testRegister() {
        RegisterResponse response = client.register(...)
            .await().atMost(Duration.ofSeconds(10));
        assertNotNull(response.getMatchId());
    }
}
```

This proves the **packaged application** exposes gRPC correctly — not just the dev classpath.

### Streaming integration test

`StreamingArenaServiceIT` opens two bidirectional streams, sends handshakes, and asserts `OPPONENT_FOUND` arrives within 10 seconds:

```java
BroadcastProcessor<BattleRequest> p1Request = BroadcastProcessor.create();
BroadcastProcessor<BattleRequest> p2Request = BroadcastProcessor.create();

client.battle(p1Request).subscribe().with(p1Responses::add);
client.battle(p2Request).subscribe().with(p2Responses::add);

p1Request.onNext(/* handshake */);
p2Request.onNext(/* handshake */);

// poll until OPPONENT_FOUND (integration test uses Thread.sleep — OK in tests)
assertTrue(matchCreated, "Match should be created within 10 seconds");
```

Streaming tests are inherently async — use timeouts and concurrent collections (`CopyOnWriteArrayList`).

---

## Level 3: Polyglot clients — the ultimate contract test

Generated Java stubs can hide proto mismatches that would break Go or Python. The CI pipeline (`.github/workflows/ci-cd.yml`) builds every client and then runs a dedicated **integration job** that starts each packaged server against a real PostgreSQL service container and plays matches with the polyglot clients:

```yaml
# .github/workflows/ci-cd.yml (simplified)
java-build:
  - ./gradlew test                                          # @QuarkusTest + Dev Services
  - ./gradlew :mutiny-server:quarkusBuild :vt-server:quarkusBuild
go-clients:
  - ./generate_protos.sh && go build ...
python-clients:
  - ./generate_protos.sh && python3 -m py_compile ...
integration-test:                       # needs all three jobs above
  services: postgres                    # real DB, prod-style config
  - start each packaged server, run Go/Python/Java clients against it
```

If Go compiles and plays a match against the running server, your **wire contract** is real.

Manual smoke test:

```bash
./run-server.sh vt &
sleep 30   # wait for Dev Services

cd clients/go && ./generate_protos.sh
go run streaming_client.go -host localhost -port 8080 -language Go-SmokeTest
```

---

## Gradle source sets

```
mutiny-server/
├── src/test/java/              ← @QuarkusTest
└── src/integrationTest/java/   ← @QuarkusIntegrationTest
```

```bash
./gradlew :mutiny-server:test           # fast, every commit
./gradlew :mutiny-server:quarkusIntTest # slower, pre-release
./gradlew test                          # all modules' unit tests
```

Native image tests (`./gradlew build -Dquarkus.native.enabled=true`) are even slower — run in CI nightly, not on every keystroke.

---

## What to test for each RPC pattern

### Unary

| Scenario | Assert |
|---|---|
| Solo register | `WAITING_FOR_OPPONENT`, match_id present |
| Pair register | Second player gets `READY`, same match_id |
| Submit before opponent | Round stays `PENDING` on check |
| Both submit | `COMPLETE`, valid outcome |
| Wrong round number | Error or rejection |
| Completed match | Further submits rejected |

### Streaming

| Scenario | Assert |
|---|---|
| Solo handshake | Waits in queue |
| Pair handshake | Both receive `OPPONENT_FOUND` |
| Full match | `MATCH_COMPLETE`, stats row inserted |
| Disconnect mid-match | Opponent gets `OPPONENT_DISCONNECTED` |
| `GetArenaResults` | Aggregates language stats after matches |

---

## Testing `vt-server` vs `mutiny-server`

Both implement the same `.proto`. The **same client tests** should pass against either — that is the point of [Lesson 6](./06-virtual-threads-vs-reactive.md).

Today the in-JVM `@QuarkusTest` suite lives in `mutiny-server` only; `vt-server` is exercised through the CI **integration job**, which starts its packaged app and runs the polyglot clients against it. That asymmetry is itself a lesson: because `vt-server`'s business logic lives in a plain blocking `ArenaRepository`, it is also the easier of the two to unit-test — call a method, assert on the returned object, no reactive test harness needed. Porting the test suite to `vt-server` is a good exercise.

---

## Exercises

1. **Add a test** — Write a `@QuarkusTest` that asserts Paper beats Rock (move 1 vs move 0 → WIN/LOSS).
2. **Break the contract** — Rename a proto field *number* (not name), regenerate, watch tests fail. Revert.
3. **Run integration only** — `./gradlew :mutiny-server:quarkusIntTest` and read the startup logs — notice the separate process.
4. **CI locally** — `./gradlew test` then build Go client and run against `quarkusDev`.

---

## What's next

Tests pass on your laptop because Dev Services provisions PostgreSQL. How does that work — and how do you configure production safely?

**[Lesson 5: Dev laptop → production config](./05-dev-services.md)**
