# Lesson 2: gRPC Patterns - Unary vs Streaming

This project demonstrates two fundamental gRPC communication patterns and how Quarkus integrates them with Mutiny.

## 1. Unary (Request-Response)
Implemented in `UnaryArenaServiceImpl.java`.

*   **Pattern:** The client sends one request and gets one response.
*   **Implementation:** Returns a `Uni<T>`.
*   **Pros:** Easy to load-balance, simple to implement, stateless.
*   **Cons:** High "Latency Tax" if the client needs to poll for updates (e.g., waiting for an opponent's move).

## 2. Bidirectional Streaming
Implemented in `StreamingArenaServiceImpl.java`.

*   **Pattern:** Both client and server send a stream of messages.
*   **Implementation:** Takes a `Multi<Request>` and returns a `Multi<Response>`.
*   **Pros:** Real-time, ultra-low latency, the connection *is* the context.
*   **Cons:** More complex state management (in-memory), requires long-lived connections.

## The Unified Server Architecture
In this project, we use a single Quarkus application instance to serve all gRPC services. We do **not** run multiple separate servers for different patterns.

*   **Configuration:** We use `quarkus.grpc.server.use-separate-server=false`.
*   **Result:** Both `UnaryArenaService` and `StreamingArenaService` are hosted on the same JVM, sharing the same port (`9000` by default) and the same database connection pool.
*   **Clients:** The project includes separate Java classes for testing the Unary and Streaming patterns (`UnaryClient` and `StreamingClient`), but these are strictly client-side tools.

## Mutiny Stubs
Quarkus generates "Mutiny" stubs for every service. In this project, we prefer these over the standard gRPC `StreamObserver` API because they integrate seamlessly with our reactive database and business logic.
