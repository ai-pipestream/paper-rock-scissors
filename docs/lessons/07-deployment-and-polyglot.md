# Lesson 7: Deploy Anywhere, Clients in Any Language

**Goal:** Ship the arena as JVM, native, or vanilla gRPC — then prove wire compatibility with Go, Python, and Java clients in a live tournament.

> **Prerequisites:** Lessons [0](./00-grpc-primer.md)–[6](./06-virtual-threads-vs-reactive.md)

This is the capstone. The `.proto` is the product. Everything else is a deployment choice.

---

## Three servers, one contract

| Server | Command | gRPC port | Database | Framework |
|---|---|---|---|---|
| **vt** | `./run-server.sh vt` | 8080 (HTTP/2 unified) | PostgreSQL | Quarkus + virtual threads |
| **mutiny** | `./run-server.sh mutiny` | 8080 | PostgreSQL | Quarkus + reactive |
| **netty** | `./run-server.sh netty` | 9000 (dedicated) | In-memory | grpc-java only |

All implement `StreamingArenaService` and (Quarkus only) `UnaryArenaService` from `common/src/main/proto/`.

```
                    ┌─────────────────┐
   Go client ──────►│                 │
   Python client ──►│  .proto contract │◄────── Java client
   Java client ────►│  (common/)      │
                    └────────┬────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
   mutiny-server        vt-server          netty-server
   (Quarkus reactive)   (Quarkus VT)       (control group)
```

---

## `netty-server`: the control group

