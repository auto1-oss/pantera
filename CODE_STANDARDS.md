# Code Standards -- Pantera Artifact Registry

This document defines the code standards, conventions, and engineering practices for the
Pantera project. All contributors must follow these standards.

---

## 1. Language and Compiler

- **Java 21+** is required. The compiler release level is set via
  `maven.compiler.release=21` in the root `pom.xml`.
- **Source encoding**: UTF-8 (`project.build.sourceEncoding=UTF-8`).
- No language preview features unless explicitly approved by maintainers.

---

## 2. Build Requirements

### Maven

- **Maven 3.4+** is required. This is enforced at build time by the
  `maven-enforcer-plugin` with `requireMavenVersion [3.4.0,)`.

### PMD Static Analysis

- The `maven-pmd-plugin` runs the project ruleset located at
  `build-tools/src/main/resources/pmd-ruleset.xml`.
- The build **fails** on any PMD violation (`printFailingErrors=true`).
- Key thresholds:
  - Cyclomatic complexity per method: 15
  - Cyclomatic complexity per class: 80
  - Cognitive complexity: 17
- Notable rules:
  - **Public static methods are prohibited** (except `main(String...)`).
  - **Only one constructor should perform initialization**; other constructors must
    delegate to the primary constructor.
  - **Do not use `Files.createFile`** in test classes; use `@TempDir` or
    `TemporaryFolder` instead.

### License Header Check

- The `license-maven-plugin` (com.mycila) checks that every Java source file includes
  the GPL-3.0 license header defined in `LICENSE.header`.
- The check runs during the `verify` phase.
- To auto-format missing headers: `mvn license:format`.

---

## 3. Naming Conventions

### Classes

- Use **PascalCase** with descriptive, intention-revealing names.
- Avoid abbreviations unless they are universally understood (e.g., `URL`, `HTTP`, `JWT`).

### Slice implementations

HTTP request handler classes follow the `*Slice` naming pattern:

```
MavenSlice, HealthSlice, CircuitBreakerSlice, GzipSlice, LoggingSlice
```

### Storage implementations

Storage abstraction implementations follow the `*Storage` naming pattern:

```
S3Storage, DiskCacheStorage, FileStorage, InMemoryStorage, VertxFileStorage
```

### Test classes

| Suffix         | Purpose                 | Maven Plugin |
|----------------|-------------------------|--------------|
| `*Test.java`   | Unit tests              | Surefire     |
| `*IT.java`     | Integration tests       | Failsafe     |
| `*ITCase.java` | Integration tests       | Failsafe     |

### Package structure

All production code lives under:

```
com.auto1.pantera.<module>
```

Examples:

```
com.auto1.pantera.http.slice
com.auto1.pantera.asto.s3
com.auto1.pantera.maven
com.auto1.pantera.docker
com.auto1.pantera.npm.proxy
```

---

## 4. Async Patterns

### Storage operations return CompletableFuture

All methods on the `Storage` interface return `CompletableFuture`. Callers must compose
operations asynchronously.

### Never block the Vert.x event loop

Blocking calls on a Vert.x event-loop thread will stall the entire application. Use
`executeBlocking()` only when absolutely necessary, and prefer non-blocking alternatives.

### Chaining async operations

- Use `thenCompose()` to chain operations that return `CompletableFuture` (async to async).
- Use `thenApply()` to transform results synchronously (async to sync transformation).

```java
storage.value(key)
    .thenCompose(content -> storage.save(newKey, content))
    .thenApply(ignored -> new RsWithStatus(RsStatus.OK));
```

### Exception handling

Always handle exceptions in async chains:

```java
storage.value(key)
    .thenApply(content -> new RsWithBody(content))
    .exceptionally(err -> new RsWithStatus(RsStatus.INTERNAL_ERROR));
```

Use `handle()` when you need access to both the result and the exception.

---

## 5. Testing Standards

### Framework

- **JUnit 5** for all tests.
- **Hamcrest** for assertions.
- **Vert.x JUnit 5 extension** (`vertx-junit5`) for tests involving the Vert.x event loop.

### Hamcrest matcher style

Prefer instantiating matcher **objects** over calling static factory methods:

```java
// Preferred
MatcherAssert.assertThat(result, new IsEquals<>(expected));

// Avoid
MatcherAssert.assertThat(result, Matchers.equalTo(expected));
```

### Assertion reason strings

- **Single assertion** per test method: omit the reason string.

```java
MatcherAssert.assertThat(result, new IsEquals<>("hello"));
```

- **Multiple assertions** per test method: include a reason string on each assertion.

```java
MatcherAssert.assertThat("Status code matches", status, new IsEquals<>(200));
MatcherAssert.assertThat("Body contains name", body, new StringContains("pantera"));
```

