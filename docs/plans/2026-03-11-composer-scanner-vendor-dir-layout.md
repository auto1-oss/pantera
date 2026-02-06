# ComposerScanner Vendor-Dir Layout Support

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix `ComposerScanner` to support the Artipie Composer proxy's native storage layout (`{vendor}/{package}.json` at root) and to skip 0-byte files gracefully instead of emitting a misleading WARN.

**Architecture:** Two surgical changes to `ComposerScanner.java`. (1) Add a size-guard in `scan()` so 0-byte `packages.json` is skipped (logged at DEBUG) and execution falls through to a new `scanVendorDirs()` method. (2) `scanVendorDirs()` walks two levels deep (`{root}/{vendor}/{package}.json`), skips `~dev.json` and empty files, and parses each file using the existing `parseJsonFile()` helper which already handles the same JSON format. No other files change.

**Tech Stack:** Java 11+, `javax.json` (Jakarta EE JSON-P), JUnit 5, `@TempDir`

---

## Background (must read before touching code)

### The three Composer storage layouts Artipie supports

| Layout | Detection | Example path |
|--------|-----------|-------------|
| **p2** (Satis-style) | `p2/` directory exists at root | `p2/psr/log.json` |
| **packages.json** | `packages.json` non-empty at root | `packages.json` |
| **vendor-dir** ← NEW | per-package `.json` files directly at root, two levels deep | `psr/log.json`, `nyholm/psr7.json` |

### Why the vendor-dir layout exists

`ComposerStorageCache.java` (in `composer-adapter`) stores each proxied package as:

```java
final Key cached = new Key.From(String.format("%s.json", name.string()));
// for "psr/log" → stores file at key "psr/log.json"
// which the filesystem backend writes as root/psr/log.json
```

So a proxy repo that has served `psr/log`, `nyholm/psr7`, `symfony/http-client` looks like:

```
psr/log.json
nyholm/psr7.json
symfony/http-client.json
packages.json          ← often 0 bytes (a stale manifest placeholder)
```

### JSON format (same as p2 and packages.json)

Every file — regardless of layout — uses the same format:

```json
{
  "packages": {
    "psr/log": {
      "1.0.0": { "name": "psr/log", "version": "1.0.0", "dist": { "url": "...", "type": "zip" } },
      "2.0.0": { "name": "psr/log", "version": "2.0.0", "dist": { "url": "...", "type": "zip" } }
    }
  }
}
```

The existing `parseJsonFile()` already handles this format — we just need to call it on the right files.

### Priority order (unchanged for existing layouts)

```
1. p2/ directory exists  →  scan p2 only (Satis, highest priority)
2. packages.json non-empty  →  parse packages.json only
3. otherwise  →  scan vendor-dir layout
```

---

## Files Modified

- **Main:** `artipie-backfill/src/main/java/com/artipie/backfill/ComposerScanner.java`
- **Test:** `artipie-backfill/src/test/java/com/artipie/backfill/ComposerScannerTest.java`

---

## Task 1: Fix empty-file handling + add vendor-dir scan

### Step 1: Write the four failing tests

Add these four tests to `ComposerScannerTest.java` after the existing tests (before the closing `}`).

**Test A: empty `packages.json` is skipped, vendor dirs are scanned**

