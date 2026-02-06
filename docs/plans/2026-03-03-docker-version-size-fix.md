# Docker Version/Size Bug Fix Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix Docker artifact records to store tag names as version (not sha256 digests), compute actual image sizes for manifest lists, prevent duplicate entries from proxy, and make tags searchable.

**Architecture:** Four independent fixes across backfill scanner, push handler, proxy cache, and DB schema. Each fix is self-contained so they can be developed and tested independently.

**Tech Stack:** Java 17, JUnit 5, Hamcrest, PostgreSQL, JDBC

---

### Task 1: Fix DockerScanner — version uses tag name instead of digest

**Files:**
- Modify: `artipie-backfill/src/main/java/com/artipie/backfill/DockerScanner.java:156-187`
- Test: `artipie-backfill/src/test/java/com/artipie/backfill/DockerScannerTest.java`

**Step 1: Update existing tests to expect tag as version**

In `DockerScannerTest.java`, the tests currently assert `record.version()` equals the digest. Update all assertions to expect the tag name instead:

- `scansImageWithTag`: Change assertion at line 56-59 from `Matchers.is(digest)` to `Matchers.is("latest")`
- `scansMultipleTagsForImage`: Change assertions at lines 114-127 to match by tag name `"latest"` and `"1.25"` instead of `digest1`/`digest2`. Update the filter on lines 128-130 and 136-138 to filter by tag.
- `handlesMissingBlob`: No version assertion to change (only checks size).
- `handlesManifestList`: No version assertion to change.
- `handlesNestedImageName`: Change assertion at line 235-239 from `Matchers.is(digest)` to `Matchers.is("7.0")`
- `scansDockerRegistryV2Layout`: No version assertion (only checks name and size).

**Step 2: Run tests to verify they fail**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn test -pl artipie-backfill -Dtest=DockerScannerTest -Dsurefire.useFile=false -q`
Expected: FAIL — version still returns digest

**Step 3: Fix processTag to use tag name as version**

In `DockerScanner.java`, method `processTag` (line 156-187), change the `ArtifactRecord` constructor call to use `tag` instead of `digest`:

```java
    private static ArtifactRecord processTag(final Path blobsRoot,
        final String repoName, final String imageName, final Path tagDir) {
        final String tag = tagDir.getFileName().toString();
        final Path linkFile = tagDir.resolve("current").resolve("link");
        if (!Files.isRegularFile(linkFile)) {
            LOG.debug("No link file at {}", linkFile);
            return null;
        }
        final String digest;
        try {
            digest = Files.readString(linkFile, StandardCharsets.UTF_8).trim();
        } catch (final IOException ex) {
            LOG.warn("Cannot read link file {}: {}", linkFile, ex.getMessage());
            return null;
        }
        if (digest.isEmpty()) {
            LOG.debug("Empty link file at {}", linkFile);
            return null;
        }
        final long createdDate = DockerScanner.linkMtime(linkFile);
        final long size = DockerScanner.resolveSize(blobsRoot, digest);
        return new ArtifactRecord(
            "docker",
            repoName,
            imageName,
            tag,
            size,
            createdDate,
            null,
            "system"
        );
    }
```

The only change is line 181: `digest` -> `tag`.

**Step 4: Run tests to verify they pass**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn test -pl artipie-backfill -Dtest=DockerScannerTest -Dsurefire.useFile=false -q`
Expected: PASS

**Step 5: Commit**

```bash
git add artipie-backfill/src/main/java/com/artipie/backfill/DockerScanner.java artipie-backfill/src/test/java/com/artipie/backfill/DockerScannerTest.java
git commit -m "fix(backfill): use tag name as Docker artifact version instead of digest"
```

---

### Task 2: Fix DockerScanner — resolve manifest list sizes from child manifests

**Files:**
- Modify: `artipie-backfill/src/main/java/com/artipie/backfill/DockerScanner.java:199-234`
- Test: `artipie-backfill/src/test/java/com/artipie/backfill/DockerScannerTest.java`

**Step 1: Update the manifest list test to expect resolved child layer sizes**

In `DockerScannerTest.java`, replace the `handlesManifestList` test. The test needs to create child manifest blobs that the scanner can resolve, and assert the size is the sum of the child's layers (not the manifest list JSON file size):

