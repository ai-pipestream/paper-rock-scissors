# Lesson 5: From Dev Laptop to Production Config

**Goal:** Run the arena locally with zero database setup, then understand exactly what changes when you ship it.

> **Prerequisites:** [Lesson 4](./04-testing-standards.md)

---

## Dev Services: zero-config PostgreSQL

Quarkus **Dev Services** detect missing configuration and start Docker containers for you.

In `application.properties`, we only declare the database *kind*:

```properties
quarkus.datasource.db-kind=postgresql
```

We deliberately **do not** set a JDBC or reactive URL in dev. Quarkus then:

1. Detects Docker is available
2. Starts a PostgreSQL Testcontainers instance
3. Wires `quarkus.datasource.jdbc.url` and `quarkus.datasource.reactive.url` automatically
4. Runs Hibernate schema generation (`drop-and-create` in dev)
5. Stops the container when you quit dev mode

Start the server:

```bash
./run-server.sh vt
# or: ./gradlew :vt-server:quarkusDev
```

Watch the logs for Dev Services startup — you will see the container ID and assigned port.

### What Dev Services is doing for you

| Without Dev Services | With Dev Services |
|---|---|
| Install PostgreSQL locally | Docker pulls `postgres` image |
| Create database + user | Auto-created per project |
| Copy connection URLs into config | Injected at runtime |
| Remember to start/stop DB | Stops when Quarkus stops |
| "Works on my machine" drift | Same container for whole team |

---

## When Dev Services fails (on purpose)

Stop Docker and run `./run-server.sh vt`. You will see an error about unavailable Dev Services and missing datasource URL.

That error is informative — it tells you exactly what Dev Services was providing. For production, **you** provide those values explicitly.

---

## Production profiles: the `%prod.` prefix

Any property prefixed with `%prod.` applies only in production mode:

```properties
# mutiny-server — both URLs required in prod
%prod.quarkus.datasource.reactive.url=postgresql://prod-db.example.com:5432/arena
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://prod-db.example.com:5432/arena
%prod.quarkus.datasource.username=arena_app
%prod.quarkus.datasource.password=${ARENA_DB_PASSWORD}
```

```properties
# vt-server — JDBC only (blocking ORM)
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://prod-db.example.com:5432/arena
%prod.quarkus.datasource.username=arena_app
%prod.quarkus.datasource.password=${ARENA_DB_PASSWORD}
```

**Rule:** Dev Services never runs in `%prod.` profile. If you forget prod URLs, the app fails at startup — not silently against a dev container.

Activate production mode:

```bash
java -jar mutiny-server/build/quarkus-app/quarkus-run.jar \
  -Dquarkus.profile=prod
```

Or set `QUARKUS_PROFILE=prod` in your container environment.

---

## Other important properties

### gRPC on the unified HTTP server

```properties
quarkus.grpc.server.use-separate-server=false
quarkus.http.port=8080
quarkus.grpc.server.enable-reflection-service=true   # dev-friendly; disable in prod
```

Clients connect to **port 8080** on Quarkus builds. Health checks live on the same port (`/q/health`).

### HTTP/2 flow control (streaming throughput)

Vert.x defaults a 64 KB per-stream window; grpc-java defaults 1 MB. For fair streaming benchmarks, both Quarkus servers raise it:

```properties
quarkus.http.initial-window-size=1048576
quarkus.http.http2-connection-window-size=1048576
```

This is an HTTP/2 setting on the unified server — **not** `quarkus.grpc.*`. See [Lesson 7](./07-deployment-and-polyglot.md) for the `netty-server` comparison.

### Schema management

```properties
# Dev — recreate schema every start
quarkus.hibernate-orm.schema-management.strategy=drop-and-create

# Prod — use migrations instead (recommended for real deployments)
%prod.quarkus.hibernate-orm.schema-management.strategy=none
# + Flyway/Liquibase for controlled migrations
```

The arena uses `drop-and-create` for teaching simplicity. **Do not copy that to production.**

### Logging

```properties
quarkus.log.level=INFO
quarkus.log.category."ai.pipestream.arena".level=DEBUG   # when debugging matches
```

---

## Continuous testing in dev mode

While `./gradlew quarkusDev` is running, press **`r`** to toggle continuous test mode. Quarkus re-runs affected tests on every save, using the same Dev Services PostgreSQL.

Press **`s`** for restart, **`w`** for reload tests only. This tightens the loop from [Lesson 4](./04-testing-standards.md).

---

## Environment-specific checklist

Before shipping any gRPC service (not just this arena):

- [ ] Explicit datasource URLs in `%prod.` — no Dev Services reliance
- [ ] Secrets via env vars or secret manager — not committed to git
- [ ] gRPC reflection **disabled** in production (or auth-protected)
- [ ] TLS enabled (`quarkus.grpc.server.ssl.*` or reverse proxy termination)
- [ ] Schema migrations — not `drop-and-create`
- [ ] Health checks wired to load balancer (`/q/health/ready`)
- [ ] HTTP/2 window sized for your payload pattern
- [ ] Logging structured for your observability stack

---

## Docker: first step toward deployment

Each Quarkus module includes Dockerfiles under `src/main/docker/`:

| File | Use case |
|---|---|
| `Dockerfile.jvm` | Standard JVM container — fast builds, larger image |
| `Dockerfile.legacy-jar` | Uber-jar layout |
| `Dockerfile.native` | GraalVM native — small image, slow build, fast startup |
| `Dockerfile.native-micro` | Native with micro base image |

Build the JVM image (example):

```bash
./gradlew :vt-server:quarkusBuild
docker build -f vt-server/src/main/docker/Dockerfile.jvm \
  -t arena-vt:jvm vt-server
```

Full deployment paths — native compilation, vanilla Netty, polyglot tournament — are in **[Lesson 7](./07-deployment-and-polyglot.md)**.

---

## Exercises

1. **Find the container** — Run `quarkusDev`, then `docker ps` — locate the PostgreSQL container Dev Services started.
2. **Prod profile dry run** — Build the app and start with `-Dquarkus.profile=prod` *without* prod URLs. Read the failure message.
3. **Toggle SQL logging** — Enable `quarkus.hibernate-orm.log.sql=true`, play one unary round, disable before you forget.
4. **Read both property files** — Diff `mutiny-server` and `vt-server` `application.properties`. Spot the reactive URL in prod for mutiny only.

---

## What's next

You can run and configure the server. Now compare **how two implementations wait on I/O** for the same contract:

**[Lesson 6: Virtual threads vs reactive](./06-virtual-threads-vs-reactive.md)**
