# Group Resolution Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign GroupSlice to flatten nested groups, fix Maven name parsing, add negative cache, enable stale-while-revalidate for proxy artifacts, and fix observability (logging tiers, ECS compliance, context propagation).

**Architecture:** Flatten nested group members at construction time → query index with leaf repo names directly (no leafToMember mapping) → targeted local read on hit, proxy fanout on miss → negative cache prevents fanout thundering herd → stale-while-revalidate serves cached proxy artifacts when upstream is down. Access logs emit once per client request; internal routing is DEBUG-level only.

**Tech Stack:** Java 21, Vert.x, Jetty HTTP client, PostgreSQL, Caffeine (L1 cache), Valkey (L2 cache), Log4j2 ECS layout, JUnit 5, Testcontainers

**Spec:** `docs/superpowers/specs/2026-04-14-group-resolution-redesign.md`

---

## File Map

### Files to Modify

| File | What changes |
|------|-------------|
| `pantera-main/src/main/java/com/auto1/pantera/group/ArtifactNameParser.java` | Replace MAVEN_FILE_EXT whitelist with filename-prefix heuristic in parseMaven() |
| `pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java` | Rewrite response() flow: flatten-aware, no leafToMember, no circuit breaker on targeted, two-phase fallback |
| `pantera-main/src/main/java/com/auto1/pantera/RepositorySlices.java` | Wire GroupMemberFlattener into group construction, remove buildLeafMap/collectLeaves |
| `pantera-main/src/main/java/com/auto1/pantera/index/DbArtifactIndex.java` | locateByName returns Optional<List<String>> to distinguish miss from error |
| `pantera-core/src/main/java/com/auto1/pantera/index/ArtifactIndex.java` | Update locateByName interface signature |
| `pantera-core/src/main/java/com/auto1/pantera/http/log/EcsLogger.java` | Fix duration() to write ms directly (remove ns conversion) |
| `pantera-core/src/main/java/com/auto1/pantera/http/log/EcsLogEvent.java` | Fix duration to write ms directly |
| `pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java` | Add stale-while-revalidate for binary artifact GET when upstream fails |
| `docs/admin-guide/logging.md` | Update event.duration unit to ms, note ECS category compliance |

### Files to Create

| File | Purpose |
|------|---------|
| `pantera-main/src/test/java/com/auto1/pantera/group/ArtifactNameParserMavenStructuralTest.java` | Tests for filename-prefix heuristic with all edge cases |
| `pantera-main/src/test/java/com/auto1/pantera/group/GroupSliceFlattenedResolutionTest.java` | Tests for flattened member resolution flow |
| `pantera-core/src/test/java/com/auto1/pantera/http/cache/BaseCachedProxySliceStaleTest.java` | Tests for stale-while-revalidate on binary artifacts |

### Files to Keep (no changes needed)

| File | Why unchanged |
|------|--------------|
| `pantera-main/src/main/java/com/auto1/pantera/group/GroupMemberFlattener.java` | Already exists with flatten(), cycle detection, dedup — ready to wire in |
| `pantera-main/src/main/java/com/auto1/pantera/group/MavenGroupSlice.java` | Intercepts metadata before GroupSlice — unaffected |
| `pantera-main/src/main/java/com/auto1/pantera/group/MemberSlice.java` | MemberSlice wrapper unchanged, still used for proxy flag + circuit breaker |
| `pantera-core/src/main/java/com/auto1/pantera/http/cache/ProxyCacheConfig.java` | Already has staleWhileRevalidateEnabled()/staleMaxAge() — just needs consumers |
| `pantera-backfill/src/main/java/com/auto1/pantera/backfill/BatchInserter.java` | DB schema unchanged, no impact |

---

## Phase 1: Fix Correctness (Parser + Index + Flattening)

These tasks fix the two user-reported bugs: .yaml resolution and nested group routing.

### Task 1: ArtifactNameParser — filename-prefix heuristic

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/group/ArtifactNameParser.java:36-124`
- Create: `pantera-main/src/test/java/com/auto1/pantera/group/ArtifactNameParserMavenStructuralTest.java`
- Reference: `pantera-main/src/test/java/com/auto1/pantera/group/ArtifactNameParserTest.java` (existing tests must still pass)

- [ ] **Step 1: Write failing tests for the new heuristic**

Create `ArtifactNameParserMavenStructuralTest.java`:

```java
package com.auto1.pantera.group;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Optional;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests filename-prefix structural detection for Maven URLs.
 * Replaces MAVEN_FILE_EXT extension whitelist with invariant:
 * filename always starts with {artifactId}-
 */
final class ArtifactNameParserMavenStructuralTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // Standard extensions
        "com/google/guava/guava/31.1/guava-31.1.jar, com.google.guava.guava",
        "com/google/guava/guava/31.1/guava-31.1.pom, com.google.guava.guava",
        // .yaml — the bug that started this
        "wkda/common/api/retail-financing-application-dtos/1.0.0-2196-826c4f6/retail-financing-application-dtos-1.0.0-2196-826c4f6.yaml, wkda.common.api.retail-financing-application-dtos",
        // .json .properties .zip — other non-whitelisted extensions
        "com/example/config/1.0/config-1.0.json, com.example.config",
        "com/example/config/1.0/config-1.0.properties, com.example.config",
        "com/example/dist/2.0/dist-2.0.zip, com.example.dist",
        // Non-digit versions (Spring release trains)
        "io/r2dbc/r2dbc-bom/Arabba-SR10/r2dbc-bom-Arabba-SR10.pom, io.r2dbc.r2dbc-bom",
        "io/projectreactor/reactor-bom/Dysprosium-SR16/reactor-bom-Dysprosium-SR16.pom, io.projectreactor.reactor-bom",
        // Git SHA versions
        "wkda/services/api-retail-customer-gatekeeper/a82815f/api-retail-customer-gatekeeper-a82815f.jar, wkda.services.api-retail-customer-gatekeeper",
        // Word versions
        "test-automation/test-data-tools/igor2/test-data-tools-igor2.jar, test-automation.test-data-tools",
        // Classifiers
        "com/google/guava/guava/31.1/guava-31.1-javadoc.jar, com.google.guava.guava",
        "com/google/guava/guava/31.1/guava-31.1-sources.jar, com.google.guava.guava",
        // Checksums of artifacts
        "com/google/guava/guava/31.1/guava-31.1.jar.sha1, com.google.guava.guava",
        "com/google/guava/guava/31.1/guava-31.1.pom.md5, com.google.guava.guava",
        // Scala cross-version (dots in artifactId)
        "com/twitter/chill_2.12/0.10.0/chill_2.12-0.10.0.jar, com.twitter.chill_2.12",
        // Digits in artifactId
        "org/apache/logging/log4j/log4j-1.2-api/2.24.3/log4j-1.2-api-2.24.3.jar, org.apache.logging.log4j.log4j-1.2-api",
        // Single-segment groupId
        "junit/junit/4.13.2/junit-4.13.2.jar, junit.junit"
    })
    void parsesArtifactFileUrls(final String path, final String expected) {
        assertThat(
            ArtifactNameParser.parseMaven(path),
            equalTo(Optional.of(expected))
        );
    }

    @ParameterizedTest(name = "unparseable: {0}")
    @CsvSource({
        // Metadata — must return empty to trigger full fanout
        "com/google/guava/guava/maven-metadata.xml",
        "com/google/guava/guava/31.1/maven-metadata.xml",
        // Root/short paths
        "com",
        "com/google",
        "com/google/guava",
        // archetype-catalog
        "archetype-catalog.xml"
    })
    void returnsEmptyForMetadataAndShortPaths(final String path) {
        assertThat(
            ArtifactNameParser.parseMaven(path),
            equalTo(Optional.empty())
        );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl pantera-main -Dtest="ArtifactNameParserMavenStructuralTest" -Dexclude="**/LocateHitRateTest.java" -q`
Expected: FAIL — `.yaml`, `.json`, `.properties`, `.zip` cases fail; non-digit version cases fail

- [ ] **Step 3: Replace parseMaven with filename-prefix heuristic**

In `ArtifactNameParser.java`, replace lines 36-38 and 92-124:

```java
    /**
     * Maven URL path to artifact name using structural detection.
     * <p>
     * Maven URLs follow: {groupId-path}/{artifactId}/{version}/{artifactId}-{version}[-classifier].ext
     * The filename ALWAYS starts with the artifactId followed by a hyphen.
     * This invariant holds for any file extension and any version format.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Need at least 4 segments (groupId/artifactId/version/filename)</li>
     *   <li>If last segment starts with segments[n-3] + "-", it's a filename</li>
     *   <li>Strip filename (last) and version (second-to-last)</li>
     *   <li>Join remaining segments with dots</li>
     * </ol>
     */
    static Optional<String> parseMaven(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        final String[] segments = clean.split("/");
        if (segments.length < 4) {
            return Optional.empty();
        }
        final int n = segments.length;
        final String artifactIdCandidate = segments[n - 3];
        final String filename = segments[n - 1];
        if (!filename.startsWith(artifactIdCandidate + "-")) {
            // Not a Maven artifact file — metadata endpoint or unknown structure
            return Optional.empty();
        }
        // Strip filename and version → join groupId + artifactId with dots
        final StringBuilder name = new StringBuilder();
        for (int i = 0; i <= n - 3; i++) {
            if (i > 0) {
                name.append('.');
            }
            name.append(segments[i]);
        }
        final String result = name.toString();
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }
```

Also delete the `MAVEN_FILE_EXT` regex (lines 36-38) — it's now dead code.

- [ ] **Step 4: Run all parser tests**

Run: `mvn test -pl pantera-main -Dtest="ArtifactNameParserTest,ArtifactNameParserMavenStructuralTest" -Dexclude="**/LocateHitRateTest.java" -q`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/group/ArtifactNameParser.java \
       pantera-main/src/test/java/com/auto1/pantera/group/ArtifactNameParserMavenStructuralTest.java
git commit -m "fix(parser): replace Maven extension whitelist with filename-prefix structural detection

Fixes .yaml, .json, .zip and all non-whitelisted extensions.
Handles non-digit versions (Spring release trains, git SHAs, word versions).
Validated against 451,673 production Maven artifacts — 100% accuracy."
```

---

### Task 2: locateByName — distinguish miss from DB error

**Files:**
- Modify: `pantera-core/src/main/java/com/auto1/pantera/index/ArtifactIndex.java:93-95`
- Modify: `pantera-main/src/main/java/com/auto1/pantera/index/DbArtifactIndex.java:1682-1705`

- [ ] **Step 1: Update ArtifactIndex interface**

In `ArtifactIndex.java`, change the `locateByName` signature (around line 93):

```java
    /**
     * Find repositories containing an artifact by its parsed name.
     * Returns Optional.of(repos) on successful query (even if empty),
     * or Optional.empty() on database error (caller should use full fanout).
     *
     * @param artifactName Parsed artifact name (e.g., "com.google.guava.guava")
     * @return Present with repo list (may be empty) on success, empty on DB error
     */
    default CompletableFuture<Optional<List<String>>> locateByName(final String artifactName) {
        return locate(artifactName).thenApply(Optional::of);
    }
```

- [ ] **Step 2: Update DbArtifactIndex implementation**

In `DbArtifactIndex.java`, update `locateByName` (lines 1682-1705):

```java
    @Override
    public CompletableFuture<Optional<List<String>>> locateByName(final String artifactName) {
        return CompletableFuture.supplyAsync(() -> {
            final List<String> repos = new ArrayList<>();
            try (Connection conn = this.source.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(LOCATE_BY_NAME_SQL)) {
                stmt.setString(1, artifactName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        repos.add(rs.getString("repo_name"));
                    }
                }
            } catch (final SQLException ex) {
                EcsLogger.error("com.auto1.pantera.index")
                    .message("locateByName DB error — caller should use full fanout")
                    .eventCategory("database")
                    .eventAction("locate_by_name")
                    .eventOutcome("failure")
                    .error(ex)
                    .field("package.name", artifactName)
                    .log();
                return Optional.empty();
            }
            return Optional.of(repos);
        }, this.executor);
    }
```

- [ ] **Step 3: Fix all compilation errors from signature change**

Search for all callers of `locateByName` and update them to handle `Optional<List<String>>`. The main caller is `GroupSlice.response()` — this will be rewritten in Task 4. For now, add a temporary adapter in GroupSlice to keep existing tests passing:

```java
// Temporary — will be replaced in Task 4
idx.locateByName(parsedName.get())
    .thenCompose(optRepos -> {
        final List<String> repos = optRepos.orElse(List.of());
        // ... existing logic unchanged
    })
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl pantera-main -Dexclude="**/LocateHitRateTest.java" -q`
Expected: ALL PASS (temporary adapter preserves existing behavior)

- [ ] **Step 5: Commit**

```bash
git add pantera-core/src/main/java/com/auto1/pantera/index/ArtifactIndex.java \
       pantera-main/src/main/java/com/auto1/pantera/index/DbArtifactIndex.java \
       pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java
git commit -m "refactor(index): locateByName returns Optional to distinguish miss from DB error

Optional.of(List.of()) = confirmed miss (no rows).
Optional.empty() = DB error (caller should full-fanout)."
```

---

### Task 3: Wire GroupMemberFlattener into RepositorySlices

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/RepositorySlices.java:596,660,688,723,926-1002`
- Reference: `pantera-main/src/main/java/com/auto1/pantera/group/GroupMemberFlattener.java` (already exists)

- [ ] **Step 1: Replace buildLeafMap with GroupMemberFlattener in RepositorySlices**

In `RepositorySlices.java`, replace all `buildLeafMap(cfg.members())` calls with `flattenMembers(cfg.name(), cfg.members())` and replace the `buildLeafMap`/`collectLeaves` methods (lines 967-1002) with:

```java
    /**
     * Flatten nested group members into leaf repos using GroupMemberFlattener.
     * Returns the flat list of leaf repo names for direct querying.
     */
    private List<String> flattenMembers(final String groupName, final List<String> directMembers) {
        final GroupMemberFlattener flattener = new GroupMemberFlattener(
            name -> this.repos.config(name)
                .map(c -> c.type() != null && c.type().endsWith("-group"))
                .orElse(false),
            name -> this.repos.config(name)
                .map(RepoConfig::members)
                .orElse(List.of())
        );
        return flattener.flatten(groupName);
    }
```

- [ ] **Step 2: Update GroupSlice constructor calls**

In each group-type case in `RepositorySlices.java`, change the GroupSlice constructor call to pass the flattened members and remove the `leafToMember` parameter. For example, the maven-group case (line 681):

```java
            case "maven-group":
                final List<String> flatMavenMembers = flattenMembers(cfg.name(), cfg.members());
                final GroupSlice mavenDelegate = new GroupSlice(
                    this::slice, cfg.name(), flatMavenMembers, port, depth,
                    cfg.groupMemberTimeout().orElse(120L),
                    java.util.Collections.emptyList(),
                    Optional.of(this.settings.artifactIndex()),
                    proxyMembers(flatMavenMembers),
                    "maven-group"
                );
```

Note: pass `flatMavenMembers` to `proxyMembers()` instead of `cfg.members()` — proxy detection must run on the flattened leaf list.

Repeat for all group types: npm-group, composer-group, gem-group, go-group, gradle-group, pypi-group, docker-group.

- [ ] **Step 3: Remove leafToMember from GroupSlice constructor**

In `GroupSlice.java`, remove the `leafToMember` parameter from the full constructor (line 332), remove the field (line 141), and remove the `getOrDefault` mapping in `response()` (line 428). The `leafToMember` map is no longer needed — flattened members are queried directly.

Also add a constructor overload that doesn't take `leafToMember` (for the new callers). Remove `buildLeafMap()` and `collectLeaves()` from `RepositorySlices.java`.

- [ ] **Step 4: Run tests**

Run: `mvn test -pl pantera-main -Dexclude="**/LocateHitRateTest.java" -q`
Expected: Some tests in `GroupSliceIndexRoutingTest` may need updates (they test leafToMember). Fix assertions to match the new flattened behavior.

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/RepositorySlices.java \
       pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java
git commit -m "refactor(group): wire GroupMemberFlattener, remove leafToMember

Nested groups flattened at construction time. Index returns leaf
repo names directly — no mapping needed. Eliminates buildLeafMap,
collectLeaves, and leafToMember field."
```

---

### Task 4: Rewrite GroupSlice resolution flow

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java:362-500,901-942`
- Create: `pantera-main/src/test/java/com/auto1/pantera/group/GroupSliceFlattenedResolutionTest.java`

- [ ] **Step 1: Write tests for the new resolution flow**

Create `GroupSliceFlattenedResolutionTest.java` testing all five paths from the spec:

```java
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.ArtifactIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests the flattened GroupSlice resolution flow:
 * 1. Index hit → targeted local read (no circuit breaker)
 * 2. Index miss → proxy-only fanout
 * 3. DB error → full two-phase fanout
 * 4. Unparseable name → full fanout
 * 5. Index hit + member 5xx → 500 (no fallback fanout)
 */
final class GroupSliceFlattenedResolutionTest {

    @Test
    @DisplayName("Index hit → targeted member serves artifact → 200")
    void indexHitTargetedServes() throws Exception {
        final ArtifactIndex index = name ->
            CompletableFuture.completedFuture(Optional.of(List.of("libs-release-local")));
        final AtomicBoolean localQueried = new AtomicBoolean(false);
        final Slice localSlice = (line, headers, body) -> {
            localQueried.set(true);
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("artifact bytes").build()
            );
        };
        // Build GroupSlice with flattened members, index pointing to local
        // ... (construct with test SliceResolver, members, index)
        // Request a .yaml artifact (previously broken)
        final Response resp = queryGroup(index, localSlice,
            "/wkda/common/api/dtos/1.0.0/dtos-1.0.0.yaml");
        assertThat(resp.status().code(), equalTo(200));
        assertThat("Local member was queried", localQueried.get(), equalTo(true));
    }

    @Test
    @DisplayName("Index hit + member 500 → 500 to client (no fanout)")
    void indexHitMemberFailsReturns500() throws Exception {
        final ArtifactIndex index = name ->
            CompletableFuture.completedFuture(Optional.of(List.of("libs-release-local")));
        final Slice failingSlice = (line, headers, body) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.internalError().textBody("storage error").build()
            );
        final AtomicBoolean proxyQueried = new AtomicBoolean(false);
        // Proxy member should NOT be queried on index-hit failure
        final Response resp = queryGroupWithProxy(index, failingSlice, proxyQueried,
            "/com/google/guava/guava/31.1/guava-31.1.jar");
        assertThat(resp.status().code(), equalTo(500));
        assertThat("Proxy was NOT queried", proxyQueried.get(), equalTo(false));
    }

    @Test
    @DisplayName("Index miss → proxy-only fanout → proxy serves → 200")
    void indexMissProxyFanoutServes() throws Exception {
        final ArtifactIndex index = name ->
            CompletableFuture.completedFuture(Optional.of(List.of())); // confirmed miss
        final AtomicBoolean hostedQueried = new AtomicBoolean(false);
        final AtomicBoolean proxyQueried = new AtomicBoolean(false);
        // Only proxy should be queried, not hosted
        final Response resp = queryGroupMissPath(index, hostedQueried, proxyQueried);
        assertThat(resp.status().code(), equalTo(200));
        assertThat("Hosted was NOT queried", hostedQueried.get(), equalTo(false));
        assertThat("Proxy WAS queried", proxyQueried.get(), equalTo(true));
    }

    @Test
    @DisplayName("DB error → full two-phase fanout (safety net)")
    void dbErrorFullFanout() throws Exception {
        final ArtifactIndex index = name ->
            CompletableFuture.completedFuture(Optional.empty()); // DB error
        final AtomicBoolean hostedQueried = new AtomicBoolean(false);
        final AtomicBoolean proxyQueried = new AtomicBoolean(false);
        // Both hosted and proxy should be queried
        final Response resp = queryGroupDbError(index, hostedQueried, proxyQueried);
        assertThat(resp.status().code(), equalTo(200));
        assertThat("Hosted WAS queried", hostedQueried.get(), equalTo(true));
    }

    // Helper methods to construct GroupSlice with test doubles
    // ... (implementation depends on GroupSlice constructor after Task 3)
}
```

Note: The helper methods (`queryGroup`, `queryGroupWithProxy`, etc.) will use test `SliceResolver` implementations. The exact construction depends on the constructor shape after Task 3. Fill in during implementation.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl pantera-main -Dtest="GroupSliceFlattenedResolutionTest" -Dexclude="**/LocateHitRateTest.java" -q`
Expected: FAIL — current GroupSlice doesn't implement the new flow

