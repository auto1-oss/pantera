# Pantera 2.2.0 — Implementation Plan (agent-executable)

**Audience**: coding agents executing tasks autonomously. Every task is self-contained — branch, files, implementation outline, acceptance criteria, tests, and verification commands are in the task body.

**Source documents**: this plan is the operational form of the strategy in `analysis/reference/canonical-architecture.md`, `gap-analysis.md`, `cooldown-redesign.md`, and `PLAN.md` (Candidate 1 recommendation).

**Scope**: three phases — performance critical path (P1–P14), security hardening (S1–S8), observability (O1–O4). Tasks are partially ordered via `Depends on:`; multiple tasks can execute in parallel where dependencies allow.

**Total**: 26 tasks. Estimated 8–12 weeks at 1 FTE.

---

## Conventions every agent must follow

1. **Branch off `master`**. One branch per task, named `task/<id>-<short-slug>` (e.g., `task/p01-circuit-breaker`).
2. **Build with**: `mvn clean install -T8 -DskipTests=true` (smoke) then `mvn test -pl <module>` for the touched module. Full integration tests are scoped per-task.
3. **License headers**: every new Java file needs the GPL-3.0 header from `LICENSE.header`. Run `mvn license:format` before committing.
4. **PMD**: `mvn verify` must pass. Common gotchas: no public static methods except `main(String...)`, one constructor initialises fields, cyclomatic complexity ≤ 15 per method.
5. **Commits**: Conventional Commits. Scope vocabulary in `git log --oneline -20` — match existing (`feat(proxy):`, `fix(http-client):`, `test(maven):`, etc.).
6. **Tests must hit a real database / network only when the file is `*IT.java` / `*ITCase.java` under the `itcase` profile**. Unit tests (`*Test.java`) use `InMemoryStorage` and mocks.
7. **No feature flags**. Settled changes ship as full replacements. Rollback is `git revert`.
8. **Never read `.env*` files**. Never use the Makefile.
9. **Stack restart**: `cd pantera-main/docker-compose/ && docker compose down && docker compose up` if the task requires a local-stack verification step.
10. **Acceptance "verified"** means: the verify command in the task exits 0, the test in the task passes, and a manual cold-bench measurement (if specified) hits the stated threshold.
11. **On test failure during a hook**: fix the underlying issue. Do not `--no-verify`. Do not amend a hook-rejected commit; create a new one.
12. **PR opens against `master`**. PR body must include the task ID, the acceptance criteria, and the perf delta (if performance task).

---

## Test commands (cheat sheet)

```bash
# Run a single test class
mvn test -pl pantera-core -Dtest=BaseCachedProxySliceTest

# Run the perf harness (cold-bench)
cd performance && ./run-cold-bench.sh sonar-maven-plugin

# Run integration tests (Docker required)
mvn verify -P itcase -pl pantera-main

# Lint + PMD
mvn verify -DskipTests=true

# License headers
mvn license:format
```

---

## Phase 1 — Performance critical path (P1–P14)

These tasks close the gaps identified in `gap-analysis.md` G1, G5, G6, G7, G8, G12. Order matters: P1 and P2 must complete before P5 because the circuit breaker is wired into the rate-limiter wrap.

---

### T-P01: Define `BlockingHttpClient` decorator interface and Fibonacci backoff

**Goal**: Introduce the upstream circuit-breaker primitive as a `Slice` decorator with Fibonacci backoff. Pure data + state machine; no wiring yet.

**Why**: Closes gap G6 (no general circuit breaker). The canonical pattern is Nexus's `BlockingHttpClient` — single qualifying failure trips the breaker, Fibonacci backoff, HEAD probe at each block-expiry instant. Pantera's current `RateLimitedClientSlice` reacts only to 429/503-with-Retry-After.

**Depends on**: none.

**Effort**: M.

**Files to create**:
- `http-client/src/main/java/com/auto1/pantera/http/client/circuitbreaker/UpstreamCircuitBreaker.java`
- `http-client/src/main/java/com/auto1/pantera/http/client/circuitbreaker/CircuitBreakerConfig.java`
- `http-client/src/main/java/com/auto1/pantera/http/client/circuitbreaker/FibonacciBackoff.java`
- `http-client/src/test/java/com/auto1/pantera/http/client/circuitbreaker/UpstreamCircuitBreakerTest.java`
- `http-client/src/test/java/com/auto1/pantera/http/client/circuitbreaker/FibonacciBackoffTest.java`

**Implementation outline**:
1. `FibonacciBackoff` — class with seed (default `Duration.ofSeconds(30)`), cap (default `Duration.ofMinutes(60)`), `next()` advancing the sequence, `reset()`. Internal `long[]` of computed Fibonacci values up to the cap, plus an index. Sequence: 30, 30, 60, 90, 150, 240, 390, 630, 1020, 1650, 2670, capped at 3600.
2. `CircuitBreakerConfig` — record: `Duration seedBackoff`, `Duration maxBackoff`, `Predicate<Throwable> shouldTripOnException`, `IntPredicate shouldTripOnStatus`. Defaults: `shouldTripOnStatus` returns true for 5xx + 401 + 407 (per Nexus pattern); `shouldTripOnException` returns true for everything except `ConnectionPoolTimeoutException` and `RejectedExecutionException`. The 429 case is NOT in the trip set — it's handled by the existing `RateLimitedClientSlice` (gate by `Retry-After`).
3. `UpstreamCircuitBreaker` — per-host state holder. Fields: `volatile Instant blockedUntil`, `volatile FibonacciBackoff backoff`, `AtomicLong tripCount`. Methods: `boolean isOpen()`, `void recordSuccess()` (resets backoff + clears `blockedUntil`), `void recordFailure(Throwable / int status)` (advances backoff + sets `blockedUntil`), `Duration timeRemaining()`. No I/O.
4. No HEAD probe yet (deferred to T-P02 which wires it to the actual HTTP client).

**Acceptance criteria**:
- `FibonacciBackoff.next()` returns the exact sequence: 30, 30, 60, 90, 150, 240, 390, 630, 1020, 1650, 2670, 3600 (then stays at 3600).
- `UpstreamCircuitBreaker.isOpen()` returns true iff `Instant.now().isBefore(blockedUntil)`.
- `recordFailure` is a single call that advances the backoff and sets `blockedUntil = now + backoff.next()`.
- `recordSuccess` immediately resets `blockedUntil` to null and the backoff to seed.
- All thread-safety verified by JMH or `ConcurrentLinkedDeque` torture test (1000 threads racing record-success / record-failure).