```java
@Test
void skipsEmptyPackagesJsonAndScansVendorDirs(@TempDir final Path temp)
    throws IOException {
    // packages.json exists but is 0 bytes (common in Artipie proxy repos)
    Files.createFile(temp.resolve("packages.json"));
    // vendor-dir layout files exist with real content
    final Path vendorDir = temp.resolve("psr");
    Files.createDirectories(vendorDir);
    Files.writeString(
        vendorDir.resolve("log.json"),
        String.join(
            "\n",
            "{",
            "  \"packages\": {",
            "    \"psr/log\": {",
            "      \"1.0.0\": {",
            "        \"name\": \"psr/log\",",
            "        \"version\": \"1.0.0\"",
            "      }",
            "    }",
            "  }",
            "}"
        ),
        StandardCharsets.UTF_8
    );
    final ComposerScanner scanner = new ComposerScanner();
    final List<ArtifactRecord> records = scanner.scan(temp, "php-proxy")
        .collect(Collectors.toList());
    MatcherAssert.assertThat(
        "Should find 1 record from vendor-dir layout despite empty packages.json",
        records,
        Matchers.hasSize(1)
    );
    MatcherAssert.assertThat(
        "Record name should be psr/log",
        records.get(0).name(),
        Matchers.is("psr/log")
    );
    MatcherAssert.assertThat(
        "Record version should be 1.0.0",
        records.get(0).version(),
        Matchers.is("1.0.0")
    );
}
```

**Test B: vendor-dir layout scans multiple vendors and packages**

```java
@Test
void scansVendorDirLayout(@TempDir final Path temp) throws IOException {
    // Two vendor directories, multiple packages
    final Path psr = temp.resolve("psr");
    Files.createDirectories(psr);
    Files.writeString(
        psr.resolve("log.json"),
        String.join(
            "\n",
            "{",
            "  \"packages\": {",
            "    \"psr/log\": {",
            "      \"1.0.0\": { \"name\": \"psr/log\", \"version\": \"1.0.0\" },",
            "      \"2.0.0\": { \"name\": \"psr/log\", \"version\": \"2.0.0\" }",
            "    }",
            "  }",
            "}"
        ),
        StandardCharsets.UTF_8
    );
    Files.writeString(
        psr.resolve("http-message.json"),
        String.join(
            "\n",
            "{",
            "  \"packages\": {",
            "    \"psr/http-message\": {",
            "      \"1.1.0\": { \"name\": \"psr/http-message\", \"version\": \"1.1.0\" }",
            "    }",
            "  }",
            "}"
        ),
        StandardCharsets.UTF_8
    );
    final Path symfony = temp.resolve("symfony");
    Files.createDirectories(symfony);
    Files.writeString(
        symfony.resolve("http-client.json"),
        String.join(
            "\n",
            "{",
            "  \"packages\": {",
            "    \"symfony/http-client\": {",
            "      \"6.4.0\": { \"name\": \"symfony/http-client\", \"version\": \"6.4.0\" }",
            "    }",
            "  }",
            "}"
        ),
        StandardCharsets.UTF_8
    );
    final ComposerScanner scanner = new ComposerScanner();
    final List<ArtifactRecord> records = scanner.scan(temp, "php-proxy")
        .collect(Collectors.toList());
    MatcherAssert.assertThat(
        "Should produce 4 records total (2 psr/log + 1 psr/http-message + 1 symfony/http-client)",
        records,
        Matchers.hasSize(4)
    );
    MatcherAssert.assertThat(
        "Should contain psr/log",
        records.stream().anyMatch(r -> "psr/log".equals(r.name())),
        Matchers.is(true)
    );
    MatcherAssert.assertThat(
        "Should contain psr/http-message",
        records.stream().anyMatch(r -> "psr/http-message".equals(r.name())),
        Matchers.is(true)
    );
    MatcherAssert.assertThat(
        "Should contain symfony/http-client",
        records.stream().anyMatch(r -> "symfony/http-client".equals(r.name())),
        Matchers.is(true)
    );
    MatcherAssert.assertThat(
        "All records should have composer repo type",
        records.stream().allMatch(r -> "composer".equals(r.repoType())),
        Matchers.is(true)
    );
}
```

**Test C: empty vendor/package.json files are silently skipped**

