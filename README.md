# Paper-Rock-Scissors Arena

A high-performance gRPC arena for automated Paper-Rock-Scissors tournaments — built **two ways**. This project serves as a "Source of Truth" for modern Quarkus development standards, demonstrating best practices for gRPC, database access, testing, and — most of all — a head-to-head comparison of **reactive vs virtual threads** for the same workload.

## 🚀 Overview

The Arena provides two modes of engagement:
1.  **Unary (Stateless):** A classic polling-based approach where clients manage state via a match ID.
2.  **Streaming (Stateful):** A bidirectional stream where the connection *is* the match state, offering minimal latency.

…and it ships in **two interchangeable server implementations** of those same gRPC contracts:

| Module | Concurrency model | Persistence |
|---|---|---|
| **`mutiny-server`** | Reactive — Mutiny `Uni`/`Multi` | Hibernate **Reactive** Panache |
| **`vt-server`** | **Virtual threads** — `@RunOnVirtualThread` | Hibernate **ORM** Panache (blocking JDBC) |

Both are wire-compatible: the Go/Python clients can't tell them apart. The virtual-threads build is the recommended default (blocking-style code, just as fast for this I/O-bound workload, far easier to write); the reactive build is kept as a first-class citizen for streaming/backpressure and pinning-prone dependencies. See **[Lesson 6](./docs/lessons/06-virtual-threads-vs-reactive.md)** for the full comparison.

## 🛠 Technology Stack

*   **Runtime:** [Quarkus 3.37+](https://quarkus.io/)
*   **Protocol:** [gRPC](https://grpc.io/) — [Mutiny](https://smallrye.io/smallrye-mutiny/) (reactive) *or* [virtual threads](https://openjdk.org/jeps/444) (`@RunOnVirtualThread`)
*   **Database:** [PostgreSQL](https://www.postgresql.org/) — reactive PG client *or* blocking JDBC
*   **Persistence:** [Hibernate Reactive](https://quarkus.io/guides/hibernate-reactive-panache) *or* [Hibernate ORM with Panache](https://quarkus.io/guides/hibernate-orm-panache)
*   **Build Tool:** [Gradle](https://gradle.org/) (multi-module)
*   **Dev Productivity:** [Quarkus Dev Services](https://quarkus.io/guides/dev-services) (Zero-config Docker containers)

## 📖 Lessons & Standards

This project is documented through a series of technical lessons located in the `docs/` directory. Each lesson maps directly to the implementation in this repository.

*   **[Lesson 1: Reactive Programming with Mutiny](./docs/lessons/01-mutiny-reactive.md)**
*   **[Lesson 2: gRPC Unary vs Streaming](./docs/lessons/02-grpc-patterns.md)**
*   **[Lesson 3: Hibernate Reactive & Panache](./docs/lessons/03-hibernate-reactive.md)**
*   **[Lesson 4: Advanced Testing (Test vs IT)](./docs/lessons/04-testing-standards.md)**
*   **[Lesson 5: Dev Services & Environment](./docs/lessons/05-dev-services.md)**
*   **[Lesson 6: Virtual Threads vs Reactive — the same arena, two ways](./docs/lessons/06-virtual-threads-vs-reactive.md)**

## 🚦 Getting Started

### Prerequisites
*   **Java 21+** (virtual threads require it; both builds use the same toolchain)
*   Docker (for Dev Services)

### Running the Arena Server
Pick a variant — both start the Unary and Streaming services and an automatic PostgreSQL container (gRPC + health on HTTP port `8080`):

```bash
./run-server.sh vt        # virtual threads   (== ./gradlew :vt-server:quarkusDev)
./run-server.sh mutiny    # reactive          (== ./gradlew :mutiny-server:quarkusDev)
```

To run both at once for a side-by-side benchmark, start one with a different port, e.g. `./gradlew :vt-server:quarkusDev -Dquarkus.http.port=8081`.

### Running Reference Clients
While the server is running, you can test it using the provided clients:

*   **Unary Client:**
    ```bash
    ./gradlew run -PmainClass=ai.pipestream.client.v1.UnaryClient
    ```
*   **Streaming Client:**
    ```bash
    ./gradlew run -PmainClass=ai.pipestream.client.v1.StreamingClient
    ```

## 🏆 Language tournament — whose PRNG is actually random?

A fun exercise: pit **Go, Python, and Java** against each other over many streaming
matches and see whether each language's default PRNG produces a uniform move
distribution. In rock-paper-scissors two random players win ~50/50 regardless of
bias, so **win rate is a sanity check** — the real signal is each language's
rock/paper/scissors split and how far its most-frequent move sits from a uniform
33.3% (`bias%`). The streaming server records per-player move counts every match;
`StreamingArenaService.GetArenaResults` aggregates them by language.

```bash
# 1. Start the arena with a small round count for a fast run (Dev Services PostgreSQL):
ARENA_TOTAL_ROUNDS=200 ./run-server.sh vt        # gRPC on HTTP port 8080

# 2. Build the clients once:
(cd clients/go && ./generate_protos.sh && go build -o streaming_client streaming_client.go)
./gradlew :mutiny-server:quarkusBuild            # Java client (runs off the built app classpath)
#   Python just needs grpcio + generated stubs (see clients/python/generate_protos.sh)

# 3. Run the tournament — N clients per language, each playing M matches:
tournament/run-tournament.sh --port 8080 --clients 4 --matches 1000
```

It launches equal pools of each language, lets them play (random FIFO pairing),
then prints the leaderboard:

```
ARENA RESULTS — 684 streaming matches
language          matches    win%   rock%  paper% scissors%   bias%        moves
Go                    446  50.44%  34.50%  32.97%    32.53%  +1.16%        8,920
Java                  480  49.91%  33.45%  32.65%    33.91%  +0.57%        9,600
Python                442  49.64%  32.71%  33.13%    34.15%  +0.82%        8,840
```

Bias shrinks as the sample grows (`bias%` is roughly ±1/√moves of noise), so run
more matches/clients to resolve sub-1% skew. `GetArenaResults` aggregates **all**
matches in the database, so results accumulate across runs.

## 🏗 Project Structure

A Gradle multi-module project so both implementations live side by side:

*   `common/` — shared proto contracts (`src/main/proto`) + pure `GameLogic`. Each server generates its own gRPC stubs from these via `quarkus.generate-code.grpc.scan-for-proto`.
*   `mutiny-server/` — reactive implementation (Mutiny + Hibernate Reactive). `src/main/java`, `src/test/java`, `src/integrationTest/java`.
*   `vt-server/` — virtual-threads implementation (`@RunOnVirtualThread` + Hibernate ORM). Thin gRPC services over plain blocking `@Transactional` repositories.
*   `clients/` — reference clients in Go, Python, and Java (wire-compatible with either server).
*   `docs/lessons/` — the tutorial series; **Lesson 6** compares the two servers.
