# Lesson 3: Hibernate Reactive & Panache

Traditional Hibernate (ORM) is blocking and relies on JDBC. In a reactive application, this creates a bottleneck. **Hibernate Reactive** uses non-blocking database drivers to ensure the event loop is never stalled.

## Reactive Panache
We use `quarkus-hibernate-reactive-panache`, which provides the Active Record pattern for Hibernate Reactive.

### 1. The Model
Models extend `PanacheEntity`. Notice that static methods return `Uni`:
```java
public static Uni<UnaryMatch> findByMatchId(String matchId) {
    return find("matchId", matchId).firstResult();
}
```

### 2. Transaction Management
We use the `@WithTransaction` annotation on gRPC service methods. This:
1.  Starts a reactive transaction.
2.  Ensures the session is flushed upon completion.
3.  Rolls back automatically if the `Uni` fails.

## Configuration
The project uses `quarkus-reactive-pg-client`. Unlike JDBC, there is no `url` starting with `jdbc:`. Instead, we use `quarkus.datasource.reactive.url`.

## Key Difference
In Hibernate Reactive, you **must** chain your database operations. If you call `persist()` and don't subscribe to (or chain) the resulting `Uni`, the write will **never happen**.
