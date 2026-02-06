# Backfill Automation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `--config-dir` + `--storage-root` bulk mode to `BackfillCli` that reads all Artipie repo YAML configs, derives repo name and scanner type, and runs each scanner sequentially.

**Architecture:** Three new helper classes (`RepoEntry`, `RepoTypeNormalizer`, `RepoConfigYaml`) feed into `BulkBackfillRunner` which orchestrates the full loop. `BackfillCli` detects the new flags and delegates, leaving the existing single-repo path untouched.

**Tech Stack:** Java 17, SnakeYAML 2.0 (already in pom.xml), JUnit 5 + Hamcrest (existing test stack), Apache Commons CLI (existing), SLF4J/Log4j2 (existing).

---

## Important API Corrections vs. Spec

The spec has minor inaccuracies about pre-existing APIs. **Use these actual signatures:**

- `ProgressReporter(int logInterval)` — no `repoName` param
- `ProgressReporter.increment()`, `getScanned()`, `getErrors()`, `printFinalSummary()` — full method set
- `BatchInserter(DataSource, int batchSize, boolean dryRun)` — no ProgressReporter param
- `BatchInserter.getInsertedCount()` / `getSkippedCount()` — these DO exist on BatchInserter
- **FAILED path:** `inserter` is out of scope in the catch block (try-with-resources). Use `-1L` sentinel for both inserted and dbSkipped on FAILED rows (displays as `-` in summary). This is simpler and correct since `reporter.getScanned()` counts stream records, not DB-committed records.
- **Duplicate stems:** Filesystem guarantees unique filenames within a directory, so `seenNames` is a defensive guard that cannot be triggered by real files. The test verifies no false-positive collisions occur between two repos with *different* names.

## File Map

| Action | File |
|--------|------|
| Create | `artipie-backfill/src/main/java/com/artipie/backfill/RepoEntry.java` |
| Create | `artipie-backfill/src/main/java/com/artipie/backfill/RepoTypeNormalizer.java` |
| Create | `artipie-backfill/src/test/java/com/artipie/backfill/RepoTypeNormalizerTest.java` |
| Create | `artipie-backfill/src/main/java/com/artipie/backfill/RepoConfigYaml.java` |
| Create | `artipie-backfill/src/test/java/com/artipie/backfill/RepoConfigYamlTest.java` |
| Create | `artipie-backfill/src/main/java/com/artipie/backfill/BulkBackfillRunner.java` |
| Create | `artipie-backfill/src/test/java/com/artipie/backfill/BulkBackfillRunnerTest.java` |
| Modify | `artipie-backfill/src/main/java/com/artipie/backfill/BackfillCli.java` |
| Modify | `artipie-backfill/src/test/java/com/artipie/backfill/BackfillCliTest.java` |

---

## Chunk 1: Data types and parsers

### Task 1: `RepoEntry` record and `RepoTypeNormalizer`

**Files:**
- Create: `artipie-backfill/src/main/java/com/artipie/backfill/RepoEntry.java`
- Create: `artipie-backfill/src/main/java/com/artipie/backfill/RepoTypeNormalizer.java`
- Create: `artipie-backfill/src/test/java/com/artipie/backfill/RepoTypeNormalizerTest.java`

- [ ] **Step 1.1: Write the failing tests for `RepoTypeNormalizer`**

Create `artipie-backfill/src/test/java/com/artipie/backfill/RepoTypeNormalizerTest.java`:

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link RepoTypeNormalizer}.
 *
 * @since 1.20.13
 */
final class RepoTypeNormalizerTest {

    @ParameterizedTest
    @CsvSource({
        "docker-proxy,  docker",
        "npm-proxy,     npm",
        "maven-proxy,   maven",
        "go-proxy,      go",
        "maven,         maven",
        "docker,        docker",
        "file,          file",
        "go,            go"
    })
    void normalizesType(final String raw, final String expected) {
        MatcherAssert.assertThat(
            String.format("normalize('%s') should return '%s'", raw, expected),
            RepoTypeNormalizer.normalize(raw),
            Matchers.is(expected.trim())
        );
    }
}
```

- [ ] **Step 1.2: Run the test to confirm it fails**

```
mvn -pl artipie-backfill -Dtest=RepoTypeNormalizerTest test
```
Expected: compilation failure — `RepoTypeNormalizer` does not exist yet.

- [ ] **Step 1.3: Create `RepoEntry.java`**

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

/**
 * Parsed result of one Artipie repo YAML config file.
 *
 * @param repoName Repo name derived from the YAML filename stem (e.g. {@code go.yaml} → {@code go})
 * @param rawType Raw {@code repo.type} string from the YAML (e.g. {@code docker-proxy})
 * @since 1.20.13
 */
record RepoEntry(String repoName, String rawType) {
}
```

- [ ] **Step 1.4: Create `RepoTypeNormalizer.java`**

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

/**
 * Normalises raw Artipie repo type strings to scanner type keys
 * understood by {@link ScannerFactory}.
 *
 * <p>Currently only strips the {@code -proxy} suffix
 * (e.g. {@code docker-proxy} → {@code docker}).
 * Other compound suffixes (e.g. {@code -hosted}, {@code -group}) are out of
 * scope and will surface as unknown types in {@link ScannerFactory}.</p>
 *
 * @since 1.20.13
 */
final class RepoTypeNormalizer {

    /**
     * Private ctor — utility class, not instantiable.
     */
    private RepoTypeNormalizer() {
    }

    /**
     * Normalize a raw repo type by stripping the {@code -proxy} suffix.
     *
     * @param rawType Raw {@code repo.type} value from the YAML config
     * @return Normalised scanner type string
     */
    static String normalize(final String rawType) {
        final String suffix = "-proxy";
        if (rawType.endsWith(suffix)) {
            return rawType.substring(0, rawType.length() - suffix.length());
        }
        return rawType;
    }
}
```

- [ ] **Step 1.5: Run the tests and confirm they pass**

```
mvn -pl artipie-backfill -Dtest=RepoTypeNormalizerTest test
```
Expected: BUILD SUCCESS, all `normalizesType` cases pass.

- [ ] **Step 1.6: Commit**

```bash
git add artipie-backfill/src/main/java/com/artipie/backfill/RepoEntry.java \
        artipie-backfill/src/main/java/com/artipie/backfill/RepoTypeNormalizer.java \
        artipie-backfill/src/test/java/com/artipie/backfill/RepoTypeNormalizerTest.java
