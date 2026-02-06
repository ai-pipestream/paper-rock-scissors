# Lesson 1: Reactive Programming with Mutiny

Quarkus is built on a reactive core (Vert.x). To achieve maximum throughput and resource efficiency, we use **Mutiny**, a type-safe, event-driven reactive programming library.

## Core Concepts

### Uni vs Multi
*   **`Uni`**: Represents a stream that emits at most one item (or a failure). Equivalent to a `CompletableFuture` or a "Single".
*   **`Multi`**: Represents a stream that emits zero, one, or many items. Equivalent to a "Flux" or "Observable".

## Best Practices in this Project

1.  **Never Block the Event Loop:** 
    All gRPC methods in this project return `Uni` or `Multi`. We avoid using `Thread.sleep()` or blocking I/O.
2.  **Declarative Pipelines:**
    Instead of imperative logic, we chain operations:
    ```java
    return match.persist()
        .chain(() -> notifyOpponent(match))
        .replaceWith(response);
    ```
3.  **Context Propagation:**
    Quarkus handles the propagation of the Vert.x context (and JTA transactions) automatically through Mutiny pipelines, provided you use the reactive extensions.

## Why Mutiny?
Compared to older reactive libraries, Mutiny is designed to be **intelligible**. It uses a "natural language" API (`onItem()`, `onFailure()`, `chain()`, `transform()`) that makes reactive code easier to read and maintain.
