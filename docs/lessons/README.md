# Paper-Rock-Scissors Arena — Tutorial Series

> **Learn gRPC by watching two ways to run the same game — then swap the server, the language, and the runtime underneath without changing the clients.**

This repository is a hands-on course in **gRPC service design and deployment choices**. Quarkus, Mutiny, Hibernate, and virtual threads appear here as *implementation options*, not prerequisites. If you can run Docker and Java 21, you can follow along.

---

## Before you start

| Requirement | Why |
|---|---|
| **Java 21+** | Virtual-thread server and Gradle toolchain |
| **Docker** | Quarkus Dev Services starts PostgreSQL automatically |
| **5 minutes** | Enough to complete the Quick Start below |

Optional but fun later: Go 1.21+, Python 3, `protoc` for polyglot clients.

---

## Quick start (do this first)

```bash
# Terminal 1 — start the arena (virtual threads + PostgreSQL via Dev Services)
./run-server.sh vt

# Terminal 2 — play a streaming match with the Java client
./run-streaming-client.sh "Java-1" "java.util.Random"
```

You should see the server pair two clients and run 1,000 rounds. That is gRPC bidirectional streaming in action — no REST, no polling, no framework magic yet.

Try the unary path next:

```bash
./run-unary-client.sh "Java-A" "java.util.Random" &
./run-unary-client.sh "Java-B" "java.security.SecureRandom"
```

Watch how much chattier the unary client is (submit move → poll → poll → poll…). That pain is intentional. The lessons explain why.

---

## Learning path

Follow the lessons in order. Each one answers one production question.

| # | Lesson | Question it answers | Time |
|---|---|---|---|
| **0** | [Your first gRPC call](./00-grpc-primer.md) | What *is* gRPC, and how do I talk to this server? | ~15 min |
| **1** | [Reactive handlers with Mutiny](./01-mutiny-reactive.md) | How does the reactive server wait without blocking threads? | ~20 min |
| **2** | [Unary vs streaming](./02-grpc-patterns.md) | Which RPC shape should my API use? | ~30 min |
| **3** | [Where state lives](./03-hibernate-reactive.md) | Database, memory, or the connection itself? | ~25 min |
| **4** | [Testing the contract](./04-testing-standards.md) | How do I prove the wire protocol works? | ~20 min |
| **5** | [Dev laptop → production config](./05-dev-services.md) | How do I run this locally and ship it safely? | ~20 min |
| **6** | [Virtual threads vs reactive](./06-virtual-threads-vs-reactive.md) | Same `.proto`, two ways to implement I/O — which do I pick? | ~25 min |
| **7** | [Deploy anywhere, clients in any language](./07-deployment-and-polyglot.md) | JVM, native, vanilla Netty, Go/Python tournament | ~30 min |

**Appendix:** [Threading models tour](./appendix-a-threading-models.md) — deep background on why non-blocking servers exist (optional reading).

---

## Decision ladder

Each lesson maps to one rung on the ladder every gRPC engineer climbs:

```
1. Shape the API        →  Lesson 0 + 2   (unary vs streaming)
2. Place the state      →  Lesson 3       (DB vs memory vs stream)
3. Handle waiting       →  Lesson 1 + 6   (reactive vs virtual threads)
4. Prove it works       →  Lesson 4       (in-process vs black-box tests)
5. Run it everywhere    →  Lesson 5 + 7   (dev → prod → polyglot)
```

---

## Three servers, one contract

All lessons reference the same `.proto` files in `common/src/main/proto/`. Three server modules implement them differently:

| Module | Stack | When to study it |
|---|---|---|
| [`mutiny-server`](../../mutiny-server) | Quarkus + Mutiny + Hibernate Reactive | Lessons 1–5 (reactive path) |
| [`vt-server`](../../vt-server) | Quarkus + virtual threads + Hibernate ORM | Lesson 6 (blocking-style path) |
| [`netty-server`](../../netty-server) | Vanilla grpc-java / Netty, no framework | Lesson 7 (control group) |

Clients in Go, Python, and Java cannot tell them apart on the wire. That is the point.

---

## Exercises (try between lessons)

1. **Feel the polling tax** — Run two unary clients and enable SQL logging (`quarkus.hibernate-orm.log.sql=true`). Count SELECTs per round.
2. **Swap the server** — Point a Go client at `vt` (port 8080) then at `netty` (port 9000). Same game, different runtime.
3. **Break Dev Services** — Stop Docker, run `./run-server.sh vt`, read the error, understand what Dev Services was doing for you.
4. **Run the tournament** — `tournament/run-tournament.sh --port 8080` pits Go, Python, and Java PRNGs against each other.
5. **Diff the implementations** — `diff mutiny-server/src/main/java/.../UnaryArenaServiceImpl.java vt-server/src/main/java/.../UnaryArenaServiceImpl.java`

---

## Where to go next

- [Main README](../../README.md) — project overview, tournament, architecture summary
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — sequence diagrams and performance tables
- [clients/go/README.md](../../clients/go/README.md) and [clients/python/README.md](../../clients/python/README.md) — polyglot client details