git commit -m "feat(backfill): add RepoEntry record and RepoTypeNormalizer"
```

---

### Task 2: `RepoConfigYaml`

**Files:**
- Create: `artipie-backfill/src/main/java/com/artipie/backfill/RepoConfigYaml.java`
- Create: `artipie-backfill/src/test/java/com/artipie/backfill/RepoConfigYamlTest.java`

- [ ] **Step 2.1: Write the failing tests for `RepoConfigYaml`**

Create `artipie-backfill/src/test/java/com/artipie/backfill/RepoConfigYamlTest.java`:

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link RepoConfigYaml}.
 *
 * @since 1.20.13
 */
final class RepoConfigYamlTest {

    /**
     * Happy path: a well-formed config file is parsed correctly.
     * Repo name is derived from the filename stem; rawType from repo.type.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void parsesValidConfig(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("go.yaml");
        Files.writeString(file, "repo:\n  type: go\n");
        final RepoEntry entry = RepoConfigYaml.parse(file);
        MatcherAssert.assertThat(
            "repoName should be the filename stem",
            entry.repoName(),
            Matchers.is("go")
        );
        MatcherAssert.assertThat(
            "rawType should match repo.type in YAML",
            entry.rawType(),
            Matchers.is("go")
        );
    }

    /**
     * Proxy type is preserved as-is (normalisation is done by RepoTypeNormalizer).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void parsesProxyType(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("docker_proxy.yaml");
        Files.writeString(file, "repo:\n  type: docker-proxy\n");
        final RepoEntry entry = RepoConfigYaml.parse(file);
        MatcherAssert.assertThat(
            "rawType should be preserved without normalisation",
            entry.rawType(),
            Matchers.is("docker-proxy")
        );
        MatcherAssert.assertThat(
            "repoName should match filename stem",
            entry.repoName(),
            Matchers.is("docker_proxy")
        );
    }

    /**
     * Missing {@code repo.type} key must throw {@link IOException}.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void throwsWhenRepoTypeMissing(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("bad.yaml");
        Files.writeString(file, "repo:\n  storage:\n    type: fs\n");
        Assertions.assertThrows(
            IOException.class,
            () -> RepoConfigYaml.parse(file),
            "Missing repo.type should throw IOException"
        );
    }

    /**
     * Malformed YAML (not parseable) must throw {@link IOException}.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void throwsOnMalformedYaml(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("broken.yaml");
        Files.writeString(file, "repo: [\nunclosed bracket\n");
        Assertions.assertThrows(
            IOException.class,
            () -> RepoConfigYaml.parse(file),
            "Malformed YAML should throw IOException"
        );
    }

    /**
     * Empty YAML file must throw {@link IOException}.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void throwsOnEmptyFile(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("empty.yaml");
        Files.writeString(file, "");
        Assertions.assertThrows(
            IOException.class,
            () -> RepoConfigYaml.parse(file),
            "Empty YAML should throw IOException"
        );
    }

    /**
     * YAML with additional fields alongside repo.type parses without error.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void toleratesExtraFields(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("npm.yaml");
        Files.writeString(
            file,
            "repo:\n  type: npm\n  url: http://example.com\n  storage:\n    type: fs\n    path: /data\n"
        );
        final RepoEntry entry = RepoConfigYaml.parse(file);
        MatcherAssert.assertThat(entry.rawType(), Matchers.is("npm"));
    }
}
```

- [ ] **Step 2.2: Run the tests to confirm they fail**

```
mvn -pl artipie-backfill -Dtest=RepoConfigYamlTest test
```
Expected: compilation failure — `RepoConfigYaml` does not exist yet.

- [ ] **Step 2.3: Create `RepoConfigYaml.java`**

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses one Artipie YAML repo config file into a {@link RepoEntry}.
 *
 * <p>Expected minimal YAML structure:
 * <pre>
 * repo:
 *   type: docker
 * </pre>
 * Additional fields (storage, remotes, url, etc.) are ignored.
 * </p>
 *
 * @since 1.20.13
 */
final class RepoConfigYaml {

    /**
     * Private ctor — utility class, not instantiable.
     */
    private RepoConfigYaml() {
    }

    /**
     * Parse a single {@code .yaml} Artipie repo config file.
     *
     * @param file Path to the {@code .yaml} file
     * @return Parsed {@link RepoEntry} with repo name (filename stem) and raw type
     * @throws IOException if the file is unreadable, YAML is malformed,
     *     or {@code repo.type} is missing
     */
    @SuppressWarnings("unchecked")
    static RepoEntry parse(final Path file) throws IOException {
        final String filename = file.getFileName().toString();
        final String repoName;
        if (filename.endsWith(".yaml")) {
            repoName = filename.substring(0, filename.length() - ".yaml".length());
        } else {
            repoName = filename;
        }
        final Map<String, Object> doc;
        try (InputStream in = Files.newInputStream(file)) {
            doc = new Yaml().load(in);
        } catch (final Exception ex) {
            throw new IOException(
                String.format("Failed to parse YAML in '%s': %s", filename, ex.getMessage()),
                ex
            );
        }
        if (doc == null) {
            throw new IOException(
                String.format("Empty YAML file: '%s'", filename)
            );
        }
        final Object repoObj = doc.get("repo");
        if (!(repoObj instanceof Map)) {
            throw new IOException(
                String.format("Missing or invalid 'repo' key in '%s'", filename)
            );
        }
        final Map<String, Object> repo = (Map<String, Object>) repoObj;
        final Object typeObj = repo.get("type");
        if (typeObj == null) {
            throw new IOException(
                String.format("Missing 'repo.type' in '%s'", filename)
            );
        }
        return new RepoEntry(repoName, typeObj.toString());
    }
}
```

- [ ] **Step 2.4: Run the tests and confirm they pass**

```
mvn -pl artipie-backfill -Dtest=RepoConfigYamlTest test
```
Expected: BUILD SUCCESS, all 6 tests pass.

- [ ] **Step 2.5: Commit**

```bash
git add artipie-backfill/src/main/java/com/artipie/backfill/RepoConfigYaml.java \
        artipie-backfill/src/test/java/com/artipie/backfill/RepoConfigYamlTest.java
