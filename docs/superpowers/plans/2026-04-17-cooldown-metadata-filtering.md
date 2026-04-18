# Cooldown Metadata Filtering — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement two-layer cooldown enforcement (metadata filtering + direct-request 403) across all 7 adapters (Maven, npm, PyPI, Docker, Go, PHP/Composer, Gradle) with performance hardening for 1000 req/s and an enterprise-grade SOLID package structure.

**Architecture:** Extend the existing `CooldownMetadataServiceImpl` orchestrator with per-adapter `MetadataParser<T>` / `MetadataFilter<T>` / `MetadataRewriter<T>` / `MetadataRequestDetector` / `CooldownResponseFactory` implementations. Harden with pre-warmed release-date cache, parallel bounded evaluation, stale-while-revalidate on the filtered metadata cache, and fix the inflight-map memory leak. Audit + harden the existing admin unblock + invalidation flow.

**Tech Stack:** Java 21 (records, sealed interfaces, pattern matching), Caffeine (L1 cache), Valkey/Redis (L2 cache), Jackson (JSON parsing), DOM (XML parsing), Micrometer (metrics), PrimeVue 4.5.5 (admin UI).

**Existing code to study first:**
- npm reference implementation: `npm-adapter/src/main/java/com/auto1/pantera/npm/cooldown/NpmMetadataParser.java` + `NpmMetadataFilter.java` + tests
- Core orchestrator: `pantera-core/.../cooldown/metadata/CooldownMetadataServiceImpl.java` (lines 165-435)
- Cache: `pantera-core/.../cooldown/metadata/FilteredMetadataCache.java` (lines 223-284)
- Inflight leak: `pantera-core/.../cooldown/CooldownCache.java` (lines 252-283)

---

## Phase 1: Package Restructure (no behaviour change)

### Task 1: Reorganize cooldown package to SOLID layout

**Files:**
- Move (not create) existing classes into the target layout from the design spec §Package Structure
- No code changes — only `package` statements and imports

Target:
```
pantera-core/.../cooldown/
├── api/         CooldownService, CooldownInspector, CooldownRequest, CooldownResult, CooldownBlock, CooldownReason
├── cache/       CooldownCache, CooldownCacheConfig, CooldownCacheMetrics
├── metadata/    (already here) CooldownMetadataService, MetadataParser, MetadataFilter, MetadataRewriter, MetadataRequestDetector, MetadataFilterService(=CooldownMetadataServiceImpl renamed), FilteredMetadataCache, VersionComparators
├── response/    CooldownResponseFactory (new interface), CooldownResponseRegistry (new)
├── config/      CooldownSettings, CooldownCircuitBreaker, InspectorRegistry
├── metrics/     CooldownMetrics
└── impl/        DefaultCooldownService(=existing impl renamed), CachedCooldownInspector, NoopCooldownService
```

- [ ] **Step 1: Create the sub-package directories**
- [ ] **Step 2: Move api/ classes** — update package declarations + imports across the codebase (`rg 'import com.auto1.pantera.cooldown.CooldownService'` to find all importers)
- [ ] **Step 3: Move cache/ classes**
- [ ] **Step 4: Move config/ classes**
- [ ] **Step 5: Move impl/ classes** — rename `CooldownMetadataServiceImpl` → `MetadataFilterService`
- [ ] **Step 6: Create response/ package** — new `CooldownResponseFactory` interface + `CooldownResponseRegistry`
- [ ] **Step 7: Run full build** — `mvn -T8 install -DskipTests -Dmaven.docker.plugin.skip=true -q` → BUILD SUCCESS
- [ ] **Step 8: Run full test** — `mvn -T8 test` → all existing 15 cooldown tests pass (unchanged behaviour)
- [ ] **Step 9: Commit** — `refactor(cooldown): reorganize to SOLID package layout (Phase 1)`

---

## Phase 2: Performance Hardening (H1-H5)

### Task 2: H5 — Fix CooldownCache inflight-map memory leak

**Files:**
- Modify: `pantera-core/.../cooldown/cache/CooldownCache.java`
- Test: `pantera-core/src/test/java/com/auto1/pantera/cooldown/cache/CooldownCacheInflightLeakTest.java` (new)

