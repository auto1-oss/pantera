# Lucene Index Production Wiring — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Wire LuceneArtifactIndex into production, replace GroupNegativeCache entirely, connect event pipeline to feed the index, add warmup on startup.

**Architecture:** Positive index with warmup fallback. During startup, GroupSlice falls back to fan-out on index-miss. Once the initial storage scan completes, the index is trusted fully. Events (upload/delete) update the index in real-time via IndexConsumer running parallel to DbConsumer.

**Tech Stack:** Apache Lucene (MMapDirectory for production, ByteBuffersDirectory for tests), Vert.x, Quartz scheduler, Java 17 records.

**Design doc:** `docs/plans/2026-02-15-lucene-index-production-design.md`

---

## Batch 1: Index Infrastructure (Foundation)

### Task 1: Add warmup state and stats to LuceneArtifactIndex

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/index/LuceneArtifactIndex.java`
- Modify: `artipie-core/src/main/java/com/artipie/index/ArtifactIndex.java`
- Modify: `artipie-main/src/test/java/com/artipie/index/LuceneArtifactIndexTest.java`

**Step 1: Add `isWarmedUp()` and `setWarmedUp()` to the `ArtifactIndex` interface**

In `artipie-core/src/main/java/com/artipie/index/ArtifactIndex.java`, add two new methods to the interface (after `close()` at line 83):

```java
/**
 * Whether the index has completed its initial warmup scan.
 * @return true if warmup is complete and the index can be trusted
 */
default boolean isWarmedUp() {
    return false;
}

/**
 * Mark the index as warmed up after initial scan completes.
 */
default void setWarmedUp() {
    // no-op by default
}
```

Also add a `getStats()` method:

```java
/**
 * Get index statistics.
 * @return map of stat name to value
 */
default CompletableFuture<Map<String, Object>> getStats() {
    return CompletableFuture.completedFuture(Map.of());
}
```

Add `import java.util.Map;` to imports.

**Step 2: Implement the methods in `LuceneArtifactIndex.java`**

Add a field after the existing fields (after line 82):

```java
/**
 * Whether the index has been fully populated by the warmup scan.
 */
private volatile boolean warmedUp;
```

Add the three method implementations before `close()` (before line 197):

```java
@Override
public boolean isWarmedUp() {
    return this.warmedUp;
}

@Override
public void setWarmedUp() {
    this.warmedUp = true;
}

@Override
public CompletableFuture<Map<String, Object>> getStats() {
    return CompletableFuture.supplyAsync(() -> {
        try {
            this.searcherManager.maybeRefresh();
            final IndexSearcher searcher = this.searcherManager.acquire();
            try {
                final int numDocs = searcher.getIndexReader().numDocs();
                return Map.of(
                    "documents", numDocs,
                    "warmedUp", this.warmedUp,
                    "directoryType", this.directory.getClass().getSimpleName()
                );
            } finally {
                this.searcherManager.release(searcher);
            }
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }, this.writeExecutor);
}
```

Add imports: `import java.util.Map;` and `import java.io.UncheckedIOException;`

**Step 3: Add tests for new methods**

In `LuceneArtifactIndexTest.java`, add after the last test (after line 119):

```java
@Test
void warmupStateDefaultsFalse() {
    assertFalse(this.index.isWarmedUp());
}

@Test
void warmupStateCanBeSet() {
    this.index.setWarmedUp();
    assertTrue(this.index.isWarmedUp());
}

@Test
void statsReturnsDocumentCount() throws Exception {
    this.index.index(doc("maven", "repo1", "org/lib/1.0/lib-1.0.jar", "lib", "1.0")).join();
    this.index.index(doc("maven", "repo1", "org/lib/2.0/lib-2.0.jar", "lib", "2.0")).join();
    final Map<String, Object> stats = this.index.getStats().join();
    assertEquals(2, stats.get("documents"));
    assertFalse((Boolean) stats.get("warmedUp"));
}
```

Add imports: `import static org.junit.jupiter.api.Assertions.assertFalse;` and `import static org.junit.jupiter.api.Assertions.assertTrue;` and `import java.util.Map;`

**Step 4: Run tests**

Run: `mvn test -pl artipie-main -Dtest=LuceneArtifactIndexTest -Dsurefire.useFile=false`
Expected: All tests pass including 3 new ones.

**Step 5: Commit**

```bash
git add artipie-core/src/main/java/com/artipie/index/ArtifactIndex.java \
  artipie-main/src/main/java/com/artipie/index/LuceneArtifactIndex.java \
  artipie-main/src/test/java/com/artipie/index/LuceneArtifactIndexTest.java
git commit -m "Add warmup state and stats to ArtifactIndex interface and LuceneArtifactIndex"
```

---

### Task 2: Create IndexConsumer — event-to-index bridge

**Files:**
- Create: `artipie-main/src/main/java/com/artipie/index/IndexConsumer.java`
- Create: `artipie-main/src/test/java/com/artipie/index/IndexConsumerTest.java`

**Step 1: Write the failing test**

Create `artipie-main/src/test/java/com/artipie/index/IndexConsumerTest.java`:

```java
package com.artipie.index;

import com.artipie.scheduling.ArtifactEvent;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link IndexConsumer}.
 */
class IndexConsumerTest {