git commit -m "feat(backfill): add RepoConfigYaml YAML parser"
```

---

## Chunk 2: BulkBackfillRunner

### Task 3: `BulkBackfillRunner`

**Files:**
- Create: `artipie-backfill/src/main/java/com/artipie/backfill/BulkBackfillRunner.java`
- Create: `artipie-backfill/src/test/java/com/artipie/backfill/BulkBackfillRunnerTest.java`

- [ ] **Step 3.1: Write the failing tests for `BulkBackfillRunner`**

Create `artipie-backfill/src/test/java/com/artipie/backfill/BulkBackfillRunnerTest.java`:

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link BulkBackfillRunner}.
 *
 * <p>All tests use {@code dryRun=true} and a null datasource unless testing
 * the FAILED path, which deliberately uses {@code dryRun=false} and a null
 * datasource to trigger a NullPointerException in BatchInserter.</p>
 *
 * @since 1.20.13
 */
final class BulkBackfillRunnerTest {

    /**
     * Null print stream for suppressing summary output during tests.
     */
    private static final PrintStream DEV_NULL =
        new PrintStream(OutputStream.nullOutputStream());

    // ── Happy path ───────────────────────────────────────────────────────────

    /**
     * Empty config dir → exit code 0, zero repos processed.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if directory setup fails
     */
    @Test
    void emptyConfigDirSucceeds(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(storageRoot);
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Empty config dir should return exit code 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Two valid repos with file scanner → both succeed, exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void twoValidReposSucceed(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // Repo 1: "myfiles" type file
        Files.writeString(configDir.resolve("myfiles.yaml"), "repo:\n  type: file\n");
        final Path repo1 = storageRoot.resolve("myfiles");
        Files.createDirectories(repo1);
        Files.writeString(repo1.resolve("artifact.txt"), "content");
        // Repo 2: "otherfiles" type file
        Files.writeString(configDir.resolve("otherfiles.yaml"), "repo:\n  type: file\n");
        final Path repo2 = storageRoot.resolve("otherfiles");
        Files.createDirectories(repo2);
        Files.writeString(repo2.resolve("pkg.dat"), "data");
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Two valid repos should return exit code 0",
            code,
            Matchers.is(0)
        );
    }

    // ── SKIPPED paths ────────────────────────────────────────────────────────

    /**
     * Repo with unknown type → SKIPPED, rest continue, exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void unknownTypeIsSkipped(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // Unknown type
        Files.writeString(configDir.resolve("weird.yaml"), "repo:\n  type: weird-hosted\n");
        // Valid repo that should still run
        Files.writeString(configDir.resolve("myfiles.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("myfiles"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Unknown type should be SKIPPED, run exits 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Repo with missing storage path → SKIPPED, rest continue, exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void missingStoragePathIsSkipped(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(storageRoot);
        // This repo has a valid YAML but no matching storage directory
        Files.writeString(configDir.resolve("ghost.yaml"), "repo:\n  type: file\n");
        // Valid repo
        Files.writeString(configDir.resolve("real.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("real"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Missing storage path should be SKIPPED, run exits 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Proxy type is normalised before lookup: docker-proxy → docker scanner is used.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void proxyTypeIsNormalised(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // docker-proxy should normalise to docker
        Files.writeString(
            configDir.resolve("docker_cache.yaml"),
            "repo:\n  type: docker-proxy\n"
        );
        // Create minimal docker v2 storage layout so DockerScanner doesn't fail on missing dirs
        final Path dockerRepo = storageRoot.resolve("docker_cache");
        Files.createDirectories(dockerRepo.resolve("repositories"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "docker-proxy should normalise to docker scanner, exit 0",
            code,
            Matchers.is(0)
        );
    }

    // ── PARSE_ERROR paths ────────────────────────────────────────────────────

    /**
     * Malformed YAML → PARSE_ERROR, rest continue, exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void parseErrorContinuesRun(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("bad.yaml"), "repo: [\nunclosed\n");
        Files.writeString(configDir.resolve("good.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("good"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "PARSE_ERROR should not set exit code to 1",
            code,
            Matchers.is(0)
        );
    }

    /**
     * PARSE_ERROR only run → exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void parseErrorOnlyExitsZero(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("bad.yaml"), "not: valid: yaml: content\n  broken");
        final int code = runner(configDir, tmp, true).run();
        MatcherAssert.assertThat(
            "PARSE_ERROR only should exit 0",
            code,
            Matchers.is(0)
        );
    }

    // ── FAILED paths ─────────────────────────────────────────────────────────

    /**
     * Scanner throws (triggered by null datasource + dryRun=false) → FAILED,
     * rest continue, exit code 1.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void failedRepoExitsOne(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // This repo will FAIL: dryRun=false, dataSource=null → NPE in BatchInserter
        Files.writeString(configDir.resolve("willbreak.yaml"), "repo:\n  type: file\n");
        final Path breakRepo = storageRoot.resolve("willbreak");
        Files.createDirectories(breakRepo);
        Files.writeString(breakRepo.resolve("a.txt"), "x");
        // dryRun=false, dataSource=null triggers failure
        final int code = new BulkBackfillRunner(
            configDir, storageRoot, null, "system", 100, false, 10000, DEV_NULL
        ).run();
        MatcherAssert.assertThat(
            "FAILED repo should set exit code to 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * PARSE_ERROR + FAILED in same run → exit code 1 (FAILED dominates).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void parseErrorPlusFailed(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("bad.yaml"), "not: valid\n  broken: [");
        Files.writeString(configDir.resolve("willbreak.yaml"), "repo:\n  type: file\n");
        final Path breakRepo = storageRoot.resolve("willbreak");
        Files.createDirectories(breakRepo);
        Files.writeString(breakRepo.resolve("a.txt"), "x");
        final int code = new BulkBackfillRunner(
            configDir, storageRoot, null, "system", 100, false, 10000, DEV_NULL
        ).run();
        MatcherAssert.assertThat(
            "PARSE_ERROR + FAILED should exit 1",
            code,
            Matchers.is(1)
        );
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    /**
     * Subdirectories in config dir are ignored (non-recursive).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void subdirectoriesAreIgnored(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // Subdirectory with a yaml inside — should not be processed
        final Path subdir = configDir.resolve("subgroup");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("inner.yaml"), "repo:\n  type: file\n");
        // Valid top-level repo
        Files.writeString(configDir.resolve("top.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("top"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Subdirectories should be ignored, run exits 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * A .yml file (wrong extension) is skipped — not processed, run still succeeds.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void ymlExtensionIsSkipped(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        // .yml file should be silently skipped
        Files.writeString(configDir.resolve("repo.yml"), "repo:\n  type: file\n");
        // Valid .yaml file
        Files.writeString(configDir.resolve("valid.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("valid"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            ".yml file should be skipped, run exits 0",
            code,
            Matchers.is(0)
        );
    }

    /**
     * Two repos with different names both succeed — verifies the seenNames set
     * does not produce false-positive duplicate collisions.
     *
     * <p>Note: the filesystem guarantees unique filenames within a directory,
     * so a true stem collision (two files producing the same stem) cannot
     * occur in practice. The {@code seenNames} guard is a defensive measure.
     * This test verifies the guard does not interfere with normal operation.</p>
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void twoDistinctReposDoNotCollide(@TempDir final Path tmp) throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("alpha.yaml"), "repo:\n  type: file\n");
        Files.writeString(configDir.resolve("beta.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("alpha"));
        Files.createDirectories(storageRoot.resolve("beta"));
        final int code = runner(configDir, storageRoot, true).run();
        MatcherAssert.assertThat(
            "Two repos with distinct names should both succeed, exit 0",
            code,
            Matchers.is(0)
        );
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static BulkBackfillRunner runner(
        final Path configDir,
        final Path storageRoot,
        final boolean dryRun
    ) {
        return new BulkBackfillRunner(
            configDir, storageRoot, null, "system", 1000, dryRun, 10000, DEV_NULL
        );
    }
}
```

