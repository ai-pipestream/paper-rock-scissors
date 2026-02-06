# Lesson 4: Advanced Testing - Test vs IntegrationTest

Quarkus provides two primary annotations for testing, and understanding the difference is critical for CI/CD stability.

## `@QuarkusTest` (Unit/Component Level)
Located in `src/test/java`.

*   **Behavior:** Runs within the same JVM as the application.
*   **Capabilities:** You can `@Inject` beans, mocks, and gRPC clients directly.
*   **Usage:** Best for testing business logic, validation, and database interactions quickly.
*   **Standard:** Use `@GrpcClient` to inject Mutiny stubs for the service under test.

## `@QuarkusIntegrationTest` (System Level)
Located in `src/integrationTest/java`.

*   **Behavior:** Packages the application (JAR or Native) and runs it in a separate process.
*   **Capabilities:** Cannot use CDI `@Inject`. It is a "Black Box" test.
*   **Usage:** Validates the actual packaging, protocol handling, and production-like startup.
*   **Standard:** Use `@TestHTTPResource` to discover the dynamically allocated port and create a `ManagedChannel` manually.

## Gradle Configuration
We maintain separate source sets for these tests.
*   `./gradlew test`: Runs unit tests.
*   `./gradlew quarkusIntTest`: Runs integration tests.

This separation ensures that a native image build (which is slow) isn't required for every small logic change, but is still validated before release.