**Tests**:
- `FibonacciBackoffTest`: 10 sequence asserts; reset assert; cap assert; thread-safety assert (parallel `next()` from 100 threads, only sequence-correct values observed).
- `UpstreamCircuitBreakerTest`: state machine — closed → open on first failure → still open within window → closed on success → reset.

**Verify**:
```bash
mvn test -pl http-client -Dtest=UpstreamCircuitBreakerTest,FibonacciBackoffTest
mvn verify -pl http-client -DskipTests=true
```

---

### T-P02: Wire `UpstreamCircuitBreaker` into the per-host slice chain

**Goal**: Compose `UpstreamCircuitBreaker` into the existing decorator chain at `JettyClientSlices`. Synthesise a 502 (`bad_gateway`) response when the breaker is open. Add daemon HEAD probe at block-expiry.

**Why**: Same as T-P01. Without wiring the primitive is unused.

**Depends on**: T-P01.

**Effort**: M.

**Files to modify**:
- `http-client/src/main/java/com/auto1/pantera/http/client/jetty/JettyClientSlices.java` — wrap each per-host `Slice` with the breaker (outside `RateLimitedClientSlice`).
- `http-client/src/main/java/com/auto1/pantera/http/client/circuitbreaker/CircuitBreakingClientSlice.java` (new) — the `Slice` decorator that checks `breaker.isOpen()` before delegating; on response inspects status; on exception calls `breaker.recordFailure`.
- `http-client/src/test/java/com/auto1/pantera/http/client/circuitbreaker/CircuitBreakingClientSliceTest.java` (new).

**Implementation outline**:
1. `CircuitBreakingClientSlice` constructor takes `Slice delegate`, `String host`, `UpstreamCircuitBreaker breaker`, `Clock clock`, `Executor probeExecutor`.
2. `response()`:
   - If `breaker.isOpen()`: return `CompletableFuture.completedFuture(ResponseBuilder.from(RsStatus.BAD_GATEWAY).header("X-Pantera-Circuit-Open", "true").build())`. Record metric `pantera_circuit_breaker_fastfail_total{host}`.
   - Else: delegate, then in `whenComplete`:
     - On success and `shouldTripOnStatus(status)` returns true: `breaker.recordFailure(status)`. Schedule HEAD probe (see below).
     - On success and status looks healthy: `breaker.recordSuccess()`.
     - On exception and `shouldTripOnException(err)` returns true: `breaker.recordFailure(err)`. Schedule HEAD probe.
3. HEAD probe: when the breaker trips, schedule `Runnable` on `probeExecutor` to fire `delegate.response(HEAD line, ...)` at `breaker.blockedUntil`. If successful, `breaker.recordSuccess()`. Use `ScheduledExecutorService` (single daemon thread, named `pantera-circuit-probe`).
4. In `JettyClientSlices.create(...)`: wrap each per-host slice with `CircuitBreakingClientSlice` *outside* `RateLimitedClientSlice`. Order: client → rate-limit (reactive 429 gate) → circuit breaker (5xx / IO trip) → outbound.
5. Configurable via env vars: `PANTERA_CIRCUIT_BREAKER_ENABLED` (default true), `PANTERA_CIRCUIT_BREAKER_SEED_SECONDS` (default 30), `PANTERA_CIRCUIT_BREAKER_MAX_SECONDS` (default 3600).

**Acceptance criteria**:
- During a synthesised 60 s upstream outage at 100 r/s (toxiproxy or similar), the upstream sees ≤ 5 wire requests (1 initial trigger + up to 4 HEAD probes if outage exceeds 30 s).
- On recovery, the breaker resets and traffic resumes within one probe interval.
- 429 responses do NOT trip the breaker (rate-limit gate handles them).
- `pantera_circuit_breaker_state{host}` gauge: 0=closed, 1=open. Visible in `/metrics`.
- 502 synthetic responses carry header `X-Pantera-Circuit-Open: true` so `BaseCachedProxySlice.handleNonSuccess` and `GroupResolver` can disambiguate from real upstream 502s.