```java
@Test
void skipsEmptyFilesInVendorDirLayout(@TempDir final Path temp)
    throws IOException {
    final Path psr = temp.resolve("psr");
    Files.createDirectories(psr);
    // One empty file (0 bytes) — should be skipped silently
    Files.createFile(psr.resolve("log.json"));
    // One non-empty file — should be scanned
    Files.writeString(
        psr.resolve("container.json"),
        String.join(
            "\n",
            "{",
            "  \"packages\": {",
            "    \"psr/container\": {",
            "      \"2.0.0\": { \"name\": \"psr/container\", \"version\": \"2.0.0\" }",
            "    }",
            "  }",
            "}"
        ),
        StandardCharsets.UTF_8
    );
    final ComposerScanner scanner = new ComposerScanner();
    final List<ArtifactRecord> records = scanner.scan(temp, "php-proxy")
        .collect(Collectors.toList());
    MatcherAssert.assertThat(
        "Should produce 1 record — empty file skipped, non-empty file scanned",
        records,
        Matchers.hasSize(1)
    );
    MatcherAssert.assertThat(
        "Record should be from psr/container (non-empty file)",
        records.get(0).name(),
        Matchers.is("psr/container")
    );
}
```

**Test D: `~dev.json` files are skipped in vendor-dir layout**

```java
@Test
void skipsDevJsonFilesInVendorDirLayout(@TempDir final Path temp)
    throws IOException {
    final Path openTelemetry = temp.resolve("open-telemetry");
    Files.createDirectories(openTelemetry);
    // dev file — should be skipped
    Files.writeString(
        openTelemetry.resolve("sem-conv~dev.json"),
        String.join(
            "\n",
            "{",
            "  \"packages\": {",
            "    \"open-telemetry/sem-conv\": {",
            "      \"dev-main\": { \"name\": \"open-telemetry/sem-conv\", \"version\": \"dev-main\" }",
            "    }",
            "  }",
            "}"
        ),
        StandardCharsets.UTF_8
    );
    // stable file — should be scanned
    Files.writeString(
        openTelemetry.resolve("sem-conv.json"),
        String.join(
            "\n",
            "{",
            "  \"packages\": {",
            "    \"open-telemetry/sem-conv\": {",
            "      \"1.0.0\": { \"name\": \"open-telemetry/sem-conv\", \"version\": \"1.0.0\" }",
            "    }",
            "  }",
            "}"
        ),
        StandardCharsets.UTF_8
    );
    final ComposerScanner scanner = new ComposerScanner();
    final List<ArtifactRecord> records = scanner.scan(temp, "php-proxy")
        .collect(Collectors.toList());
    MatcherAssert.assertThat(
        "Should produce 1 record — ~dev.json file skipped",
        records,
        Matchers.hasSize(1)
    );
    MatcherAssert.assertThat(
        "Record version should be 1.0.0 (from stable file, not dev)",
        records.get(0).version(),
        Matchers.is("1.0.0")
    );
}
```

### Step 2: Run the tests to confirm they ALL fail

```bash
cd /Users/ayd/DevOps/code/auto1/artipie/artipie-backfill && \
  mvn test -Dtest=ComposerScannerTest#skipsEmptyPackagesJsonAndScansVendorDirs+scansVendorDirLayout+skipsEmptyFilesInVendorDirLayout+skipsDevJsonFilesInVendorDirLayout -q 2>&1 | tail -15
```

Expected: all 4 FAIL. If any unexpectedly pass, stop and report back.

### Step 3: Apply the fix to `ComposerScanner.java`

**3a. Replace the `scan()` method** (lines 50–62) with:

```java
@Override
public Stream<ArtifactRecord> scan(final Path root, final String repoName)
    throws IOException {
    final Path p2dir = root.resolve("p2");
    if (Files.isDirectory(p2dir)) {
        return this.scanP2(root, repoName, p2dir);
    }
    final Path packagesJson = root.resolve("packages.json");
    if (Files.isRegularFile(packagesJson)) {
        if (Files.size(packagesJson) > 0) {
            return this.parseJsonFile(root, repoName, packagesJson);
        }
        LOG.debug("Skipping empty packages.json, trying vendor-dir layout");
    }
    return this.scanVendorDirs(root, repoName);
}
```

