# PyPI PEP 503/691 Full Compliance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Use ralph loop with agent roles: expert developer, code reviewer, tester, and signing off.

**Goal:** Add PEP 691 JSON Simple API support (with `upload-time` for `uv --exclude-newer`), PEP 503 full attribute compliance for hosted repos, yank/unyank API + UI, and one-time metadata migration CLI.

**Architecture:** Content negotiation at the request entry point routes to HTML or JSON rendering. Proxy repos fetch JSON from upstream PyPI and rewrite URLs. Hosted repos generate JSON from sidecar metadata files (`.pypi/metadata/{package}/{filename}.json`) created at upload time. `PackageInfo` extended to parse `Requires-Python`. Yank API updates sidecar files. Migration CLI backfills metadata for existing packages.

**Tech Stack:** Java 21, Vert.x (existing), javax.json (existing), JUnit 5 + Hamcrest (existing), Apache Commons CLI (existing in pantera-backfill)

**Spec:** `docs/superpowers/specs/2026-04-02-pypi-pep691-compliance-design.md`

---

## File Map

### New Files

| File | Responsibility |
|------|---------------|
| `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SimpleApiFormat.java` | Enum: `HTML`, `JSON`. Content negotiation from Accept header. |
| `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SimpleJsonRenderer.java` | Renders PEP 691 JSON from file entries + sidecar metadata. |
| `pypi-adapter/src/main/java/com/auto1/pantera/pypi/meta/PypiSidecar.java` | Read/write sidecar metadata JSON files. |
| `pantera-main/src/main/java/com/auto1/pantera/api/v1/PypiHandler.java` | Yank/unyank API endpoints. |
| `pantera-backfill/src/main/java/com/auto1/pantera/backfill/PypiMetadataBackfill.java` | One-time migration CLI command. |

### New Test Files

| File | Responsibility |
|------|---------------|
| `pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SimpleApiFormatTest.java` | Content negotiation tests. |
| `pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SimpleJsonRendererTest.java` | JSON rendering tests. |
| `pypi-adapter/src/test/java/com/auto1/pantera/pypi/meta/PypiSidecarTest.java` | Sidecar read/write tests. |
| `pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SliceIndexJsonTest.java` | End-to-end JSON index tests. |

### Modified Files

| File | Change |
|------|--------|
| `pypi-adapter/src/main/java/com/auto1/pantera/pypi/meta/PackageInfo.java` | Add `requiresPython()` method. |
| `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SliceIndex.java` | Content negotiation, sidecar-enriched HTML, JSON rendering path. |
| `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/IndexGenerator.java` | Sidecar-enriched HTML attributes. |
| `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/WheelSlice.java` | Create sidecar on upload. |
| `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/ProxySlice.java` | Fetch + cache JSON from upstream, content negotiation. |
| `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/CachedPyProxySlice.java` | Separate JSON cache key. |
| `pantera-main/src/main/java/com/auto1/pantera/api/v1/AsyncApiVerticle.java` | Register PypiHandler. |

### Modified Frontend Files

| File | Change |
|------|--------|
| `pantera-ui/src/api/pypi.ts` | New: yank/unyank API functions. |
| Artifact browser view | Yank/unyank buttons, yanked tag, confirmation dialog. |

---

## Task 1: PackageInfo — Add requiresPython()

**Files:**
- Modify: `pypi-adapter/src/main/java/com/auto1/pantera/pypi/meta/PackageInfo.java`

- [ ] **Step 1: Add requiresPython() to the interface**

In `PackageInfo.java`, add a new default method after `summary()` (line 37):

```java
    /**
     * Python version requirement from Requires-Python header.
     * @return Requires-Python value, or empty string if not specified
     */
    default String requiresPython() {
        return "";
    }
```

- [ ] **Step 2: Implement in FromMetadata**

In the `FromMetadata` inner class, override the method. Unlike `name()` and `version()`, `Requires-Python` is optional — it should not throw when missing:

