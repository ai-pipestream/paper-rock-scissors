# Paper-Rock-Scissors Arena

A high-performance, fully reactive gRPC arena for automated Paper-Rock-Scissors tournaments. This project serves as a "Source of Truth" for modern Quarkus development standards, demonstrating best practices for gRPC, reactive database access, and advanced testing strategies.

## 🚀 Overview

The Arena provides two modes of engagement:
1.  **Unary (Stateless):** A classic polling-based approach where clients manage state via a match ID.
2.  **Streaming (Stateful):** A bidirectional stream where the connection *is* the match state, offering minimal latency.

## 🛠 Technology Stack

*   **Runtime:** [Quarkus 3.31+](https://quarkus.io/)
*   **Protocol:** [gRPC](https://grpc.io/) with [Mutiny](https://smallrye.io/smallrye-mutiny/) (Non-blocking)
*   **Database:** [PostgreSQL](https://www.postgresql.org/) (Reactive)
*   **Persistence:** [Hibernate Reactive with Panache](https://quarkus.io/guides/hibernate-reactive-panache)
*   **Build Tool:** [Gradle](https://gradle.org/)
*   **Dev Productivity:** [Quarkus Dev Services](https://quarkus.io/guides/dev-services) (Zero-config Docker containers)

## 📖 Lessons & Standards

This project is documented through a series of technical lessons located in the `docs/` directory. Each lesson maps directly to the implementation in this repository.

*   **[Lesson 1: Reactive Programming with Mutiny](./docs/lessons/01-mutiny-reactive.md)**
*   **[Lesson 2: gRPC Unary vs Streaming](./docs/lessons/02-grpc-patterns.md)**
*   **[Lesson 3: Hibernate Reactive & Panache](./docs/lessons/03-hibernate-reactive.md)**
*   **[Lesson 4: Advanced Testing (Test vs IT)](./docs/lessons/04-testing-standards.md)**
*   **[Lesson 5: Dev Services & Environment](./docs/lessons/05-dev-services.md)**

## 🚦 Getting Started

### Prerequisites
*   Java 17+
*   Docker (for Dev Services)

### Running the Arena
Start the server in development mode:
```bash
./gradlew quarkusDev
```
Quarkus will automatically start a PostgreSQL container and the gRPC server on port `9000`.

### Running Tests
*   **Unit Tests:** `./gradlew test` (Fast, same-JVM tests using `@QuarkusTest`)
*   **Integration Tests:** `./gradlew quarkusIntTest` (Tests against the packaged JAR using `@QuarkusIntegrationTest`)

## 🏗 Project Structure

*   `src/main/java`: Reactive service implementations and models.
*   `src/main/proto`: Protocol Buffer definitions.
*   `src/test/java`: Unit tests.
*   `src/integrationTest/java`: Integration tests.
*   `clients/`: Reference clients in Go, Python, and Java.