Vanilla [grpc-java](https://github.com/grpc/grpc-java) on Netty — no Quarkus, no Vert.x, no Mutiny, no CDI:

```java
NettyServerBuilder builder = NettyServerBuilder.forPort(port)
    .addService(new StreamingArenaServiceImpl(totalRounds));

if (vt) builder.executor(Executors.newVirtualThreadPerTaskExecutor());

Server server = builder.build().start();
```

### Why it exists

When you benchmark Quarkus vs "plain gRPC," you need a **fair baseline**. `netty-server` answers:

> How much latency is gRPC itself vs the framework layers Quarkus adds?

It uses hand-written `StreamObserver` handlers and an in-memory leaderboard — isolating transport cost from database cost.

### Tunable env vars (echoed at startup)

| Variable | Default | Effect |
|---|---|---|
| `ARENA_PORT` | `9000` | Listen port |
| `ARENA_TOTAL_ROUNDS` | `1000` | Rounds per match |
| `ARENA_FLOW_CONTROL_WINDOW` | grpc-java default (1 MB) | HTTP/2 window size |
| `ARENA_MAX_CONCURRENT_STREAMS` | grpc default | Streams per connection |
| `ARENA_VIRTUAL_THREADS` | `false` | Dispatch callbacks on virtual threads |

Compare apples to apples with Quarkus:

```bash
# Quarkus default was 64 KB — now explicitly 1 MB in application.properties
./run-server.sh vt

# Match old Vert.x window against netty
ARENA_FLOW_CONTROL_WINDOW=65535 ./run-server.sh netty
```

HTTP/2 **flow-control windows** throttle how much data can be in flight per stream. Too small a window stalls high-throughput streaming even on a fast network — a deployment detail that shows up in benchmarks, not "Hello World."

---

## Polyglot clients

### Code generation

```bash
# Shared source of truth
ls common/src/main/proto/ai/pipestream/tourney/

# Go
cd clients/go && ./generate_protos.sh && go build -o streaming_client streaming_client.go

# Python
cd clients/python && ./generate_protos.sh
pip install -r requirements.txt

# Java — generated at Quarkus build time from :common
./gradlew :mutiny-server:quarkusBuild
```

Each language gets typed stubs from the **same** `.proto`. Field **numbers** matter on the wire, not names.

### Running against Quarkus (port 8080)

A streaming match needs **two** clients — mix languages freely, the server pairs whoever shows up:

```bash
./run-server.sh vt

# Go vs Python — a cross-language match
clients/go/streaming_client -host localhost -port 8080 -language Go -prng math/rand &
python3 clients/python/streaming_client.py --host localhost --port 8080 --language Python

# Java vs Go
./run-streaming-client.sh "Java-Capstone" "java.util.Random" &
clients/go/streaming_client -host localhost -port 8080 -language Go
```

### Running against netty (port 9000)

```bash
./run-server.sh netty
clients/go/streaming_client -host localhost -port 9000 &
clients/go/streaming_client -host localhost -port 9000
```

Same client binaries. Different port. Different server implementation. **Wire-identical behavior.**

> The vanilla `netty-server` implements only the **streaming** service — unary clients need one of the Quarkus servers.

---

## The language tournament

Pit Go, Python, and Java PRNGs against each other — not for gameplay (random vs random ≈ 50/50), but to measure **move distribution bias**:

```bash
# Fast run — fewer rounds per match
ARENA_TOTAL_ROUNDS=200 ./run-server.sh vt

# Build clients (once)
(cd clients/go && ./generate_protos.sh && go build -o streaming_client streaming_client.go)
./gradlew :mutiny-server:quarkusBuild

# Launch tournament — 4 clients per language, 1000 matches each
tournament/run-tournament.sh --port 8080 --clients 4 --matches 1000
```

Sample output:

```
ARENA RESULTS — 684 streaming matches
language          matches    win%   rock%  paper% scissors%   bias%        moves
Go                    446  50.44%  34.50%  32.97%    32.53%  +1.16%        8,920
Java                  480  49.91%  33.45%  32.65%    33.91%  +0.57%        9,600
Python                442  49.64%  32.71%  33.13%    34.15%  +0.82%        8,840
```

`GetArenaResults` aggregates **all** matches in the database — results accumulate across runs. Win rate is a sanity check; **bias%** (deviation from uniform 33.3%) is the interesting signal.

This is the ultimate **contract test**: three languages, one server, one `.proto`, statistics persisted through the same gRPC API.

---

## Deployment options

### 1. JVM container (recommended starting point)

```bash
./gradlew :vt-server:quarkusBuild

docker build -f vt-server/src/main/docker/Dockerfile.jvm \
  -t arena:vt-jvm vt-server

docker run --rm -p 8080:8080 \
  -e QUARKUS_PROFILE=prod \
  -e QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host.docker.internal:5432/arena \
  -e QUARKUS_DATASOURCE_USERNAME=arena \
  -e QUARKUS_DATASOURCE_PASSWORD=secret \
  arena:vt-jvm
```

Fast builds. Larger image (~200 MB+). Full JVM diagnostics. Best default for most teams.

### 2. Native image (GraalVM)

```bash
./gradlew :vt-server:build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true

docker build -f vt-server/src/main/docker/Dockerfile.native \
  -t arena:vt-native vt-server
```

Slow compile. Small image. Sub-second startup. Ideal for scale-to-zero and edge — but test thoroughly ([Lesson 4](./04-testing-standards.md) integration tests + native CI job).

### 3. Vanilla Netty (minimal footprint)

```bash
./gradlew :netty-server:installDist
netty-server/build/install/netty-server/bin/netty-server
```

No database. No Quarkus. Single JAR + scripts. Perfect as a **sidecar**, benchmark harness, or teaching "raw gRPC."

### 4. Client containers

```dockerfile
# clients/go/Dockerfile — multi-stage static binary
# clients/python/Dockerfile — slim Python + grpcio
```

CI builds all images — see `.github/workflows/ci-cd.yml`.

---

## CI/CD pipeline overview

On every push/PR:

1. `./gradlew test` — `@QuarkusTest` with Dev Services
2. `./gradlew :mutiny-server:quarkusBuild :vt-server:quarkusBuild`
3. Go/Python stub generation + compile
4. Integration job — start server, run clients
5. Upload Quarkus app artifacts

The pipeline treats **wire compatibility** as a release gate, not an afterthought.

---

## Choosing your deployment stack

```
Need PostgreSQL persistence + easy debugging?
  └── vt-server JVM container

Need maximum streaming operators / backpressure in-process?
  └── mutiny-server

Need smallest image + cold-start SLA?
  └── vt-server native (after testing)

Need baseline latency or no framework?
  └── netty-server

Need clients in language X?
  └── generate from common/*.proto — server choice is independent
```

---

## Full capstone exercise

Run the complete path:

```bash
# 1. Start server
ARENA_TOTAL_ROUNDS=100 ./run-server.sh vt

# 2+3. Java vs Go smoke test (clients pair with each other)
./run-streaming-client.sh "Capstone-Java" "Random" &
clients/go/streaming_client -host localhost -port 8080 -language Go-Capstone

# 4. Mini tournament
tournament/run-tournament.sh --port 8080 --clients 2 --matches 50

# 5. Swap to netty — same Go client, port 9000
./run-server.sh netty
clients/go/streaming_client -host localhost -port 9000 -language Go-Capstone &
clients/go/streaming_client -host localhost -port 9000 -language Go-Capstone-2

# 6. Diff implementations
diff mutiny-server/.../UnaryArenaServiceImpl.java vt-server/.../UnaryArenaServiceImpl.java
```

If steps 2–5 work, you understand what this repository teaches: **design the wire first, then choose how to carry it.**

---

## Series complete

| You learned | Lesson |
|---|---|
| gRPC basics + first RPC | [0](./00-grpc-primer.md) |
| Non-blocking handlers (Mutiny) | [1](./01-mutiny-reactive.md) |
| Unary vs streaming design | [2](./02-grpc-patterns.md) |
| State placement + persistence | [3](./03-hibernate-reactive.md) |
| Contract testing | [4](./04-testing-standards.md) |
| Dev → prod configuration | [5](./05-dev-services.md) |
| Reactive vs virtual threads | [6](./06-virtual-threads-vs-reactive.md) |
| Deployment + polyglot | **7 (this lesson)** |

Return to the [lesson index](./README.md) anytime. For architecture diagrams and performance tables, see [ARCHITECTURE.md](../../ARCHITECTURE.md).