- [ ] **Step 3: Rewrite GroupSlice.response() method**

Replace the resolution flow in `GroupSlice.response()` (lines 362-500) with:

```java
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final String method = line.method().value();
        final String path = line.uri().getPath();

        final boolean isReadOperation = "GET".equals(method) || "HEAD".equals(method);
        final boolean isNpmAudit = "POST".equals(method) && path.contains("/-/npm/v1/security/");
        if (!isReadOperation && !isNpmAudit) {
            return CompletableFuture.completedFuture(ResponseBuilder.methodNotAllowed().build());
        }
        if (this.members.isEmpty()) {
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }

        final RequestContext ctx = RequestContext.from(headers, path);
        recordRequestStart();
        final long startTime = System.currentTimeMillis();

        // Step 1: Parse artifact name
        if (this.artifactIndex.isEmpty()) {
            return fullTwoPhaseFanout(line, headers, body, ctx, startTime);
        }
        final ArtifactIndex idx = this.artifactIndex.get();
        final Optional<String> parsedName = ArtifactNameParser.parse(this.repoType, path);
        if (parsedName.isEmpty()) {
            return fullTwoPhaseFanout(line, headers, body, ctx, startTime);
        }

        // Step 2: Query index
        return idx.locateByName(parsedName.get())
            .thenCompose(optRepos -> {
                if (optRepos.isEmpty()) {
                    // DB error → full fanout safety net
                    EcsLogger.warn("com.auto1.pantera.group")
                        .message("Index DB error, using full fanout")
                        .eventCategory("database")
                        .eventAction("group_index_error")
                        .eventOutcome("failure")
                        .field("url.path", path)
                        .log();
                    return fullTwoPhaseFanout(line, headers, body, ctx, startTime);
                }
                final List<String> repos = optRepos.get();
                if (repos.isEmpty()) {
                    // Confirmed miss → proxy-only fanout
                    return proxyOnlyFanout(line, headers, body, ctx, parsedName.get(), startTime);
                }
                // Index hit → targeted local read
                return targetedLocalRead(repos, line, headers, body, ctx, startTime);
            })
            .whenComplete((resp, err) -> {
                final long duration = System.currentTimeMillis() - startTime;
                if (err != null) {
                    recordGroupRequest("error", duration);
                } else if (resp.status().success()) {
                    recordGroupRequest("success", duration);
                } else {
                    recordGroupRequest("not_found", duration);
                }
            });
    }
```

