# Paper-Rock-Scissors Arena

A Quarkus gRPC reactive application demonstrating the architectural differences between Unary (polling) and Streaming (push) gRPC patterns through a Paper-Rock-Scissors tournament system.

## Overview

This project implements a "Judge" server that orchestrates Paper-Rock-Scissors matches between "Gladiator" clients. It demonstrates:

- **The "Polling vs. Pushing" Pain Point**: Unary vs. Streaming gRPC approaches
- **The Context Tax**: Database state management vs. in-memory stream state
- **Statistical Fairness Auditing**: PRNG bias detection across different languages
- **Performance Metrics**: Latency, throughput, and resource usage comparison

## Architecture

### The Judge (Quarkus Server)

The central Arena server exposes two gRPC services:

1. **UnaryArena** - The "Painful" Approach
   - Stateless, database-dependent
   - Clients must poll for results
   - Demonstrates "Context Tax" with database lookups
   - Tracks database IOPS per round

2. **StreamingArena** - The "Clean" Approach
   - Stateful, in-memory state management
   - Bidirectional streaming
   - Server-push architecture
   - Zero database writes during matches

### The Gladiators (Clients)

Polyglot clients implement the "Dumb Client" pattern - they only calculate `rand() % 3` when requested by the Judge.

## Project Structure

```
paper-rock-scissors/
├── src/main/proto/
│   ├── tourney_unary.proto      # Unary service definition
│   └── tourney_stream.proto     # Streaming service definition
├── src/main/java/com/rickert/
│   ├── arena/
│   │   ├── model/               # Database entities
│   │   │   ├── UnaryMatch.java
│   │   │   ├── UnaryRound.java
│   │   │   └── MatchStatistics.java
│   │   ├── service/             # gRPC service implementations
│   │   │   ├── UnaryArenaService.java
│   │   │   └── StreamingArenaService.java
│   │   └── util/
│   │       └── GameLogic.java
│   └── client/                  # Demo clients
│       ├── UnaryClient.java
│       └── StreamingClient.java
└── src/main/resources/
    └── application.properties
```

## Requirements

- Java 17+
- Maven 3.8+
- Docker (optional, for PostgreSQL)

## Building the Project

```bash
# Compile and generate gRPC code
mvn clean compile

# Package the application
mvn package

# Build native image (optional)
mvn package -Pnative
```

## Running the Application

### Start the Arena Server

```bash
# Development mode with hot reload
mvn quarkus:dev

# Production mode
java -jar target/quarkus-app/quarkus-run.jar
```

The server will start:
- gRPC server on port **9000**
- HTTP health checks on port **8080**

### Run Demo Clients

Open multiple terminals to run clients:

#### Unary Client Demo

Terminal 1:
```bash
mvn exec:java -Dexec.mainClass="com.rickert.client.UnaryClient" \
  -Dlanguage.name="Java-21-Client1" \
  -Dprng.algorithm="java.util.Random"
```

Terminal 2:
```bash
mvn exec:java -Dexec.mainClass="com.rickert.client.UnaryClient" \
  -Dlanguage.name="Java-21-Client2" \
  -Dprng.algorithm="java.util.Random"
```

#### Streaming Client Demo

Terminal 1:
```bash
mvn exec:java -Dexec.mainClass="com.rickert.client.StreamingClient" \
  -Dlanguage.name="Java-21-Client1" \
  -Dprng.algorithm="L64X128MixRandom"
```

Terminal 2:
```bash
mvn exec:java -Dexec.mainClass="com.rickert.client.StreamingClient" \
  -Dlanguage.name="Java-21-Client2" \
  -Dprng.algorithm="SplittableRandom"
```

## Match Flow

### Unary (Polling) Flow
1. Client calls `Register()` → receives `match_id`
2. Client calls `SubmitMove(match_id, round, move)` → receives "ACCEPTED"
3. Client **polls** `CheckRoundResult(match_id, round)` repeatedly until "COMPLETE"
4. Repeat steps 2-3 for 1,000 rounds

**Pain Points:**
- Database lookup on every call
- Polling overhead and latency
- Complex state synchronization

### Streaming (Push) Flow
1. Client opens bidirectional stream and sends `Handshake`
2. Server sends "OPPONENT_FOUND"
3. Server sends `RequestMove` (the "Pulse")
4. Client sends `Move`
5. Server immediately sends `RoundResult`
6. Repeat steps 3-5 for 1,000 rounds

**Benefits:**
- Zero database writes during match
- Immediate feedback (no polling)
- Stream is the context

## Statistical Analysis

After each match, the system calculates:

1. **Distribution**: Percentage of Rock/Paper/Scissors for each player
2. **Bias Detection**: Deviation from expected 33.33% for each move
3. **Win/Loss Ratio**: Fairness analysis
4. **Seed Collision Detection**: Identifies if two clients produced identical sequences

View statistics in the `match_statistics` table.

## Performance Metrics

The application tracks and compares:

| Metric | Unary | Streaming |
|--------|-------|-----------|
| Database IOPS | ~3000-4000 per match | 1 (only final write) |
| Latency | High (polling delays) | Low (immediate push) |
| Rounds per Second | ~10-50 | ~500-2000 |
| Code Complexity | Higher (state management) | Lower (stream context) |

## Configuration

Edit `src/main/resources/application.properties`:

```properties
# gRPC Server
quarkus.grpc.server.port=9000
quarkus.grpc.server.host=0.0.0.0

# Database (H2 for development)
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:arena

# For Production PostgreSQL
# quarkus.datasource.db-kind=postgresql
# quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/arena
# quarkus.datasource.username=arena_user
# quarkus.datasource.password=arena_pass
```