- [ ] **Step 1: Write failing test** — submit a query that throws; assert the inflight map is empty after the exception propagates
- [ ] **Step 2: Fix the leak** — in `queryAndCache()` (line ~270), change:
  ```java
  // Before: only removes on successful whenComplete
  .whenComplete((blocked, error) -> { this.inflight.remove(key); ... })
  
  // After: guaranteed removal via try-finally pattern
  final CompletableFuture<Boolean> future = dbQuery.get();
  this.inflight.put(key, future);
  future.whenComplete((blocked, error) -> {
      this.inflight.remove(key);  // Always fires — success, error, OR cancellation
      if (error == null && blocked != null) { /* cache result */ }
  });
  ```
  ALSO add `.orTimeout(30, TimeUnit.SECONDS)` as a zombie safety net matching SingleFlight's pattern.
- [ ] **Step 3: Run test** → PASS
- [ ] **Step 4: Commit** — `fix(cooldown): fix inflight-map memory leak on exceptional completion (H5)`

### Task 3: H1 — Pre-warm release-date cache on metadata fetch

**Files:**
- Modify: `pantera-core/.../cooldown/metadata/MetadataFilterService.java` (renamed from CooldownMetadataServiceImpl)
- Modify: `pantera-core/.../cooldown/metadata/MetadataParser.java` — add `Map<String, Instant> extractReleaseDates(T metadata)` default method
- Modify: `npm-adapter/.../NpmMetadataParser.java` — implement extractReleaseDates (reads npm `time` field)
- Test: `pantera-core/src/test/.../CooldownCachePreWarmTest.java`

- [ ] **Step 1: Add `extractReleaseDates` to MetadataParser interface**
  ```java
  default Map<String, Instant> extractReleaseDates(T metadata) { return Map.of(); }
  ```
- [ ] **Step 2: Implement in NpmMetadataParser** — parse the `time` JSON object
- [ ] **Step 3: In MetadataFilterService.computeFilteredMetadata()**, after parsing, call `parser.extractReleaseDates(metadata)` and bulk-populate CooldownCache L1
- [ ] **Step 4: Write test** — verify after metadata parse, release-date lookup for known versions hits L1 (no DB/Valkey call)
- [ ] **Step 5: Run** → PASS
- [ ] **Step 6: Commit** — `perf(cooldown): pre-warm release-date cache from metadata on fetch (H1)`

### Task 4: H2 — Parallel bounded version evaluation

**Files:**
- Modify: `pantera-core/.../cooldown/metadata/MetadataFilterService.java`
- Test: `pantera-core/src/test/.../VersionEvaluationParallelTest.java`

- [ ] **Step 1: Replace the sequential evaluation loop** with `CompletableFuture.allOf()` over a max-50 list, using a dedicated `ContextualExecutorService`-wrapped 4-thread pool
- [ ] **Step 2: Write test** — 50 versions, all L1-cached, assert total evaluation time < 50ms
- [ ] **Step 3: Commit** — `perf(cooldown): parallel bounded version evaluation with dedicated executor (H2)`

### Task 5: H3 — Stale-while-revalidate on FilteredMetadataCache

**Files:**
- Modify: `pantera-core/.../cooldown/metadata/FilteredMetadataCache.java`
- Test: `pantera-core/src/test/.../FilteredMetadataCacheSWRTest.java`

- [ ] **Step 1: Add SWR logic** — on TTL expiry, return stale bytes immediately + trigger background re-evaluation
- [ ] **Step 2: Write test** — expire entry, next get() returns stale bytes (not null), background task fires, subsequent get() returns fresh bytes
- [ ] **Step 3: Commit** — `perf(cooldown): stale-while-revalidate on filtered metadata cache (H3)`

### Task 6: H4 — Increase FilteredMetadataCache L1 capacity

**Files:**
- Modify: `pantera-core/.../cooldown/metadata/FilteredMetadataCache.java`
- Modify: `pantera-core/.../cooldown/config/CooldownSettings.java` (or equivalent config)

- [ ] **Step 1: Change default** from 5K → 50K entries
- [ ] **Step 2: Make configurable** via `PANTERA_COOLDOWN_METADATA_L1_SIZE` env var
- [ ] **Step 3: Commit** — `perf(cooldown): increase metadata cache L1 capacity to 50K (H4)`

---

## Phase 3: Per-Adapter Metadata Implementations

### Task 7: Maven MetadataParser + Filter + Rewriter + RequestDetector

