# Lesson 3: Where State Lives

**Goal:** Understand how the arena stores (or avoids storing) game state — and why your gRPC pattern drives your persistence design.

> **Prerequisites:** [Lesson 2](./02-grpc-patterns.md)

This lesson is titled "Hibernate Reactive" in the repo history, but the real topic is broader: **state is an architectural choice, not a framework feature.** Hibernate Reactive is simply how the reactive server talks to PostgreSQL without blocking.

---

## Three places to remember a match

| Location | Used by | Durability | Speed | Survives restart? |
|---|---|---|---|---|
| **PostgreSQL rows** | Unary service | Durable | Slow (ms per query) | Yes |
| **In-memory maps** | Streaming service (active play) | Volatile | Fast (µs) | No |
| **The gRPC stream** | Streaming clients | Connection-scoped | Instant | No |

The unary path treats the database as **working memory** — every RPC reloads context. The streaming path treats the connection as **working memory** and writes summary stats once at the end.

---

## Unary state: tables as game board

### `UnaryMatch` — the match record

```java
@Entity
public class UnaryMatch extends PanacheEntity {
    public String matchId;
    public String playerOneName;
    public String playerTwoName;
    public int playerOneWins;
    public int playerTwoWins;
    public int ties;
    public int currentRound = 1;
    public MatchStatus status;   // WAITING_FOR_OPPONENT, READY, COMPLETED
    public Instant createdAt;
    public Instant startedAt;
}
```

Every `Register`, `SubmitMove`, and `CheckRoundResult` begins with a lookup on `matchId`. The client carries the key; the server validates it against a row.

### `UnaryRound` — one row per round

```java
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"matchId", "roundNumber"}))
public class UnaryRound extends PanacheEntity {
    public String matchId;
    public int roundNumber;
    public Integer playerOneMove;
    public Integer playerTwoMove;
    public String outcome;
    public RoundStatus status;   // WAITING_PLAYER_TWO, COMPLETE
}
```

The unique constraint on `(matchId, roundNumber)` prevents duplicate half-rounds — a real bug that appeared when two players submitted concurrently without locking.

### Pessimistic locking — concurrency is not free

When both players call `SubmitMove` for the same round at the same time, both might read "no round yet" and each insert a half-round — the match hangs forever in the poll loop.

The fix:

```java
public static Uni<UnaryMatch> findByMatchIdForUpdate(String matchId) {
    return find("matchId", matchId)
        .withLock(LockModeType.PESSIMISTIC_WRITE)
        .firstResult();
}
```

`submitMove` locks the match row for the duration of the transaction, serializing concurrent submits. **Stateless RPCs still need concurrency control** — you just moved the problem into the database.

---

## Streaming state: memory for play, database for history

During a streaming match, state lives in plain Java objects:

```java
// Conceptual — see StreamingArenaServiceImpl
ConcurrentHashMap<String, StreamPlayer> waitingPlayers;
ConcurrentHashMap<String, StreamMatch> activeMatches;
```

Each `StreamMatch` holds direct references to both players' `BroadcastProcessor` instances. When both moves arrive, `processRound` runs immediately — no SELECT, no poll.

At match completion, **one** `MatchStatistics` row is written:

```java
@Entity
public class MatchStatistics extends PanacheEntity {
    public String matchId;
    public String matchType;        // "STREAMING" or "UNARY"
    public String playerOneLanguage;
    public int playerOneRocks, playerOnePapers, playerOneScissors;
    public double playerOneBias;    // PRNG skew detection
    public long databaseIops;       // 1 for streaming, ~6000 for unary
    public double roundsPerSecond;
}
```

The streaming path **chooses** not to persist every round because the protocol does not require it. If the server crashes mid-match, that match is lost — an acceptable trade-off for a game; maybe not for a bank transfer.

---

## Hibernate Reactive: non-blocking access to PostgreSQL

Traditional Hibernate ORM uses blocking JDBC. On a reactive event loop, one blocking query freezes all connections sharing that thread.

**Hibernate Reactive** uses the Vert.x PostgreSQL client — queries return `Uni`:

```java
// mutiny-server entity
public static Uni<UnaryMatch> findByMatchId(String matchId) {
    return find("matchId", matchId).firstResult();
}

public static Uni<List<UnaryMatch>> findWaitingMatches() {
    return list("status", MatchStatus.WAITING_FOR_OPPONENT);
}
```

Compare the virtual-thread entity — **identical fields**, blocking return types:

```java
// vt-server entity
public static List<UnaryMatch> findWaitingMatches() {
    return list("status", MatchStatus.WAITING_FOR_OPPONENT);
}
```

Same schema. Same SQL. Different way of waiting for the answer. See [Lesson 6](./06-virtual-threads-vs-reactive.md).

### Transactions: `@WithTransaction` vs `@Transactional`

| | Reactive (`mutiny-server`) | Blocking (`vt-server`) |
|---|---|---|
| Annotation | `@WithTransaction` | `@Transactional` |
| Return type | Must chain `Uni` | Plain return |
| Rollback | Automatic on `Uni` failure | Automatic on exception |

### The lazy-write trap

```java
// ❌ WRONG — write never happens
void badExample() {
    UnaryMatch match = new UnaryMatch();
    match.persist();  // returns Uni<Void> — ignored!
}

// ✅ CORRECT
return newMatch.persist().replaceWith(response);
```

In reactive code, **unsubscribed `Uni`s are no-ops**. This bites newcomers constantly.

---

## Configuration: reactive URL vs JDBC

Dev mode — only specify the database kind:

```properties
quarkus.datasource.db-kind=postgresql
# Dev Services starts PostgreSQL and wires both reactive + JDBC URLs
```

Production — both URLs required (reactive for queries, JDBC for schema tooling):

```properties
%prod.quarkus.datasource.reactive.url=postgresql://prod-db:5432/arena
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://prod-db:5432/arena
%prod.quarkus.datasource.username=quarkus
%prod.quarkus.datasource.password=quarkus
```

The `%prod.` prefix activates only when Quarkus runs in production mode. Dev Services magic never leaks to prod. Details in [Lesson 5](./05-dev-services.md).

### Why include JDBC in a reactive project?

Even a purely reactive app often includes `quarkus-jdbc-postgresql`:

1. **Dev Services** — mature Testcontainers integration for PostgreSQL
2. **Schema management** — Hibernate ORM DDL runs over JDBC during startup
3. **Transitive extensions** — some Quarkus extensions expect a JDBC driver at build time

One container, two connection paths. Quarkus manages both.

---

## Decision guide: where should *your* state go?

```
Is each request independent?
├── YES → Stateless handlers, persist only what you must (unary pattern)
└── NO  → Long-lived interaction
         ├── Can you afford to lose in-flight state on crash?
         │   ├── YES → In-memory + optional summary writes (streaming pattern)
         │   └── NO  → Stream + durable event log, or state machine in DB
         └── Do clients poll?
             └── YES → You probably wanted server push instead
```

---

## Exercises

1. **Watch SQL** — Set `quarkus.hibernate-orm.log.sql=true`, run two unary clients, count statements per round.
2. **Find the lock** — Trace `findByMatchIdForUpdate` from `submitMove`. What happens without it?
3. **Stats row** — After a streaming match, query `MatchStatistics` in PostgreSQL (`databaseIops` should be `1`).
4. **Diff entities** — Compare `mutiny-server/.../UnaryMatch.java` with `vt-server/.../UnaryMatch.java`. Same fields, different base imports.

---

## What's next

You know the protocol and the data model. Now prove it works:

**[Lesson 4: Testing the contract](./04-testing-standards.md)**