```java
    @Test
    void handlesManifestList(@TempDir final Path temp) throws IOException {
        final String childDigest = "sha256:child111222333";
        final String childManifest = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"mediaType\": \"application/vnd.oci.image.manifest.v1+json\",",
            "  \"config\": { \"size\": 1504, \"digest\": \"sha256:cfgchild\" },",
            "  \"layers\": [",
            "    { \"size\": 28865120, \"digest\": \"sha256:layerchild\" }",
            "  ]",
            "}"
        );
        DockerScannerTest.createBlob(temp, childDigest, childManifest);
        final String attestDigest = "sha256:attest999888777";
        final String attestManifest = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"mediaType\": \"application/vnd.oci.image.manifest.v1+json\",",
            "  \"config\": { \"size\": 167, \"digest\": \"sha256:cfgattest\" },",
            "  \"layers\": [",
            "    { \"size\": 1331, \"digest\": \"sha256:layerattest\",",
            "      \"mediaType\": \"application/vnd.in-toto+json\" }",
            "  ]",
            "}"
        );
        DockerScannerTest.createBlob(temp, attestDigest, attestManifest);
        final String listDigest = "sha256:ffee00112233";
        final String manifestList = String.join(
            "\n",
            "{",
            "  \"schemaVersion\": 2,",
            "  \"mediaType\": \"application/vnd.docker.distribution.manifest.list.v2+json\",",
            "  \"manifests\": [",
            "    { \"digest\": \"" + childDigest + "\", \"size\": 482,",
            "      \"platform\": { \"architecture\": \"amd64\", \"os\": \"linux\" } },",
            "    { \"digest\": \"" + attestDigest + "\", \"size\": 566,",
            "      \"platform\": { \"architecture\": \"unknown\", \"os\": \"unknown\" } }",
            "  ]",
            "}"
        );
        DockerScannerTest.createTagLink(temp, "ubuntu", "22.04", listDigest);
        DockerScannerTest.createBlob(temp, listDigest, manifestList);
        final DockerScanner scanner = new DockerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "docker-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record",
            records,
            Matchers.hasSize(1)
        );
        final ArtifactRecord record = records.get(0);
        MatcherAssert.assertThat(
            "Size should be sum of ALL child manifests' layers and configs",
            record.size(),
            Matchers.is(1504L + 28865120L + 167L + 1331L)
        );
    }
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn test -pl artipie-backfill -Dtest=DockerScannerTest#handlesManifestList -Dsurefire.useFile=false -q`
Expected: FAIL — size returns the manifest list JSON file size, not child layer sum

**Step 3: Fix resolveSize to resolve manifest list children**

Replace the manifest list branch in `resolveSize` (lines 220-231) to iterate child manifests and sum their layers:

```java
    private static long resolveSize(final Path blobsRoot,
        final String digest) {
        final Path blobPath = DockerScanner.digestToPath(blobsRoot, digest);
        if (blobPath == null || !Files.isRegularFile(blobPath)) {
            LOG.debug("Blob not found for digest {}", digest);
            return 0L;
        }
        final JsonObject manifest;
        try (InputStream input = Files.newInputStream(blobPath);
            JsonReader reader = Json.createReader(input)) {
            manifest = reader.readObject();
        } catch (final JsonException ex) {
            LOG.warn(
                "Corrupted manifest JSON for digest {}: {}",
                digest, ex.getMessage()
            );
            return 0L;
        } catch (final IOException ex) {
            LOG.warn("Cannot read blob {}: {}", blobPath, ex.getMessage());
            return 0L;
        }
        if (manifest.containsKey("manifests")
            && manifest.get("manifests").getValueType()
            == JsonValue.ValueType.ARRAY) {
            return DockerScanner.resolveManifestListSize(
                blobsRoot, manifest.getJsonArray("manifests")
            );
        }
        return DockerScanner.sumLayersAndConfig(manifest);
    }
```

Add the new helper method after `sumLayersAndConfig`:

```java
    /**
     * Resolve the total size of a manifest list by summing the sizes
     * of all child image manifests' layers and configs.
     *
     * @param blobsRoot Path to the blobs directory
     * @param children The "manifests" JSON array from the manifest list
     * @return Total size in bytes across all child manifests
     */
    private static long resolveManifestListSize(final Path blobsRoot,
        final JsonArray children) {
        long total = 0L;
        for (final JsonValue entry : children) {
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            final JsonObject child = entry.asJsonObject();
            final String childDigest = child.getString("digest", null);
            if (childDigest == null || childDigest.isEmpty()) {
                continue;
            }
            final Path childPath =
                DockerScanner.digestToPath(blobsRoot, childDigest);
            if (childPath == null || !Files.isRegularFile(childPath)) {
                LOG.debug("Child manifest blob not found: {}", childDigest);
                continue;
            }
            try (InputStream input = Files.newInputStream(childPath);
                JsonReader reader = Json.createReader(input)) {
                final JsonObject childManifest = reader.readObject();
                total += DockerScanner.sumLayersAndConfig(childManifest);
            } catch (final JsonException | IOException ex) {
                LOG.warn("Cannot read child manifest {}: {}",
                    childDigest, ex.getMessage());
            }
        }
        return total;
    }
```

**Step 4: Run tests to verify they pass**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn test -pl artipie-backfill -Dtest=DockerScannerTest -Dsurefire.useFile=false -q`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add artipie-backfill/src/main/java/com/artipie/backfill/DockerScanner.java artipie-backfill/src/test/java/com/artipie/backfill/DockerScannerTest.java
git commit -m "fix(backfill): resolve manifest list sizes from child manifests"
```

---

### Task 3: Fix CacheManifests — add tag guard to prevent duplicate entries

**Files:**
- Modify: `docker-adapter/src/main/java/com/artipie/docker/cache/CacheManifests.java:300-327`
- Test: `docker-adapter/src/test/java/com/artipie/docker/cache/CacheManifestsTest.java`

**Step 1: Write test that digest-based refs do NOT create events**

Add a new test to `CacheManifestsTest.java`:

```java
    @Test
    void doesNotCreateEventForDigestRef() throws Exception {
        final ManifestReference ref = ManifestReference.from(
            new Digest.Sha256("cb8a924afdf0229ef7515d9e5b3024e23b3eb03ddbba287f4a19c6ac90b8d221")
        );
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Repo cache = new AstoDocker("registry", new InMemoryStorage())
            .repo("my-cache");
        new CacheManifests("cache-alpine",
            new AstoDocker("registry", new ExampleStorage()).repo("my-alpine"),
            cache,
            Optional.of(events),
            "my-docker-proxy",
            Optional.of(new DockerProxyCooldownInspector())
        ).get(ref).toCompletableFuture().join();
        Thread.sleep(500);
        final boolean hasDigestEvent = events.stream().anyMatch(
            e -> e.artifactVersion().startsWith("sha256:")
        );
        MatcherAssert.assertThat(
            "Digest-based refs should NOT create artifact events",
            hasDigestEvent,
            new IsEqual<>(false)
        );
    }
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn test -pl docker-adapter -Dtest=CacheManifestsTest#doesNotCreateEventForDigestRef -Dsurefire.useFile=false -q`
Expected: FAIL — digest-based refs currently create events

**Step 3: Add ImageTag.valid guard in CacheManifests**

In `CacheManifests.java`, add import at the top:

```java
import com.artipie.docker.misc.ImageTag;
```

Then wrap the event creation block (lines 300-327) with the tag guard. Change:

```java
            this.events.ifPresent(queue -> {
```

to:

```java
            this.events.filter(q -> ImageTag.valid(ref.digest())).ifPresent(queue -> {
```

This single-line change ensures events are only created when the ref is a tag (not a digest). The `ImageTag.valid()` pattern rejects strings containing `:` (like `sha256:...`).

**Step 4: Run tests to verify they pass**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn test -pl docker-adapter -Dtest=CacheManifestsTest -Dsurefire.useFile=false -q`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add docker-adapter/src/main/java/com/artipie/docker/cache/CacheManifests.java docker-adapter/src/test/java/com/artipie/docker/cache/CacheManifestsTest.java
git commit -m "fix(docker-proxy): skip artifact events for digest-based manifest refs"
```

---

### Task 4: Fix PushManifestSlice — compute manifest list size from child manifests