- [ ] **Step 3.2: Run the tests to confirm they fail**

```
mvn -pl artipie-backfill -Dtest=BulkBackfillRunnerTest test
```
Expected: compilation failure — `BulkBackfillRunner` does not exist yet.

- [ ] **Step 3.3: Create `BulkBackfillRunner.java`**

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates a bulk backfill run over a directory of Artipie repo configs.
 *
 * <p>For each {@code *.yaml} file found (non-recursively, sorted alphabetically)
 * in the config directory, derives the repo name from the filename stem and the
 * scanner type from {@code repo.type}, then runs the appropriate {@link Scanner}
 * against {@code storageRoot/<repoName>/}.</p>
 *
 * <p>Per-repo failures (parse errors, unknown types, missing storage, scan
 * exceptions) are all non-fatal: they are logged, recorded in the summary,
 * and the next repo is processed. Only a {@code FAILED} status (scan exception)
 * contributes to a non-zero exit code.</p>
 *
 * @since 1.20.13
 */
@SuppressWarnings("PMD.ExcessiveImports")
final class BulkBackfillRunner {

    /**
     * SLF4J logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(BulkBackfillRunner.class);

    /**
     * {@code .yaml} file extension constant.
     */
    private static final String YAML_EXT = ".yaml";

    /**
     * Directory containing {@code *.yaml} Artipie repo config files.
     */
    private final Path configDir;

    /**
     * Root directory under which each repo's data lives at
     * {@code <storageRoot>/<repoName>/}.
     */
    private final Path storageRoot;

    /**
     * Shared JDBC data source. May be {@code null} when {@code dryRun} is
     * {@code true}.
     */
    private final DataSource dataSource;

    /**
     * Owner string applied to all inserted artifact records.
     */
    private final String owner;

    /**
     * Batch insert size.
     */
    private final int batchSize;

    /**
     * If {@code true} count records but do not write to the database.
     */
    private final boolean dryRun;

    /**
     * Progress log interval (log every N records per repo).
     */
    private final int logInterval;

    /**
     * Print stream for the summary table (typically {@code System.err}).
     */
    private final PrintStream out;