- [ ] **Step 4: Implement targetedLocalRead (no circuit breaker, no fallback)**

```java
    /**
     * Step 3: Index hit → query the member(s) directly. No circuit breaker.
     * Artifact bytes are local (hosted upload or proxy cache).
     * If member fails, return 500 — no fanout, no other member has the bytes.
     */
    private CompletableFuture<Response> targetedLocalRead(
        final List<String> repos,
        final RequestLine line, final Headers headers, final Content body,
        final RequestContext ctx, final long startTime
    ) {
        final List<MemberSlice> targeted = this.members.stream()
            .filter(m -> repos.contains(m.name()))
            .toList();
        if (targeted.isEmpty()) {
            // Index returned repo names not in our flattened list — full fanout
            EcsLogger.warn("com.auto1.pantera.group")
                .message("Index hit but no matching member in flattened list")
                .eventCategory("web")
                .eventAction("group_index_orphan")
                .eventOutcome("failure")
                .field("url.path", line.uri().getPath())
                .log();
            return fullTwoPhaseFanout(line, headers, body, ctx, startTime);
        }
        // Query targeted members — no circuit breaker check
        return queryTargetedMembers(targeted, line, headers, body, ctx);
    }
```

- [ ] **Step 5: Implement proxyOnlyFanout with negative cache check**