**Files:**
- Modify: `docker-adapter/src/main/java/com/artipie/docker/http/manifest/PushManifestSlice.java:38-67`

**Step 1: Refactor push handler to resolve manifest list sizes**

Replace the `response` method to use `thenCompose` for async child manifest resolution:

```java
    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        ManifestRequest request = ManifestRequest.from(line);
        final ManifestReference ref = request.reference();
        return this.docker.repo(request.name())
            .manifests()
            .put(ref, new Content.From(body))
            .thenCompose(
                manifest -> {
                    final CompletableFuture<Long> sizeFuture;
                    if (queue != null && ImageTag.valid(ref.digest()) && manifest.isManifestList()) {
                        sizeFuture = resolveManifestListSize(
                            this.docker.repo(request.name()), manifest
                        );
                    } else if (queue != null && ImageTag.valid(ref.digest())) {
                        sizeFuture = CompletableFuture.completedFuture(
                            manifest.layers().stream().mapToLong(ManifestLayer::size).sum()
                        );
                    } else {
                        sizeFuture = CompletableFuture.completedFuture(0L);
                    }
                    return sizeFuture.thenApply(size -> {
                        if (queue != null && ImageTag.valid(ref.digest())) {
                            queue.add(
                                new ArtifactEvent(
                                    "docker",
                                    docker.registryName(),
                                    new Login(headers).getValue(),
                                    request.name(), ref.digest(),
                                    size
                                )
                            );
                        }
                        return ResponseBuilder.created()
                            .header(new Location(String.format("/v2/%s/manifests/%s", request.name(), ref.digest())))
                            .header(new ContentLength("0"))
                            .header(new DigestHeader(manifest.digest()))
                            .build();
                    });
                }
            );
    }
```

Add the helper method after `response`:

```java
    /**
     * Resolve total size of a manifest list by fetching child manifests
     * from storage and summing their layer sizes.
     *
     * @param repo Repository containing the child manifests
     * @param manifestList The manifest list
     * @return Future with total size in bytes
     */
    private static CompletableFuture<Long> resolveManifestListSize(
        final Repo repo, final Manifest manifestList
    ) {
        final Collection<Digest> children = manifestList.manifestListChildren();
        if (children.isEmpty()) {
            return CompletableFuture.completedFuture(0L);
        }
        CompletableFuture<Long> result = CompletableFuture.completedFuture(0L);
        for (final Digest child : children) {
            result = result.thenCompose(
                running -> repo.manifests()
                    .get(ManifestReference.from(child))
                    .thenApply(opt -> {
                        if (opt.isPresent() && !opt.get().isManifestList()) {
                            return running + opt.get().layers().stream()
                                .mapToLong(ManifestLayer::size).sum();
                        }
                        return running;
                    })
                    .exceptionally(ex -> running)
            );
        }
        return result;
    }
```

Add these imports at the top of the file:

```java
import com.artipie.docker.Digest;
import com.artipie.docker.Repo;
import java.util.Collection;
```

