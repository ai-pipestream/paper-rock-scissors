# Build Status

## Current Status: ✅ Gradle build working

The project builds and tests with **Gradle** and **Quarkus 3.37+** on **Java 21+**.

```bash
./gradlew clean build          # Full multi-module build
./gradlew test                 # Unit tests (@QuarkusTest + Dev Services)
./gradlew :mutiny-server:quarkusBuild :vt-server:quarkusBuild
./gradlew :netty-server:installDist
```

## Module build matrix

| Module | Build | Tests | Notes |
|---|---|---|---|
| `:common` | ✅ | — | Shared protos + `GameLogic` |
| `:mutiny-server` | ✅ | ✅ `test` + `quarkusIntTest` | Reactive + Hibernate Reactive |
| `:vt-server` | ✅ | ✅ (shared patterns) | Virtual threads + Hibernate ORM |
| `:netty-server` | ✅ | — | Vanilla grpc-java, no Quarkus |
| Go clients | ✅ | CI compile | `clients/go/generate_protos.sh` |
| Python clients | ✅ | CI syntax check | `clients/python/generate_protos.sh` |

## CI/CD

GitHub Actions (`.github/workflows/ci-cd.yml`) on push/PR to `main` and `develop`:

- Gradle build + unit tests
- Quarkus app packaging (both servers)
- Go/Python client generation and validation
- Integration tests with server + polyglot clients
- Docker image builds

## Dependencies (representative)

| Component | Version / source |
|---|---|
| Gradle | 9.x (wrapper) |
| Quarkus | 3.37+ (`gradle.properties`) |
| Java | 21+ (25 recommended; virtual threads + JEP 491) |
| gRPC (Go) | 1.78.0 |
| gRPC (Python) | grpcio 1.78.0 |
| PostgreSQL | Via Dev Services (dev) / external (prod) |

## Historical note

Earlier revisions of this repo documented a Maven/Gradle concurrency issue with Quarkus 3.31.x. That has been resolved in the current Gradle multi-module layout. If you see outdated references to Maven or H2 in old forks, refer to this file and the [lesson series](./docs/lessons/README.md) for the current setup.

## Local prerequisites

- **Java 21+**
- **Docker** (Quarkus Dev Services for PostgreSQL)
- Optional: Go 1.21+, Python 3, `protoc` for polyglot client development