### Unit tests

- Must not depend on external services (no Docker, no network, no database).
- Use `InMemoryStorage` from `com.auto1.pantera.asto.memory` for storage-dependent tests.
- Named `*Test.java`.

### Integration tests

- Use **TestContainers** for Docker-based tests (PostgreSQL, Valkey, etc.).
- Named `*IT.java` or `*ITCase.java`.
- Run under the `itcase` Maven profile: `mvn verify -Pitcase`.

### Gating external service tests

Tests that require a running external service (not managed by TestContainers) must be
gated with JUnit conditions:

```java
@EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
@Test
void shouldConnectToValkey() {
    final String host = System.getenv("VALKEY_HOST");
    // ...
}
```

---

## 6. Error Handling

### Structured logging

- Use SLF4J as the logging facade with Log4j2 as the implementation.
- Production logging uses the Elastic ECS JSON layout (`log4j2-ecs-layout`) for
  Elasticsearch/Kibana compatibility.
- Log exceptions with structured fields. Include enough context for debugging without
  leaking sensitive data (credentials, tokens).

### Circuit breakers

- Use circuit breakers (`CircuitBreakerSlice`, `CooldownCircuitBreaker`) for external
  upstream calls.
- The cooldown service tracks failed upstream requests and prevents repeated failures for
  a configurable period (default: 72 hours).

### Retry with backoff

- For transient failures (network timeouts, temporary HTTP 5xx), use retry with
  exponential backoff and jitter.
- Avoid unbounded retries. Set a maximum retry count and total timeout.

---

## 7. Configuration

### YAML configuration

The primary configuration file is `pantera.yml`. It supports `${ENV_VAR}` substitution
for secrets and environment-specific values:

```yaml
meta:
  artifacts_database:
    postgres_user: ${POSTGRES_USER}
    postgres_password: ${POSTGRES_PASSWORD}
```

### Environment variables

Runtime tuning environment variables use the `PANTERA_` prefix:

| Variable               | Purpose                                    |
|------------------------|--------------------------------------------|
| `PANTERA_USER_NAME`    | Default admin username                     |
| `PANTERA_USER_PASS`    | Default admin password                     |
| `PANTERA_CONFIG`       | Path to configuration file                 |
| `PANTERA_VERSION`      | Application version string                 |
| `JVM_ARGS`             | JVM flags passed to the runtime            |
| `VALKEY_HOST`          | Valkey connection host (test gating)       |
| `LOG4J_CONFIGURATION_FILE` | Path to Log4j2 XML configuration      |

### Defaults

Provide sensible defaults for all configuration values. A minimal `pantera.yml` with only
`meta.storage` defined should produce a working (if limited) instance.

---

## 8. API Design

### REST endpoints

- Management API endpoints live under `/api/v1/`.
- Handlers are organized by resource in `com.auto1.pantera.api.v1`:
  - `RepositoryHandler` -- repository CRUD
  - `UserHandler` -- user management
  - `RoleHandler` -- role management
  - `ArtifactHandler` -- artifact search and metadata
  - `SettingsHandler` -- server configuration
  - `CooldownHandler` -- cooldown service management
  - `DashboardHandler` -- dashboard statistics
  - `SearchHandler` -- full-text search
  - `StorageAliasHandler` -- storage alias management

### Authentication

- JWT authentication is required for all management API endpoints.
- Keycloak is the default identity provider in the Docker Compose stack.
- Credentials can also be sourced from environment variables or native Pantera user
  storage.

### Request and response format

- All API request and response bodies use JSON.
- Use conventional HTTP status codes:
  - `200 OK` for successful reads and updates.
  - `201 Created` for successful resource creation.
  - `204 No Content` for successful deletions.
  - `400 Bad Request` for malformed input.
  - `401 Unauthorized` for missing or invalid authentication.
  - `403 Forbidden` for insufficient permissions.
  - `404 Not Found` for missing resources.
  - `409 Conflict` for duplicate resources.
  - `500 Internal Server Error` for unexpected failures.

---

## 9. Documentation

### Javadoc

- Javadoc lint is disabled (`doclint=none`) to allow non-standard tags.
- Document all public APIs: classes, interfaces, and public methods.
- Document non-obvious behavior, edge cases, and thread-safety guarantees.

### Inline comments

- Use inline comments sparingly. Code should be self-documenting through clear naming.
- Comment the **why**, not the **what**.

### Documentation updates

- When changing configuration options, update the relevant documentation files in
  `docs/`.
- When adding or modifying REST API endpoints, update the API handler Javadoc and any
  related documentation.
- When adding new repository adapter types, document the supported features and
  configuration in `docs/`.