**Step 2: Run existing docker-adapter tests**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn test -pl docker-adapter -Dsurefire.useFile=false -q`
Expected: ALL PASS (no regression)

**Step 3: Commit**

```bash
git add docker-adapter/src/main/java/com/artipie/docker/http/manifest/PushManifestSlice.java
git commit -m "fix(docker): compute manifest list size from child manifests on push"
```

---

### Task 5: Fix search tokens trigger — add version to tsvector

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/db/ArtifactDbFactory.java:373-430`
- Modify: `artipie-backfill/src/main/java/com/artipie/backfill/BatchInserter.java` (no change needed — backfill doesn't set search_tokens, the DB trigger handles it)

**Step 1: Update the trigger function to include version**

In `ArtifactDbFactory.java`, replace the trigger function SQL (lines 373-387):

```java
            try {
                statement.executeUpdate(
                    String.join(
                        "\n",
                        "CREATE OR REPLACE FUNCTION artifacts_search_update() RETURNS trigger AS $$",
                        "BEGIN",
                        "  NEW.search_tokens := to_tsvector('simple',",
                        "    coalesce(NEW.name, '') || ' ' ||",
                        "    coalesce(NEW.version, '') || ' ' ||",
                        "    coalesce(NEW.owner, '') || ' ' ||",
                        "    coalesce(NEW.repo_name, '') || ' ' ||",
                        "    coalesce(NEW.repo_type, ''));",
                        "  RETURN NEW;",
                        "END;",
                        "$$ LANGUAGE plpgsql;"
                    )
                );
            } catch (final SQLException ex) {
```

The only change is adding `"    coalesce(NEW.version, '') || ' ' ||",` after the name line.

**Step 2: Update the backfill statement for existing rows**

In `ArtifactDbFactory.java`, update the backfill UPDATE statement (lines 416-422):

```java
            try {
                statement.executeUpdate(
                    String.join(
                        " ",
                        "UPDATE artifacts SET search_tokens = to_tsvector('simple',",
                        "coalesce(name, '') || ' ' || coalesce(version, '') || ' ' ||",
                        "coalesce(owner, '') || ' ' ||",
                        "coalesce(repo_name, '') || ' ' || coalesce(repo_type, ''))",
                        "WHERE search_tokens IS NULL"
                    )
                );
            } catch (final SQLException ex) {
```

Same change — add `coalesce(version, '') || ' ' ||`.

**Step 3: Run artipie-main tests**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn test -pl artipie-main -Dsurefire.useFile=false -q`
Expected: ALL PASS

**Step 4: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/db/ArtifactDbFactory.java
git commit -m "fix(search): include version in search_tokens tsvector for tag-based search"
```

---

### Task 6: Reindex existing search tokens (one-time migration)

**Note:** The trigger function uses `CREATE OR REPLACE`, so it updates automatically on next Artipie startup. However, existing rows that already have `search_tokens` populated (non-NULL) won't be re-indexed because the backfill `WHERE` clause only targets `NULL` rows.

**Step 1: Change the backfill WHERE clause to re-index ALL rows**

In `ArtifactDbFactory.java`, change the backfill statement's WHERE clause from:

```java
                        "WHERE search_tokens IS NULL"
```

to:

```java
                        "WHERE TRUE"
```

This ensures all existing rows get their search_tokens rebuilt with the new formula (including version). On subsequent startups, this is a no-op because the trigger keeps tokens current.

**Note:** For large deployments, this UPDATE runs on every startup. If performance is a concern, consider adding a schema version flag. For most deployments, this is fast enough (< 1 second for < 100K rows).

**Step 2: Run artipie-main tests**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn test -pl artipie-main -Dsurefire.useFile=false -q`
Expected: ALL PASS

**Step 3: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/db/ArtifactDbFactory.java
git commit -m "fix(search): reindex all search_tokens to include version field"
```

---

### Task 7: Final verification — full build and re-run backfill

**Step 1: Run full build**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn clean test -pl artipie-backfill,docker-adapter,artipie-main -Dsurefire.useFile=false -q`
Expected: ALL PASS

**Step 2: Rebuild the backfill JAR**

Run: `cd /Users/ayd/DevOps/code/auto1/artipie && mvn package -pl artipie-backfill -DskipTests -q`

**Step 3: Re-run backfill against docker_local**

Run:
```bash
java -jar artipie-backfill/target/artipie-backfill-1.20.13.jar \
  -t docker \
  -p /Users/ayd/DevOps/code/auto1/artipie/artipie-main/docker-compose/artipie/data/docker_local \
  -r docker_local \
  --db-url jdbc:postgresql://localhost:5432/artifacts \
  --db-user artipie \
  --db-password artipie \
  --owner system_import
```

Expected output:
- `Total scanned : 1`
- `Total errors  : 0`

**Step 4: Verify database record**

Run: `psql -U artipie -d artifacts -c "SELECT name, version, size FROM artifacts WHERE repo_name = 'docker_local'"`

Expected:
- `name` = `auto1/hello`
- `version` = `1.0.0` (was `sha256:7299b6e5...`)
- `size` = `28866624` (was `855`) — this is 1504 (config) + 28865120 (layer)

**Step 5: Verify search tokens include version**

Run: `psql -U artipie -d artifacts -c "SELECT version, search_tokens FROM artifacts WHERE repo_name = 'docker_local'"`

Expected: `search_tokens` should contain the token `'1.0.0'`.