**Files:**
- Create: `maven-adapter/src/main/java/com/auto1/pantera/maven/cooldown/MavenMetadataParser.java`
- Create: `maven-adapter/src/main/java/com/auto1/pantera/maven/cooldown/MavenMetadataFilter.java`
- Create: `maven-adapter/src/main/java/com/auto1/pantera/maven/cooldown/MavenMetadataRewriter.java`
- Create: `maven-adapter/src/main/java/com/auto1/pantera/maven/cooldown/MavenMetadataRequestDetector.java`
- Test: `maven-adapter/src/test/java/com/auto1/pantera/maven/cooldown/MavenMetadataParserTest.java`
- Test: `maven-adapter/src/test/java/com/auto1/pantera/maven/cooldown/MavenMetadataFilterTest.java`
- Test: `maven-adapter/src/test/java/com/auto1/pantera/maven/cooldown/MavenMetadataRewriterTest.java`
- Test: `maven-adapter/src/test/java/com/auto1/pantera/maven/cooldown/MavenMetadataRequestDetectorTest.java`
- Resource: `maven-adapter/src/test/resources/cooldown/maven-metadata-sample.xml`

**Parser** — DOM parse `maven-metadata.xml`; `extractVersions()` reads `<versions><version>` nodes; `extractReleaseDates()` returns empty (Maven metadata has no timestamps — inspector uses HTTP HEAD Last-Modified).
**Filter** — remove `<version>X</version>` nodes where X is blocked; update `<latest>` + `<lastUpdated>`.
**Rewriter** — serialize DOM back to XML bytes, preserving original formatting where possible.
**Detector** — `isMetadataRequest()` = path ends with `maven-metadata.xml`.

- [ ] **Step 1: Write MavenMetadataParserTest** with a sample `maven-metadata.xml` fixture (10 versions)
- [ ] **Step 2: Implement MavenMetadataParser**
- [ ] **Step 3: Write MavenMetadataFilterTest** — block 3 of 10 → assert 7 remain + latest updated
- [ ] **Step 4: Implement MavenMetadataFilter**
- [ ] **Step 5: Write MavenMetadataRewriterTest** — round-trip: parse → filter → rewrite → parse → assert
- [ ] **Step 6: Implement MavenMetadataRewriter**
- [ ] **Step 7: Write + implement MavenMetadataRequestDetector** (simple — path.endsWith check)
- [ ] **Step 8: Run** — `mvn -pl maven-adapter test` → PASS
- [ ] **Step 9: Commit** — `feat(maven): cooldown metadata parser/filter/rewriter/detector`

### Task 8: PyPI MetadataParser + Filter + Rewriter + RequestDetector

Same pattern as Task 7 but for PyPI HTML simple index. Parser extracts links from `<a>` tags. Filter removes links for blocked versions. Rewriter serializes back to HTML. Detector matches `/simple/`.

- [ ] Steps 1-9: mirror Task 7 structure for PyPI
- [ ] **Commit** — `feat(pypi): cooldown metadata parser/filter/rewriter/detector`

### Task 9: Docker MetadataParser + Filter + Rewriter + RequestDetector

JSON `tags/list`. Parser reads `tags` array. Filter removes blocked tag strings. Rewriter serializes JSON. Detector matches `/v2/{name}/tags/list`.

- [ ] Steps 1-9: mirror Task 7 structure for Docker
- [ ] **Commit** — `feat(docker): cooldown metadata parser/filter/rewriter/detector`

### Task 10: Go MetadataParser + Filter + Rewriter + RequestDetector

Plain text, one version per line. Parser splits by newline. Filter removes blocked version lines. Rewriter joins with newline. Detector matches `/@v/list`.

- [ ] Steps 1-9: mirror Task 7 structure for Go
- [ ] **Commit** — `feat(go): cooldown metadata parser/filter/rewriter/detector`

### Task 11: PHP/Composer MetadataParser + Filter + Rewriter + RequestDetector

JSON `packages.json`. Parser reads `packages.{name}` version map. Filter removes blocked version keys. Rewriter serializes JSON. Detector matches `/packages/` or `/p2/`.

- [ ] Steps 1-9: mirror Task 7 structure for Composer
- [ ] **Commit** — `feat(composer): cooldown metadata parser/filter/rewriter/detector`

### Task 12: Verify npm + wire Gradle (reuses Maven)

**npm** already has full implementations — verify they compile against the (possibly renamed) interfaces after Phase 1. Fix imports if needed.

**Gradle** — create thin delegating classes that reuse Maven's implementations:

```java
// gradle-adapter or pantera-main, depending on whether gradle has its own adapter module
public final class GradleMetadataParser extends MavenMetadataParser {}
// ... same for filter, rewriter, detector
```