**Tests**:
- `CircuitBreakingClientSliceTest` — unit, mock delegate. Tests: closed → first 503 trips; subsequent calls fast-fail with 502 + X-Pantera-Circuit-Open; HEAD probe fires at deadline; success on probe clears.
- `CircuitBreakingClientSliceIT.java` — integration with `toxiproxy` (https://github.com/Shopify/toxiproxy). Marker test under `itcase` profile. Validates "60 s outage at 100 r/s → ≤ 5 wire requests".

**Verify**:
```bash
mvn test -pl http-client -Dtest='*CircuitBreak*'
mvn verify -P itcase -pl http-client
```

---

### T-P03: Refactor `ProxyCacheWriter.streamThroughAndCommit` to accept empty sidecar/checksum maps

**Goal**: Make `ProxyCacheWriter` consumable by any adapter (npm, pypi, composer, go, docker, etc.), not just Maven. Maven keeps its sidecar map; other adapters pass `Map.of()`.

**Why**: Closes gap G7 by making the streaming-tee primitive reusable across all proxy adapters. Currently only Maven primaries use it.

**Depends on**: none.

**Effort**: M.

**Files to modify**:
- `pantera-core/src/main/java/com/auto1/pantera/http/cache/ProxyCacheWriter.java` — relax assumptions; allow empty sidecar map; allow null upstream-checksum claim.
- `pantera-core/src/test/java/com/auto1/pantera/http/cache/ProxyCacheWriterTest.java` — add test for empty-sidecar case.
- `maven-adapter/src/main/java/com/auto1/pantera/maven/http/CachedProxySlice.java` — no behavioural change; verify it still compiles and tests pass.

**Implementation outline**:
1. Inspect current `streamThroughAndCommit(key, uri, size, body, sidecars, upstreamChecksum, ctx)`. Identify Maven-specific assumptions (presumably: requires non-empty sidecar map, requires upstream .sha1 verification).
2. Change contract: when `sidecars.isEmpty()`, skip verification. When `upstreamChecksum == null`, skip checksum claim verification. The tee + atomic commit must still work.
3. The success path: `Result.Ok<StreamedArtifact>` with `body()` (the tee'd response body Publisher), `verificationOutcome()` (completes immediately with `Verified.NONE` when there's nothing to verify).
4. Add a `Verified` enum / record: `VERIFIED_SHA1`, `VERIFIED_SHA256`, `NONE`, `MISMATCH`. Use it consistently.

**Acceptance criteria**:
- Maven test pack passes unchanged.
- New `ProxyCacheWriterEmptySidecarsTest`: write a small artifact with empty `sidecars`, no `upstreamChecksum`, assert the bytes land in storage byte-for-byte identical to the input.
- The `StreamedArtifact.verificationOutcome()` returned future completes synchronously when `sidecars.isEmpty()`.
- No regression in `maven-adapter` itcase suite.

**Tests**:
- Unit: `ProxyCacheWriterTest` adds `whenSidecarsEmpty_thenSkipsVerification`.
- Maven regression: `maven-adapter/src/test/...` must all pass.

**Verify**:
```bash
mvn test -pl pantera-core -Dtest=ProxyCacheWriterTest
mvn test -pl maven-adapter
```

---

### T-P04: Add streaming cache-write helper in `BaseCachedProxySlice`

**Goal**: Add a protected method `streamingCacheWrite(line, headers, key, store)` to `BaseCachedProxySlice` that calls `ProxyCacheWriter.streamThroughAndCommit` and returns the `Response` with the tee'd body. Replaces the sequential `cacheResponse` for adapters that opt in.

**Why**: Closes gap G7 universally. The Maven primary path's pattern, exposed as a helper for every adapter to call from `fetchAndCache` or a subclass override.

**Depends on**: T-P03.

**Effort**: M.

**Files to modify**:
- `pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java` — add the helper.
- `pantera-core/src/test/java/com/auto1/pantera/http/cache/BaseCachedProxySliceTest.java` — add a test that exercises the helper with a fake adapter.

**Implementation outline**:
1. New protected method:
   ```java
   protected final CompletableFuture<Response> streamingCacheWrite(
       final RequestLine line, final Headers headers, final Key key,
       final CompletableFuture<Void> leaderGate
   ) {
       // 1. Fetch primary body via this.client.response(...)
       // 2. On 200: cacheWriter.streamThroughAndCommit(key, uri, size, body, Map.of(), null, ctx)
       // 3. Wire leaderGate to artifact.verificationOutcome()
       // 4. Return Response wrapping artifact.body()
       // On non-200 or exception: existing handleNonSuccess / mapUpstreamStatus path.
   }
   ```
2. The `cacheWriter` must be constructed in `BaseCachedProxySlice` (move from `CachedProxySlice` constructor). Use `storage.orElseThrow()` — same fail-fast contract as the Maven path.
3. The `client` field is private; expose via existing `client()` accessor.
4. Existing `cacheResponse` is retained as the fallback for adapters that haven't migrated.

**Acceptance criteria**:
- `streamingCacheWrite` correctly tees: a 1 MB blob's first byte arrives at the client TTFB + ~1 ms (not TTFB + body-transfer-time).
- The cache is durably written after `verificationOutcome()` completes; the response body is consumable independently of the storage write.
- On upstream 5xx: returns 502 via `mapUpstreamStatus`; cache is NOT written.
- On exception: returns 502 via existing `tryServeStale`; cache is NOT written.
- Race test: 50 concurrent calls produce 1 upstream call (validated via mock client request counter).

**Tests**:
- Unit: `BaseCachedProxySliceTest.streamingCacheWrite_teesBytesAndCommits` with a fake `client` that emits known bytes and a `cache.exists()` assertion after.
- Race: `BaseCachedProxySliceTest.streamingCacheWrite_singleFlightCollapsesConcurrent` — 50 concurrent callers, mock upstream counts requests = 1.

**Verify**:
```bash
mvn test -pl pantera-core -Dtest=BaseCachedProxySliceTest
```

---

### T-P05: Migrate `npm-adapter/CachedNpmProxySlice` to `streamingCacheWrite`

**Goal**: npm tarball fetches use the streaming tee. Cache hit serves from storage as today.

**Why**: G7 generalisation for the biggest non-Maven adapter.

**Depends on**: T-P04.

**Effort**: M.

**Files to modify**:
- `npm-adapter/src/main/java/com/auto1/pantera/npm/http/CachedNpmProxySlice.java` (or equivalent — verify exact path via `find`)
- `npm-adapter/src/test/java/com/auto1/pantera/npm/http/CachedNpmProxySliceTest.java`

**Implementation outline**:
1. Find the current `fetchAndCache` / equivalent flow in the npm adapter.
2. For tarball paths (`*.tgz`), replace with a `coalesceUpstream` + `streamingCacheWrite` call.
3. Keep the existing manifest (package.json) path on the current code — npm manifests are mutable and have their own caching needs (covered separately in T-P11 if needed).
4. Sidecar/digest handling: npm uses SHA-512 integrity in the manifest, not as separate sidecars. No sidecar fetch needed; `streamingCacheWrite` is called with empty sidecar map.

**Acceptance criteria**:
- Cold-fetch of `lodash@4.17.21.tgz` through the npm proxy: TTFB to client ≤ upstream TTFB + 50 ms.
- Cache hit serves in ≤ 30 ms.
- Existing npm-adapter test pack passes unchanged.
- Cold-bench: a 50-package `npm install` against an empty npm proxy completes in ≤ direct-registry-time + 5 s.

**Tests**:
- Unit: as above.
- itcase: `CachedNpmProxySliceIT.java` — install a small package set, measure wall-clock, assert within bound.

**Verify**:
```bash
mvn test -pl npm-adapter
cd performance && ./run-cold-bench.sh npm  # if perf harness supports npm
```

---

### T-P06: Migrate `pypi-adapter` to `streamingCacheWrite`

**Goal**: PyPI wheel / sdist fetches use the streaming tee.

**Why**: G7 generalisation.

**Depends on**: T-P04.

**Effort**: S.

**Files to modify**:
- `pypi-adapter/src/main/java/com/auto1/pantera/pypi/http/proxy/CachedProxySlice.java` (verify path).
- `pypi-adapter/src/test/...`.

**Implementation outline**:
1. Identical pattern to T-P05. PyPI artifacts are immutable wheels/sdists; no sidecars (PyPI uses inline SHA-256 in the simple index).
2. Simple index page handling is separate (in `preProcess`) and unaffected.

**Acceptance criteria**:
- Cold-fetch of `numpy-2.0.0-cp311-cp311-manylinux_2_17_x86_64.whl` (~15 MB): TTFB ≤ upstream TTFB + 50 ms.
- Existing pypi-adapter tests pass.

**Tests**: unit + itcase mirror of T-P05.

**Verify**:
```bash
mvn test -pl pypi-adapter
```

---

### T-P07: Migrate `composer-adapter` to `streamingCacheWrite`

**Goal**: Same pattern.

**Why**: G7 generalisation.

**Depends on**: T-P04.

**Effort**: S.

**Files**:
- `composer-adapter/src/main/java/com/auto1/pantera/composer/http/proxy/CachedProxySlice.java`.

**Acceptance**: cold-fetch of a known Composer package tarball: TTFB ≤ upstream + 50 ms.

**Verify**:
```bash
mvn test -pl composer-adapter
```

---

### T-P08: Migrate `go-adapter` to `streamingCacheWrite`

**Goal**: Same pattern for Go module proxy.

**Why**: G7 generalisation.

**Depends on**: T-P04.

**Effort**: M (Go's module proxy protocol has `.info`, `.mod`, `.zip` URL shapes; the `.zip` path is the byte path).

**Files**: `go-adapter/src/main/java/com/auto1/pantera/http/CachedProxySlice.java`.

**Acceptance**: cold-fetch of a Go module `.zip`: TTFB ≤ upstream + 50 ms.

**Verify**:
```bash
mvn test -pl go-adapter
```

---

### T-P09: Audit remaining adapters (docker, helm, debian, gem, hex, nuget, rpm, conda, conan, files) for migration

**Goal**: For each remaining adapter, either (a) migrate to `streamingCacheWrite`, or (b) document why the adapter does not benefit (e.g., docker manifest list `Accept`-driven response variation).

**Why**: G7 generalisation completeness. Some adapters may have format-specific reasons to retain the current `cacheResponse` shape.

**Depends on**: T-P04.

**Effort**: L (10 adapters, ~half-day each).

**Files**: each adapter's `CachedProxySlice.java` (or equivalent). The output of this task is either a migration commit per adapter, or a documented-rationale comment in the adapter's class JavaDoc.

**Implementation outline**: for each adapter:
1. Read the adapter's `fetchAndCache` / equivalent.
2. If the upstream returns a single byte stream per request → migrate to `streamingCacheWrite`.
3. If the upstream returns a body that varies by request headers (docker manifest lists, helm chart index) → leave on `cacheResponse`, add a `// G7: retained sequential path because <reason>` comment.

**Acceptance criteria**:
- Every adapter under `*-adapter/` is either migrated or has a documented exception.
- Aggregate "adapters using streaming tee" metric: ≥ 8/12.

**Tests**: per-adapter regression as in T-P05–T-P08.

**Verify**:
```bash
mvn test  # all adapters
```

---

### T-P10: Add `If-None-Match` / `If-Modified-Since` to `MetadataCache.load`

**Goal**: `maven-metadata.xml` and similar mutable-index refreshes send conditional headers; 304 → bump `lastVerified` only.

**Why**: Closes gap G5/G8. Maven Central serves both validators; we ignore them.

**Depends on**: none.

**Effort**: M.

**Files to modify**:
- `maven-adapter/src/main/java/com/auto1/pantera/maven/http/MetadataCache.java` — store ETag + Last-Modified per key; loader signature change.
- `pantera-core/src/main/java/com/auto1/pantera/http/cache/ConditionalCache.java` (new) — generic primitive usable beyond Maven.
- `maven-adapter/src/test/java/com/auto1/pantera/maven/http/MetadataCacheConditionalTest.java` (new).

**Implementation outline**:
1. `ConditionalCache<K>`: stores `(K key, byte[] body, String etag, Instant lastModified, Instant lastVerified, long size)`. Persistence: same `Storage` abstraction as binary cache, with a sidecar `.meta.json` per key holding the validators (similar to `CachedArtifactMetadataStore`).
2. `MetadataCache.load(key, fetcher)` becomes `MetadataCache.load(key, fetcher)` where `fetcher` now takes the stored ETag + Last-Modified as conditional headers and returns one of: `(body, etag, lastModified)` on 200, `unchanged` on 304, `notFound` on 404.
3. `CachedProxySlice.handleMetadata` plumbs the validators into the upstream call.
4. On 304: bump `lastVerified` to `Instant.now()`. Return cached body. No blob rewrite.
5. On 200: replace body + validators + `lastVerified`.
6. On 404: clear the entry; surface as 404 to client.

**Acceptance criteria**:
- A second metadata refresh after the soft TTL elapses sends `If-None-Match` + `If-Modified-Since` headers (verified via mock upstream request capture).
- Mock upstream returning 304: cache entry's `lastVerified` advances, blob is byte-for-byte identical (sameness verified via `assertSame` or `Arrays.equals`).
- Mock upstream returning 200 with new content: cache replaces.
- Reproduction cold walk: ≥ 5 of the 10–15 metadata refreshes hit 304 (verified via mock-upstream-aware itcase or by reading the `recordOutboundMetric` histograms).

**Tests**:
- Unit: `MetadataCacheConditionalTest` — 200, 304, 404 paths.
- itcase: `MetadataCacheConditionalIT.java` — real Maven Central or local Nexus mirror.

**Verify**:
```bash
mvn test -pl maven-adapter -Dtest=MetadataCacheConditionalTest
mvn verify -P itcase -pl maven-adapter -Dit.test=MetadataCacheConditionalIT
```

---

### T-P11: Add stale-while-revalidate to `MetadataCache`

**Goal**: When the soft TTL has elapsed (default 30 s) but the hard TTL has not (default 2 h), serve the cached body to the client immediately AND fire a background refresh (single-flighted on the metadata key).

**Why**: Closes gap G8. Hides upstream metadata refresh latency from foreground requests.

**Depends on**: T-P10.

**Effort**: M.

**Files to modify**:
- `maven-adapter/src/main/java/com/auto1/pantera/maven/http/MetadataCache.java`.
- `maven-adapter/src/test/...`.

**Implementation outline**:
1. Add `Duration softTtl` and `Duration hardTtl` to `MetadataCache` constructor. Defaults: 30 s soft, 2 h hard. Configurable via `ProxyCacheConfig`.
2. On `load(key, fetcher)`:
   - If `Instant.now().minus(softTtl).isBefore(lastVerified)`: serve cached. No refresh.
   - If `lastVerified` is between `softTtl` and `hardTtl` ago: serve cached + fire `singleFlight.load(key, fetcher)` and discard the result (the refresh updates the cache; the foreground response was already sent).
   - If `lastVerified` is older than `hardTtl`: block on the fetcher (current behaviour for cold misses).
3. The single-flight is the existing `SingleFlight` instance scoped per `MetadataCache` (one per repo). Key: the cache key.

**Acceptance criteria**:
- After the first miss, every subsequent metadata request within 30 s serves in ≤ 5 ms (no upstream call).
- Between 30 s and 2 h: serves in ≤ 5 ms AND triggers a background refresh visible in upstream metrics.
- After 2 h: blocks on upstream.
- No more than one background refresh per `(repo, metadata-key)` per 30 s window — verified by single-flight test.

**Tests**:
- Unit: time-travel test using a `Clock` injection.
- itcase: end-to-end with a controllable upstream.

**Verify**:
```bash
mvn test -pl maven-adapter -Dtest='MetadataCacheSwr*'
```

---

### T-P12: Wire `RepoBulkhead` into `BaseCachedProxySlice.response`

**Goal**: Every direct slice call passes through the per-repo bulkhead semaphore. Currently only the group resolver path is gated.

**Why**: Closes gap G12 layer 5. Prevents one misbehaving repo from saturating the global request queue.

**Depends on**: none.

**Effort**: M.

**Files to modify**:
- `pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java` — wrap `response()` body in a bulkhead acquire/release.
- `pantera-main/src/main/java/com/auto1/pantera/RepositorySlices.java` — ensure every proxy repo (not just group) gets a `RepoBulkhead` instance.
- `pantera-core/src/test/...` — saturation test.

**Implementation outline**:
1. Add `RepoBulkhead bulkhead` as a constructor parameter to `BaseCachedProxySlice`. (Add the corresponding overload that accepts a default unlimited bulkhead for tests.)
2. In `response()`: call `bulkhead.run(() -> /* existing body wrapped in a Supplier<CompletionStage<Result<Response>>> */)`. On `Fault.Overload`: return 503 with `Retry-After: 1`.
3. In `RepositorySlices.java`: extend `getOrCreateBulkhead` to be called for proxy repos. Default `maxConcurrent=256`, `maxQueueDepth=64`. Configurable via repo YAML.
4. Adjust `Result<Response>` ↔ `Response` boundaries.

**Acceptance criteria**:
- A burst of 1000 concurrent requests to a single repo with `maxConcurrent=256` results in 256 in-flight + 64 queued + 680 immediate 503s.
- Other repos remain unaffected by the saturating one.
- Metric `pantera_bulkhead_overflow_total{repo}` increments on 503.

**Tests**:
- Unit: `BaseCachedProxySliceBulkheadTest` — burst-traffic test.
- itcase: two-repo isolation test.

**Verify**:
```bash
mvn test -pl pantera-core -Dtest='*BulkheadTest'
mvn verify -P itcase -pl pantera-main -Dit.test='*BulkheadIT'
```

---

### T-P13: Single-flight the cooldown evaluate call

**Goal**: N concurrent cache-miss callers for the same `(artifact, version)` produce one cooldown evaluate, not N.

**Why**: Closes cooldown-redesign §5.1. Small win in absolute terms; eliminates a thundering-herd DB lookup on cold L1.

**Depends on**: none.

**Effort**: S.

**Files to modify**:
- `pantera-main/src/main/java/com/auto1/pantera/cooldown/JdbcCooldownService.java`.

**Implementation outline**:
1. Add a `SingleFlight<CooldownKey, CooldownResult>` instance, scoped per `JdbcCooldownService`. Inflight TTL: 30 s. Max: 10000.
2. `evaluate(request, inspector)` becomes: `singleFlight.load(cooldownKey(request), () -> /* existing evaluate body */)`.
3. `cooldownKey(request)` is a stable record of `(repoType, repoName, artifact, version)`.

**Acceptance criteria**:
- 100 concurrent evaluates on a cold cooldown L1 produce 1 DB call (verified via mock or counter).
- Existing behaviour preserved for sequential calls.

**Tests**:
- Unit: `JdbcCooldownServiceSingleFlightTest`.

**Verify**:
```bash
mvn test -pl pantera-main -Dtest=JdbcCooldownServiceSingleFlightTest
```

---

### T-P14: Cold-bench perf gate — enforce 20 s target

**Goal**: Extend the M6 perf-gate workflow to fail the build if the cold-bench reproduction exceeds 20 s (no throttling) or 25 s (with throttling).

**Why**: Locks in the Candidate-1 target. Any future regression surfaces in CI, not production.

**Depends on**: T-P02, T-P04, T-P10, T-P11 (the perf-moving tasks must land first or the gate will fail immediately).

**Effort**: M.

**Files to modify**:
- `.github/workflows/amplification.yml` (per recent commit `e985769f2` — the existing perf-gate workflow).
- `performance/run-cold-bench.sh` (verify the harness emits the metric in a parseable form).

**Implementation outline**:
1. Add a step that runs `./performance/run-cold-bench.sh sonar-maven-plugin -i 5` and parses the median wall-clock.
2. Fail the build if median > 20 s.
3. Capture the artifact `cold-bench-5x.md` to the workflow run.

**Acceptance criteria**:
- A PR that regresses the cold-bench above 20 s fails CI.
- A PR that stays ≤ 20 s passes.
- The harness produces a reproducible result (variance < 2 s across 5 runs).

**Verify**:
```bash
gh workflow run amplification.yml --ref task/p14-perf-gate
```

---

## Phase 2 — Security hardening (S1–S8)

These tasks bring Pantera to industry-standard security posture for an artifact registry. Reference: Artifactory's security model + Nexus's Repository Firewall design pattern + OWASP top-10 for proxy services.

---

### T-S01: Audit and harden URL path traversal across all adapters

**Goal**: Every adapter rejects requests whose decoded URL path contains `..`, `%2e%2e`, raw NULs, or path-segment shenanigans that could escape the per-repo storage prefix.

**Why**: Path traversal is the canonical OWASP top-10 vulnerability for proxy / file-serving services. A single adapter that fails to canonicalise correctly compromises the whole system.

**Depends on**: none.

**Effort**: L (12+ adapters, one inspection each).

**Files**:
- Every `*-adapter/src/main/.../CachedProxySlice.java` and any slice that constructs a `Key` from a request path.
- New: `pantera-core/src/main/java/com/auto1/pantera/http/security/PathTraversalGuard.java` — shared canonicaliser + rejector.

**Implementation outline**:
1. `PathTraversalGuard.canonicalise(String rawPath) → Optional<String>`: returns `Optional.empty()` if the path is unsafe (contains `..`, double-encoded sequences, NUL, absolute paths after canonicalisation, paths that escape the implicit root). Returns canonical form otherwise.
2. Every `BaseCachedProxySlice.response()` already calls `KeyFromPath` — wrap that with the guard. Reject unsafe paths with 400.
3. Per-adapter audit: confirm each adapter calls `BaseCachedProxySlice.response` (and thus inherits the guard) and does not have its own path-construction shortcut.

**Acceptance criteria**:
- Requests like `GET /../../../../etc/passwd` return 400 (not 200, not 500, not directory listing).
- `GET /a/%2e%2e/b` returns 400.
- `GET /a%00b` returns 400.
- Existing legitimate paths still work.
- Test vector list: 20+ malicious path patterns from OWASP and observed-in-wild attempts. All return 400.

**Tests**:
- Unit: `PathTraversalGuardTest` with the 20-vector pattern list.
- Integration: `PathTraversalIT.java` — fires each vector at a running Pantera; asserts 400.

**Verify**:
```bash
mvn test -pl pantera-core -Dtest=PathTraversalGuardTest
```

---

### T-S02: Strip outbound `Authorization` headers; never log auth headers

**Goal**: Inbound `Authorization` headers (from Pantera clients) are NEVER forwarded to upstream. Log sanitiser must redact them in all log statements.

**Why**: Credential leakage is a P0 security issue. Pantera proxies multiple ecosystems; an inbound user's Bearer token must not end up in a log line or at Maven Central.

**Depends on**: none.

**Effort**: S.

**Files**:
- `pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java` — verify `upstreamHeaders()` strips `Authorization` (line ~390 currently). Already correct per the existing code, but add an explicit assertion test.
- `http-client/src/main/java/com/auto1/pantera/http/log/LogSanitizer.java` (existing; verify `Authorization`, `Cookie`, `X-API-Key`, `X-Auth-Token` are in the redact list).
- New: `pantera-core/src/test/java/com/auto1/pantera/http/cache/BaseCachedProxySliceAuthorizationStrippingTest.java`.

**Implementation outline**:
1. Test: send a request with `Authorization: Bearer secret-token-xyz`. Capture the upstream client's `headers`. Assert no `Authorization` header is in the forwarded set.
2. Test: capture log output (using `ListAppender` or similar). Assert no log line contains `secret-token-xyz`.
3. Audit `LogSanitizer` for completeness: every sensitive header name in the redact list.

**Acceptance criteria**:
- `Authorization`, `Cookie`, `Proxy-Authorization`, `X-API-Key`, `X-Auth-Token` headers are stripped from every outbound request.
- These header names + their values never appear in any log line, even at DEBUG.
- Test exists for both behaviours.

**Tests**:
- Unit: as outlined.
- Static analysis: a CI grep for `Authorization` / `Bearer` / `Cookie` in log statements that don't go through `LogSanitizer`.

**Verify**:
```bash
mvn test -pl pantera-core -Dtest='*AuthorizationStripping*'
grep -rn 'Authorization' --include='*.java' pantera-core/src/main http-client/src/main | grep -v LogSanitizer | grep -v Test
```

---

### T-S03: Verify PGP signature for Maven artifacts (`*.asc`)

**Goal**: When Maven Central serves a `.asc` sidecar for a cached primary, store it. On configurable per-repo policy, verify the signature against an admin-trusted PGP keyring before serving the primary. On verification failure, return 403 + audit event.

**Why**: Industry-standard for Maven (Sonatype recommends GPG-signed artifacts; Maven Central rejects unsigned uploads for the Central tier). Pantera proxies these signatures but does not verify them — meaning a man-in-the-middle could swap an unsigned artifact and downstream consumers would have no way to detect it.

**Depends on**: T-P03 (sidecar handling).

**Effort**: L.

**Files**:
- `maven-adapter/src/main/java/com/auto1/pantera/maven/security/PgpVerifier.java` (new).
- `maven-adapter/src/main/java/com/auto1/pantera/maven/security/KeyringStore.java` (new).
- `maven-adapter/src/main/java/com/auto1/pantera/maven/http/CachedProxySlice.java` — wire optional verification.
- `pantera-main/src/main/resources/db/migration/V<n>__pgp_keyring.sql` — store admin-uploaded trusted keys.

**Implementation outline**:
1. Use BouncyCastle (`org.bouncycastle:bcpg-jdk18on`) for PGP. Add to `maven-adapter` POM.
2. Admin uploads ASCII-armored public keys via a new REST endpoint `POST /api/v1/admin/pgp/keys`. Persist in `pgp_keyring` table.
3. Per-repo config flag `verifyPgp: true|false` (default false for backwards compat).
4. On primary fetch with `.asc` sidecar present and verifyPgp=true: verify signature against the keyring. On verification failure: 403 to the client, audit event `pgp_verification_failed`, do NOT cache the primary.
5. Cache verified primaries normally.

**Acceptance criteria**:
- Verified artifact: 200, cached, normal serve thereafter.
- Unverified (no matching key): 403, not cached, audit event recorded.
- Tampered signature: 403, not cached, audit event recorded.
- The admin REST endpoint requires `pantera:admin` permission.
- Existing repos without `verifyPgp: true` are unaffected.

**Tests**:
- Unit: `PgpVerifierTest` with known good / bad / tampered fixtures.
- Integration: `MavenPgpVerificationIT.java` against a controllable upstream.

**Verify**:
```bash
mvn test -pl maven-adapter -Dtest=PgpVerifierTest
mvn verify -P itcase -pl maven-adapter -Dit.test=MavenPgpVerificationIT
```

---

### T-S04: Audit log for admin operations

**Goal**: Every admin action (cooldown unblock, cache clear, repo create/delete/modify, user/role changes, PGP keyring changes) emits a structured audit event persisted to PostgreSQL.

**Why**: Industry-standard for compliance (SOC2, ISO 27001). Required for incident forensics. Currently scattered across the codebase as ad-hoc logging.

**Depends on**: none.

**Effort**: M.

**Files**:
- `pantera-core/src/main/java/com/auto1/pantera/audit/AuditEvent.java` (new).
- `pantera-core/src/main/java/com/auto1/pantera/audit/AuditService.java` (new).
- `pantera-main/src/main/resources/db/migration/V<n>__audit_log.sql`.
- Every admin REST handler: wrap the mutation with an `auditService.record(...)` call.

**Implementation outline**:
1. `AuditEvent`: record with `id`, `timestamp`, `actor` (username), `action` (enum), `target` (repo / user / artifact), `details` (JSON), `success` (boolean), `ip_address`.
2. `AuditService.record(event)`: writes to `audit_log` table. Same `DbConsumer` batching pattern as artifact events.
3. REST endpoint `GET /api/v1/admin/audit` — paged list, filterable by action/actor/target/timerange. Requires `pantera:admin:read` permission.
4. Retention: 7 years default (compliance). Automatic archival after 1 year (move to S3 cold storage if configured).

**Acceptance criteria**:
- Every admin mutation produces exactly one audit event.
- Failed mutations (e.g., 403 unauthorized) produce a `success=false` audit event.
- Audit events cannot be modified or deleted by any user (table is `INSERT`-only at the DB level — enforce with a `BEFORE UPDATE / BEFORE DELETE` trigger).
- The `GET /api/v1/admin/audit` endpoint requires the right permission.

**Tests**:
- Unit: `AuditServiceTest`.
- Integration: `AuditIT.java` — exercise each admin endpoint, assert one audit event per action.

**Verify**:
```bash
mvn test -pl pantera-main -Dtest=AuditServiceTest
mvn verify -P itcase -pl pantera-main -Dit.test=AuditIT
```

---

### T-S05: Security headers on every Pantera HTTP response

**Goal**: Every response from Pantera carries CSP, HSTS, X-Content-Type-Options, X-Frame-Options, Referrer-Policy, Permissions-Policy headers.

**Why**: Industry-standard hardening for any web-facing service. The UI is browser-served; even API endpoints can be embedded.

**Depends on**: none.

**Effort**: S.

**Files**:
- `vertx-server/src/main/java/com/auto1/pantera/http/server/SecurityHeadersSlice.java` (new).
- `vertx-server/src/main/java/com/auto1/pantera/http/server/...` — wire as the outermost decorator in the server chain.

**Implementation outline**:
1. `SecurityHeadersSlice` decorator: adds the headers to every response.
2. Defaults (override per route if needed):
   - `Strict-Transport-Security: max-age=31536000; includeSubDomains`
   - `X-Content-Type-Options: nosniff`
   - `X-Frame-Options: DENY` (UI: SAMEORIGIN)
   - `Referrer-Policy: strict-origin-when-cross-origin`
   - `Content-Security-Policy: default-src 'self'; ...` (tuned for the UI)
   - `Permissions-Policy: geolocation=(), microphone=(), camera=()`

**Acceptance criteria**:
- A `curl -I` to any endpoint shows all six headers.
- The UI loads correctly (no CSP violations in browser console).
- The API endpoints serve correctly.
- TLS-only deployments enforce HSTS; HTTP-only dev deployments skip HSTS.

**Tests**:
- Unit: `SecurityHeadersSliceTest`.
- itcase: `SecurityHeadersIT.java` — assert headers across every endpoint family.

**Verify**:
```bash
mvn test -pl vertx-server -Dtest=SecurityHeadersSliceTest
```

---

### T-S06: Tighten TLS configuration (TLS 1.2+ only, modern ciphers)

**Goal**: Both the server (inbound) and the Jetty client (outbound) enforce TLS 1.2 minimum; preference order matches Mozilla "intermediate" config; weak ciphers (RC4, 3DES, NULL) disabled.

**Why**: Industry-standard hardening. Many compliance frameworks require TLS 1.2+.

**Depends on**: none.

**Effort**: S.

**Files**:
- `vertx-server/.../TlsConfig.java` (verify or create).
- `http-client/.../JettyClientSlices.java` — `SslContextFactory` configuration.

**Implementation outline**:
1. Server SSL options: `setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"})`. Cipher suites per Mozilla intermediate.
2. Client `SslContextFactory.Client`: same. Disable hostname verification opt-out flags.
3. Document the choice in `docs/security/tls.md`.

**Acceptance criteria**:
- `openssl s_client -connect localhost:8080 -tls1` fails.
- `nmap --script ssl-enum-ciphers -p 8080 localhost` reports no weak ciphers.
- Outbound TLS handshake to Maven Central succeeds (Cloudflare supports TLS 1.3).

**Tests**:
- itcase: assert handshake behaviour.

**Verify**:
```bash
mvn verify -P itcase -pl vertx-server -Dit.test='*TlsConfigIT*'
```

---

### T-S07: Anonymous access controls per repo

**Goal**: Each repo configuration can declare `anonymousRead: true|false`, `anonymousWrite: true|false`. Anonymous (unauthenticated) requests are rejected with 401 if the repo disallows it.

**Why**: Industry-standard (Artifactory, Nexus both expose this). Required for internal-only repositories.

**Depends on**: none.

**Effort**: M.

**Files**:
- `pantera-main/src/main/resources/schema/repository.yml` (if schema file exists) — add fields.
- `pantera-core/src/main/java/com/auto1/pantera/security/AnonymousAccessSlice.java` (new).
- Wire into `RepositorySlices` as a decorator outside auth.

**Implementation outline**:
1. Repository YAML accepts `anonymousRead: false` (default true for proxies; false for hosted).
2. `AnonymousAccessSlice` checks: if request has no authenticated principal AND the repo's `anonymousRead` is false AND the method is read-like → return 401 with `WWW-Authenticate: Basic realm="pantera"`.
3. Same for write.

**Acceptance criteria**:
- A read against a private repo without auth: 401.
- A read against a public repo without auth: 200.
- A write against any repo without auth: 401 (write defaults to disabled regardless of read setting).
- Existing repos default to "anonymousRead: true" unless explicitly configured otherwise (backwards compat).

**Tests**:
- itcase: `AnonymousAccessIT.java` — public/private read/write matrix.

**Verify**:
```bash
mvn verify -P itcase -pl pantera-main -Dit.test=AnonymousAccessIT
```

---

### T-S08: Optional malware / CVE scan via OSV-Scanner integration

**Goal**: On successful cache write of a primary, optionally fire an async scan against the OSV.dev (Open Source Vulnerabilities) database. Tag the artifact in DB with discovered CVEs. Surface the CVEs via a new `GET /api/v1/artifacts/{key}/vulnerabilities` endpoint.

**Why**: Industry-standard (Artifactory Xray, Nexus IQ). Asynchronous and opt-in to avoid hot-path latency.

**Depends on**: none.

**Effort**: L.

**Files**:
- `pantera-main/src/main/java/com/auto1/pantera/security/VulnerabilityScanner.java` (new).
- `pantera-main/src/main/java/com/auto1/pantera/security/OsvDevClient.java` (new).
- `pantera-main/src/main/resources/db/migration/V<n>__artifact_vulnerabilities.sql`.

**Implementation outline**:
1. Hook into `onCacheWrite` callback (existing `CacheWriteCallbackRegistry`). On every primary write, push a task to a `VulnerabilityScanQueue` (bounded, 100 in-flight max).
2. `OsvDevClient`: queries `https://api.osv.dev/v1/query` with the package's purl (purl-spec.org). Parses the response into CVE records.
3. Persist results in `artifact_vulnerabilities` table.
4. New REST endpoint serves the joined view.
5. Operator can opt-out per repo via `scanForVulnerabilities: false`.

**Acceptance criteria**:
- Cache-write hot-path latency unchanged (scan is async).
- Known vulnerable artifact (e.g., `log4j-core:2.14.1`): scan completes, CVE-2021-44228 recorded.
- API endpoint returns the CVE list.
- Failed scans are retried with exponential backoff; after 5 failures, mark as `scan_failed=true`.

**Tests**:
- Unit: `OsvDevClientTest` with mocked HTTP.
- itcase: `VulnerabilityScannerIT.java` against the public OSV.dev API.

**Verify**:
```bash
mvn test -pl pantera-main -Dtest=OsvDevClientTest
```

---

## Phase 3 — Observability (O1–O4)

These tasks close the loop: with perf and security work in flight, operators need to see the new metrics.

---

### T-O01: Grafana dashboard for upstream circuit breaker

**Goal**: Pre-built Grafana JSON dashboard showing per-host circuit breaker state, trip frequency, recovery time, current backoff value.

**Why**: Operators must be able to see the circuit breaker working without writing PromQL.

**Depends on**: T-P02.

**Effort**: S.

**Files**:
- `pantera-main/src/main/resources/grafana/upstream-circuit-breaker.json` (new).
- `docs/observability/dashboards.md` — add reference.

**Acceptance criteria**:
- Dashboard imports cleanly into Grafana 10+.
- Panels: trip count per host (last 24 h), current state per host, backoff value when open, time-since-last-trip.

**Verify**: import the JSON; visual check.

---

### T-O02: Alert rules for the new metrics

**Goal**: Prometheus alert rules for: circuit breaker open > 5 minutes, upstream 429 rate > 0 sustained, bulkhead overflow > 0 sustained, conditional-GET hit rate < 70%.

**Why**: Industry-standard alerting maturity.

**Depends on**: T-P02, T-P10, T-P12.

**Effort**: S.

**Files**:
- `pantera-main/src/main/resources/prometheus/alert-rules.yml` (extend existing).

**Acceptance criteria**:
- `promtool check rules` passes.
- Alerts fire in a synthetic test (use the M6 perf-gate workflow to trigger).

**Verify**:
```bash
promtool check rules pantera-main/src/main/resources/prometheus/alert-rules.yml
```

---

### T-O03: Documented runbook for each new alert

**Goal**: For each new alert in T-O02, a runbook entry in `docs/runbooks/` describes: what the alert means, how to confirm, how to mitigate.

**Why**: Industry-standard ops practice. Reduces MTTR.

**Depends on**: T-O02.

**Effort**: S.

**Files**:
- `docs/runbooks/upstream-circuit-breaker-open.md`
- `docs/runbooks/upstream-429-sustained.md`
- `docs/runbooks/bulkhead-overflow.md`
- `docs/runbooks/low-conditional-get-hit-rate.md`

**Acceptance criteria**:
- Each runbook ≤ 1 page; lists symptoms, confirmation queries (PromQL), mitigations.
- Linked from the corresponding alert annotation.

---

### T-O04: Phase histogram dashboard (M6 extension)

**Goal**: Extend the existing M6 perf-gate dashboard to include the `proxy_phase_duration_seconds` histogram broken out by phase + repo. Operators can see exactly which phase dominates cold-miss latency.

**Why**: Per the PLAN.md recommendation: this dashboard is the first thing to read before further perf work.

**Depends on**: none (data already produced by `recordProxyPhase`).

**Effort**: S.

**Files**:
- `pantera-main/src/main/resources/grafana/proxy-phase-latency.json` (new or extend existing).

**Acceptance criteria**:
- Panel showing stacked p99 of each phase per repo.
- Cold-bench reproduction can be visually attributed to the dominant phase.

---

## Sequencing and dependencies

```
Phase 1 (parallel where possible):
  T-P01 ─┐
  T-P02 ─┤  (T-P02 depends on T-P01)
  T-P03 ─┤
  T-P04 ─┤  (depends on T-P03)
  T-P05 ─┼─ T-P09 (depend on T-P04, parallel after)
  T-P06 ─┤
  T-P07 ─┤
  T-P08 ─┘
  T-P10 ─── T-P11 (P11 depends on P10)
  T-P12 (independent)
  T-P13 (independent)
  T-P14 (after all perf-moving tasks)

Phase 2 (parallel, all independent of Phase 1 except T-S03):
  T-S01 (audit; can run in parallel with everything)
  T-S02
  T-S03 (depends on T-P03)
  T-S04
  T-S05
  T-S06
  T-S07
  T-S08

Phase 3 (after Phase 1):
  T-O01 (depends on T-P02)
  T-O02 (depends on T-P02, T-P10, T-P12)
  T-O03 (depends on T-O02)
  T-O04 (independent)
```

## Definition of done for the program

The full program is done when:

1. The cold-bench reproduction `mvn dependency:resolve -Dartifact=org.codehaus.mojo:sonar-maven-plugin:4.0.0.4121 -U` against a clean state completes in ≤ 15 s (without throttling) and ≤ 20 s (with throttling), measured as the median of 5 runs.
2. All Phase 1 tasks (P1–P14) have shipped, with PRs merged to `master` and the perf-gate CI green.
3. All Phase 2 tasks (S1–S8) have shipped. The OSV-Scanner integration (T-S08) may ship in a follow-up if the OSV.dev quota or schema requirements don't fit.
4. Phase 3 tasks (O1–O4) have shipped, dashboards imported into staging, alerts firing correctly on synthetic triggers.
5. The `analysis/reference/gap-analysis.md` is updated: every gap classified as "primary" or "broken" is now "aligned" or has a documented `divergent-by-design` rationale.

---

## Per-task PR template

```markdown
## Task: T-Pxx <title>

### Goal
<one sentence from the task body>

### Acceptance criteria (from IMPLEMENTATION.md)
- [ ] criterion 1
- [ ] criterion 2
- [ ] ...

### Perf delta (perf tasks only)
- Baseline: <wall-clock from prior commit>
- After:    <wall-clock from this PR>
- Delta:    <negative number — improvement>

### Tests added
- <file path>:<test method names>

### Verify command
\`\`\`
<command from the task body>
\`\`\`
```

---

## Notes for agents

- **Before starting a task**: re-read the task body. Re-read the canonical-architecture.md section that the gap-analysis.md says this task closes. Confirm the implementation outline still matches the codebase (Pantera moves fast — file paths can drift).
- **If a task's implementation outline is wrong**: open a PR with the task body update first; do not silently implement a different approach.
- **If a test fails for reasons unrelated to the task**: open a separate bug-fix PR; do not bundle.
- **If a perf-moving task does not actually move the perf number**: stop, escalate, re-read the dashboard. Do not paper over with more changes.
- **If you need to read `.env*` to understand a config**: don't. Find the values in `pantera.yml`, `docker-compose.yml`, or ask.
- **If a Maven Central rate-limit error blocks an itcase run**: pause; switch to a mock-upstream variant; flag for the human reviewer to coordinate with `mavencentral@sonatype.com`.
- **If a hook fails on commit**: fix the underlying issue; do not `--no-verify`. Do not amend the hook-rejected commit; create a new one.