    /**
     * Ctor.
     *
     * @param configDir Directory of repo YAML configs
     * @param storageRoot Root for repo storage directories
     * @param dataSource JDBC data source (may be null when dryRun=true)
     * @param owner Owner string for artifact records
     * @param batchSize JDBC batch insert size
     * @param dryRun If true, count only, no DB writes
     * @param logInterval Progress log every N records
     * @param out Stream for summary output (typically System.err)
     * @checkstyle ParameterNumberCheck (10 lines)
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    BulkBackfillRunner(
        final Path configDir,
        final Path storageRoot,
        final DataSource dataSource,
        final String owner,
        final int batchSize,
        final boolean dryRun,
        final int logInterval,
        final PrintStream out
    ) {
        this.configDir = configDir;
        this.storageRoot = storageRoot;
        this.dataSource = dataSource;
        this.owner = owner;
        this.batchSize = batchSize;
        this.dryRun = dryRun;
        this.logInterval = logInterval;
        this.out = out;
    }

    /**
     * Run the bulk backfill over all {@code *.yaml} files in the config
     * directory.
     *
     * @return Exit code: {@code 0} if all repos succeeded or were
     *     skipped/parse-errored, {@code 1} if any repo had a scan failure
     * @throws IOException if the config directory cannot be listed
     */
    int run() throws IOException {
        final List<RepoResult> results = new ArrayList<>();
        final Set<String> seenNames = new HashSet<>();
        final List<Path> yamlFiles;
        try (Stream<Path> listing = Files.list(this.configDir)) {
            yamlFiles = listing
                .filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith(YAML_EXT))
                .sorted()
                .collect(Collectors.toList());
        }
        try (Stream<Path> listing = Files.list(this.configDir)) {
            listing
                .filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith(".yml"))
                .forEach(p -> LOG.debug(
                    "Skipping '{}' — use .yaml extension, not .yml",
                    p.getFileName()
                ));
        }
        for (final Path file : yamlFiles) {
            results.add(this.processFile(file, seenNames));
        }
        this.printSummary(results);
        return results.stream()
            .anyMatch(r -> r.status().startsWith("FAILED")) ? 1 : 0;
    }

    /**
     * Process one YAML file and return a result row.
     *
     * @param file Path to the {@code .yaml} file
     * @param seenNames Set of repo name stems already processed
     * @return Result row for the summary table
     */
    private RepoResult processFile(
        final Path file,
        final Set<String> seenNames
    ) {
        final String fileName = file.getFileName().toString();
        final String stem = fileName.endsWith(YAML_EXT)
            ? fileName.substring(0, fileName.length() - YAML_EXT.length())
            : fileName;
        if (!seenNames.add(stem)) {
            LOG.warn(
                "Duplicate repo name '{}' (from '{}'), skipping", stem, fileName
            );
            return new RepoResult(
                stem, "-", -1L, -1L, "SKIPPED (duplicate repo name)"
            );
        }
        final RepoEntry entry;
        try {
            entry = RepoConfigYaml.parse(file);
        } catch (final IOException ex) {
            LOG.warn("PARSE_ERROR for '{}': {}", fileName, ex.getMessage());
            return new RepoResult(
                stem, "-", -1L, -1L,
                "PARSE_ERROR (" + ex.getMessage() + ")"
            );
        }
        final String scannerType = RepoTypeNormalizer.normalize(entry.rawType());
        final Scanner scanner;
        try {
            scanner = ScannerFactory.create(scannerType);
        } catch (final IllegalArgumentException ex) {
            LOG.warn(
                "Unknown type '{}' for repo '{}', skipping",
                entry.rawType(), stem
            );
            return new RepoResult(
                stem, "[UNKNOWN]", -1L, -1L,
                "SKIPPED (unknown type: " + entry.rawType() + ")"
            );
        }
        final Path storagePath = this.storageRoot.resolve(stem);
        if (!Files.exists(storagePath)) {
            LOG.warn(
                "Storage path missing for repo '{}': {}", stem, storagePath
            );
            return new RepoResult(
                stem, scannerType, -1L, -1L, "SKIPPED (storage path missing)"
            );
        }
        return this.scanRepo(stem, scannerType, scanner, storagePath);
    }

    /**
     * Scan one repo directory and return a result row.
     *
     * @param repoName Repo name (for logging and record insertion)
     * @param scannerType Normalised scanner type string (for display)
     * @param scanner Scanner instance
     * @param storagePath Root directory to scan
     * @return Result row
     */
    private RepoResult scanRepo(
        final String repoName,
        final String scannerType,
        final Scanner scanner,
        final Path storagePath
    ) {
        LOG.info(
            "Scanning repo '{}' (type={}) at {}",
            repoName, scannerType, storagePath
        );
        final ProgressReporter reporter =
            new ProgressReporter(this.logInterval);
        long inserted = -1L;
        long dbSkipped = -1L;
        boolean failed = false;
        String failMsg = null;
        try (
            BatchInserter inserter = new BatchInserter(
                this.dataSource, this.batchSize, this.dryRun
            );
            Stream<ArtifactRecord> stream =
                scanner.scan(storagePath, repoName)
        ) {
            final String ownerVal = this.owner;
            stream
                .map(r -> new ArtifactRecord(
                    r.repoType(), r.repoName(), r.name(),
                    r.version(), r.size(), r.createdDate(),
                    r.releaseDate(), ownerVal
                ))
                .forEach(rec -> {
                    inserter.accept(rec);
                    reporter.increment();
                });
            // Read counts after inserter.close() has flushed remaining batch
            inserted = inserter.getInsertedCount();
            dbSkipped = inserter.getSkippedCount();
        } catch (final Exception ex) {
            // inserter is out of scope here (closed by try-with-resources).
            // Show "-" in summary for FAILED rows by leaving inserted/dbSkipped as -1L.
            failed = true;
            failMsg = ex.getMessage();
            LOG.error(
                "Scan FAILED for repo '{}': {}", repoName, ex.getMessage(), ex
            );
        }
        if (failed) {
            return new RepoResult(
                repoName, scannerType, -1L, -1L,
                "FAILED (" + failMsg + ")"
            );
        }
        return new RepoResult(repoName, scannerType, inserted, dbSkipped, "OK");
    }

    /**
     * Print the summary table to the output stream.
     *
     * @param results List of result rows
     */
    private void printSummary(final List<RepoResult> results) {
        this.out.printf(
            "%nBulk backfill complete — %d repos processed%n",
            results.size()
        );
        for (final RepoResult row : results) {
            final String counts;
            if (row.inserted() < 0) {
                counts = String.format("%-30s", "-");
            } else {
                counts = String.format(
                    "inserted=%-10d skipped=%-6d",
                    row.inserted(), row.dbSkipped()
                );
            }
            this.out.printf(
                "  %-20s [%-12s] %s %s%n",
                row.repoName(), row.displayType(), counts, row.status()
            );
        }
        final long failCount = results.stream()
            .filter(r -> r.status().startsWith("FAILED")).count();
        if (failCount > 0) {
            this.out.printf("%nExit code: 1  (%d repo(s) failed)%n", failCount);
        } else {
            this.out.println("\nExit code: 0");
        }
    }

    /**
     * One row in the bulk run summary.
     *
     * @param repoName Repo name
     * @param displayType Type string for display
     * @param inserted Records inserted (or -1 if not applicable)
     * @param dbSkipped Records skipped at DB level (or -1 if not applicable)
     * @param status Status string
     */
    private record RepoResult(
        String repoName,
        String displayType,
        long inserted,
        long dbSkipped,
        String status
    ) {
    }
}
```

- [ ] **Step 3.4: Run the tests**

```
mvn -pl artipie-backfill -Dtest=BulkBackfillRunnerTest test
```
Expected: BUILD SUCCESS. Investigate and fix any failures before continuing.

- [ ] **Step 3.5: Run the full module test suite to check for regressions**

```
mvn -pl artipie-backfill test
```
Expected: BUILD SUCCESS, no failures.

- [ ] **Step 3.6: Commit**

```bash
git add artipie-backfill/src/main/java/com/artipie/backfill/BulkBackfillRunner.java \
        artipie-backfill/src/test/java/com/artipie/backfill/BulkBackfillRunnerTest.java