    private LuceneArtifactIndex index;
    private IndexConsumer consumer;

    @BeforeEach
    void setUp() {
        this.index = new LuceneArtifactIndex(new ByteBuffersDirectory());
        this.consumer = new IndexConsumer(this.index);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.index.close();
    }

    @Test
    void indexesInsertEvent() {
        final ArtifactEvent event = new ArtifactEvent(
            "maven", "my-repo", "admin",
            "org/example/lib/1.0/lib-1.0.jar", "1.0", 2048L,
            System.currentTimeMillis()
        );
        this.consumer.accept(event);
        final var results = this.index.locate(
            "org/example/lib/1.0/lib-1.0.jar"
        ).join();
        assertEquals(1, results.size());
        assertEquals("my-repo", results.get(0));
    }

    @Test
    void removesOnDeleteVersionEvent() {
        final ArtifactEvent insert = new ArtifactEvent(
            "npm", "npm-repo", "admin",
            "@scope/pkg/-/pkg-1.0.0.tgz", "1.0.0", 1024L,
            System.currentTimeMillis()
        );
        this.consumer.accept(insert);
        assertEquals(1, this.index.locate("@scope/pkg/-/pkg-1.0.0.tgz").join().size());
        final ArtifactEvent delete = new ArtifactEvent(
            "npm", "npm-repo",
            "@scope/pkg/-/pkg-1.0.0.tgz", "1.0.0"
        );
        this.consumer.accept(delete);
        assertTrue(this.index.locate("@scope/pkg/-/pkg-1.0.0.tgz").join().isEmpty());
    }