```java
        @Override
        public String requiresPython() {
            final String name = "Requires-Python:";
            return Stream.of(this.input.split("\n"))
                .filter(line -> line.startsWith(name)).findFirst()
                .map(line -> line.replace(name, "").trim())
                .orElse("");
        }
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl pypi-adapter -q`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add pypi-adapter/src/main/java/com/auto1/pantera/pypi/meta/PackageInfo.java
git commit -m "feat(pypi): add requiresPython() to PackageInfo interface"
```

---

## Task 2: PypiSidecar — Read/Write Sidecar Metadata

**Files:**
- Create: `pypi-adapter/src/main/java/com/auto1/pantera/pypi/meta/PypiSidecar.java`
- Create: `pypi-adapter/src/test/java/com/auto1/pantera/pypi/meta/PypiSidecarTest.java`

- [ ] **Step 1: Write the failing test**

Create `pypi-adapter/src/test/java/com/auto1/pantera/pypi/meta/PypiSidecarTest.java`:

```java
package com.auto1.pantera.pypi.meta;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.time.Instant;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PypiSidecarTest {

    private Storage storage;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void writesAndReadsSidecar() {
        final Key key = new Key.From("mypackage", "mypackage-1.0.0.tar.gz");
        final Instant uploadTime = Instant.parse("2026-04-01T10:30:00Z");
        PypiSidecar.write(this.storage, key, ">=3.8", uploadTime);
        final Optional<PypiSidecar.Meta> meta = PypiSidecar.read(this.storage, key);
        MatcherAssert.assertThat(meta.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(meta.get().requiresPython(), new IsEqual<>(">=3.8"));
        MatcherAssert.assertThat(meta.get().yanked(), new IsEqual<>(false));
        MatcherAssert.assertThat(meta.get().uploadTime().isPresent(), new IsEqual<>(true));
    }

    @Test
    void returnsEmptyWhenNoSidecar() {
        final Key key = new Key.From("mypackage", "mypackage-1.0.0.tar.gz");
        final Optional<PypiSidecar.Meta> meta = PypiSidecar.read(this.storage, key);
        MatcherAssert.assertThat(meta.isEmpty(), new IsEqual<>(true));
    }

    @Test
    void yanksAndUnyanks() {
        final Key key = new Key.From("mypackage", "mypackage-1.0.0.tar.gz");
        final Instant uploadTime = Instant.parse("2026-04-01T10:30:00Z");
        PypiSidecar.write(this.storage, key, ">=3.8", uploadTime);
        PypiSidecar.yank(this.storage, key, "CVE-2026-1234");
        final PypiSidecar.Meta yanked = PypiSidecar.read(this.storage, key).get();
        MatcherAssert.assertThat(yanked.yanked(), new IsEqual<>(true));
        MatcherAssert.assertThat(yanked.yankedReason().get(), new IsEqual<>("CVE-2026-1234"));
        PypiSidecar.unyank(this.storage, key);
        final PypiSidecar.Meta unyanked = PypiSidecar.read(this.storage, key).get();
        MatcherAssert.assertThat(unyanked.yanked(), new IsEqual<>(false));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl pypi-adapter -Dtest="PypiSidecarTest" -q`
Expected: FAIL — `PypiSidecar` does not exist

- [ ] **Step 3: Implement PypiSidecar**

Create `pypi-adapter/src/main/java/com/auto1/pantera/pypi/meta/PypiSidecar.java`:

```java
package com.auto1.pantera.pypi.meta;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

/**
 * Read/write PyPI sidecar metadata files stored at
 * {@code .pypi/metadata/{package}/{filename}.json}.
 */
public final class PypiSidecar {

    /**
     * Metadata directory prefix.
     */
    private static final String PREFIX = ".pypi/metadata";

    private PypiSidecar() {
    }

    /**
     * Read sidecar metadata for an artifact.
     * @param storage Storage
     * @param artifactKey Key like "package/version/filename.whl"
     * @return Metadata if sidecar exists
     */
    public static Optional<Meta> read(final Storage storage, final Key artifactKey) {
        final Key sidecar = sidecarKey(artifactKey);
        if (!storage.exists(sidecar).join()) {
            return Optional.empty();
        }
        final byte[] bytes = storage.value(sidecar).join().asBytes();
        final String json = new String(bytes, StandardCharsets.UTF_8);
        try (var reader = Json.createReader(new StringReader(json))) {
            final JsonObject obj = reader.readObject();
            return Optional.of(new Meta(
                obj.getString("requires-python", ""),
                Instant.parse(obj.getString("upload-time", "1970-01-01T00:00:00Z")),
                obj.getBoolean("yanked", false),
                obj.containsKey("yanked-reason") && !obj.isNull("yanked-reason")
                    ? Optional.of(obj.getString("yanked-reason"))
                    : Optional.empty(),
                obj.containsKey("dist-info-metadata") && !obj.isNull("dist-info-metadata")
                    ? Optional.of(obj.getString("dist-info-metadata"))
                    : Optional.empty()
            ));
        }
    }

    /**
     * Write sidecar metadata for a newly uploaded artifact.
     * @param storage Storage
     * @param artifactKey Artifact key
     * @param requiresPython Requires-Python value (empty string if not specified)
     * @param uploadTime Upload timestamp
     */
    public static void write(final Storage storage, final Key artifactKey,
        final String requiresPython, final Instant uploadTime) {
        final JsonObjectBuilder builder = Json.createObjectBuilder()
            .add("requires-python", requiresPython != null ? requiresPython : "")
            .add("upload-time", uploadTime.toString())
            .add("yanked", false)
            .addNull("yanked-reason")
            .addNull("dist-info-metadata");
        save(storage, artifactKey, builder.build());
    }

    /**
     * Mark an artifact as yanked.
     * @param storage Storage
     * @param artifactKey Artifact key
     * @param reason Yank reason (nullable)
     */
    public static void yank(final Storage storage, final Key artifactKey, final String reason) {
        final Optional<Meta> existing = read(storage, artifactKey);
        final JsonObjectBuilder builder = Json.createObjectBuilder()
            .add("requires-python", existing.map(Meta::requiresPython).orElse(""))
            .add("upload-time", existing.map(m -> m.uploadTime().toString())
                .orElse(Instant.now().toString()))
            .add("yanked", true);
        if (reason != null && !reason.isEmpty()) {
            builder.add("yanked-reason", reason);
        } else {
            builder.addNull("yanked-reason");
        }
        builder.addNull("dist-info-metadata");
        save(storage, artifactKey, builder.build());
    }

    /**
     * Remove yank status from an artifact.
     */
    public static void unyank(final Storage storage, final Key artifactKey) {
        final Optional<Meta> existing = read(storage, artifactKey);
        final JsonObjectBuilder builder = Json.createObjectBuilder()
            .add("requires-python", existing.map(Meta::requiresPython).orElse(""))
            .add("upload-time", existing.map(m -> m.uploadTime().toString())
                .orElse(Instant.now().toString()))
            .add("yanked", false)
            .addNull("yanked-reason")
            .addNull("dist-info-metadata");
        save(storage, artifactKey, builder.build());
    }

    /**
     * Compute the sidecar key for an artifact.
     * Input: "mypackage/1.0.0/mypackage-1.0.0.tar.gz"
     * Output: ".pypi/metadata/mypackage/mypackage-1.0.0.tar.gz.json"
     */
    static Key sidecarKey(final Key artifactKey) {
        final String[] parts = artifactKey.string().split("/");
        final String packageName = parts[0];
        final String filename = parts[parts.length - 1];
        return new Key.From(PREFIX, packageName, filename + ".json");
    }

    private static void save(final Storage storage, final Key artifactKey,
        final JsonObject obj) {
        storage.save(
            sidecarKey(artifactKey),
            new Content.From(obj.toString().getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    /**
     * Sidecar metadata record.
     */
    public record Meta(
        String requiresPython,
        Instant uploadTime,
        boolean yanked,
        Optional<String> yankedReason,
        Optional<String> distInfoMetadata
    ) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl pypi-adapter -Dtest="PypiSidecarTest" -q`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add pypi-adapter/src/main/java/com/auto1/pantera/pypi/meta/PypiSidecar.java \
       pypi-adapter/src/test/java/com/auto1/pantera/pypi/meta/PypiSidecarTest.java
git commit -m "feat(pypi): add PypiSidecar for reading/writing package metadata"
```

---

## Task 3: SimpleApiFormat — Content Negotiation

**Files:**
- Create: `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SimpleApiFormat.java`
- Create: `pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SimpleApiFormatTest.java`

- [ ] **Step 1: Write the failing test**

Create `pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SimpleApiFormatTest.java`:

```java
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

class SimpleApiFormatTest {

    @Test
    void defaultsToHtml() {
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(Headers.EMPTY),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void detectsJsonAcceptHeader() {
        final Headers headers = new Headers.From(
            new Header("Accept", "application/vnd.pypi.simple.v1+json")
        );
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(headers),
            new IsEqual<>(SimpleApiFormat.JSON)
        );
    }

    @Test
    void detectsJsonWithQualityFactor() {
        final Headers headers = new Headers.From(
            new Header("Accept",
                "application/vnd.pypi.simple.v1+json, "
                + "application/vnd.pypi.simple.v1+html;q=0.2, "
                + "text/html;q=0.01")
        );
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(headers),
            new IsEqual<>(SimpleApiFormat.JSON)
        );
    }

    @Test
    void detectsExplicitHtml() {
        final Headers headers = new Headers.From(
            new Header("Accept", "application/vnd.pypi.simple.v1+html")
        );
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(headers),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void wildcardDefaultsToHtml() {
        final Headers headers = new Headers.From(
            new Header("Accept", "*/*")
        );
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(headers),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void textHtmlDefaultsToHtml() {
        final Headers headers = new Headers.From(
            new Header("Accept", "text/html")
        );
        MatcherAssert.assertThat(
            SimpleApiFormat.fromHeaders(headers),
            new IsEqual<>(SimpleApiFormat.HTML)
        );
    }

    @Test
    void jsonContentTypeIsCorrect() {
        MatcherAssert.assertThat(
            SimpleApiFormat.JSON.contentType(),
            new IsEqual<>("application/vnd.pypi.simple.v1+json")
        );
    }

    @Test
    void htmlContentTypeIsCorrect() {
        MatcherAssert.assertThat(
            SimpleApiFormat.HTML.contentType(),
            new IsEqual<>("text/html")
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl pypi-adapter -Dtest="SimpleApiFormatTest" -q`
Expected: FAIL — `SimpleApiFormat` does not exist

- [ ] **Step 3: Implement SimpleApiFormat**

Create `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SimpleApiFormat.java`:

```java
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.http.Headers;

/**
 * PEP 691 content negotiation for the Simple Repository API.
 * Determines whether to serve HTML (PEP 503) or JSON (PEP 691) based on Accept header.
 */
public enum SimpleApiFormat {

    HTML("text/html"),
    JSON("application/vnd.pypi.simple.v1+json");

    private static final String JSON_MIME = "application/vnd.pypi.simple.v1+json";

    private final String contentType;

    SimpleApiFormat(final String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return this.contentType;
    }

    /**
     * Determine format from request headers.
     * Returns JSON if Accept header contains the PEP 691 JSON MIME type,
     * otherwise HTML (backward-compatible default).
     * @param headers Request headers
     * @return Format to use
     */
    public static SimpleApiFormat fromHeaders(final Headers headers) {
        for (final var header : headers) {
            if ("accept".equalsIgnoreCase(header.getKey())) {
                if (header.getValue().contains(JSON_MIME)) {
                    return JSON;
                }
            }
        }
        return HTML;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl pypi-adapter -Dtest="SimpleApiFormatTest" -q`
Expected: All 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SimpleApiFormat.java \
       pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SimpleApiFormatTest.java
git commit -m "feat(pypi): add SimpleApiFormat content negotiation for PEP 691"
```

---

## Task 4: SimpleJsonRenderer — PEP 691 JSON Generation

**Files:**
- Create: `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SimpleJsonRenderer.java`
- Create: `pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SimpleJsonRendererTest.java`

- [ ] **Step 1: Write the failing test**

Create `pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SimpleJsonRendererTest.java`:

```java
package com.auto1.pantera.pypi.http;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

class SimpleJsonRendererTest {

    @Test
    void rendersBasicPackageJson() {
        final SimpleJsonRenderer.FileEntry entry = new SimpleJsonRenderer.FileEntry(
            "mylib-1.0.0.tar.gz",
            "/pypi-hosted/mylib/1.0.0/mylib-1.0.0.tar.gz",
            "abc123def456",
            ">=3.8",
            Instant.parse("2026-04-01T10:30:00Z"),
            false,
            Optional.empty(),
            Optional.empty()
        );
        final String json = SimpleJsonRenderer.render("mylib", List.of(entry));
        try (var reader = Json.createReader(new StringReader(json))) {
            final JsonObject obj = reader.readObject();
            MatcherAssert.assertThat(
                obj.getJsonObject("meta").getString("api-version"),
                new IsEqual<>("1.1")
            );
            MatcherAssert.assertThat(obj.getString("name"), new IsEqual<>("mylib"));
            final JsonObject file = obj.getJsonArray("files").getJsonObject(0);
            MatcherAssert.assertThat(file.getString("filename"), new IsEqual<>("mylib-1.0.0.tar.gz"));
            MatcherAssert.assertThat(
                file.getJsonObject("hashes").getString("sha256"),
                new IsEqual<>("abc123def456")
            );
            MatcherAssert.assertThat(file.getString("requires-python"), new IsEqual<>(">=3.8"));
            MatcherAssert.assertThat(file.getString("upload-time"), new IsEqual<>("2026-04-01T10:30:00Z"));
            MatcherAssert.assertThat(file.getBoolean("yanked"), new IsEqual<>(false));
        }
    }

    @Test
    void omitsOptionalFieldsWhenEmpty() {
        final SimpleJsonRenderer.FileEntry entry = new SimpleJsonRenderer.FileEntry(
            "mylib-1.0.0.tar.gz",
            "/pypi-hosted/mylib/1.0.0/mylib-1.0.0.tar.gz",
            "abc123",
            "",
            null,
            false,
            Optional.empty(),
            Optional.empty()
        );
        final String json = SimpleJsonRenderer.render("mylib", List.of(entry));
        try (var reader = Json.createReader(new StringReader(json))) {
            final JsonObject file = reader.readObject().getJsonArray("files").getJsonObject(0);
            MatcherAssert.assertThat(file.containsKey("requires-python"), new IsEqual<>(false));
            MatcherAssert.assertThat(file.containsKey("upload-time"), new IsEqual<>(false));
        }
    }

    @Test
    void rendersYankedPackage() {
        final SimpleJsonRenderer.FileEntry entry = new SimpleJsonRenderer.FileEntry(
            "mylib-1.0.0.tar.gz",
            "/mylib/1.0.0/mylib-1.0.0.tar.gz",
            "abc123",
            "",
            null,
            true,
            Optional.of("CVE-2026-1234"),
            Optional.empty()
        );
        final String json = SimpleJsonRenderer.render("mylib", List.of(entry));
        try (var reader = Json.createReader(new StringReader(json))) {
            final JsonObject file = reader.readObject().getJsonArray("files").getJsonObject(0);
            MatcherAssert.assertThat(file.getBoolean("yanked"), new IsEqual<>(true));
            MatcherAssert.assertThat(file.getString("yanked-reason"), new IsEqual<>("CVE-2026-1234"));
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl pypi-adapter -Dtest="SimpleJsonRendererTest" -q`
Expected: FAIL — `SimpleJsonRenderer` does not exist

- [ ] **Step 3: Implement SimpleJsonRenderer**

Create `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SimpleJsonRenderer.java`:

```java
package com.auto1.pantera.pypi.http;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

/**
 * Renders PEP 691 (v1.1) JSON Simple Repository API responses.
 * Includes upload-time per PEP 700.
 */
public final class SimpleJsonRenderer {

    private SimpleJsonRenderer() {
    }

    /**
     * Render a package detail page as PEP 691 JSON.
     * @param packageName Normalized package name
     * @param files List of file entries
     * @return JSON string
     */
    public static String render(final String packageName, final List<FileEntry> files) {
        final JsonArrayBuilder filesArray = Json.createArrayBuilder();
        for (final FileEntry file : files) {
            final JsonObjectBuilder entry = Json.createObjectBuilder()
                .add("filename", file.filename())
                .add("url", file.url() + "#sha256=" + file.sha256())
                .add("hashes", Json.createObjectBuilder().add("sha256", file.sha256()));
            if (file.requiresPython() != null && !file.requiresPython().isEmpty()) {
                entry.add("requires-python", file.requiresPython());
            }
            if (file.uploadTime() != null) {
                entry.add("upload-time", file.uploadTime().toString());
            }
            entry.add("yanked", file.yanked());
            if (file.yanked() && file.yankedReason().isPresent()) {
                entry.add("yanked-reason", file.yankedReason().get());
            }
            if (file.distInfoMetadata().isPresent()) {
                entry.add("data-dist-info-metadata",
                    Json.createObjectBuilder().add("sha256", file.distInfoMetadata().get()));
            }
            filesArray.add(entry);
        }
        return Json.createObjectBuilder()
            .add("meta", Json.createObjectBuilder().add("api-version", "1.1"))
            .add("name", packageName)
            .add("files", filesArray)
            .build()
            .toString();
    }

    /**
     * A file entry for the PEP 691 JSON response.
     */
    public record FileEntry(
        String filename,
        String url,
        String sha256,
        String requiresPython,
        Instant uploadTime,
        boolean yanked,
        Optional<String> yankedReason,
        Optional<String> distInfoMetadata
    ) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl pypi-adapter -Dtest="SimpleJsonRendererTest" -q`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SimpleJsonRenderer.java \
       pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SimpleJsonRendererTest.java
git commit -m "feat(pypi): add SimpleJsonRenderer for PEP 691 JSON responses"
```

---

## Task 5: SliceIndex — Enriched HTML + JSON for Hosted Repos

**Files:**
- Modify: `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SliceIndex.java`
- Create: `pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SliceIndexJsonTest.java`

- [ ] **Step 1: Write failing JSON test**

Create `pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SliceIndexJsonTest.java`:

```java
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SliceIndexJsonTest {

    private Storage storage;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void servesJsonWhenAcceptHeaderPresent() throws Exception {
        final byte[] content = "wheel content".getBytes(StandardCharsets.UTF_8);
        this.storage.save(
            new Key.From("mylib", "1.0.0", "mylib-1.0.0-py3-none-any.whl"),
            new Content.From(content)
        ).join();
        PypiSidecar.write(this.storage,
            new Key.From("mylib", "1.0.0", "mylib-1.0.0-py3-none-any.whl"),
            ">=3.8", Instant.parse("2026-04-01T10:30:00Z"));
        final var response = new SliceIndex(this.storage).response(
            new RequestLine("GET", "/mylib/"),
            new Headers.From(
                new Header("Accept", "application/vnd.pypi.simple.v1+json")
            ),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(response.status().code(), new IsEqual<>(200));
        final String body = new String(
            response.body().asBytes(), StandardCharsets.UTF_8
        );
        try (var reader = Json.createReader(new StringReader(body))) {
            final JsonObject obj = reader.readObject();
            MatcherAssert.assertThat(
                obj.getJsonObject("meta").getString("api-version"),
                new IsEqual<>("1.1")
            );
            MatcherAssert.assertThat(obj.getString("name"), new IsEqual<>("mylib"));
            final JsonObject file = obj.getJsonArray("files").getJsonObject(0);
            MatcherAssert.assertThat(
                file.getString("requires-python"),
                new IsEqual<>(">=3.8")
            );
            MatcherAssert.assertThat(
                file.getString("upload-time"),
                new IsEqual<>("2026-04-01T10:30:00Z")
            );
        }
    }

    @Test
    void servesHtmlWithAttributesWhenSidecarExists() throws Exception {
        final byte[] content = "wheel content".getBytes(StandardCharsets.UTF_8);
        this.storage.save(
            new Key.From("mylib", "1.0.0", "mylib-1.0.0-py3-none-any.whl"),
            new Content.From(content)
        ).join();
        PypiSidecar.write(this.storage,
            new Key.From("mylib", "1.0.0", "mylib-1.0.0-py3-none-any.whl"),
            ">=3.8", Instant.parse("2026-04-01T10:30:00Z"));
        final var response = new SliceIndex(this.storage).response(
            new RequestLine("GET", "/mylib/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        final String body = new String(
            response.body().asBytes(), StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(body,
            new StringContains("data-requires-python=\"&gt;=3.8\""));
    }
}
```

- [ ] **Step 2: Modify SliceIndex to support content negotiation + sidecar attributes**

In `SliceIndex.java`, modify the `response()` method to:
1. Check `SimpleApiFormat.fromHeaders(headers)` at the entry point
2. Pass the format to `generateDynamicIndex()`
3. In the HTML path: read `PypiSidecar.read()` for each file and include `data-requires-python`, `data-yanked`, `data-dist-info-metadata` attributes
4. In the JSON path: collect `SimpleJsonRenderer.FileEntry` list and render with `SimpleJsonRenderer.render()`

Key changes to the HTML format string (line ~150):

From:
```java
"<a href=\"%s#sha256=%s\">%s</a><br/>"
```

To:
```java
"<a href=\"%s#sha256=%s\"%s>%s</a><br/>"
```

Where `%s` (the third one) is a string of data attributes built from the sidecar:
```java
private static String buildHtmlAttributes(final PypiSidecar.Meta meta) {
    final StringBuilder attrs = new StringBuilder();
    if (!meta.requiresPython().isEmpty()) {
        attrs.append(String.format(" data-requires-python=\"%s\"",
            meta.requiresPython().replace(">", "&gt;").replace("<", "&lt;")));
    }
    if (meta.yanked()) {
        final String reason = meta.yankedReason().orElse("");
        attrs.append(String.format(" data-yanked=\"%s\"", reason));
    }
    if (meta.distInfoMetadata().isPresent()) {
        attrs.append(String.format(" data-dist-info-metadata=\"sha256=%s\"",
            meta.distInfoMetadata().get()));
    }
    return attrs.toString();
}
```

For JSON: collect entries and call `SimpleJsonRenderer.render()`.

Set the response `Content-Type` header based on format:
- HTML: `text/html`
- JSON: `application/vnd.pypi.simple.v1+json`

- [ ] **Step 3: Run tests**

Run: `mvn test -pl pypi-adapter -Dtest="SliceIndexJsonTest,SliceIndexTest" -q`
Expected: All tests PASS (new JSON tests + existing HTML tests still work)

- [ ] **Step 4: Commit**

```bash
git add pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/SliceIndex.java \
       pypi-adapter/src/test/java/com/auto1/pantera/pypi/http/SliceIndexJsonTest.java
git commit -m "feat(pypi): PEP 691 JSON + PEP 503 enriched HTML for hosted repo index"
```

---

## Task 6: IndexGenerator — Enriched HTML Attributes

**Files:**
- Modify: `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/IndexGenerator.java`

- [ ] **Step 1: Update IndexGenerator to include sidecar attributes**

In `IndexGenerator.java`, modify the `generate()` method (line ~121) to:
1. For each file, attempt `PypiSidecar.read(this.storage, artifactKey)`
2. If sidecar exists, include data attributes using the same `buildHtmlAttributes()` helper (extract to a shared utility or duplicate — the method is small)
3. If no sidecar, generate bare link as before (backward compatible)

Change the format string from:
```java
"<a href=\"%s/%s#sha256=%s\">%s</a><br/>"
```
to:
```java
"<a href=\"%s/%s#sha256=%s\"%s>%s</a><br/>"
```

- [ ] **Step 2: Verify existing tests pass**

Run: `mvn test -pl pypi-adapter -q`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/IndexGenerator.java
git commit -m "feat(pypi): add PEP 503 data attributes to IndexGenerator HTML"
```

---

## Task 7: WheelSlice — Create Sidecar on Upload

**Files:**
- Modify: `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/WheelSlice.java`

- [ ] **Step 1: Create sidecar after successful upload**

In `WheelSlice.java`, in the `response()` method, after the file is saved to storage and before index regeneration (around line 113):

```java
// Create sidecar metadata for PEP 503/691 compliance
final String requiresPython = info.requiresPython();
final Key artifactKey = new Key.From(packageName, version, filename);
PypiSidecar.write(this.storage, artifactKey, requiresPython, Instant.now());
```

Add imports:
```java
import com.auto1.pantera.pypi.meta.PypiSidecar;
import java.time.Instant;
```

`info` is the `PackageInfo` already extracted at line 85-87 via `Metadata.FromArchive()`.

- [ ] **Step 2: Verify compilation and tests**

Run: `mvn test -pl pypi-adapter -q`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/WheelSlice.java
git commit -m "feat(pypi): create sidecar metadata on wheel/sdist upload"
```

---

## Task 8: ProxySlice — Fetch + Serve JSON from Upstream

**Files:**
- Modify: `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/ProxySlice.java`

- [ ] **Step 1: Add JSON content negotiation to upstream fetch**

In `ProxySlice.java`, modify the non-artifact request path (`serveNonArtifact()` or the index-serving method) to:

1. Check `SimpleApiFormat.fromHeaders(headers)` on incoming request
2. If JSON: forward to upstream with `Accept: application/vnd.pypi.simple.v1+json` header
3. Rewrite JSON URLs using existing `rewriteJsonLinks()` method (already exists at line 1124)
4. Cache JSON separately from HTML (append `.json` to cache key or use a separate cache prefix)
5. Set response `Content-Type: application/vnd.pypi.simple.v1+json`

Key change: When fetching from upstream, include the Accept header:

```java
final Headers upstreamHeaders = format == SimpleApiFormat.JSON
    ? new Headers.From(new Header("Accept", SimpleApiFormat.JSON.contentType()))
    : Headers.EMPTY;
```

For caching, the JSON response should be cached with a distinct key to avoid serving JSON when HTML is requested and vice versa.

- [ ] **Step 2: Verify existing proxy tests still pass**

Run: `mvn test -pl pypi-adapter -q`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/ProxySlice.java
git commit -m "feat(pypi): PEP 691 JSON support for proxy repos via upstream fetch"
```

---

## Task 9: Yank/Unyank API Endpoints

**Files:**
- Create: `pantera-main/src/main/java/com/auto1/pantera/api/v1/PypiHandler.java`
- Modify: `pantera-main/src/main/java/com/auto1/pantera/api/v1/AsyncApiVerticle.java`

- [ ] **Step 1: Create PypiHandler**

```java
package com.auto1.pantera.api.v1;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * PyPI-specific management endpoints: yank/unyank packages.
 */
public final class PypiHandler {

    private final CrudRepoSettings repos;
    private final Storage configsStorage;

    public PypiHandler(final CrudRepoSettings repos, final Storage configsStorage) {
        this.repos = repos;
        this.configsStorage = configsStorage;
    }

    public void register(final Router router) {
        router.post("/api/v1/pypi/:repo/:package/:version/yank")
            .handler(this::yankEndpoint);
        router.post("/api/v1/pypi/:repo/:package/:version/unyank")
            .handler(this::unyankEndpoint);
    }

    private void yankEndpoint(final RoutingContext ctx) {
        final String repo = ctx.pathParam("repo");
        final String pkg = ctx.pathParam("package");
        final String version = ctx.pathParam("version");
        final JsonObject body = ctx.body().asJsonObject();
        final String reason = body != null ? body.getString("reason", "") : "";
        ctx.vertx().<Void>executeBlocking(() -> {
            // Find all files for this package/version in the repo storage
            final Storage repoStorage = this.getRepoStorage(repo);
            final Key versionKey = new Key.From(pkg, version);
            for (final Key fileKey : repoStorage.list(versionKey).join()) {
                final String name = fileKey.string();
                if (name.endsWith(".whl") || name.endsWith(".tar.gz")
                    || name.endsWith(".zip") || name.endsWith(".egg")) {
                    PypiSidecar.yank(repoStorage, fileKey, reason);
                }
            }
            EcsLogger.info("com.auto1.pantera.api.v1")
                .message("Yanked " + pkg + " " + version + " in " + repo)
                .eventCategory("pypi")
                .eventAction("yank")
                .eventOutcome("success")
                .log();
            return null;
        }, false).onSuccess(
            v -> ctx.response().setStatusCode(204).end()
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    private void unyankEndpoint(final RoutingContext ctx) {
        final String repo = ctx.pathParam("repo");
        final String pkg = ctx.pathParam("package");
        final String version = ctx.pathParam("version");
        ctx.vertx().<Void>executeBlocking(() -> {
            final Storage repoStorage = this.getRepoStorage(repo);
            final Key versionKey = new Key.From(pkg, version);
            for (final Key fileKey : repoStorage.list(versionKey).join()) {
                final String name = fileKey.string();
                if (name.endsWith(".whl") || name.endsWith(".tar.gz")
                    || name.endsWith(".zip") || name.endsWith(".egg")) {
                    PypiSidecar.unyank(repoStorage, fileKey);
                }
            }
            EcsLogger.info("com.auto1.pantera.api.v1")
                .message("Unyanked " + pkg + " " + version + " in " + repo)
                .eventCategory("pypi")
                .eventAction("unyank")
                .eventOutcome("success")
                .log();
            return null;
        }, false).onSuccess(
            v -> ctx.response().setStatusCode(204).end()
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    private Storage getRepoStorage(final String repoName) {
        // Resolve repo storage from configs — follows existing pattern in RepositoryHandler
        return new com.auto1.pantera.asto.SubStorage(
            new Key.From(repoName), this.configsStorage
        );
    }
}
```

- [ ] **Step 2: Register in AsyncApiVerticle**

Add after existing handler registrations:

```java
        new PypiHandler(crs, this.configsStorage).register(router);
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl pantera-main -q`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/api/v1/PypiHandler.java \
       pantera-main/src/main/java/com/auto1/pantera/api/v1/AsyncApiVerticle.java
git commit -m "feat(pypi): add yank/unyank API endpoints"
```

---

## Task 10: Frontend — Yank/Unyank UI

**Files:**
- Create: `pantera-ui/src/api/pypi.ts`
- Modify: artifact browser view (identify exact file by checking the repo detail / artifact views)

- [ ] **Step 1: Create pypi.ts API functions**

Create `pantera-ui/src/api/pypi.ts`:

```typescript
import { getApiClient } from './client'

export async function yankVersion(
  repo: string,
  pkg: string,
  version: string,
  reason?: string,
): Promise<void> {
  await getApiClient().post(`/pypi/${repo}/${pkg}/${version}/yank`, { reason: reason ?? '' })
}

export async function unyankVersion(
  repo: string,
  pkg: string,
  version: string,
): Promise<void> {
  await getApiClient().post(`/pypi/${repo}/${pkg}/${version}/unyank`)
}
```

- [ ] **Step 2: Add yank/unyank UI to artifact browser**

Find the artifact browser view (likely in `pantera-ui/src/views/` — search for where package versions are displayed). Add:

1. Import `yankVersion`, `unyankVersion` from `@/api/pypi`
2. "Yanked" tag (PrimeVue `Tag`, severity `danger`) on yanked versions
3. "Yank" button for users with write permission → confirmation dialog with optional reason textarea
4. "Unyank" button on yanked versions → simpler confirmation dialog

- [ ] **Step 3: Verify frontend builds**

Run: `cd pantera-ui && npx vue-tsc --noEmit`
Expected: Clean type check

- [ ] **Step 4: Commit**

```bash
git add pantera-ui/src/api/pypi.ts pantera-ui/src/views/
git commit -m "feat(ui): yank/unyank buttons in artifact browser"
```

---

## Task 11: PyPI Metadata Migration CLI

**Files:**
- Create: `pantera-backfill/src/main/java/com/auto1/pantera/backfill/PypiMetadataBackfill.java`
- Modify: `pantera-backfill/src/main/java/com/auto1/pantera/backfill/BackfillCli.java`

- [ ] **Step 1: Create PypiMetadataBackfill**

```java
package com.auto1.pantera.backfill;

import com.auto1.pantera.pypi.meta.Metadata;
import com.auto1.pantera.pypi.meta.PackageInfo;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time migration: backfills PyPI sidecar metadata for existing packages.
 * Extracts Requires-Python from wheel METADATA / sdist PKG-INFO.
 * Sets upload-time to file's last-modified timestamp.
 */
public final class PypiMetadataBackfill {

    private static final Logger LOG =
        LoggerFactory.getLogger(PypiMetadataBackfill.class);

    private final Path storageRoot;
    private final boolean dryRun;

    public PypiMetadataBackfill(final Path storageRoot, final boolean dryRun) {
        this.storageRoot = storageRoot;
        this.dryRun = dryRun;
    }

    /**
     * Run the backfill for a specific repo.
     * @param repoName Repository name
     * @return Stats: [processed, created, skipped]
     */
    public int[] backfill(final String repoName) throws IOException {
        final Path repoDir = this.storageRoot.resolve(repoName);
        if (!Files.isDirectory(repoDir)) {
            LOG.warn("Repo directory not found: {}", repoDir);
            return new int[]{0, 0, 0};
        }
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        try (Stream<Path> walk = Files.walk(repoDir, 4)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    final String name = p.getFileName().toString();
                    return name.endsWith(".whl") || name.endsWith(".tar.gz")
                        || name.endsWith(".zip") || name.endsWith(".egg");
                })
                .forEach(artifactPath -> {
                    processed.incrementAndGet();
                    final Path relative = repoDir.relativize(artifactPath);
                    final String relStr = relative.toString();
                    final String[] parts = relStr.split("/");
                    if (parts.length < 2) {
                        skipped.incrementAndGet();
                        return;
                    }
                    final String packageName = parts[0];
                    final String filename = parts[parts.length - 1];
                    // Check if sidecar already exists
                    final Path sidecarDir = repoDir.resolve(".pypi")
                        .resolve("metadata").resolve(packageName);
                    final Path sidecarFile = sidecarDir.resolve(filename + ".json");
                    if (Files.exists(sidecarFile)) {
                        skipped.incrementAndGet();
                        return;
                    }
                    // Extract metadata
                    String requiresPython = "";
                    try {
                        final PackageInfo info = new Metadata.FromArchive(artifactPath).read();
                        requiresPython = info.requiresPython();
                    } catch (final Exception ex) {
                        LOG.warn("Could not extract metadata from {}: {}",
                            artifactPath, ex.getMessage());
                    }
                    // Get upload time from file mtime
                    Instant uploadTime;
                    try {
                        final BasicFileAttributes attrs =
                            Files.readAttributes(artifactPath, BasicFileAttributes.class);
                        uploadTime = attrs.lastModifiedTime().toInstant();
                    } catch (final IOException ex) {
                        uploadTime = Instant.now();
                    }
                    if (this.dryRun) {
                        LOG.info("[DRY RUN] Would create sidecar for {} "
                            + "(requires-python={}, upload-time={})",
                            relStr, requiresPython, uploadTime);
                    } else {
                        try {
                            Files.createDirectories(sidecarDir);
                            final String json = String.format(
                                "{\"requires-python\":\"%s\","
                                + "\"upload-time\":\"%s\","
                                + "\"yanked\":false,"
                                + "\"yanked-reason\":null,"
                                + "\"dist-info-metadata\":null}",
                                requiresPython.replace("\"", "\\\""),
                                uploadTime.toString()
                            );
                            Files.writeString(sidecarFile, json);
                        } catch (final IOException ex) {
                            LOG.error("Failed to write sidecar for {}: {}",
                                relStr, ex.getMessage());
                            return;
                        }
                    }
                    created.incrementAndGet();
                });
        }
        return new int[]{processed.get(), created.get(), skipped.get()};
    }
}
```

- [ ] **Step 2: Add `pypi-metadata` command to BackfillCli**

In `BackfillCli.java`, add a new command mode in the CLI argument parsing that accepts:
- `--mode pypi-metadata`
- `--storage-root <path>` (existing)
- `--repos <repo1,repo2,...>` (comma-separated list of PyPI hosted repo names)
- `--dry-run` (optional flag)

Route to `PypiMetadataBackfill` when mode is `pypi-metadata`.

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl pantera-backfill -q`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add pantera-backfill/src/main/java/com/auto1/pantera/backfill/PypiMetadataBackfill.java \
       pantera-backfill/src/main/java/com/auto1/pantera/backfill/BackfillCli.java
git commit -m "feat(pypi): add one-time metadata backfill CLI for existing packages"
```

---

## Task 12: Documentation + Final Verification

**Files:**
- Modify: `docs/CHANGELOG.md`
- Modify: relevant docs (admin guide, developer guide)

- [ ] **Step 1: Update changelog**

Add PEP 691/503 compliance to the v2.1.0 changelog section.

- [ ] **Step 2: Full compilation check**

Run: `mvn compile -q`
Expected: Clean

- [ ] **Step 3: Full test suite**

Run: `mvn test -pl pypi-adapter -q`
Expected: All tests pass

Run: `mvn test -pl pantera-main -Dexclude="**/LocateHitRateTest.java" -q`
Expected: All tests pass

- [ ] **Step 4: Frontend build**

Run: `cd pantera-ui && npx vue-tsc --noEmit`
Expected: Clean

- [ ] **Step 5: Commit and push**

```bash
git add -A
git commit -m "feat(pypi): PEP 503/691 compliance — documentation and final verification"
git push
```
