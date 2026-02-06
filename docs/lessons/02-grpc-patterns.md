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

## The "Unified" Server
We use `quarkus.grpc.server.use-separate-server=false`. This allows gRPC to run on the same port as the HTTP server (using HTTP/2), simplifying networking and firewall configuration.

## Mutiny Stubs
Quarkus generates "Mutiny" stubs for every service. In this project, we prefer these over the standard gRPC `StreamObserver` API because they integrate seamlessly with our reactive database and business logic.