    @Test
    void removesOnDeleteAllEvent() {
        final ArtifactEvent insert = new ArtifactEvent(
            "pypi", "pypi-repo", "admin",
            "packages/mylib/mylib-1.0.tar.gz", "1.0", 512L,
            System.currentTimeMillis()
        );
        this.consumer.accept(insert);
        assertEquals(1, this.index.locate("packages/mylib/mylib-1.0.tar.gz").join().size());
        final ArtifactEvent delete = new ArtifactEvent(
            "pypi", "pypi-repo",
            "packages/mylib/mylib-1.0.tar.gz"
        );
        this.consumer.accept(delete);
        assertTrue(this.index.locate("packages/mylib/mylib-1.0.tar.gz").join().isEmpty());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl artipie-main -Dtest=IndexConsumerTest -Dsurefire.useFile=false`
Expected: Compilation error — `IndexConsumer` class doesn't exist.

**Step 3: Write IndexConsumer implementation**

Create `artipie-main/src/main/java/com/artipie/index/IndexConsumer.java`:

```java
package com.artipie.index;

import com.artipie.scheduling.ArtifactEvent;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Consumes artifact events and updates the Lucene search index.
 * Runs in parallel with DbConsumer in the event pipeline.
 * <p>
 * INSERT events are converted to ArtifactDocument and indexed.
 * DELETE events remove documents from the index.
 */
public final class IndexConsumer implements Consumer<ArtifactEvent> {

    /**
     * Lucene artifact index to update.
     */
    private final ArtifactIndex index;

    /**
     * Creates a new IndexConsumer.
     * @param index Lucene artifact index
     */
    public IndexConsumer(final ArtifactIndex index) {
        this.index = index;
    }

    @Override
    public void accept(final ArtifactEvent event) {
        switch (event.eventType()) {
            case INSERT -> this.index.index(
                new ArtifactDocument(
                    event.repoType(),
                    event.repoName(),
                    event.artifactName(),
                    event.artifactName(),
                    event.version(),
                    event.size(),
                    Instant.ofEpochMilli(event.created()),
                    event.owner()
                )
            ).join();
            case DELETE_VERSION, DELETE_ALL -> this.index.remove(
                event.repoName(),
                event.artifactName()
            ).join();
            default -> { }
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl artipie-main -Dtest=IndexConsumerTest -Dsurefire.useFile=false`
Expected: All 3 tests pass.

**Step 5: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/index/IndexConsumer.java \
  artipie-main/src/test/java/com/artipie/index/IndexConsumerTest.java
git commit -m "Add IndexConsumer: event-to-index bridge for artifact events"
```

---

### Task 3: Create IndexWarmupService — initial storage scan

**Files:**
- Create: `artipie-main/src/main/java/com/artipie/index/IndexWarmupService.java`
- Create: `artipie-main/src/test/java/com/artipie/index/IndexWarmupServiceTest.java`

**Step 1: Write the failing test**

Create `artipie-main/src/test/java/com/artipie/index/IndexWarmupServiceTest.java`:

```java
package com.artipie.index;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link IndexWarmupService}.
 */
class IndexWarmupServiceTest {

    private LuceneArtifactIndex index;

    @BeforeEach
    void setUp() {
        this.index = new LuceneArtifactIndex(new ByteBuffersDirectory());
    }

    @AfterEach
    void tearDown() throws Exception {
        this.index.close();
    }

    @Test
    void scansStorageAndPopulatesIndex() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("org/lib/1.0/lib-1.0.jar"),
            new Content.From(new byte[128])
        ).join();
        storage.save(
            new Key.From("org/lib/2.0/lib-2.0.jar"),
            new Content.From(new byte[256])
        ).join();
        final Map<String, Storage> repos = Map.of("maven-central", storage);
        final Map<String, String> repoTypes = Map.of("maven-central", "maven");
        final IndexWarmupService warmup = new IndexWarmupService(
            this.index, repos, repoTypes
        );
        warmup.run();
        assertTrue(this.index.isWarmedUp());
        assertEquals(2, this.index.getStats().join().get("documents"));
        assertEquals(1,
            this.index.locate("org/lib/1.0/lib-1.0.jar").join().size()
        );
        assertEquals("maven-central",
            this.index.locate("org/lib/1.0/lib-1.0.jar").join().get(0)
        );
    }

    @Test
    void setsWarmedUpEvenWithEmptyStorage() throws Exception {
        final Map<String, Storage> repos = Map.of();
        final Map<String, String> repoTypes = Map.of();
        final IndexWarmupService warmup = new IndexWarmupService(
            this.index, repos, repoTypes
        );
        assertFalse(this.index.isWarmedUp());
        warmup.run();
        assertTrue(this.index.isWarmedUp());
    }

    @Test
    void scansMultipleRepos() throws Exception {
        final Storage storage1 = new InMemoryStorage();
        storage1.save(
            new Key.From("com/foo/1.0/foo-1.0.jar"),
            new Content.From(new byte[64])
        ).join();
        final Storage storage2 = new InMemoryStorage();
        storage2.save(
            new Key.From("com/bar/1.0/bar-1.0.jar"),
            new Content.From(new byte[64])
        ).join();
        final Map<String, Storage> repos = Map.of(
            "repo-a", storage1,
            "repo-b", storage2
        );
        final Map<String, String> repoTypes = Map.of(
            "repo-a", "maven",
            "repo-b", "maven"
        );
        final IndexWarmupService warmup = new IndexWarmupService(
            this.index, repos, repoTypes
        );
        warmup.run();
        assertTrue(this.index.isWarmedUp());
        assertEquals(2, this.index.getStats().join().get("documents"));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl artipie-main -Dtest=IndexWarmupServiceTest -Dsurefire.useFile=false`
Expected: Compilation error — `IndexWarmupService` doesn't exist.

**Step 3: Write IndexWarmupService implementation**

Create `artipie-main/src/main/java/com/artipie/index/IndexWarmupService.java`:

```java
package com.artipie.index;

import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.misc.EcsLogger;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Scans group member repository storage to populate the Lucene index on startup.
 * After scanning completes, marks the index as warmed up.
 * <p>
 * This runs on a background thread and does not block startup.
 * While scanning, GroupSlice falls back to fan-out on index miss.
 */
public final class IndexWarmupService implements Runnable {

    /**
     * Lucene artifact index to populate.
     */
    private final ArtifactIndex index;

    /**
     * Map of repository name to its storage.
     */
    private final Map<String, Storage> repos;

    /**
     * Map of repository name to its type (e.g. "maven", "npm").
     */
    private final Map<String, String> repoTypes;

    /**
     * Creates a new warmup service.
     * @param index Lucene artifact index
     * @param repos Map of repo name to storage
     * @param repoTypes Map of repo name to type
     */
    public IndexWarmupService(
        final ArtifactIndex index,
        final Map<String, Storage> repos,
        final Map<String, String> repoTypes
    ) {
        this.index = index;
        this.repos = repos;
        this.repoTypes = repoTypes;
    }

    @Override
    public void run() {
        EcsLogger.log("index-warmup-start",
            Map.of("repos", String.valueOf(this.repos.size()))
        );
        int total = 0;
        for (final Map.Entry<String, Storage> entry : this.repos.entrySet()) {
            final String repoName = entry.getKey();
            final Storage storage = entry.getValue();
            final String repoType = this.repoTypes.getOrDefault(repoName, "unknown");
            try {
                final Collection<Key> keys = storage.list(Key.ROOT).join();
                for (final Key key : keys) {
                    this.index.index(
                        new ArtifactDocument(
                            repoType,
                            repoName,
                            key.string(),
                            key.string(),
                            "",
                            0L,
                            Instant.now(),
                            ""
                        )
                    ).join();
                    total++;
                }
            } catch (final Exception ex) {
                EcsLogger.log("index-warmup-repo-error",
                    Map.of(
                        "repo", repoName,
                        "error", ex.getMessage() != null ? ex.getMessage() : ex.toString()
                    )
                );
            }
        }
        this.index.setWarmedUp();
        EcsLogger.log("index-warmup-complete",
            Map.of("documents", String.valueOf(total))
        );
    }
}
```

**Step 4: Run tests**

Run: `mvn test -pl artipie-main -Dtest=IndexWarmupServiceTest -Dsurefire.useFile=false`
Expected: All 3 tests pass.

**Step 5: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/index/IndexWarmupService.java \
  artipie-main/src/test/java/com/artipie/index/IndexWarmupServiceTest.java
git commit -m "Add IndexWarmupService: scans storage to populate index on startup"
```

---

### Task 4: Add `artifactIndex()` to Settings interface

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/settings/Settings.java`

**Step 1: Add `artifactIndex()` method to Settings**

In `artipie-main/src/main/java/com/artipie/settings/Settings.java`, add after the `caches()` method (line 80):

```java
/**
 * Artifact search index.
 * @return Artifact index (NOP if indexing is disabled)
 */
ArtifactIndex artifactIndex();
```

Add import: `import com.artipie.index.ArtifactIndex;`

**Step 2: Verify it compiles**

Run: `mvn test-compile -pl artipie-main`
Expected: Compilation error in `YamlSettings` (doesn't implement `artifactIndex()` yet). This is expected — we'll implement it in Batch 2.

Add a temporary default method to Settings.java instead:

```java
/**
 * Artifact search index.
 * @return Artifact index (NOP if indexing is disabled)
 */
default ArtifactIndex artifactIndex() {
    return ArtifactIndex.NOP;
}
```

**Step 3: Verify it compiles**

Run: `mvn test-compile -pl artipie-main`
Expected: Compiles successfully.

**Step 4: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/settings/Settings.java
git commit -m "Add artifactIndex() method to Settings interface"
```

**Verify full batch: `mvn test -pl artipie-main`** — All tests pass.

---

## Batch 2: Production Wiring

### Task 5: YamlSettings — parse config, create index, wire IndexConsumer

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/settings/YamlSettings.java`

**Step 1: Add index field and config parsing**

In `YamlSettings.java`, add a field after existing fields (around line 135):

```java
/**
 * Lucene artifact index.
 */
private final ArtifactIndex artifactIndex;
```

Add imports at the top:
```java
import com.artipie.index.ArtifactIndex;
import com.artipie.index.LuceneArtifactIndex;
import com.artipie.index.IndexConsumer;
import org.apache.lucene.store.MMapDirectory;
import java.nio.file.Paths;
```

In the constructor (lines 174-208), after the existing initialization and before the closing brace, add index creation:

```java
// Initialize artifact index
final YamlMapping indexConfig = meta.yamlMapping("artifact_index");
if (indexConfig != null && "true".equals(indexConfig.string("enabled"))) {
    final String dir = indexConfig.string("directory");
    if (dir == null || dir.isEmpty()) {
        throw new IllegalArgumentException(
            "artifact_index.directory is required when indexing is enabled"
        );
    }
    try {
        final java.nio.file.Path indexPath = Paths.get(dir);
        java.nio.file.Files.createDirectories(indexPath);
        this.artifactIndex = new LuceneArtifactIndex(
            new MMapDirectory(indexPath)
        );
    } catch (final IOException err) {
        throw new UncheckedIOException(
            "Failed to create Lucene index directory: " + dir, err
        );
    }
} else {
    this.artifactIndex = ArtifactIndex.NOP;
}
```

Add `import java.io.UncheckedIOException;` if not already present.

**Step 2: Override `artifactIndex()` method**

Add the method override:

```java
@Override
public ArtifactIndex artifactIndex() {
    return this.artifactIndex;
}
```

**Step 3: Wire IndexConsumer into the event pipeline**

In the `initArtifactsEvents()` method (lines 552-580), after the `DbConsumer` creation (around line 572), add the IndexConsumer:

Find the section that creates consumers and add IndexConsumer as an additional consumer. The method creates `MetadataEventQueues` — the event queue dispatches to all registered consumers.

Look at how `DbConsumer` instances are created and registered. Add an `IndexConsumer` that wraps this.artifactIndex:

After the line that creates the `MetadataEventQueues` (the one that creates and configures the event processing), add IndexConsumer registration. The exact integration point depends on how MetadataEventQueues dispatches — it likely uses a list of consumers. Add the IndexConsumer to whatever consumer list is used.

If MetadataEventQueues only supports a single consumer via `DbConsumer`, then modify the `DbConsumer` batch processing or add a composite consumer. The simplest approach: check if `EventsProcessor` (the Quartz job that drains the queue) can accept multiple consumers.

**Important**: Read the actual `EventsProcessor` and `MetadataEventQueues` code at implementation time to find the exact integration point. The IndexConsumer must be called for every event alongside DbConsumer.

**Step 4: Run tests**

Run: `mvn test -pl artipie-main -Dsurefire.useFile=false`
Expected: All tests pass. YamlSettings tests may not exercise the new code path since they likely don't configure `artifact_index`.

**Step 5: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/settings/YamlSettings.java
git commit -m "Wire LuceneArtifactIndex in YamlSettings: config parsing + IndexConsumer"
```

---

### Task 6: VertxMain — pass index to RestApi, trigger warmup

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/VertxMain.java`

**Step 1: Pass index to RestApi**

In `VertxMain.start()` (line 301), change the RestApi constructor call from:

```java
new RestApi(settings, apiPort, jwt)
```

to use the full constructor that accepts ArtifactIndex:

```java
new RestApi(settings, apiPort, jwt, settings.artifactIndex())
```

Wait — check the RestApi constructor signatures. The simple constructor (lines 135-143) takes `(Settings, int, JWTAuth)` and hardcodes `ArtifactIndex.NOP`. The full constructor (lines 105-127) takes 10 parameters.

The better approach: modify the simple RestApi constructor to use `settings.artifactIndex()` instead of `ArtifactIndex.NOP`. This way VertxMain doesn't need to change.

Actually, since we're modifying RestApi in Task 8, let's do the simplest change in VertxMain:

Find where RestApi is created in VertxMain.start() and ensure it uses settings.artifactIndex(). If the simple constructor is used, we'll fix it in Task 8.

**Step 2: Trigger warmup on startup**

After RepositorySlices is created and the server is started, add warmup triggering. Find the point after server startup (around line 321) and add:

```java
// Trigger index warmup if enabled
final ArtifactIndex artIndex = settings.artifactIndex();
if (!(artIndex instanceof ArtifactIndex) || artIndex != ArtifactIndex.NOP) {
    // Build repo name -> storage map for group members
    // This requires iterating configured repos
    // Launch warmup on background thread
    new Thread(
        new IndexWarmupService(artIndex, repoStorages, repoTypes),
        "index-warmup"
    ).start();
}
```

**Important**: The exact warmup triggering depends on how repository storage is accessible from VertxMain. At implementation time, check how `RepositorySlices` resolves repositories and their storage. The warmup needs:
1. A map of repo name -> Storage (for group member repos)
2. A map of repo name -> repo type string

These may need to be extracted from the Settings/repos configuration. If not easily available in VertxMain, the warmup can be triggered from YamlSettings constructor or RepositorySlices constructor instead.

**Step 3: Run tests**

Run: `mvn test -pl artipie-main -Dsurefire.useFile=false`
Expected: All tests pass.

**Step 4: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/VertxMain.java
git commit -m "Pass artifact index to RestApi, trigger warmup on startup"
```

---

### Task 7: RepositorySlices — pass index to GroupSlice

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/RepositorySlices.java`

**Step 1: Store index in RepositorySlices**

Add a field:

```java
private final ArtifactIndex artifactIndex;
```

In the constructor (lines 146-186), store the index from settings:

```java
this.artifactIndex = settings.artifactIndex();
```

Add import: `import com.artipie.index.ArtifactIndex;`

**Step 2: Pass index to all GroupSlice constructors**

Find every place where `new GroupSlice(...)` is created (lines ~605-720). There are multiple group types: npm-group, maven-group, and generic group types.

For each GroupSlice creation, use the full constructor that accepts `Optional<ArtifactIndex>`:

```java
new GroupSlice(
    resolver, group, members, port, depth, timeoutSeconds,
    routingRules, Optional.of(this.artifactIndex)
)
```

If the current call uses a shorter constructor, expand it to the full form, passing the existing parameters plus `routingRules` (which may be `List.of()` if not used) and `Optional.of(this.artifactIndex)`.

**Step 3: Run tests**

Run: `mvn test -pl artipie-main -Dsurefire.useFile=false`
Expected: All tests pass. GroupSliceTest may still use NOP index — that's fine.

**Step 4: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/RepositorySlices.java
git commit -m "Pass artifact index from settings to all GroupSlice instances"
```

---

### Task 8: RestApi — use real index from settings

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/api/RestApi.java`

**Step 1: Change simple constructor to use settings.artifactIndex()**

In the simple RestApi constructor (lines 135-143), change line 141 from:

```java
ArtifactIndex.NOP
```

to:

```java
settings.artifactIndex()
```

This ensures SearchRest gets the real index.

**Step 2: Run tests**

Run: `mvn test -pl artipie-main -Dsurefire.useFile=false`
Expected: All tests pass.

**Step 3: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/api/RestApi.java
git commit -m "RestApi: use real artifact index from settings instead of NOP"
```

**Verify full batch: `mvn test -pl artipie-main`** — All tests pass.

---

## Batch 3: GroupSlice Replacement + Search Stats

### Task 9: GroupSlice — replace negative cache with index lookup

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/group/GroupSlice.java`

This is the most critical change. GroupSlice currently has two parallel paths:
1. Index-first lookup (lines 274-297) — already exists but receives Optional.empty()
2. Negative cache check in queryMember() (lines 392-408) — the fallback

**Step 1: Remove GroupNegativeCache field and creation**

Remove the `GroupNegativeCache negativeCache` field (line 77).

In the full constructor (lines 206-240), remove the line that creates GroupNegativeCache:
```java
this.negativeCache = new GroupNegativeCache(group, ...);
```

Remove the import: `import com.artipie.group.GroupNegativeCache;`

**Step 2: Make artifactIndex non-optional**

Change field from `Optional<ArtifactIndex> artifactIndex` to `ArtifactIndex artifactIndex`.

Update constructor to take `ArtifactIndex` directly instead of `Optional<ArtifactIndex>`:
```java
this.artifactIndex = artifactIndex;
```

Update all shorter constructors to pass `ArtifactIndex.NOP` as default.

**Step 3: Rewrite response() method with warmup-aware logic**

Replace the existing index-first path and fan-out logic in `response()` (lines 242-310) with:

```java
public CompletableFuture<Response> response(
    final RequestLine line, final Headers headers, final Content body
) {
    // Reject upload methods
    if (line.method() == RqMethod.PUT || line.method() == RqMethod.POST) {
        return CompletableFuture.completedFuture(
            new RsWithStatus(RsStatus.METHOD_NOT_ALLOWED)
        );
    }
    final String path = extractPath(line);
    // Index lookup
    return this.artifactIndex.locate(path).thenCompose(repos -> {
        if (!repos.isEmpty()) {
            // Index hit: query only matching members
            final List<MemberSlice> targeted = this.members.stream()
                .filter(m -> repos.contains(m.name()))
                .collect(Collectors.toList());
            if (!targeted.isEmpty()) {
                return this.queryMembers(targeted, line, headers, body);
            }
        }
        if (repos.isEmpty() && this.artifactIndex.isWarmedUp()) {
            // Index warm, nobody has it: 404
            return CompletableFuture.completedFuture(NOT_FOUND);
        }
        // Index cold or targeted members empty: fan-out
        return this.queryAllMembersInParallel(line, headers, body);
    });
}
```

**Step 4: Remove negative cache checks from queryMember()**

In `queryMember()` (lines ~392-408), remove the `negativeCache.isNotFoundAsync()` check. The method should just query the member directly.

In `handleMemberResponse()` (lines ~515-517), remove the `negativeCache.cacheNotFound()` call on 404.

**Step 5: Add belt-and-suspenders index update on fan-out success**

When a member returns a successful response during fan-out (in `handleMemberResponse()` or equivalent), add an index update:

```java
if (response.status().success() && this.artifactIndex != ArtifactIndex.NOP) {
    this.artifactIndex.index(
        new ArtifactDocument(
            "", memberName, path, path, "", 0L, Instant.now(), ""
        )
    );
}
```

This ensures the index learns from fan-out results even if the event pipeline is delayed.

**Step 6: Run tests**

Run: `mvn test -pl artipie-main -Dtest=GroupSliceTest -Dsurefire.useFile=false`
Expected: All tests pass. Tests use the default constructor which now uses `ArtifactIndex.NOP`, which makes `locate()` return empty, `isWarmedUp()` returns false → fan-out mode.

**Step 7: Run full test suite**

Run: `mvn test -pl artipie-main -Dsurefire.useFile=false`
Expected: All tests pass. Some tests may need fixing if they reference GroupNegativeCache.

**Step 8: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/group/GroupSlice.java
git commit -m "GroupSlice: replace negative cache with Lucene index lookup + warmup fallback"
```

---

### Task 10: SearchRest — add stats endpoint, wire reindex

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/api/SearchRest.java`
- Modify: `artipie-main/src/main/resources/swagger-ui/yaml/search.yaml`

**Step 1: Add /api/v1/search/stats endpoint**

In `SearchRest.java`, add a new route in `init()` method:

```java
router.get("/stats")
    .handler(new AuthzHandler(this.policy, new ApiSearchPermission(ApiSearchPermission.SearchAction.READ)))
    .handler(this::stats);
```

Add the handler method:

```java
private void stats(final RoutingContext context) {
    this.index.getStats().whenComplete((stats, err) -> {
        if (err != null) {
            sendError(context, 500, err.getMessage());
        } else {
            context.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new io.vertx.core.json.JsonObject(stats).encode());
        }
    });
}
```

**Step 2: Update search.yaml**

Add the stats endpoint documentation:

```yaml
  /api/v1/search/stats:
    get:
      summary: Get index statistics
      description: Returns statistics about the artifact search index including document count and warmup status.
      operationId: getIndexStats
      tags:
        - search
      security:
        - bearerAuth: []
      responses:
        '200':
          description: Index statistics
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/IndexStats'
        '401':
          $ref: '#/components/responses/UnauthorizedError'
```

Add the IndexStats schema to components/schemas:

```yaml
    IndexStats:
      type: object
      description: Artifact search index statistics
      properties:
        documents:
          type: integer
          description: Number of documents in the index
          example: 1542
        warmedUp:
          type: boolean
          description: Whether the initial warmup scan has completed
          example: true
        directoryType:
          type: string
          description: Lucene directory implementation in use
          example: "MMapDirectory"
```

**Step 3: Run tests**

Run: `mvn test -pl artipie-main -Dsurefire.useFile=false`
Expected: All tests pass.

**Step 4: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/api/SearchRest.java \
  artipie-main/src/main/resources/swagger-ui/yaml/search.yaml
git commit -m "SearchRest: add /stats endpoint for index statistics"
```

**Verify full batch: `mvn test -pl artipie-main`** — All tests pass.

---

## Batch 4: Cleanup — Remove Negative Cache

### Task 11: Remove GroupNegativeCache and all references

**Files:**
- Delete: `artipie-main/src/main/java/com/artipie/group/GroupNegativeCache.java`
- Modify: `artipie-main/src/main/java/com/artipie/db/DbConsumer.java` — remove negative cache invalidation
- Modify: any files that import GroupNegativeCache

**Step 1: Remove GroupNegativeCache.java**

```bash
rm artipie-main/src/main/java/com/artipie/group/GroupNegativeCache.java
```

**Step 2: Remove negative cache invalidation from DbConsumer**

In `DbConsumer.java`, find `invalidateGroupNegativeCache()` method (lines 114-148) and remove it entirely.

In `DbObserver.onNext()` (lines 216-217), remove the NPM negative cache invalidation call:

```java
if ("npm".equals(record.repoType())) {
    invalidateGroupNegativeCache(record.artifactName());
}
```

Remove the `import com.artipie.group.GroupNegativeCache;` import.

**Step 3: Search for other GroupNegativeCache references**

Search the codebase for any remaining imports or references to `GroupNegativeCache`. Remove all of them.

Check:
- `NegativeCacheConfig.java` — if it only exists for GroupNegativeCache, remove it
- `GlobalCacheConfig.java` — if it has group-negative-cache-specific config
- Any test files referencing GroupNegativeCache

**Step 4: Run tests**

Run: `mvn test -pl artipie-main -Dsurefire.useFile=false`
Expected: All tests pass. If any test references GroupNegativeCache, fix it.

**Step 5: Commit**

```bash
git add -A
git commit -m "Remove GroupNegativeCache: replaced by Lucene positive index"
```

---

### Task 12: Remove CacheRest, cache.yaml, ApiCachePermission

**Files:**
- Delete: `artipie-main/src/main/java/com/artipie/api/CacheRest.java`
- Delete: `artipie-main/src/main/resources/swagger-ui/yaml/cache.yaml`
- Delete: `artipie-main/src/main/java/com/artipie/api/perms/ApiCachePermission.java`
- Delete: `artipie-main/src/main/java/com/artipie/api/perms/ApiCachePermissionFactory.java`
- Modify: `artipie-main/src/main/java/com/artipie/api/RestApi.java` — remove CacheRest initialization
- Modify: `artipie-main/src/main/resources/swagger-ui/swagger-initializer.js` — remove cache.yaml reference
- Modify: `artipie-main/src/main/resources/swagger-ui/yaml/roles.yaml` — remove `api_cache_permissions` from Permissions schema

**Step 1: Delete files**

```bash
rm artipie-main/src/main/java/com/artipie/api/CacheRest.java
rm artipie-main/src/main/resources/swagger-ui/yaml/cache.yaml
rm artipie-main/src/main/java/com/artipie/api/perms/ApiCachePermission.java
rm artipie-main/src/main/java/com/artipie/api/perms/ApiCachePermissionFactory.java
```

**Step 2: Remove CacheRest from RestApi.java**

In `RestApi.java start()` method, find where CacheRest is initialized and remove:
- The CacheRest creation line
- Any CacheRest route mounting

Remove imports for CacheRest and ApiCachePermission.

**Step 3: Remove cache.yaml from swagger-initializer.js**

In `swagger-initializer.js` (lines 7-15), remove the entry:
```js
{url: "yaml/cache.yaml", name: "Cache & Health"},
```

Keep the health check endpoint — move it to a settings route or add it to search.yaml if it's used for monitoring. Or better: keep the `/api/health` endpoint in RestApi directly without CacheRest.

**Step 4: Move health endpoint**

The `/api/health` endpoint from CacheRest is useful for monitoring. Keep it by adding it directly in RestApi.start():

```java
router.get("/api/health").handler(ctx ->
    ctx.response()
        .putHeader("Content-Type", "application/json")
        .end("{\"status\":\"ok\"}")
);
```

**Step 5: Remove api_cache_permissions from roles.yaml Permissions schema**

In `roles.yaml`, remove the `api_cache_permissions` property from the Permissions schema (lines ~316-325).

**Step 6: Remove api_cache_permissions references from permission loading**

Search for `api_cache_permissions` in Java code. Check:
- Permission factory registry/loading
- Role validation code
- Any test that references cache permissions

Remove all references.

**Step 7: Run tests**

Run: `mvn test -pl artipie-main -Dsurefire.useFile=false`
Expected: Tests pass. If any test references CacheRest or ApiCachePermission, remove/fix the test.

**Step 8: Commit**

```bash
git add -A
git commit -m "Remove CacheRest, cache.yaml, ApiCachePermission: negative cache API eliminated"
```

**Verify full batch: `mvn test -pl artipie-main`** — All tests pass.

---

## Batch 5: Documentation

### Task 13: Update USER_GUIDE.md

**Files:**
- Modify: `docs/USER_GUIDE.md`

**Step 1: Add artifact_index configuration section**

Find the configuration section and add:

```markdown
### Artifact Index

The artifact index provides fast lookup for group repositories, eliminating the need
for fan-out queries to all group members. When enabled, group repositories query the
Lucene index to find which member has a requested artifact, then query only that member.

```yaml
meta:
  artifact_index:
    enabled: true
    directory: /var/artipie/index
    warmup_on_startup: true
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `enabled` | `false` | Enable the Lucene artifact index |
| `directory` | — | Path for index files (required if enabled) |
| `warmup_on_startup` | `true` | Scan repositories on startup to populate the index |

When enabled, the index:
- Tracks which repository contains each artifact
- Updates in real-time as artifacts are uploaded or deleted
- Provides O(1) lookup for group repository requests
- Falls back to fan-out during warmup (initial storage scan)
```

**Step 2: Update group repositories section**

Find the group repositories section and update to explain how groups use the index:
- With index: targeted lookup, no fan-out
- Without index: fan-out to all members (original behavior)

**Step 3: Remove any references to negative cache configuration**

Remove any mention of `meta.caches.negative` or Valkey/Redis for group caching.

**Step 4: Commit**

```bash
git add docs/USER_GUIDE.md
git commit -m "docs: add artifact index configuration to user guide"
```

---

### Task 14: Update DEVELOPER_GUIDE.md

**Files:**
- Modify: `docs/DEVELOPER_GUIDE.md`

**Step 1: Add index architecture section**

Add a section explaining:

```markdown
### Artifact Index Architecture

The artifact index uses Apache Lucene to maintain a searchable catalog of all artifacts
across repositories. This enables O(1) group repository lookups instead of O(N) fan-out.

#### Event Flow

```
Upload/Delete → Event Queue → IndexConsumer → LuceneArtifactIndex
                            → DbConsumer → PostgreSQL (unchanged)
```

IndexConsumer runs in parallel with DbConsumer. Both consume from the same event queue.

#### Request Flow (Group Repositories)

```
Request → GroupSlice → index.locate(path)
  ├─ Index hit: [member-A] → query member-A only → return response
  ├─ Index miss + warm → return 404 immediately
  └─ Index miss + cold → fan-out to all members (warmup mode)
```

#### Warmup

On startup, IndexWarmupService scans all group member repositories in the background.
During warmup, GroupSlice falls back to fan-out on index miss. Once the scan completes,
the index is trusted fully.

#### Key Classes

| Class | Purpose |
|-------|---------|
| `LuceneArtifactIndex` | Lucene-backed index with index/remove/search/locate |
| `IndexConsumer` | Event → index bridge (parallel to DbConsumer) |
| `IndexWarmupService` | Startup storage scan to populate index |
| `ArtifactDocument` | Immutable record for index documents |
| `ArtifactIndex.NOP` | No-op implementation when indexing is disabled |
```

**Step 2: Remove negative cache architecture documentation**

Remove any sections about GroupNegativeCache, L1/L2 cache tiers, or Valkey for group lookups.

**Step 3: Commit**

```bash
git add docs/DEVELOPER_GUIDE.md
git commit -m "docs: add index architecture to developer guide, remove negative cache docs"
```

---

### Task 15: Update wiki pages

**Files:**
- Modify: `.wiki/Configuration-Metadata.md`
- Modify: `.wiki/Rest-api.md`

**Step 1: Add index configuration to Configuration-Metadata.md**

Add the `artifact_index` configuration reference with all parameters.

**Step 2: Update Rest-api.md**

- Add search API documentation (`/api/v1/search/*` endpoints)
- Remove cache API documentation (`/api/v1/cache/*` endpoints)
- Add stats endpoint documentation

**Step 3: Commit**

```bash
git add .wiki/Configuration-Metadata.md .wiki/Rest-api.md
git commit -m "docs: update wiki with index config and search API, remove cache API"
```

---

### Task 16: Full build verification

**Step 1: Full build**

Run: `mvn clean install -U -DskipTests`
Expected: BUILD SUCCESS across all modules.

**Step 2: Full test suite**

Run: `mvn test -pl artipie-main -Dsurefire.useFile=false`
Expected: All tests pass, 0 failures.

**Step 3: Verify no dead code**

Search for any remaining references to:
- `GroupNegativeCache`
- `CacheRest`
- `ApiCachePermission`
- `ApiCachePermissionFactory`
- `cache.yaml`

Expected: No references found.

---

## Files Summary

| File | Batch | Action | Changes |
|------|-------|--------|---------|
| `ArtifactIndex.java` | 1 | Modify | Add isWarmedUp(), setWarmedUp(), getStats() |
| `LuceneArtifactIndex.java` | 1 | Modify | Implement warmup state + stats |
| `LuceneArtifactIndexTest.java` | 1 | Modify | 3 new tests |
| `IndexConsumer.java` | 1 | Create | Event-to-index bridge |
| `IndexConsumerTest.java` | 1 | Create | 3 tests |
| `IndexWarmupService.java` | 1 | Create | Startup storage scan |
| `IndexWarmupServiceTest.java` | 1 | Create | 3 tests |
| `Settings.java` | 1 | Modify | Add artifactIndex() method |
| `YamlSettings.java` | 2 | Modify | Parse config, create index, wire IndexConsumer |
| `VertxMain.java` | 2 | Modify | Trigger warmup on startup |
| `RepositorySlices.java` | 2 | Modify | Pass index to GroupSlice |
| `RestApi.java` | 2 | Modify | Use real index from settings |
| `GroupSlice.java` | 3 | Modify | Replace negative cache with index lookup |
| `SearchRest.java` | 3 | Modify | Add /stats endpoint |
| `search.yaml` | 3 | Modify | Add stats endpoint spec |
| `GroupNegativeCache.java` | 4 | Delete | Replaced by Lucene index |
| `DbConsumer.java` | 4 | Modify | Remove negative cache invalidation |
| `CacheRest.java` | 4 | Delete | Removed |
| `cache.yaml` | 4 | Delete | Removed |
| `ApiCachePermission.java` | 4 | Delete | Removed |
| `ApiCachePermissionFactory.java` | 4 | Delete | Removed |
| `swagger-initializer.js` | 4 | Modify | Remove cache.yaml reference |
| `roles.yaml` | 4 | Modify | Remove api_cache_permissions |
| `USER_GUIDE.md` | 5 | Modify | Add index config |
| `DEVELOPER_GUIDE.md` | 5 | Modify | Add index architecture |
| `.wiki/Configuration-Metadata.md` | 5 | Modify | Add index config reference |
| `.wiki/Rest-api.md` | 5 | Modify | Add search API, remove cache API |

## Verification

After each batch: `mvn test -pl artipie-main` — all tests pass
After all batches: `mvn clean install -U -DskipTests` — full build green