**3b. Add the new `scanVendorDirs()` method** — insert it immediately after the closing `}` of `scanP2()` (around line 81):

```java
/**
 * Scan the Artipie Composer proxy layout.
 *
 * <p>The Artipie Composer proxy caches per-package metadata as
 * {@code {vendor}/{package}.json} files directly under the repository
 * root (no {@code p2/} prefix). Each file uses the standard Composer
 * {@code {"packages":{...}}} JSON format.</p>
 *
 * <p>Files ending with {@code ~dev.json} and 0-byte files are skipped.</p>
 *
 * @param root Repository root directory
 * @param repoName Logical repository name
 * @return Stream of artifact records
 * @throws IOException If an I/O error occurs
 */
private Stream<ArtifactRecord> scanVendorDirs(final Path root,
    final String repoName) throws IOException {
    return Files.list(root)
        .filter(Files::isDirectory)
        .flatMap(
            vendorDir -> {
                try {
                    return Files.list(vendorDir)
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".json"))
                        .filter(
                            path -> !path.getFileName().toString().endsWith("~dev.json")
                        )
                        .filter(
                            path -> {
                                try {
                                    return Files.size(path) > 0L;
                                } catch (final IOException ex) {
                                    return false;
                                }
                            }
                        )
                        .flatMap(path -> this.parseJsonFile(root, repoName, path));
                } catch (final IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        );
}
```

No other changes to `ComposerScanner.java`.

### Step 4: Run the four new tests — all must pass

```bash
cd /Users/ayd/DevOps/code/auto1/artipie/artipie-backfill && \
  mvn test -Dtest=ComposerScannerTest#skipsEmptyPackagesJsonAndScansVendorDirs+scansVendorDirLayout+skipsEmptyFilesInVendorDirLayout+skipsDevJsonFilesInVendorDirLayout -q 2>&1 | tail -10
```

Expected: 4 PASS.

### Step 5: Run the full `ComposerScannerTest` suite to confirm no regressions

```bash
cd /Users/ayd/DevOps/code/auto1/artipie/artipie-backfill && \
  mvn test -Dtest=ComposerScannerTest -q 2>&1 | tail -10
```

Expected: all 9 tests pass (5 existing + 4 new).

### Step 6: Run the full backfill module test suite

```bash
cd /Users/ayd/DevOps/code/auto1/artipie/artipie-backfill && \
  mvn test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 0 failures.

### Step 7: Commit

```bash
cd /Users/ayd/DevOps/code/auto1/artipie && \
  git add artipie-backfill/src/main/java/com/artipie/backfill/ComposerScanner.java \
          artipie-backfill/src/test/java/com/artipie/backfill/ComposerScannerTest.java && \
  git commit -m "$(cat <<'EOF'
fix(backfill): add Artipie proxy vendor-dir layout to ComposerScanner

The Artipie Composer proxy (ComposerStorageCache) stores per-package
metadata as {vendor}/{package}.json directly at the repo root — not
under p2/ and not in a flat packages.json. ComposerScanner did not
support this third layout, causing backfill to scan 0 packages.

Also fixes: empty packages.json (0 bytes) previously triggered a
misleading WARN "Cannot auto-detect encoding, not enough chars".
Now logged at DEBUG and execution falls through to vendor-dir scan.

Priority order unchanged: p2/ > non-empty packages.json > vendor dirs.
EOF
)"
```

---

## Expected outcome after this fix

Re-running the backfill command:

```
java -jar artipie-backfill-1.20.13.jar -t php -p .../data/php_proxy -r php_proxy ...
```

With populated `{vendor}/{package}.json` files: will scan and insert all packages.

With the current empty files: will scan 0 packages with no WARN (files are empty, nothing to backfill — expected and correct).