- [ ] **Step 1: Verify npm tests** — `mvn -pl npm-adapter test -Dtest='Npm*Cooldown*,Npm*Metadata*'` → PASS
- [ ] **Step 2: Create Gradle delegating classes** (or document that Gradle IS Maven — no separate classes needed if the registry maps `gradle` → Maven components)
- [ ] **Step 3: Commit** — `feat(cooldown): verify npm + wire Gradle reusing Maven components`

---

## Phase 4: 403 Response Factories

### Task 13: Create CooldownResponseFactory interface + per-adapter implementations

**Files:**
- Create: `pantera-core/.../cooldown/response/CooldownResponseFactory.java` (interface, if not created in Phase 1)
- Create: `pantera-core/.../cooldown/response/CooldownResponseRegistry.java`
- Create: one `{Adapter}CooldownResponseFactory.java` per adapter (7 classes, in each adapter module's `cooldown/` package)
- Test: one test per adapter asserting Content-Type, body shape, headers

The existing monolithic `CooldownResponses.forbidden()` is replaced by the per-adapter factory.

- [ ] **Step 1: Define interface**
  ```java
  public interface CooldownResponseFactory {
      Response forbidden(CooldownBlock block);
      default String repoType() { return ""; } // for registry auto-discovery
  }
  ```
- [ ] **Step 2: Implement MavenCooldownResponseFactory** — `text/plain` body
- [ ] **Step 3: Implement NpmCooldownResponseFactory** — npm JSON error envelope
- [ ] **Step 4: Implement PyPiCooldownResponseFactory** — `text/plain`
- [ ] **Step 5: Implement DockerCooldownResponseFactory** — Docker error spec JSON
- [ ] **Step 6: Implement GoCooldownResponseFactory** — `text/plain`
- [ ] **Step 7: Implement ComposerCooldownResponseFactory** — Composer JSON
- [ ] **Step 8: Implement CooldownResponseRegistry** — `Map<String, CooldownResponseFactory>`, populated at startup
- [ ] **Step 9: Wire** — `BaseCachedProxySlice.evaluateCooldownAndFetch()` calls `registry.get(repoType).forbidden(block)` instead of `CooldownResponses.forbidden(block)`
- [ ] **Step 10: Tests** for each factory (7 test classes)
- [ ] **Step 11: Deprecate old CooldownResponses.forbidden()**
- [ ] **Step 12: Commit** — `feat(cooldown): per-adapter 403 response factories with registry`

---

## Phase 5: Admin/Invalidation Audit + Hardening

### Task 14: Audit and harden the unblock → invalidation flow

**Files:**
- Modify: `pantera-main/.../api/v1/CooldownHandler.java`
- Modify: `pantera-core/.../cooldown/metadata/FilteredMetadataCache.java`
- Modify: `pantera-core/.../cooldown/cache/CooldownCache.java`
- Test: `pantera-main/src/test/.../CooldownHandlerUnblockFlowTest.java` (new)

- [ ] **Step 1: Trace the unblock flow** — read `CooldownHandler.java:460-506` and verify:
  - DB write completes before cache invalidation fires
  - `metadataService.invalidate()` reaches FilteredMetadataCache L1 + L2
  - CooldownCache L1 + L2 are also invalidated (check if this happens — the current code at line 493-498 only calls `metadataService.invalidate`, not `cooldownCache.invalidate`)
  - NegativeCache is invalidated if applicable
- [ ] **Step 2: Fix any gaps** — add missing cache invalidations to the unblock handler
- [ ] **Step 3: Add synchronous completion** — ensure all invalidation futures complete before the 200 response is sent (currently the `.whenComplete` is fire-and-forget for the invalidation)
- [ ] **Step 4: Migrate to v2.2.0 primitives** — replace any `MdcPropagation` remnants with `HandlerExecutor.get()`, use `StructuredLogger.local()` for audit logging, use `Result<T>` / `Fault` for error handling
- [ ] **Step 5: Add Micrometer counters** — `pantera.cooldown.admin{action=unblock|reblock|policy_change}`
- [ ] **Step 6: Write integration test** — unblock → verify version is servable on next request; test race condition (concurrent request during invalidation)
- [ ] **Step 7: Run** — `mvn -pl pantera-main test -Dtest='CooldownHandler*'` → PASS
- [ ] **Step 8: Commit** — `fix(cooldown): harden unblock→invalidation flow — sync completion + missing cache invalidations`

### Task 15: Verify policy-change invalidation + upstream-publish re-evaluation

- [ ] **Step 1: Test** — change cooldown duration from 30d → 7d via the config API; assert FilteredMetadataCache is invalidated (not just stale until TTL)
- [ ] **Step 2: Test** — new version published upstream; assert metadata re-evaluation includes the new version on next request
- [ ] **Step 3: Fix gaps** if either test reveals missing invalidation paths
- [ ] **Step 4: Commit** — `fix(cooldown): invalidate metadata cache on policy change + upstream publish`

---

## Phase 6: Wire everything together + registration

### Task 16: Register all adapter components at startup

**Files:**
- Modify: `pantera-main/.../RepositorySlices.java` — register per-adapter parser/filter/rewriter/detector/responseFactory
- Modify: `pantera-core/.../cooldown/config/InspectorRegistry.java` — extend to hold all 5 component types per adapter (or create a new `CooldownAdapterRegistry`)

- [ ] **Step 1: Create or extend the registry** to hold per-repoType:
  ```java
  public record CooldownAdapterBundle<T>(
      CooldownInspector inspector,
      MetadataParser<T> parser,
      MetadataFilter<T> filter,
      MetadataRewriter<T> rewriter,
      MetadataRequestDetector detector,
      CooldownResponseFactory responseFactory
  ) {}
  ```
- [ ] **Step 2: Register all 7 adapters** in `RepositorySlices` at startup
- [ ] **Step 3: Wire into BaseCachedProxySlice** — when a metadata request arrives, look up the adapter bundle by repoType, delegate to `MetadataFilterService.filterMetadata(...)` with the adapter's parser/filter/rewriter
- [ ] **Step 4: Run full build + test** — `mvn -T8 install test -Dmaven.docker.plugin.skip=true`
- [ ] **Step 5: Commit** — `feat(cooldown): register all 7 adapter bundles at startup + wire metadata filtering`

---

## Phase 7: Testing (comprehensive)

### Task 17: Tier-1 unit tests for remaining adapters

For each adapter that doesn't yet have tests (Maven, PyPI, Docker, Go, Composer), add:
- `{Adapter}MetadataParserTest` — fixture-based, edge cases
- `{Adapter}MetadataFilterTest` — block 3/10, all blocked, none blocked
- `{Adapter}MetadataRewriterTest` — round-trip
- `{Adapter}CooldownResponseFactoryTest` — content-type, headers, body shape
- `{Adapter}MetadataRequestDetectorTest` — positive + negative paths

~25 new test classes (5 per adapter × 5 adapters without tests; npm already covered).

- [ ] **Step 1-5: Per adapter** (parallelize across adapters)
- [ ] **Step 6: Commit per adapter** — `test(maven): cooldown metadata unit tests`, etc.

### Task 18: Tier-2 integration tests

- [ ] **Step 1: MetadataFilterServiceTest** — end-to-end with mock adapter bundle; verify SWR
- [ ] **Step 2: FilteredMetadataCacheSWRTest** — verify stale-then-fresh cycle
- [ ] **Step 3: CooldownCacheInflightLeakTest** — verify H5 fix under concurrent failures
- [ ] **Step 4: VersionEvaluationParallelTest** — 50 versions parallel, sub-50ms after pre-warm
- [ ] **Step 5: Commit** — `test(cooldown): integration tests for metadata filtering + SWR + inflight`

### Task 19: Tier-3 chaos tests

- [ ] **Step 1: CooldownValkeyStalenessTest** — disable Valkey, L1 serves
- [ ] **Step 2: CooldownHighCardinalityTest** — 10K packages, LRU eviction, no OOM
- [ ] **Step 3: CooldownConcurrentFilterStampedeTest** — 100 concurrent, parser runs once
- [ ] **Step 4: Commit** — `test(cooldown): chaos tests for Valkey staleness + high cardinality + stampede`

---

## Phase 8: Documentation + Final Verification

### Task 20: Update documentation

- [ ] **Step 1: Update cooldown section** in user guide (all 7 adapters supported, metadata filtering behaviour, 403 response per adapter)
- [ ] **Step 2: Update API docs** for cooldown config/unblock endpoints
- [ ] **Step 3: Update changelog** — add cooldown metadata filtering entries
- [ ] **Step 4: Commit** — `docs: cooldown metadata filtering for all 7 adapters`

### Task 21: Final full verification

- [ ] **Step 1: Full build** — `mvn -T8 install -Dmaven.docker.plugin.skip=true`
- [ ] **Step 2: Full test** — `mvn -T8 test` → all pass
- [ ] **Step 3: Chaos tests** — `mvn -pl pantera-main test -Dgroups=Chaos`
- [ ] **Step 4: Push + update PR** — `git push origin 2.2.0 && gh pr edit 34 --body-file ...`