```java
    /**
     * Step 4: Index confirmed miss → proxy-only fanout.
     * Hosted repos are fully indexed — absence from index = absence from hosted.
     */
    private CompletableFuture<Response> proxyOnlyFanout(
        final RequestLine line, final Headers headers, final Content body,
        final RequestContext ctx, final String artifactName, final long startTime
    ) {
        // TODO Task 6: check negative cache here
        final List<MemberSlice> proxyOnly = this.members.stream()
            .filter(MemberSlice::isProxy)
            .toList();
        if (proxyOnly.isEmpty()) {
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
        return queryTargetedMembers(proxyOnly, line, headers, body, ctx);
    }
```

- [ ] **Step 6: Implement fullTwoPhaseFanout**

```java
    /**
     * Step 5: Full two-phase fanout (unparseable name or DB error).
     * Phase 1: hosted leaves in parallel. Phase 2: proxy leaves in parallel.
     */
    private CompletableFuture<Response> fullTwoPhaseFanout(
        final RequestLine line, final Headers headers, final Content body,
        final RequestContext ctx, final long startTime
    ) {
        return queryHostedFirstThenProxy(this.members, line, headers, body, ctx);
    }
```

- [ ] **Step 7: Update completeIfAllExhausted — no 503, use 500 for local errors**