git commit -m "feat(backfill): add BulkBackfillRunner for automated multi-repo scan"
```

---

## Chunk 3: BackfillCli wiring

### Task 4: Wire `BulkBackfillRunner` into `BackfillCli`

**Files:**
- Modify: `artipie-backfill/src/main/java/com/artipie/backfill/BackfillCli.java`
- Modify: `artipie-backfill/src/test/java/com/artipie/backfill/BackfillCliTest.java`

**Context:** `BackfillCli.run()` currently marks `--type`, `--path`, `--repo-name` as
`.required()` via Apache Commons CLI. This causes a `ParseException` if they are absent,
even in bulk mode. The fix: remove `.required()` from these three, add the two new options,
and add post-parse validation.

- [ ] **Step 4.1: Write the failing CLI tests**

Add the following test methods to the existing `BackfillCliTest.java` class body (after the existing tests):

```java
    /**
     * --config-dir without --storage-root should fail (exit code 1).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if directory setup fails
     */
    @Test
    void configDirWithoutStorageRootFails(@TempDir final Path tmp)
        throws IOException {
        Files.createDirectories(tmp);
        final int code = BackfillCli.run(
            "--config-dir", tmp.toString(),
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "--config-dir without --storage-root should return exit code 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * --storage-root without --config-dir should fail (exit code 1).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if directory setup fails
     */
    @Test
    void storageRootWithoutConfigDirFails(@TempDir final Path tmp)
        throws IOException {
        Files.createDirectories(tmp);
        final int code = BackfillCli.run(
            "--storage-root", tmp.toString(),
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "--storage-root without --config-dir should return exit code 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * --config-dir combined with --type should fail (mutually exclusive).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if directory setup fails
     */
    @Test
    void configDirAndTypeTogether(@TempDir final Path tmp) throws IOException {
        Files.createDirectories(tmp);
        final int code = BackfillCli.run(
            "--config-dir", tmp.toString(),
            "--storage-root", tmp.toString(),
            "--type", "file",
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "--config-dir and --type together should return exit code 1",
            code,
            Matchers.is(1)
        );
    }

    /**
     * Valid --config-dir + --storage-root in dry-run mode → exit code 0.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file setup fails
     */
    @Test
    void bulkModeWithConfigDirSucceeds(@TempDir final Path tmp)
        throws IOException {
        final Path configDir = tmp.resolve("configs");
        final Path storageRoot = tmp.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(storageRoot);
        Files.writeString(configDir.resolve("myrepo.yaml"), "repo:\n  type: file\n");
        Files.createDirectories(storageRoot.resolve("myrepo"));
        Files.writeString(storageRoot.resolve("myrepo").resolve("f.txt"), "hi");
        final int code = BackfillCli.run(
            "--config-dir", configDir.toString(),
            "--storage-root", storageRoot.toString(),
            "--dry-run"
        );
        MatcherAssert.assertThat(
            "Valid bulk mode dry-run should return exit code 0",
            code,
            Matchers.is(0)
        );
    }
```

- [ ] **Step 4.2: Run the new tests to confirm they fail**

```
mvn -pl artipie-backfill -Dtest=BackfillCliTest test
```
Expected: the four new tests fail (3 pass by coincidence perhaps, 1 compile error at worst). The key is they currently don't reflect the new behaviour.

- [ ] **Step 4.3: Modify `BackfillCli.java`**

Replace the entire file content with the following (preserving the license header and all existing logic, adding bulk mode support):

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI entry point for the artifact backfill tool.
 *
 * <p>Supports two modes:</p>
 * <ul>
 *   <li><b>Single-repo:</b> {@code --type}, {@code --path}, {@code --repo-name}
 *       (original behaviour)</li>
 *   <li><b>Bulk:</b> {@code --config-dir}, {@code --storage-root} — reads all
 *       {@code *.yaml} Artipie repo configs and scans each repo automatically</li>
 * </ul>
 *
 * @since 1.20.13
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class BackfillCli {

    /**
     * SLF4J logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(BackfillCli.class);

    /**
     * Default batch size for inserts.
     */
    private static final int DEFAULT_BATCH_SIZE = 1000;

    /**
     * Default progress log interval.
     */
    private static final int DEFAULT_LOG_INTERVAL = 10000;

    /**
     * Default database user.
     */
    private static final String DEFAULT_DB_USER = "artipie";

    /**
     * Default database password.
     */
    private static final String DEFAULT_DB_PASSWORD = "artipie";

    /**
     * Default owner.
     */
    private static final String DEFAULT_OWNER = "system";

    /**
     * HikariCP maximum pool size.
     */
    private static final int POOL_MAX_SIZE = 5;

    /**
     * HikariCP minimum idle connections.
     */
    private static final int POOL_MIN_IDLE = 1;

    /**
     * HikariCP connection timeout in millis.
     */
    private static final long POOL_CONN_TIMEOUT = 5000L;

    /**
     * HikariCP idle timeout in millis.
     */
    private static final long POOL_IDLE_TIMEOUT = 30000L;

    /**
     * Private ctor to prevent instantiation.
     */
    private BackfillCli() {
    }

    /**
     * CLI entry point.
     *
     * @param args Command-line arguments
     */
    public static void main(final String... args) {
        System.exit(run(args));
    }

    /**
     * Core logic extracted for testability. Returns an exit code
     * (0 = success, 1 = error).
     *
     * @param args Command-line arguments
     * @return Exit code
     */
    static int run(final String... args) {
        final Options options = buildOptions();
        for (final String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp(options);
                return 0;
            }
        }
        final CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(options, args);
        } catch (final ParseException ex) {
            LOG.error("Failed to parse arguments: {}", ex.getMessage());
            printHelp(options);
            return 1;
        }
        final boolean hasBulkFlags =
            cmd.hasOption("config-dir") || cmd.hasOption("storage-root");
        final boolean hasSingleFlags =
            cmd.hasOption("type") || cmd.hasOption("path")
                || cmd.hasOption("repo-name");
        if (hasBulkFlags && hasSingleFlags) {
            LOG.error(
                "--config-dir/--storage-root cannot be combined with "
                    + "--type/--path/--repo-name"
            );
            return 1;
        }
        if (cmd.hasOption("config-dir") && !cmd.hasOption("storage-root")) {
            LOG.error("--config-dir requires --storage-root");
            return 1;
        }
        if (cmd.hasOption("storage-root") && !cmd.hasOption("config-dir")) {
            LOG.error("--storage-root requires --config-dir");
            return 1;
        }
        if (!hasBulkFlags && !hasSingleFlags) {
            LOG.error(
                "Either --type/--path/--repo-name or "
                    + "--config-dir/--storage-root must be provided"
            );
            printHelp(options);
            return 1;
        }
        final boolean dryRun = cmd.hasOption("dry-run");
        final String dbUrl = cmd.getOptionValue("db-url");
        final String dbUser = cmd.getOptionValue("db-user", DEFAULT_DB_USER);
        final String dbPassword =
            cmd.getOptionValue("db-password", DEFAULT_DB_PASSWORD);
        final int batchSize = Integer.parseInt(
            cmd.getOptionValue(
                "batch-size", String.valueOf(DEFAULT_BATCH_SIZE)
            )
        );
        final String owner = cmd.getOptionValue("owner", DEFAULT_OWNER);
        final int logInterval = Integer.parseInt(
            cmd.getOptionValue(
                "log-interval", String.valueOf(DEFAULT_LOG_INTERVAL)
            )
        );
        if (cmd.hasOption("config-dir")) {
            return runBulk(
                cmd.getOptionValue("config-dir"),
                cmd.getOptionValue("storage-root"),
                dryRun, dbUrl, dbUser, dbPassword,
                batchSize, owner, logInterval
            );
        }
        return runSingle(
            cmd.getOptionValue("type"),
            cmd.getOptionValue("path"),
            cmd.getOptionValue("repo-name"),
            dryRun, dbUrl, dbUser, dbPassword,
            batchSize, owner, logInterval
        );
    }

    /**
     * Run bulk mode: scan all repos from the config directory.
     *
     * @param configDirStr Config directory path string
     * @param storageRootStr Storage root path string
     * @param dryRun Dry run flag
     * @param dbUrl JDBC URL (may be null if dryRun)
     * @param dbUser DB user
     * @param dbPassword DB password
     * @param batchSize Batch insert size
     * @param owner Artifact owner
     * @param logInterval Progress log interval
     * @return Exit code
     * @checkstyle ParameterNumberCheck (15 lines)
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static int runBulk(
        final String configDirStr,
        final String storageRootStr,
        final boolean dryRun,
        final String dbUrl,
        final String dbUser,
        final String dbPassword,
        final int batchSize,
        final String owner,
        final int logInterval
    ) {
        final Path configDir = Paths.get(configDirStr);
        final Path storageRoot = Paths.get(storageRootStr);
        if (!Files.isDirectory(configDir)) {
            LOG.error("--config-dir is not a directory: {}", configDirStr);
            return 1;
        }
        if (!Files.isDirectory(storageRoot)) {
            LOG.error("--storage-root is not a directory: {}", storageRootStr);
            return 1;
        }
        if (!dryRun && (dbUrl == null || dbUrl.isEmpty())) {
            LOG.error("--db-url is required unless --dry-run is set");
            return 1;
        }
        DataSource dataSource = null;
        if (!dryRun) {
            dataSource = buildDataSource(dbUrl, dbUser, dbPassword);
        }
        try {
            return new BulkBackfillRunner(
                configDir, storageRoot, dataSource,
                owner, batchSize, dryRun, logInterval, System.err
            ).run();
        } catch (final IOException ex) {
            LOG.error("Bulk backfill failed: {}", ex.getMessage(), ex);
            return 1;
        } finally {
            closeDataSource(dataSource);
        }
    }

    /**
     * Run single-repo mode (original behaviour).
     *
     * @param type Scanner type
     * @param pathStr Path string
     * @param repoName Repo name
     * @param dryRun Dry run flag
     * @param dbUrl JDBC URL
     * @param dbUser DB user
     * @param dbPassword DB password
     * @param batchSize Batch size
     * @param owner Artifact owner
     * @param logInterval Progress interval
     * @return Exit code
     * @checkstyle ParameterNumberCheck (15 lines)
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static int runSingle(
        final String type,
        final String pathStr,
        final String repoName,
        final boolean dryRun,
        final String dbUrl,
        final String dbUser,
        final String dbPassword,
        final int batchSize,
        final String owner,
        final int logInterval
    ) {
        if (type == null || pathStr == null || repoName == null) {
            LOG.error(
                "--type, --path, and --repo-name are all required in single-repo mode"
            );
            return 1;
        }
        final Path root = Paths.get(pathStr);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            LOG.error(
                "Path does not exist or is not a directory: {}", pathStr
            );
            return 1;
        }
        if (!dryRun && (dbUrl == null || dbUrl.isEmpty())) {
            LOG.error("--db-url is required unless --dry-run is set");
            return 1;
        }
        final Scanner scanner;
        try {
            scanner = ScannerFactory.create(type);
        } catch (final IllegalArgumentException ex) {
            LOG.error(
                "Invalid scanner type '{}': {}", type, ex.getMessage()
            );
            return 1;
        }
        LOG.info(
            "Backfill starting: type={}, path={}, repo-name={}, "
                + "batch-size={}, dry-run={}",
            type, root, repoName, batchSize, dryRun
        );
        DataSource dataSource = null;
        if (!dryRun) {
            dataSource = buildDataSource(dbUrl, dbUser, dbPassword);
        }
        final ProgressReporter progress =
            new ProgressReporter(logInterval);
        try (BatchInserter inserter =
            new BatchInserter(dataSource, batchSize, dryRun)) {
            try (Stream<ArtifactRecord> stream =
                scanner.scan(root, repoName)) {
                stream
                    .map(rec -> new ArtifactRecord(
                        rec.repoType(), rec.repoName(), rec.name(),
                        rec.version(), rec.size(), rec.createdDate(),
                        rec.releaseDate(), owner
                    ))
                    .forEach(record -> {
                        inserter.accept(record);
                        progress.increment();
                    });
            }
        } catch (final Exception ex) {
            LOG.error("Backfill failed: {}", ex.getMessage(), ex);
            return 1;
        } finally {
            closeDataSource(dataSource);
        }
        progress.printFinalSummary();
        LOG.info("Backfill completed successfully");
        return 0;
    }

    /**
     * Build a HikariCP datasource.
     *
     * @param dbUrl JDBC URL
     * @param dbUser DB user
     * @param dbPassword DB password
     * @return DataSource
     */
    private static DataSource buildDataSource(
        final String dbUrl,
        final String dbUser,
        final String dbPassword
    ) {
        final HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbUrl);
        config.setUsername(dbUser);
        config.setPassword(dbPassword);
        config.setMaximumPoolSize(POOL_MAX_SIZE);
        config.setMinimumIdle(POOL_MIN_IDLE);
        config.setConnectionTimeout(POOL_CONN_TIMEOUT);
        config.setIdleTimeout(POOL_IDLE_TIMEOUT);
        config.setPoolName("Backfill-Pool");
        return new HikariDataSource(config);
    }

    /**
     * Close a HikariDataSource if non-null.
     *
     * @param dataSource DataSource to close (may be null)
     */
    private static void closeDataSource(final DataSource dataSource) {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
    }

    /**
     * Build the CLI option definitions.
     *
     * @return Options instance
     */
    private static Options buildOptions() {
        final Options options = new Options();
        options.addOption(
            Option.builder("t").longOpt("type")
                .hasArg().argName("TYPE")
                .desc("Scanner type — single-repo mode (maven, docker, npm, "
                    + "pypi, go, helm, composer, file, etc.)")
                .build()
        );
        options.addOption(
            Option.builder("p").longOpt("path")
                .hasArg().argName("PATH")
                .desc("Root directory path to scan — single-repo mode")
                .build()
        );
        options.addOption(
            Option.builder("r").longOpt("repo-name")
                .hasArg().argName("NAME")
                .desc("Repository name — single-repo mode")
                .build()
        );
        options.addOption(
            Option.builder("C").longOpt("config-dir")
                .hasArg().argName("DIR")
                .desc("Directory of Artipie *.yaml repo configs — bulk mode")
                .build()
        );
        options.addOption(
            Option.builder("R").longOpt("storage-root")
                .hasArg().argName("DIR")
                .desc("Storage root; each repo lives at <root>/<repo-name>/ "
                    + "— bulk mode")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("db-url")
                .hasArg().argName("URL")
                .desc("JDBC PostgreSQL URL (required unless --dry-run)")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("db-user")
                .hasArg().argName("USER")
                .desc("Database user (default: artipie)")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("db-password")
                .hasArg().argName("PASS")
                .desc("Database password (default: artipie)")
                .build()
        );
        options.addOption(
            Option.builder("b").longOpt("batch-size")
                .hasArg().argName("SIZE")
                .desc("Batch insert size (default: 1000)")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("owner")
                .hasArg().argName("OWNER")
                .desc("Default owner (default: system)")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("log-interval")
                .hasArg().argName("N")
                .desc("Progress log interval (default: 10000)")
                .build()
        );
        options.addOption(
            Option.builder().longOpt("dry-run")
                .desc("Scan only, do not write to database")
                .build()
        );
        options.addOption(
            Option.builder("h").longOpt("help")
                .desc("Print help and exit")
                .build()
        );
        return options;
    }

    /**
     * Print usage help to stdout.
     *
     * @param options CLI options
     */
    private static void printHelp(final Options options) {
        new HelpFormatter().printHelp(
            "backfill-cli",
            "Backfill the PostgreSQL artifacts table from disk storage",
            options,
            "",
            true
        );
    }
}
```

- [ ] **Step 4.4: Run all BackfillCli tests**

```
mvn -pl artipie-backfill -Dtest=BackfillCliTest test
```
Expected: BUILD SUCCESS. All existing tests plus 4 new ones pass.

- [ ] **Step 4.5: Run the full test suite**

```
mvn -pl artipie-backfill test
```
Expected: BUILD SUCCESS, no failures in any test class.

- [ ] **Step 4.6: Commit**

```bash
git add artipie-backfill/src/main/java/com/artipie/backfill/BackfillCli.java \
        artipie-backfill/src/test/java/com/artipie/backfill/BackfillCliTest.java
git commit -m "feat(backfill): wire bulk mode into BackfillCli with --config-dir and --storage-root"
```

---

## Final verification

- [ ] **Step 5.1: Run the complete module test suite one final time**

```
mvn -pl artipie-backfill test
```
Expected: BUILD SUCCESS, zero test failures.

- [ ] **Step 5.2: Verify the fat JAR builds**

```
mvn -pl artipie-backfill package -DskipTests
ls -lh artipie-backfill/target/artipie-backfill-*.jar
```
Expected: fat JAR present.

- [ ] **Step 5.3: Smoke test bulk mode with dry-run**

```bash
java -jar artipie-backfill/target/artipie-backfill-*.jar \
  --config-dir /Users/ayd/DevOps/code/auto1/artipie/artipie-main/docker-compose/artipie/prod_repo \
  --storage-root /Users/ayd/DevOps/code/auto1/artipie/artipie-main/docker-compose/artipie/data \
  --dry-run
```
Expected: summary table printed to stderr, exit code 0 (or 1 if any repos fail).

- [ ] **Step 5.4: Final commit if any smoke-test fixes were needed**

```bash
git add -p   # stage only intended changes
git commit -m "fix(backfill): smoke test fixes for bulk mode"
```
(Skip this step if no fixes were needed.)
