# Artipie Developer Guide

**Version:** 1.21.0
**Last Updated:** January 2026

---

## Table of Contents

1. [Introduction](#introduction)
2. [Architecture Overview](#architecture-overview)
3. [Development Environment Setup](#development-environment-setup)
4. [Build System](#build-system)
5. [Project Structure](#project-structure)
6. [Core Concepts](#core-concepts)
7. [Group Repository Internals](#group-repository-internals)
8. [Adding New Features](#adding-new-features)
9. [Testing](#testing)
10. [Code Style & Standards](#code-style--standards)
11. [Debugging](#debugging)
12. [Contributing](#contributing)
13. [Roadster (Rust) Development](#roadster-rust-development)

---

## Introduction

This guide is for developers who want to contribute to Artipie or understand its internal architecture. Artipie is a binary artifact repository manager supporting 16+ package formats.

### Technology Stack

| Component | Technology | Version |
|-----------|------------|---------|
| **Language** | Java | 21+ |
| **Build** | Apache Maven | 3.2+ |
| **HTTP Framework** | Vert.x | 5.x |
| **Async I/O** | CompletableFuture | Java 21 |
| **HTTP Client** | Vert.x WebClient | 5.x |
| **Serialization** | Jackson | 2.16.2 |
| **Caching** | Caffeine (L1) + Valkey (L2) | 3.x / 8.x |
| **Metrics** | Micrometer | 1.12.1 |
| **Logging** | Log4j 2 | 2.22.1 |
| **Testing** | JUnit 5 | 5.10.0 |
| **Containers** | TestContainers | 2.0.2 |

> **Note:** As of v1.21.0, all HTTP client operations use Vert.x WebClient exclusively. The previous Jetty HTTP client has been removed. This consolidation improves performance by sharing the Vert.x event loop and reduces memory overhead.

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

## Group Repository Internals

Group repositories aggregate multiple member repositories, providing unified access to packages across local and proxy repositories. As of v1.21.0, Artipie implements intelligent metadata merging with a two-tier caching architecture.

### Architecture Overview

```
Request
  |
  v
GroupSlice (e.g., NpmGroupSlice, GoGroupSlice)
  |
  v
UnifiedGroupCache
  |
  +-- MergedMetadataCache (L1 Caffeine + L2 Valkey)
  |     |-- Cache key: "meta:{group}:{adapter}:{package}"
  |     |-- Stores merged metadata from all members
  |     +-- Configurable TTL with background refresh
  |
  +-- PackageLocationIndex (L1 Caffeine + L2 Valkey)
        |-- Cache key: "idx:{group}:{package}"
        |-- Tracks which members have which packages
        +-- Event-driven updates for local repos
          |
          v
Member Repositories
  |-- Metadata: Parallel fetch from known locations, merge results
  +-- Artifacts: Cascade by priority until found
```

### Key Components

#### 1. UnifiedGroupCache

The central orchestrator for group caching operations. Located in `artipie-core/src/main/java/com/artipie/cache/UnifiedGroupCache.java`.

**Responsibilities:**
- Coordinates `PackageLocationIndex` and `MergedMetadataCache`
- Handles metadata fetch and merge operations
- Processes local publish/delete events for immediate index updates
- Provides `MemberFetcher` interface for parallel member queries

```java
// Example: Getting merged metadata
UnifiedGroupCache cache = new UnifiedGroupCache("my-npm-group", settings);

CompletableFuture<Optional<byte[]>> result = cache.getMetadata(
    "npm",           // adapter type
    "lodash",        // package name
    memberFetchers,  // list of member fetchers
    new NpmMetadataMerger()
);
```

#### 2. PackageLocationIndex

Tracks which member repositories contain which packages. This prevents unnecessary upstream calls by knowing exactly where to look.

**Key features:**
- **Two-tier caching**: L1 (Caffeine) for microsecond lookups, L2 (Valkey) for distributed state
- **Event-driven updates**: Local repo changes are immediately reflected (no TTL)
- **Negative caching**: Remembers 404s to avoid repeated failed lookups
- **TTL-based expiration**: Remote locations expire after configurable TTL

```java
// Mark package exists in a member
locationIndex.markExists("npm-local", "lodash");

// Mark package not found (negative cache)
locationIndex.markNotExists("npm-proxy-2", "lodash");

// Get known locations
PackageLocations locations = locationIndex.getLocations("lodash").join();
List<String> knownMembers = locations.knownLocations();  // ["npm-local", "npm-proxy-1"]
```

#### 3. MergedMetadataCache

Stores merged metadata from all group members with configurable TTL.

**Key features:**
- Caches merged results to avoid repeated fetch+merge operations
- Supports background refresh before expiry
- Automatic L1-to-L2 promotion on cache miss
- Invalidation on local publish/delete events

#### 4. MetadataMerger Interface

Adapter-specific implementations for merging metadata from multiple sources.

```java
@FunctionalInterface
public interface MetadataMerger {
    /**
     * Merge metadata from multiple members.
     * @param responses Map of member name -> metadata bytes (priority order)
     * @return Merged metadata bytes
     */
    byte[] merge(LinkedHashMap<String, byte[]> responses);
}
```

**Available implementations:**

| Adapter | Class | Merge Strategy |
|---------|-------|----------------|
| NPM | `NpmMetadataMerger` | Union of `versions` object, priority wins conflicts |
| Go | `GoMetadataMerger` | Sorted union of `@v/list` entries |
| PyPI | `PypiMetadataMerger` | Union of `/simple/` HTML links |
| Docker | Uses manifest merging | Tag union with digest-based deduplication |
| Composer | Similar to NPM | Union of `packages` object |

### Metadata vs Artifact Strategy

Group repositories use different strategies for metadata and artifacts:

| Request Type | Strategy | Reason |
|--------------|----------|--------|
| **Metadata** | Parallel fetch + merge | Need ALL versions from ALL members |
| **Artifact** | Cascade by priority | Large files, stop at first success |

```
Metadata (e.g., package.json, @v/list, /simple/):
  1. Check MergedMetadataCache -> HIT: return cached
  2. Check PackageLocationIndex for known locations
  3. Fetch from known members in parallel
  4. Merge results using adapter-specific merger
  5. Cache merged result
  6. Return merged metadata

Artifact (e.g., .tgz, .jar, .whl):
  1. Check PackageLocationIndex for known locations
  2. Try priority-1 member first
  3. If 404, try priority-2, etc.
  4. Update index on success/failure
  5. Return artifact from first successful member
```

### Cache Configuration

Group cache settings can be configured at both global and repository levels:

```java
// Default settings
GroupSettings defaults = new GroupSettings();
defaults.remoteExistsTtl();      // Duration.ofMinutes(15)
defaults.remoteNotExistsTtl();   // Duration.ofMinutes(5)
defaults.metadataTtl();          // Duration.ofMinutes(5)
defaults.upstreamTimeout();      // Duration.ofSeconds(5)
defaults.maxParallelFetches();   // 10
defaults.l1MaxEntries();         // 10,000
defaults.l2MaxEntries();         // 1,000,000

// Parse from YAML with repo-level override
GroupSettings settings = GroupSettings.fromYaml(globalYaml, repoYaml);
```

### Event-Driven Index Updates

For local repositories, the index is updated immediately on publish/delete events:

```java
// On local publish - immediate update, no TTL
cache.onLocalPublish("npm-local", "my-package");

// On local delete - remove from index
cache.onLocalDelete("npm-local", "my-package");
```

This ensures zero-delay visibility for internal packages while still caching remote lookups.

### Valkey/Redis Integration

For multi-instance deployments, connect Valkey/Redis for shared L2 cache:

```java
// With Valkey L2 cache
ValkeyConnection valkey = new ValkeyConnection("redis://localhost:6379");
UnifiedGroupCache cache = new UnifiedGroupCache(
    "my-group",
    settings,
    Optional.of(valkey)
);
```

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

### Adding Group Support for a New Adapter

To add group repository support with metadata merging for a new adapter, follow these steps:

#### Step 1: Create MetadataMerger Implementation

Create an adapter-specific metadata merger in the adapter module:

```java
// myformat-adapter/src/main/java/com/artipie/myformat/metadata/MyFormatMetadataMerger.java
package com.artipie.myformat.metadata;

import com.artipie.cache.MetadataMerger;
import java.util.LinkedHashMap;

/**
 * Metadata merger for MyFormat repositories.
 * Merges metadata from multiple group members.
 *
 * @since 1.21.0
 */
public final class MyFormatMetadataMerger implements MetadataMerger {

    @Override
    public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
        if (responses.isEmpty()) {
            return new byte[0];
        }

        // Merge logic depends on your metadata format:
        // - JSON: merge objects/arrays, priority member wins conflicts
        // - XML: merge elements, deduplicate
        // - Text list: concatenate, sort, deduplicate

        // Example for text-based version list:
        Set<String> versions = new TreeSet<>();
        for (byte[] response : responses.values()) {
            String text = new String(response, StandardCharsets.UTF_8);
            for (String line : text.split("\\n")) {
                String version = line.trim();
                if (!version.isEmpty()) {
                    versions.add(version);
                }
            }
        }
        return String.join("\n", versions).getBytes(StandardCharsets.UTF_8);
    }
}
```

#### Step 2: Create Adapter-Specific GroupSlice

Create a group slice that knows which requests are metadata vs artifacts:

```java
// artipie-main/src/main/java/com/artipie/group/MyFormatGroupSlice.java
package com.artipie.group;

import com.artipie.cache.GroupSettings;
import com.artipie.cache.MetadataMerger;
import com.artipie.cache.UnifiedGroupCache;
import com.artipie.http.Slice;
import com.artipie.myformat.metadata.MyFormatMetadataMerger;

/**
 * Group slice for MyFormat repositories with metadata merging.
 *
 * @since 1.21.0
 */
public final class MyFormatGroupSlice implements Slice {

    private final String name;
    private final List<GroupMember> members;
    private final UnifiedGroupCache cache;
    private final MetadataMerger merger;

    public MyFormatGroupSlice(
        final String name,
        final List<GroupMember> members,
        final GroupSettings settings
    ) {
        this.name = name;
        this.members = members;
        this.cache = new UnifiedGroupCache(name, settings);
        this.merger = new MyFormatMetadataMerger();
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();

        if (isMetadataRequest(path)) {
            return handleMetadataRequest(path, headers);
        } else {
            return handleArtifactRequest(path, headers);
        }
    }

    /**
     * Determine if request is for metadata.
     * Customize based on your adapter's URL patterns.
     */
    private boolean isMetadataRequest(final String path) {
        // Example: metadata files end with .json or specific paths
        return path.endsWith("/metadata.json")
            || path.contains("/index/");
    }

    private CompletableFuture<Response> handleMetadataRequest(
        final String path,
        final Headers headers
    ) {
        final String packageName = extractPackageName(path);

        // Create fetchers for each member
        List<UnifiedGroupCache.MemberFetcher> fetchers = this.members.stream()
            .map(member -> new UnifiedGroupCache.MemberFetcher() {
                @Override
                public String memberName() {
                    return member.name();
                }

                @Override
                public CompletableFuture<Optional<byte[]>> fetch() {
                    return member.slice()
                        .response(new RequestLine("GET", path), headers, Content.EMPTY)
                        .thenCompose(response -> {
                            if (response.status().code() == 200) {
                                return response.body().asBytes()
                                    .thenApply(Optional::of);
                            }
                            return CompletableFuture.completedFuture(Optional.empty());
                        });
                }
            })
            .toList();

        // Get merged metadata (cached or fresh)
        return this.cache.getMetadata("myformat", packageName, fetchers, this.merger)
            .thenApply(result -> {
                if (result.isPresent()) {
                    return new RsWithBody(
                        StandardRs.OK,
                        new Content.From(result.get())
                    );
                }
                return StandardRs.NOT_FOUND;
            });
    }

    private CompletableFuture<Response> handleArtifactRequest(
        final String path,
        final Headers headers
    ) {
        // Cascade through members by priority
        return cascadeRequest(path, headers, 0);
    }

    private CompletableFuture<Response> cascadeRequest(
        final String path,
        final Headers headers,
        final int index
    ) {
        if (index >= this.members.size()) {
            return CompletableFuture.completedFuture(StandardRs.NOT_FOUND);
        }

        final GroupMember member = this.members.get(index);
        return member.slice()
            .response(new RequestLine("GET", path), headers, Content.EMPTY)
            .thenCompose(response -> {
                if (response.status().code() == 200) {
                    // Update location index on hit
                    this.cache.recordMemberHit(member.name(), extractPackageName(path));
                    return CompletableFuture.completedFuture(response);
                }
                // Try next member
                return cascadeRequest(path, headers, index + 1);
            });
    }

    private String extractPackageName(final String path) {
        // Implement based on your URL structure
        // e.g., /myformat/com/example/pkg/1.0/file.ext -> com.example.pkg
        return path.split("/")[2];
    }
}
```

#### Step 3: Wire Up in RepositorySlices

Register the new group slice in `RepositorySlices.java`:

```java
// In RepositorySlices.java slice() method
case "myformat-group":
    return new MyFormatGroupSlice(
        name,
        groupMembers(config, settings),
        groupSettings(config, settings)
    );
```

#### Step 4: Add Tests

Create unit tests for the merger:

```java
// myformat-adapter/src/test/java/com/artipie/myformat/metadata/MyFormatMetadataMergerTest.java
class MyFormatMetadataMergerTest {

    @Test
    void mergesMetadataFromMultipleMembers() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("local", "v1.0.0\nv1.1.0".getBytes());
        responses.put("proxy", "v1.1.0\nv1.2.0".getBytes());

        final MyFormatMetadataMerger merger = new MyFormatMetadataMerger();
        final byte[] result = merger.merge(responses);
        final String text = new String(result, StandardCharsets.UTF_8);

        assertTrue(text.contains("v1.0.0"));
        assertTrue(text.contains("v1.1.0"));
        assertTrue(text.contains("v1.2.0"));
    }

    @Test
    void priorityMemberWinsForConflicts() {
        // Test that first member (highest priority) wins for conflicts
    }

    @Test
    void handlesEmptyResponses() {
        final MyFormatMetadataMerger merger = new MyFormatMetadataMerger();
        final byte[] result = merger.merge(new LinkedHashMap<>());
        assertEquals(0, result.length);
    }
}
```

Create integration tests for the group slice:

```java
// artipie-main/src/test/java/com/artipie/group/MyFormatGroupSliceTest.java
class MyFormatGroupSliceTest {

    @Test
    void mergesMetadataFromAllMembers() {
        // Set up local and proxy members
        // Send metadata request
        // Verify merged response contains all versions
    }

    @Test
    void cascadesArtifactRequestsByPriority() {
        // Set up members with different artifacts
        // Send artifact request
        // Verify first available member wins
    }

    @Test
    void cachesMetadataOnSubsequentRequests() {
        // Send same metadata request twice
        // Verify cache hit on second request
    }
}
```

#### Configuration Example

Users can then configure the group:

```yaml
# myformat-group.yaml
repo:
  type: myformat-group
  members:
    - myformat-local    # Priority 1
    - myformat-proxy    # Priority 2

  # Optional: override cache settings
  group:
    metadata:
      ttl: 10m
    resolution:
      upstream_timeout: 15s
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

*This guide covers Artipie development for version 1.21.0. For the latest updates, see the repository.*