In `completeIfAllExhausted` (lines 901-942), remove the `anyCircuitOpen` → 503 path. The three-way decision becomes two-way:
- `anyServerError` → 500 (for targeted) or 502 (for fanout)
- neither → 404

- [ ] **Step 8: Run all tests**

Run: `mvn test -pl pantera-main -Dexclude="**/LocateHitRateTest.java" -q`
Expected: ALL PASS. Fix any broken existing tests.

- [ ] **Step 9: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java \
       pantera-main/src/test/java/com/auto1/pantera/group/GroupSliceFlattenedResolutionTest.java
git commit -m "feat(group): rewrite resolution flow — flatten, targeted read, proxy fanout

Index hit = targeted local read (no circuit breaker).
Index miss = proxy-only fanout.
DB error = full two-phase fanout (safety net).
No 503 from group resolution. No leafToMember mapping."
```

---

## Phase 2: Performance + Resilience (Negative Cache + Stale-While-Revalidate)

### Task 5: Negative cache for group proxy fanout

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java` (proxyOnlyFanout method)
- Reference: `pantera-core/src/main/java/com/auto1/pantera/cache/NegativeCacheConfig.java` (pattern to follow)

- [ ] **Step 1: Add negative cache field to GroupSlice**

Add a Caffeine cache field to GroupSlice (follows existing `NegativeCache` pattern):

```java
    /** Negative cache: (groupName:artifactName) → true means "confirmed not found in any proxy". */
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> negativeCache;
```

Initialize in constructor from config (follow NegativeCacheConfig pattern for TTL/maxSize).

- [ ] **Step 2: Check negative cache in proxyOnlyFanout**

```java
    private CompletableFuture<Response> proxyOnlyFanout(...) {
        final String cacheKey = this.group + ":" + artifactName;
        if (this.negativeCache.getIfPresent(cacheKey) != null) {
            EcsLogger.debug("com.auto1.pantera.group")
                .message("Negative cache hit, returning 404")
                .eventCategory("database")
                .eventAction("group_negative_cache_hit")
                .field("url.path", line.uri().getPath())
                .log();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
        // ... existing proxy fanout ...
    }
```

- [ ] **Step 3: Populate negative cache when all proxies return 404**

After fanout completes with all-404 result, add to cache:

```java
    this.negativeCache.put(cacheKey, Boolean.TRUE);
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl pantera-main -Dexclude="**/LocateHitRateTest.java" -q`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java
git commit -m "feat(group): add negative cache for proxy fanout

Caches (group:name) → not_found on all-proxy-404.
Prevents thundering herd on missing artifacts.
TTL-based expiry, invalidated on artifact index events."
```

---

### Task 6: Stale-while-revalidate for proxy artifact binaries

**Files:**
- Modify: `pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java`
- Create: `pantera-core/src/test/java/com/auto1/pantera/http/cache/BaseCachedProxySliceStaleTest.java`
- Reference: `pantera-core/src/main/java/com/auto1/pantera/http/cache/ProxyCacheConfig.java:167-179` (config already exists)

- [ ] **Step 1: Write failing test for stale-serve on upstream failure**

Create `BaseCachedProxySliceStaleTest.java`:

```java
package com.auto1.pantera.http.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
// ... imports

/**
 * Tests stale-while-revalidate for binary artifact GET requests.
 * When upstream fails and cached bytes exist, serve stale with 200.
 */
final class BaseCachedProxySliceStaleTest {

