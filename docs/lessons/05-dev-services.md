# Lesson 5: Dev Services & Environment

One of the most powerful features of Quarkus is **Dev Services**. It eliminates the "it works on my machine" problem by provisioning infrastructure automatically.

## Zero-Config Infrastructure
In `application.properties`, we only specify `quarkus.datasource.db-kind=postgresql`. Because we haven't provided a connection URL, Quarkus:
1.  Detects that Docker is available.
2.  Starts a PostgreSQL container.
3.  Configures the application to connect to it.
4.  Shuts down the container when the application stops.

## Production Profiles
We use the `%prod.` prefix to define production-only configurations:
```properties
%prod.quarkus.datasource.reactive.url=postgresql://prod-db:5432/arena
```
This ensures that the "magic" of Dev Services never accidentally touches production databases.

## Continuous Testing
When running `./gradlew quarkusDev`, you can press `r` to toggle "Continuous Testing". Quarkus will automatically run affected tests every time you save a file, using the same Dev Services infrastructure.
