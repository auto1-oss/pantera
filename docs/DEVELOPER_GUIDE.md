# Artipie Developer Guide

**Version:** 1.20.11
**Last Updated:** January 2026

---

## Table of Contents

1. [Introduction](#introduction)
2. [Architecture Overview](#architecture-overview)
3. [Development Environment Setup](#development-environment-setup)
4. [Build System](#build-system)
5. [Project Structure](#project-structure)
6. [Core Concepts](#core-concepts)
7. [Adding New Features](#adding-new-features)
8. [Testing](#testing)
9. [Code Style & Standards](#code-style--standards)
10. [Debugging](#debugging)
11. [Contributing](#contributing)
12. [Roadster (Rust) Development](#roadster-rust-development)

---

## Introduction

This guide is for developers who want to contribute to Artipie or understand its internal architecture. Artipie is a binary artifact repository manager supporting 16+ package formats.

### Technology Stack

| Component | Technology | Version |
|-----------|------------|---------|
| **Language** | Java | 21+ |
| **Build** | Apache Maven | 3.2+ |
| **HTTP Framework** | Vert.x | 4.5.22 |
| **Async I/O** | CompletableFuture | Java 21 |
| **HTTP Client** | Jetty | 12.1.4 |
| **Serialization** | Jackson | 2.16.2 |
| **Caching** | Guava/Caffeine | 33.0.0 |
| **Metrics** | Micrometer | 1.12.1 |
| **Logging** | Log4j 2 | 2.22.1 |
| **Testing** | JUnit 5 | 5.10.0 |
| **Containers** | TestContainers | 2.0.2 |

---

## Architecture Overview

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     HTTP Layer (Vert.x)                      │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────────────┐ │
│  │MainSlice│──│TimeoutSl│──│AuthSlice│──│RepositorySlices │ │
│  └─────────┘  └─────────┘  └─────────┘  └─────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                   Repository Adapters                        │
│  ┌──────┐ ┌──────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐        │
│  │Maven │ │Docker│ │ NPM │ │PyPI │ │Helm │ │ ... │        │
│  └──────┘ └──────┘ └─────┘ └─────┘ └─────┘ └─────┘        │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                  Storage Layer (Asto)                        │
│  ┌────────────┐  ┌────────┐  ┌──────┐  ┌───────┐           │
│  │ FileSystem │  │   S3   │  │ etcd │  │ Redis │           │
│  └────────────┘  └────────┘  └──────┘  └───────┘           │
└─────────────────────────────────────────────────────────────┘
```

### Request Flow

```
HTTP Request (port 8080)
    ↓
[BaseSlice] - metrics, headers, observability
    ↓
[TimeoutSlice] - 120s timeout protection
    ↓
[MainSlice] - routing dispatcher
    ↓
├─→ /.health → HealthSlice
├─→ /.version → VersionSlice
└─→ /* (fallback) → DockerRoutingSlice → ApiRoutingSlice → SliceByPath
                            ↓
                    RepositorySlices Cache lookup/create
                            ↓
                    [Adapter Slice] (e.g., NpmSlice, MavenSlice)
                            ↓
                    Local: read from Storage
                    Proxy: check cache → upstream → cache
                    Group: parallel member query → first success
                            ↓
                    Response (async/reactive)
```

### Key Subsystems

1. **HTTP Layer**: Vert.x-based non-blocking HTTP server
2. **Repository Management**: Dynamic slice creation and caching
3. **Storage Abstraction (Asto)**: Pluggable storage backends
4. **Authentication**: Basic, JWT, OAuth/OIDC support
5. **Authorization**: Role-based access control (RBAC)
6. **REST API**: Management endpoints on separate port

---

## Development Environment Setup

### Prerequisites

- **JDK 21+** (OpenJDK recommended)
- **Maven 3.2+**
- **Docker** (for integration tests)
- **Git**

### Clone and Build

```bash
# Clone repository
git clone https://github.com/artipie/artipie.git
cd artipie

# Full build with tests
mvn clean verify

# Fast build (skip tests)
mvn install -DskipTests -Dpmd.skip=true

# Multi-threaded build
mvn clean install -U -DskipTests -T 1C
```

### IDE Setup

#### IntelliJ IDEA

1. Open project (`File → Open → pom.xml`)
2. Import as Maven project
3. Configure run configuration:
   - **Main class**: `com.artipie.VertxMain`
   - **VM options**: `--config-file=/path/to/artipie.yaml`
   - **Working directory**: `artipie-main`

#### VS Code

1. Install Java Extension Pack
2. Open project folder
3. Configure launch.json:

```json
{
  "type": "java",
  "name": "VertxMain",
  "request": "launch",
  "mainClass": "com.artipie.VertxMain",
  "args": "--config-file=example/artipie.yaml"
}
```

### Running Locally

**Option 1: Docker Compose (Recommended)**
```bash
cd artipie-main/docker-compose
docker-compose up -d
```

**Option 2: Direct Execution**
```bash
java -jar artipie-main/target/artipie.jar \
  --config-file=example/artipie.yaml \
  --port=8080 \
  --api-port=8086
```

---

## Build System

### Maven Profiles

| Profile | Description |
|---------|-------------|
| `docker-build` | Build Docker images (auto-enabled if Docker socket exists) |
| `sonatype` | Deploy to Maven Central |
| `gpg-sign` | GPG sign artifacts for release |
| `bench` | Run benchmarks |
| `itcase` | Integration test cases |

### Common Build Commands

```bash
# Full build with all tests
mvn clean verify

# Unit tests only
mvn test

# Integration tests
mvn verify -Pitcase

# Build specific module
mvn clean install -pl maven-adapter

# Skip tests and PMD
mvn install -DskipTests -Dpmd.skip=true

# Run specific test class
mvn test -Dtest=LargeArtifactPerformanceIT -DskipITs=false

# Package with dependencies
mvn package dependency:copy-dependencies

# Extract project version
mvn help:evaluate -Dexpression=project.version -q -DforceStdout
```

### Versioning

```bash
# Bump version across all modules
./bump-version.sh 1.21.0

# Build and deploy to local Docker
./build-and-deploy.sh

# Build and deploy with tests
./build-and-deploy.sh --with-tests
```

---

## Project Structure

### Module Overview

```
artipie/
├── pom.xml                          # Parent POM
│
├── artipie-main/                    # Main application
│   ├── src/main/java/com/artipie/
│   │   ├── VertxMain.java          # Entry point
│   │   ├── api/                    # REST API handlers
│   │   ├── auth/                   # Authentication
│   │   ├── cooldown/               # Cooldown service
│   │   └── settings/               # Configuration
│   └── docker-compose/             # Production deployment
│
├── artipie-core/                    # Core types and HTTP layer
│   └── src/main/java/com/artipie/
│       ├── http/                   # Slice pattern, HTTP utilities
│       ├── auth/                   # Auth abstractions
│       └── settings/               # Settings interfaces
│
├── vertx-server/                    # Vert.x HTTP server wrapper
├── http-client/                     # HTTP client utilities
│
├── asto/                            # Abstract storage
│   ├── asto-core/                  # Storage interfaces
│   ├── asto-s3/                    # S3 implementation
│   ├── asto-vertx-file/            # Async filesystem
│   ├── asto-redis/                 # Redis implementation
│   └── asto-etcd/                  # etcd implementation
│
├── [16 Adapter Modules]
│   ├── maven-adapter/
│   ├── npm-adapter/
│   ├── docker-adapter/
│   ├── pypi-adapter/
│   ├── gradle-adapter/
│   ├── go-adapter/
│   ├── helm-adapter/
│   ├── composer-adapter/
│   ├── gem-adapter/
│   ├── nuget-adapter/
│   ├── debian-adapter/
│   ├── rpm-adapter/
│   ├── hexpm-adapter/
│   ├── conan-adapter/
│   ├── conda-adapter/
│   └── files-adapter/
│
├── roadster/                        # Rust rewrite (next-gen)
├── artipie-import-cli/              # Rust import tool
│
└── docs/                            # Documentation
```

### Key Files

| File | Description |
|------|-------------|
| `artipie-main/.../VertxMain.java` | Application entry point |
| `artipie-main/.../api/RestApi.java` | REST API verticle |
| `artipie-core/.../http/Slice.java` | Core HTTP abstraction |
| `artipie-core/.../RepositorySlices.java` | Repository routing |
| `asto/asto-core/.../Storage.java` | Storage interface |

---

## Core Concepts

### 1. The Slice Pattern

The **Slice** is the fundamental HTTP handler abstraction:

```java
public interface Slice {
    /**
     * Process HTTP request and return response.
     *
     * @param line Request line (method, URI, version)
     * @param headers Request headers
     * @param body Request body as reactive stream
     * @return CompletableFuture of Response
     */
    CompletableFuture<Response> response(
        RequestLine line,
        Headers headers,
        Content body
    );
}
```

**Benefits:**
- Composable via decorators
- Async/non-blocking by design
- Easy to test in isolation

**Decorator Pattern:**
```java
// Wrap with timeout, logging, and metrics
Slice wrapped = new LoggingSlice(
    new TimeoutSlice(
        new MetricsSlice(
            new MySlice(storage)
        ),
        Duration.ofSeconds(120)
    )
);
```

### 2. Storage Abstraction (Asto)

All storage backends implement the `Storage` interface:

```java
public interface Storage {
    CompletableFuture<Boolean> exists(Key key);
    CompletableFuture<Collection<Key>> list(Key prefix);
    CompletableFuture<Void> save(Key key, Content content);
    CompletableFuture<Content> value(Key key);
    CompletableFuture<Void> move(Key source, Key destination);
    CompletableFuture<Void> delete(Key key);
    <T> CompletableFuture<T> exclusively(Key key, Function<Storage, CompletableFuture<T>> operation);
}
```

**Key Design Principles:**
- All operations return `CompletableFuture` for async execution
- `Key` represents path-like identifiers (e.g., `org/example/artifact/1.0.0/file.jar`)
- `Content` is a reactive byte stream with optional metadata

### 3. Repository Types

**Local Repository:**
- Hosts artifacts directly in storage
- Supports read and write operations
- Example: `MavenSlice`, `NpmSlice`

**Proxy Repository:**
- Caches artifacts from upstream registries
- Read-only (downloads from upstream)
- Example: `MavenProxySlice`, `NpmProxySlice`

**Group Repository:**
- Aggregates multiple repositories
- Queries members in parallel
- Returns first successful response
- Example: `GroupSlice`

### 4. Async/Reactive Programming

Artipie uses `CompletableFuture` for all async operations:

```java
// Chaining operations
storage.exists(key)
    .thenCompose(exists -> {
        if (exists) {
            return storage.value(key);
        }
        return CompletableFuture.completedFuture(null);
    })
    .thenApply(content -> processContent(content));

// Error handling
future.exceptionally(error -> {
    logger.error("Operation failed", error);
    return defaultValue;
});

// Parallel operations
CompletableFuture.allOf(
    storage.exists(key1),
    storage.exists(key2),
    storage.exists(key3)
).thenApply(v -> "All complete");
```

**Critical Rules:**
- Never block on Vert.x event loop threads
- Use `thenCompose()` for chaining async operations
- Use `thenApply()` for synchronous transformations
- Always handle exceptions with `exceptionally()` or `handle()`

### 5. Configuration System

Configuration is loaded from YAML files:

```java
// Settings interface
public interface Settings {
    Storage storage();
    Authentication authentication();
    Policy policy();
    Optional<MetricsConfig> metrics();
    // ...
}

// Repository configuration
public interface RepoConfig {
    String type();
    Storage storage();
    Optional<List<String>> remotes();
    // ...
}
```

**Hot Reload:**
- `ConfigWatchService` monitors configuration files
- Changes apply without restart
- Slice cache is invalidated on config change

---

## Adding New Features

### Adding a New Repository Adapter

1. **Create Maven Module**

```xml
<!-- Add to root pom.xml -->
<module>myformat-adapter</module>
```

```xml
<!-- myformat-adapter/pom.xml -->
<artifactId>myformat-adapter</artifactId>
<dependencies>
    <dependency>
        <groupId>com.artipie</groupId>
        <artifactId>artipie-core</artifactId>
    </dependency>
</dependencies>
```

2. **Implement Slice**

```java
public final class MyFormatSlice implements Slice {
    private final Storage storage;

    public MyFormatSlice(Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line,
        Headers headers,
        Content body
    ) {
        // Handle GET requests
        if (line.method().equals("GET")) {
            return handleGet(line.uri());
        }
        // Handle PUT requests
        if (line.method().equals("PUT")) {
            return handlePut(line.uri(), body);
        }
        return CompletableFuture.completedFuture(
            new RsWithStatus(RsStatus.METHOD_NOT_ALLOWED)
        );
    }

    private CompletableFuture<Response> handleGet(String uri) {
        Key key = new Key.From(uri);
        return storage.value(key)
            .thenApply(content -> new RsWithBody(content))
            .exceptionally(ex -> new RsWithStatus(RsStatus.NOT_FOUND));
    }
}
```

3. **Register in RepositorySlices**

```java
// In RepositorySlices.java slice() method
case "myformat":
    return new MyFormatSlice(storage);
case "myformat-proxy":
    return new MyFormatProxySlice(storage, remotes);
```

4. **Add Tests**

```java
class MyFormatSliceTest {
    @Test
    void shouldReturnArtifact() {
        Storage storage = new InMemoryStorage();
        storage.save(new Key.From("file.txt"), new Content.From("hello"));

        Slice slice = new MyFormatSlice(storage);

        Response response = slice.response(
            new RequestLine("GET", "/file.txt"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            response.status(),
            new IsEquals<>(RsStatus.OK)
        );
    }
}
```

### Adding a New API Endpoint

1. **Create Handler Class**

```java
public final class MyNewRest {
    private final Settings settings;

    public MyNewRest(Settings settings) {
        this.settings = settings;
    }

    public void init(Router router, JWTAuth auth) {
        router.get("/api/v1/mynew/:id")
            .handler(JWTAuthHandler.create(auth))
            .handler(this::handleGet);

        router.post("/api/v1/mynew")
            .handler(JWTAuthHandler.create(auth))
            .handler(this::handlePost);
    }

    private void handleGet(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        // Implementation
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject.mapFrom(result).encode());
    }
}
```

2. **Register in RestApi**

```java
// In RestApi.java start() method
new MyNewRest(settings).init(router, auth);
```

3. **Update OpenAPI Documentation**

Add endpoint specification to Swagger/OpenAPI resources.

### Adding a New Storage Backend

1. **Create Module in asto/**

```java
public final class MyStorage implements Storage {

    @Override
    public CompletableFuture<Boolean> exists(Key key) {
        return CompletableFuture.supplyAsync(() -> {
            // Check if key exists
            return myClient.exists(key.string());
        });
    }

    @Override
    public CompletableFuture<Content> value(Key key) {
        return CompletableFuture.supplyAsync(() -> {
            byte[] data = myClient.get(key.string());
            return new Content.From(data);
        });
    }

    // Implement other methods...
}
```

2. **Create Factory**

```java
public final class MyStorageFactory implements StorageFactory {
    @Override
    public Storage create(Config config) {
        String endpoint = config.string("endpoint");
        return new MyStorage(endpoint);
    }
}
```

3. **Register in Storage Configuration**

Update `StorageFactory` to recognize new storage type.

---

## Testing

### Unit Tests

```java
class MySliceTest {

    @Test
    void shouldReturnNotFoundForMissingKey() {
        Storage storage = new InMemoryStorage();
        Slice slice = new MySlice(storage);

        Response response = slice.response(
            new RequestLine("GET", "/missing.txt"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            response.status(),
            new IsEquals<>(RsStatus.NOT_FOUND)
        );
    }
}
```

### Integration Tests

```java
@Testcontainers
class MyAdapterIT {

    @Container
    private static final GenericContainer<?> ARTIPIE =
        new GenericContainer<>("artipie/artipie:1.0-SNAPSHOT")
            .withExposedPorts(8080);

    @Test
    void shouldUploadAndDownload() {
        String url = String.format(
            "http://localhost:%d/myrepo/file.txt",
            ARTIPIE.getMappedPort(8080)
        );

        // Upload
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .PUT(HttpRequest.BodyPublishers.ofString("hello"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

        // Download
        HttpResponse<String> response = HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

        MatcherAssert.assertThat(
            response.body(),
            new IsEquals<>("hello")
        );
    }
}
```

### Running Tests

```bash
# All unit tests
mvn test

# Specific test class
mvn test -Dtest=MySliceTest

# Integration tests
mvn verify -Pitcase

# Module-specific tests
mvn test -pl maven-adapter
```

---

## Code Style & Standards

### PMD Enforcement

Code style is enforced by PMD Maven plugin. Build fails on violations.

```bash
# Check PMD rules
mvn pmd:check

# Skip PMD
mvn install -Dpmd.skip=true
```

### Hamcrest Matchers

Prefer matcher objects over static methods:

```java
// Good
MatcherAssert.assertThat(target, new IsEquals<>(expected));

// Bad
MatcherAssert.assertThat(target, Matchers.equalTo(expected));
```

### Test Assertions

Single assertion - no reason needed:
```java
MatcherAssert.assertThat(result, new IsEquals<>(expected));
```

Multiple assertions - add reasons:
```java
MatcherAssert.assertThat("Check status", response.status(), new IsEquals<>(200));
MatcherAssert.assertThat("Check body", response.body(), new IsEquals<>("hello"));
```

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

**Types:**
- `feat` - New feature
- `fix` - Bug fix
- `test` - Tests
- `refactor` - Code refactoring
- `docs` - Documentation
- `chore` - Maintenance
- `perf` - Performance
- `ci` - CI/CD changes
- `build` - Build system

**Example:**
```
feat(npm): add support for scoped packages

Implemented @scope/package-name handling in NPM adapter.
Added unit tests for scoped package resolution.

Close: #123
```

### Pull Request Format

**Title:** `<type>[scope]: <description>`

**Description:**
- Explain HOW the problem was solved
- Not just a copy of the title
- Include technical details

**Footer:**
- `Close: #123` - Closes issue
- `Fix: #123` - Fixes issue
- `Ref: #123` - References issue

---

## Debugging

### Enable Debug Logging

Edit `log4j2.xml`:
```xml
<Logger name="com.artipie" level="DEBUG"/>
<Logger name="com.artipie.maven" level="DEBUG"/>
<Logger name="software.amazon.awssdk" level="DEBUG"/>
```

### JVM Debug Flags

```bash
# Remote debugging
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
  -jar artipie.jar --config-file=artipie.yaml

# Heap dumps on OOM
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/var/artipie/logs/heapdump.hprof

# GC logging
-Xlog:gc*:file=/var/artipie/logs/gc.log:time,uptime:filecount=5,filesize=100m
```

### Common Issues

**Thread Blocking:**
- Symptom: Requests hang, CPU low
- Cause: Blocking call on event loop
- Fix: Use `executeBlocking()` or async operations

**Memory Leaks:**
- Symptom: Heap grows continuously
- Cause: Unclosed Content streams, leaked CompletableFutures
- Fix: Always close Content, add timeouts

**Connection Pool Exhaustion:**
- Symptom: "Failed to acquire connection"
- Cause: S3 connections not returned to pool
- Fix: Configure `connection-max-idle-millis`

### Tools

```bash
# Thread dump
jstack <PID>

# Heap dump
jmap -dump:live,format=b,file=heap.hprof <PID>

# Monitor GC
jstat -gc <PID> 1000

# JFR recording
java -XX:StartFlightRecording=filename=recording.jfr ...

# VisualVM
visualvm --openjmx localhost:9010
```

---

## Contributing

### Workflow

1. Fork the repository
2. Create feature branch: `git checkout -b feat/my-feature`
3. Make changes
4. Run full build: `mvn clean verify`
5. Commit with conventional message
6. Push and create PR

### PR Checklist

- [ ] Code compiles without errors
- [ ] All tests pass
- [ ] PMD checks pass
- [ ] New code has tests
- [ ] Commit messages follow convention
- [ ] PR description explains changes
- [ ] Issue reference in footer

### Review Process

1. Author creates PR
2. CI checks run automatically
3. Reviewer is assigned
4. Review comments addressed
5. Maintainer approves and merges

---

## Roadster (Rust) Development

Roadster is the next-generation Artipie rewrite in Rust.

### Why Rust?

- **Zero GC pauses** (critical production issue)
- **< 100ms startup** (vs ~5s for Java)
- **< 100MB memory** (vs ~500MB for Java)
- **< 50MB Docker image** (vs ~500MB for Java)

### Project Structure

```
roadster/
├── crates/
│   ├── roadster-core/         # Core types
│   ├── roadster-http/         # HTTP layer
│   ├── roadster-storage/      # Storage backends
│   ├── roadster-auth/         # Authentication
│   ├── roadster-config/       # Configuration
│   ├── roadster-telemetry/    # Observability
│   └── adapters/              # 16 repository adapters
├── bins/
│   ├── roadster-server/       # Main binary
│   └── roadster-cli/          # CLI tool
└── docs/
```

### Build Commands

```bash
cd roadster

# Check compilation
cargo check --workspace

# Build
cargo build --workspace

# Release build
cargo build --release

# Run tests
cargo test --workspace

# Format
cargo fmt --all

# Lint
cargo clippy --workspace --all-targets -- -D warnings

# Documentation
cargo doc --no-deps --workspace --open
```

### Core Patterns

**Slice Pattern (Rust):**
```rust
#[async_trait]
pub trait Slice: Send + Sync {
    async fn response(
        &self,
        line: RequestLine,
        headers: Headers,
        body: Body,
        ctx: &RequestContext,
    ) -> Response;
}
```

**Storage Trait:**
```rust
#[async_trait]
pub trait Storage: Send + Sync {
    async fn exists(&self, key: &Key) -> StorageResult<bool>;
    async fn value(&self, key: &Key) -> StorageResult<Content>;
    async fn save(&self, key: &Key, content: Content) -> StorageResult<()>;
    async fn delete(&self, key: &Key) -> StorageResult<()>;
}
```

### Development Setup

```bash
# Install Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# Install tools
rustup component add rustfmt clippy
cargo install cargo-watch cargo-audit

# Setup git hooks
git config core.hooksPath roadster/.githooks

# Auto-rebuild on changes
cargo watch -x check -x test
```

### Documentation

- [QUICKSTART.md](../roadster/QUICKSTART.md) - Quick reference
- [CODING_STANDARDS.md](../roadster/CODING_STANDARDS.md) - Code style
- [CONTRIBUTING.md](../roadster/CONTRIBUTING.md) - Contribution guide
- [AGENTS-ROADSTER.md](../roadster/AGENTS-ROADSTER.md) - Architecture guide

---

## Appendix A: Key Classes Reference

| Class | Location | Description |
|-------|----------|-------------|
| `VertxMain` | artipie-main | Application entry point |
| `RestApi` | artipie-main/api | REST API verticle |
| `MainSlice` | artipie-core/http | Main request router |
| `RepositorySlices` | artipie-main | Repository slice factory |
| `Slice` | artipie-core/http | Core HTTP handler interface |
| `Storage` | asto-core | Storage interface |
| `S3Storage` | asto-s3 | S3 storage implementation |
| `Settings` | artipie-core/settings | Configuration interface |
| `Authentication` | artipie-core/auth | Auth interface |
| `Policy` | artipie-core/auth | Authorization interface |

---

## Appendix B: Maven Module Dependencies

```
artipie-main
├── artipie-core
├── vertx-server
├── http-client
├── asto-core
├── asto-s3
├── asto-vertx-file
├── maven-adapter
├── npm-adapter
├── docker-adapter
└── ... (other adapters)

artipie-core
├── asto-core
└── http-client

asto-s3
└── asto-core

*-adapter
├── artipie-core
└── asto-core
```

---

## Appendix C: Useful Links

- **Repository**: https://github.com/artipie/artipie
- **Issues**: https://github.com/artipie/artipie/issues
- **Discussions**: https://github.com/artipie/artipie/discussions
- **Wiki**: https://github.com/artipie/artipie/wiki
- **Vert.x Docs**: https://vertx.io/docs/
- **Rust Book**: https://doc.rust-lang.org/book/

---

*This guide covers Artipie development for version 1.20.11. For the latest updates, see the repository.*