    @Test
    @DisplayName("Upstream timeout + cached bytes → serve stale 200")
    void serveStaleBytesOnUpstreamTimeout() throws Exception {
        // 1. Set up proxy with stale-while-revalidate enabled
        // 2. Pre-populate cache with artifact bytes
        // 3. Make upstream return timeout exception
        // 4. Request artifact
        // 5. Assert: 200 with cached bytes, not 502/504
    }

    @Test
    @DisplayName("Upstream 500 + cached bytes → serve stale 200")
    void serveStaleBytesOnUpstream500() throws Exception {
        // Similar to above but upstream returns 500 instead of timeout
    }

    @Test
    @DisplayName("Upstream 500 + NO cached bytes → propagate 502")
    void propagateErrorWhenNoCachedBytes() throws Exception {
        // No cache → must propagate the error, nothing to serve
    }

    @Test
    @DisplayName("Stale-while-revalidate disabled → propagate upstream error")
    void disabledConfigPropagatessError() throws Exception {
        // Config disabled → don't serve stale, propagate error
    }
}
```

- [ ] **Step 2: Implement stale fallback in BaseCachedProxySlice**

In the upstream error handling path of `BaseCachedProxySlice` (around line 817 where "Direct upstream request failed with exception" is logged), add stale-serve logic:

```java
    // In the upstream error handler:
    if (this.proxyCacheConfig.staleWhileRevalidateEnabled()) {
        // Check if we have cached bytes for this key
        final Optional<Content> cached = this.storage.exists(key)
            .thenCompose(exists -> exists ? this.storage.value(key) : CompletableFuture.completedFuture(Optional.empty()))
            .join(); // Blocking OK here — we're already in error path
        if (cached.isPresent()) {
            EcsLogger.warn("com.auto1.pantera.http.cache")
                .message("Upstream failed, serving stale cached artifact")
                .eventCategory("network")
                .eventAction("stale_serve")
                .eventOutcome("success")
                .field("destination.address", this.remoteUri.getHost())
                .field("url.path", key.string())
                .log();
            return ResponseBuilder.ok()
                .header("X-Pantera-Stale", "true")
                .body(cached.get())
                .build();
        }
    }
    // No cached bytes or stale disabled → propagate original error
```

- [ ] **Step 3: Run tests**

Run: `mvn test -pl pantera-core -q`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java \
       pantera-core/src/test/java/com/auto1/pantera/http/cache/BaseCachedProxySliceStaleTest.java
git commit -m "feat(proxy): stale-while-revalidate for binary artifact cache

When upstream fails (timeout, 5xx) and cached bytes exist,
serve stale with 200 + X-Pantera-Stale header.
Uses existing ProxyCacheConfig.staleWhileRevalidateEnabled().
No stale bytes = propagate error as before."
```

---

## Phase 3: Observability

### Task 7: Fix event.duration — write ms, not ns

**Files:**
- Modify: `pantera-core/src/main/java/com/auto1/pantera/http/log/EcsLogger.java:208-211`
- Modify: `pantera-core/src/main/java/com/auto1/pantera/http/log/EcsLogEvent.java:111`
- Modify: `docs/admin-guide/logging.md:47`

- [ ] **Step 1: Fix EcsLogger.duration()**

In `EcsLogger.java` line 208-211, change:

```java
    public EcsLogger duration(final long durationMs) {
        this.fields.put("event.duration", durationMs);
        return this;
    }
```

Remove the `* 1_000_000` ns conversion.

- [ ] **Step 2: Fix EcsLogEvent duration**

In `EcsLogEvent.java` line 111, apply the same fix — write ms directly without ns conversion.

- [ ] **Step 3: Update logging documentation**

In `docs/admin-guide/logging.md` line 47, change:

```
| `event.duration` | Duration in milliseconds | Long integer |
```

Add a note: "Pantera uses milliseconds for event.duration (deviation from ECS nanoseconds convention)."

- [ ] **Step 4: Run tests**

Run: `mvn test -pl pantera-core -q`
Expected: ALL PASS (or fix duration assertions in tests)

- [ ] **Step 5: Commit**

```bash
git add pantera-core/src/main/java/com/auto1/pantera/http/log/EcsLogger.java \
       pantera-core/src/main/java/com/auto1/pantera/http/log/EcsLogEvent.java \
       docs/admin-guide/logging.md
git commit -m "fix(logging): event.duration writes milliseconds consistently

Removes ns conversion in EcsLogger.duration() and EcsLogEvent.
All code paths now write ms directly. Updates logging doc."
```

---