## Polyglot Clients

This project now includes clients in multiple languages demonstrating cross-language gRPC compatibility:

### Java Clients (Built-in)

Located in `src/main/java/com/rickert/client/`:
- `UnaryClient.java` - Polling-based approach
- `StreamingClient.java` - Bidirectional streaming approach

See "Running the Application" section above for usage.

### Python Clients

Located in `clients/python/`:

**Setup:**
```bash
cd clients/python
pip install -r requirements.txt
./generate_protos.sh  # Generate gRPC stubs
```

**Run:**
```bash
# Unary client
python3 unary_client.py --host localhost --port 9000 --language Python-3.12 --prng random.Random

# Streaming client
python3 streaming_client.py --host localhost --port 9000 --language Python-3.12 --prng random.SystemRandom
```

**Docker:**
```bash
docker build -f clients/python/Dockerfile -t prs-python-client .
docker run --rm --network host prs-python-client python3 unary_client.py
```

See [clients/python/README.md](clients/python/README.md) for details.

### Go Clients

Located in `clients/go/`:

**Setup:**
```bash
cd clients/go
go mod download
./generate_protos.sh  # Generate gRPC stubs
```

**Build:**
```bash
go build -o unary_client unary_client.go
go build -o streaming_client streaming_client.go
```

**Run:**
```bash
# Unary client
./unary_client -host localhost -port 9000 -language Go-1.24 -prng math/rand

# Streaming client
./streaming_client -host localhost -port 9000 -language Go-1.24 -prng crypto/rand
```

**Docker:**
```bash
docker build -f clients/go/Dockerfile -t prs-go-client .
docker run --rm --network host prs-go-client ./unary_client
```

See [clients/go/README.md](clients/go/README.md) for details.

## CI/CD Pipeline

The project includes a comprehensive GitHub Actions workflow (`.github/workflows/ci-cd.yml`) that:

1. **Builds and Tests Java Server** - Compiles, tests, and packages the Quarkus application
2. **Builds Python Clients** - Generates stubs, validates syntax, and packages clients
3. **Builds Go Clients** - Generates stubs, compiles binaries, and runs tests
4. **Integration Tests** - Starts the server and tests all clients end-to-end
5. **Docker Images** - Builds and publishes Docker images for all components

The pipeline runs on:
- Push to `main` or `develop` branches
- Pull requests to `main` or `develop` branches

## Docker Deployment

### Server
```bash
# Build
mvn package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t prs-arena .

# Run
docker run -i --rm -p 8080:8080 -p 9000:9000 prs-arena
```

### Complete Multi-Client Demo with Docker
```bash
# Terminal 1: Start server
docker run --rm --name prs-arena -p 8080:8080 -p 9000:9000 prs-arena

# Terminal 2: Python unary clients
docker run --rm --network host prs-python-client python3 unary_client.py &
docker run --rm --network host prs-python-client python3 unary_client.py

# Terminal 3: Go streaming clients
docker run --rm --network host prs-go-client ./streaming_client &
docker run --rm --network host prs-go-client ./streaming_client
```

## Database Schema

The application automatically creates these tables:

- `unary_match` - Match metadata
- `unary_round` - Individual round state for Unary matches
- `match_statistics` - Aggregate statistics and analysis

## Extending with Other Languages

✅ **Implemented:** Python, Go, Java

The project demonstrates polyglot gRPC clients. To add clients in additional languages:

1. Copy the `.proto` files from `src/main/proto/`
2. Add appropriate `option` for your language (e.g., `option python_package`, `option go_package`)
3. Generate gRPC stubs for your language
4. Implement the client following the patterns in existing clients
5. Use language-specific PRNG for statistical comparison

**Suggested languages to add:**
- Node.js (using `Math.random()` or `crypto.randomInt()`)
- Rust (using `rand` crate)
- C++ (using `<random>`)
- C# (using `System.Random` or `System.Security.Cryptography`)

**Proto file locations:**
- `src/main/proto/tourney_unary.proto` - Unary/polling service
- `src/main/proto/tourney_stream.proto` - Bidirectional streaming service

## Development

### Hot Reload in Dev Mode

```bash
mvn quarkus:dev
```

Any changes to Java code or proto files will automatically recompile and reload.

### Testing

```bash
# Run tests
mvn test

# Run with coverage
mvn verify
```

### Native Compilation

```bash
# Requires GraalVM
mvn package -Pnative

# Run native executable
./target/paper-rock-scissors-arena-1.0.0-SNAPSHOT-runner
```

## Troubleshooting

### Port Already in Use
If port 9000 is occupied:
```properties
quarkus.grpc.server.port=9001
```

### Database Connection Issues
Check H2 console at http://localhost:8080/h2-console in dev mode.

### gRPC Reflection
Use tools like `grpcurl` to inspect services:
```bash
grpcurl -plaintext localhost:9000 list
```

## Key Learnings

This project demonstrates:

1. **Architectural Trade-offs**: Polling vs. streaming patterns
2. **Performance Impact**: Database overhead vs. in-memory state
3. **Code Complexity**: Stateless context management vs. stream-based flow
4. **Statistical Fairness**: PRNG quality affects game outcomes
5. **Reactive Programming**: Mutiny Uni and Multi for non-blocking operations

## License

MIT License - See LICENSE file for details

## Contributing

Contributions welcome! Please submit pull requests with:
- Additional language clients
- Performance optimizations
- Enhanced statistical analysis
- Documentation improvements