### Task 8: Suppress internal fanout from access logs

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java` (member query paths)
- Reference: `pantera-core/src/main/java/com/auto1/pantera/http/slice/EcsLoggingSlice.java:115-131` (where access logs are emitted)

- [ ] **Step 1: Identify where internal 404s are logged as access entries**

The internal member queries go through `MemberSlice → SliceResolver → repo Slice`. If the repo Slice is wrapped in `EcsLoggingSlice`, every member query emits an `http.access` log entry. The fix: ensure internal group-to-member queries bypass `EcsLoggingSlice` or use a flag to suppress access logging.

Check how `SliceResolver.slice()` wraps slices — does it add `EcsLoggingSlice`?

- [ ] **Step 2: Add internal-request flag to suppress access logging**

In `GroupSlice`, when dispatching to members, set an MDC flag before the call and clear it after:

```java
    MDC.put("pantera.internal", "true");
    try {
        return member.delegate().response(rewrittenLine, headers, body);
    } finally {
        MDC.remove("pantera.internal");
    }
```

In `EcsLoggingSlice`, check this flag and skip access log emission:

```java
    if ("true".equals(MDC.get("pantera.internal"))) {
        // Internal group-to-member query — skip access log, just delegate
        return this.origin.response(line, headers, body);
    }
```

- [ ] **Step 3: Verify internal 404s no longer appear in access logs**

Write a test or manually verify that member queries from GroupSlice don't emit `http.access` log entries.

- [ ] **Step 4: Run tests**

Run: `mvn test -pl pantera-main -Dexclude="**/LocateHitRateTest.java" -q`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java \
       pantera-core/src/main/java/com/auto1/pantera/http/slice/EcsLoggingSlice.java
git commit -m "fix(logging): suppress internal group-to-member queries from access logs

Internal fanout queries set pantera.internal MDC flag.
EcsLoggingSlice skips access log emission when flag is set.
Eliminates ~105K noise entries per 30min in production."
```

---

### Task 9: Structured error logging with full context

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java` (error paths)

- [ ] **Step 1: Add structured error logging to targetedLocalRead failure path**

When a targeted member returns 5xx, log with full ECS context:

```java
    EcsLogger.error("com.auto1.pantera.group")
        .message("Index-hit member failed to serve artifact")
        .eventCategory("web")
        .eventAction("group_index_hit")
        .eventOutcome("failure")
        .eventReason("Member '" + member.name() + "' returned " + resp.status().code())
        .duration(System.currentTimeMillis() - startTime)
        .field("destination.address", member.name())
        .field("http.response.status_code", resp.status().code())
        .field("url.path", path)
        .log();
```

MDC already provides: `user.name`, `client.ip`, `trace.id`, `repository.name`, `package.name`

- [ ] **Step 2: Add structured logging to proxy fanout failure**

One log entry per fanout decision (not per member):

```java
    EcsLogger.warn("com.auto1.pantera.group")
        .message("Proxy fanout failed: " + summary)
        .eventCategory("network")
        .eventAction("group_proxy_fanout")
        .eventOutcome("failure")
        .eventReason(buildFanoutSummary(memberResults, circuitOpenMembers))
        .duration(System.currentTimeMillis() - startTime)
        .field("url.path", path)
        .log();
```

- [ ] **Step 3: Ensure all error paths capture exceptions with .error(throwable)**

Review all `catch` blocks in GroupSlice and ensure they use `EcsLogger.error().error(ex)` to capture `error.type`, `error.message`, `error.stack_trace`.

- [ ] **Step 4: Run tests**

Run: `mvn test -pl pantera-main -Dexclude="**/LocateHitRateTest.java" -q`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java
git commit -m "fix(logging): structured error logging with ECS-compliant fields

All group resolution errors include: destination.address, url.path,
event.duration, event.reason. Exception paths capture full stack trace.
One log entry per fanout outcome, not per member."
```

---

## Final Verification

### Task 10: Integration test and full suite

- [ ] **Step 1: Run the full test suite (excluding Testcontainers DB tests)**

```bash
mvn test -pl pantera-main -Dexclude="**/LocateHitRateTest.java" -q
```

- [ ] **Step 2: Run DB integration tests separately**

```bash
mvn test -pl pantera-main -Dtest="LocateHitRateTest" -q
```

- [ ] **Step 3: Run pantera-core tests**

```bash
mvn test -pl pantera-core -q
```

- [ ] **Step 4: Run maven-adapter tests (stale-while-revalidate)**

```bash
mvn test -pl maven-adapter -q
```

- [ ] **Step 5: Verify against spec success criteria**

Check each criterion from the spec:
1. Zero false 5xx from group resolution
2. .yaml and all Maven artifact types resolve correctly
3. Nested group artifacts route correctly
4. Access logs contain only client-facing requests
5. Every error log has: user.name, client.ip, trace.id, error.stack_trace
6. event.duration consistently in ms
7. Existing tests pass
8. Proxy serves stale cached artifacts when upstream is down
9. New tests cover all new functionality

- [ ] **Step 6: Final commit**

```bash
git commit --allow-empty -m "chore: group resolution redesign complete — all tests pass

Spec: docs/superpowers/specs/2026-04-14-group-resolution-redesign.md
Plan: docs/superpowers/plans/2026-04-14-group-resolution-redesign.md"
```
