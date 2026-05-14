# Pantera 2.2.0 — three candidate plans

Written 2026-05-14. Builds on `analysis/reference/canonical-architecture.md`, `gap-analysis.md`, and `cooldown-redesign.md`. The user has been burned by previous plans that derived from "reasonable-sounding" fixes without a model. This document grounds every latency claim in arithmetic and spreads confidence intervals honestly.

## Reproduction baseline (the dependent variable)

`mvn dependency:resolve -Dartifact=org.codehaus.mojo:sonar-maven-plugin:4.0.0.4121 -U` against a clean `~/.m2`, clean `maven_proxy` disk, purged `artifacts` DB, restarted Pantera:

| Path | Cold wall-clock |
|---|---|
| Direct Maven Central (Cloudflare) | **9.6 s** (per CHANGELOG line 4-7) |
| Through Pantera `maven_group` | **38–55 s** (current 2.2.0 HEAD, with Maven Central rate-limiting the workstation IP) |

The gap is **~28–45 s**. We want to drive this to **2–7 s** (matching the "+2 s overhead vs direct" target stated in CHANGELOG line 4).

## Performance model

A typical `dependency:resolve` for the sonar-maven-plugin tree fetches ~150 resources: ~50 primaries (`.pom` + `.jar`), ~50 `.sha1` sidecars (Pantera fetches these eagerly alongside primaries via `streamThroughAndCommit`), ~30 `.module` Gradle metadata (optional, 404s for some), and ~10–15 `maven-metadata.xml` files.

Per-resource latency budget against Cloudflare HIT (from `systems/maven-central.md` §5):

| Resource | TTFB | Body | Total |
|---|---|---|---|
| `.pom` (~10 KB) | 85 ms | <5 ms | ~90 ms |
| `.jar` (~1 MB median) | 60 ms | 70 ms | ~130 ms |
| `.sha1` | 78 ms | <1 ms | ~78 ms |
| `maven-metadata.xml` | 62 ms | <5 ms | ~65 ms |

With HTTP/2 multiplexing on one TCP connection and mvn's 5-worker concurrency, the direct wall-clock at ~150 resources / 5 workers × ~95 ms median ≈ **2.9 s** of pure RTT. The observed 9.6 s is ~3× that, consistent with parser / verification / disk-write overhead on the mvn side. This is our **floor**.

Through Pantera, every resource adds:

| Source of overhead | Per-resource (ms) | Resources affected | Total overhead (s) |
|---|---|---|---|
| Non-Maven write-then-read (G1/G7) | 80–200 | ~0 for maven *primaries* (already teed); 75 non-primary maven resources DO hit this | 6–15 |
| Eager `.sha1` sidecar fetch alongside primary (G7 sub-issue) | +1 upstream GET per primary, partially amortised by H2 multiplexing | 50 primaries | 1–3 |
| Unconditional `maven-metadata.xml` refresh (G5/G8) | 60–90 each | 10–15 | 0.6–1.4 |
| Cooldown evaluate on cache miss (cold L1+L2 first time) | 5–20 each | 150 cache misses | 0.8–3 |
| No circuit breaker — keep firing during 429 storm (G6/G12) | catastrophic; adds Maven-Central-imposed backoff | repo-wide | 10–25 (when triggered) |
| RepoBulkhead not wired on slice path — no per-repo queueing | 0 in happy path; head-of-line stalls under burst | up to all | 0–5 |

Sum without throttling: **~9–22 s** of overhead. With the 13 s direct baseline that gives **22–35 s** through Pantera, which matches the lower end of the observed range (38–55 s) once Maven Central's escalating throttling adds the remaining 13–20 s.

The dominant levers are:
1. **G7 generalisation (write-then-read → tee)** — explains 6–15 s
2. **G6 (circuit breaker)** — explains the 10–25 s throttling tail
3. **G5/G8 (conditional metadata)** — 0.6–1.4 s
4. **G7 sub-issue (eager .sha1 batching)** — 1–3 s, partially fixable via HTTP/2 multiplexing already in flight

Items 1 and 2 are independent; item 3 is small but high-confidence. The remaining minor levers (cooldown microcaching, bulkhead wiring) are <1 s each.

This model is the basis for every latency target below. If you don't believe the model, the candidate targets are wrong by a corresponding amount.

---

## Candidate 1 — Close the gap, preserve the structure

**Scope**: surgical fixes for G1/G7, G5/G8, G6. Keep `BaseCachedProxySlice`, the slice/storage abstraction, the cooldown wiring, and the module structure. Add components where required.

**Approach**:
1. **G6 — circuit breaker** (`http-client/.../UpstreamCircuitBreaker.java`, new). Wrap `RateLimitedClientSlice` (or insert above it). Trip on single 5xx / IOException / TLS-handshake-failure / connection-refused. Fibonacci backoff seeded at 30 s, capped at 1 hour. Background HEAD probe at every block-expiry instant. Per-host gauge `pantera_circuit_breaker_state{host}` for observability. Fail-fast `RemoteBlockedException` when tripped — surfaces as 502 to clients (via existing `mapUpstreamStatus`). Skip negative-cache writes while tripped.
2. **G7 generalisation — `streamThroughAndCommit` as the default cache-write path**. Refactor `BaseCachedProxySlice.cacheResponse` to call `ProxyCacheWriter.streamThroughAndCommit` (with empty sidecar map for non-Maven adapters). Every adapter inherits the streaming tee. Maven keeps its existing sidecar map; npm/pypi/composer/go get the same primitive with `Map.of()`. The `signalToResponse(SUCCESS, ...)` re-read path becomes the fallback for the rare case `streamThroughAndCommit` returns `Result.Err` (temp-file creation failure).
3. **G5/G8 — conditional `MetadataCache`**. Add ETag + Last-Modified persistence to `MetadataCache` and the `loader` shape. On TTL expiry, fetch with `If-None-Match` + `If-Modified-Since`. On 304, return the cached bytes with `lastVerified` bumped. Wire stale-while-revalidate: serve the cached XML immediately while a background fetch refreshes (single-flighted on the metadata path key).
4. **G12 layer 5 — wire `RepoBulkhead` into proxy slice path**. Currently only group resolver uses it. Move the `getOrCreateBulkhead(repoName)` call into the slice's `response` method so every direct slice call passes through the per-repo semaphore. Tune `maxConcurrent` per repo (default 256 for proxy repos; tighter for ones with low-budget upstreams).
5. **Cooldown §5.1** — single-flight the cooldown evaluate. One `SingleFlight<CooldownKey, CooldownResult>` instance per `JdbcCooldownService`. Three lines of code; high-confidence small win.

**Workstream breakdown**:

```
W1 — Circuit breaker primitive (new code, no migration)
   Files:  http-client/.../UpstreamCircuitBreaker.java (new)
           http-client/.../JettyClientSlices.java (wire decorator)
           http-client/.../UpstreamRateLimiter.java (extend or compose)
   Effort: M
   Risk:   low (well-understood pattern; Nexus is the reference)
   Tests:  unit (state machine + Fibonacci + HEAD probe); itcase (toxiproxy 5xx → trip → recovery)
   Acceptance: at 100 r/s during a synthetic 60 s 5xx outage, ≤ 3 wire requests reach upstream

W2 — streamThroughAndCommit generalisation
   Files:  pantera-core/.../BaseCachedProxySlice.java (rewire cacheResponse)
           pantera-core/.../ProxyCacheWriter.java (relax Maven-specific assumptions)
           per-adapter: npm-adapter, pypi-adapter, composer-adapter, go-adapter, docker-adapter, helm-adapter, debian-adapter, gem-adapter (sanity-check sidecar/digest hooks compile)
   Effort: L
   Risk:   medium (touches every adapter's cache write path; potential for subtle behavioural drift)
   Tests:  per-adapter regression suite + new cold-miss latency test on each adapter
   Acceptance: per-adapter cold-miss median TTFB ≤ upstream TTFB + 50 ms; cache hit serve unchanged

W3 — MetadataCache conditional GET + SWR
   Files:  maven-adapter/.../MetadataCache.java (refactor)
           pantera-core/.../http/cache/ConditionalCache.java (new; usable beyond Maven)
   Effort: M
   Risk:   low-medium (well-defined; 304 path is small)
   Tests:  itcase against a mock upstream that switches Last-Modified
   Acceptance: 304 fast-path measured at < 100 ms (TTFB upstream + bump)

W4 — RepoBulkhead wired into slice path
   Files:  pantera-core/.../BaseCachedProxySlice.java (acquire/release around response)
           pantera-main/.../RepositorySlices.java (ensure bulkhead created for proxy repos, not just groups)
   Effort: S
   Risk:   medium (could starve a busy repo if maxConcurrent is too small)
   Tests:  itcase burst-traffic + bulkhead-overflow
   Acceptance: a misbehaving repo cannot saturate the global request queue

W5 — Cooldown evaluate single-flight (§5.1)
   Files:  pantera-main/.../JdbcCooldownService.java
   Effort: S
   Risk:   low
   Tests:  unit + JMH or simple concurrent benchmark
   Acceptance: N=100 concurrent evaluates on cold L1 → 1 DB lookup (was 100)

W6 — Perf harness extension
   Files:  performance/ (existing) + new metric assertions for circuit-breaker state, conditional-GET hit rate, streamThroughAndCommit usage by adapter
   Effort: S
   Risk:   low
```

**Expected cold-miss latency for the reproduction**:

- Without throttling: **13 + 2 = ~15 s** (close to the +2 s target). Removing G7 closes 6–15 s; G5/G8 closes another 0.6–1.4 s; G6 not triggered.
- With throttling: **15 + 5 = ~20 s** (circuit breaker absorbs most of the 10–25 s tail). Worst case if Sonatype's escalating block has already kicked in: **15 + 12 = ~27 s** until the 24h block expires.

**Risk**: medium.

**Effort**: L (4–8 weeks of engineering at one full-time contributor + reviewers).

**Workstream confidence (calibrated)**:

| Workstream | Closes | Confidence it ships correctly | Confidence it moves the number |
|---|---|---|---|
| W1 (circuit breaker) | G6 | 80% | 75% (the canonical lever for the throttling problem) |
| W2 (streamThroughAndCommit generalisation) | G1/G7 | 60% | 70% (every adapter is a separate edge case) |
| W3 (conditional MetadataCache) | G5/G8 | 75% | 50% (small absolute number, but high-confidence) |
| W4 (RepoBulkhead wiring) | G12 layer 5 | 80% | 25% (no big perf win; primarily a safety lever) |
| W5 (cooldown single-flight) | §5.1 | 90% | 15% (small absolute number) |

The 60% on W2 is the dominant honest uncertainty. Maven primary path works; npm tarballs probably work the same way; pypi probably works; docker manifests / Helm charts / etc. each have format quirks (e.g., docker manifest lists vary by `Accept` header — does that interact with the tee? Probably yes, and we need to confirm).

**Risk register**:
1. **W2 introduces a regression in a non-Maven adapter that's discovered post-deploy.** The shape of the bug: a particular content type (e.g., a Helm chart `index.yaml`, a Composer `packages.json`) breaks the cache write because its expected sidecars / digests / post-processing differs subtly from Maven. *Mitigation*: ship adapter-by-adapter behind feature flags... actually, per the durable instruction "no feature flags." Ship adapter-by-adapter via separate PRs with explicit per-adapter cold-bench gates.
2. **W1's circuit-breaker tripping is too aggressive (false positives).** A single TLS handshake error trips the breaker for 40 s; a TLS dance in a transient network glitch could now block legitimate traffic for 40 s. *Mitigation*: tune the trip predicate (Nexus's `AutoBlockConfiguration.shouldBlock` filters out `ConnectionPoolTimeoutException` explicitly; we need a similar exclusion list).
3. **W3's stale-while-revalidate produces a freshness regression a user notices.** A package was published 30 s ago; SWR serves the 60-s-old metadata; user runs `mvn install` and the new version is invisible. *Mitigation*: make the soft TTL configurable per repo; document the trade-off; default to 30 s which matches Cloudflare's `age` value on typical fetches.
4. **The reproduction's "55 s" was driven primarily by Maven Central's escalating per-org throttling, which W1 cannot fully fix.** If we've crossed Sonatype's threshold for the "longer or permanent blocks" path (the platform-class evaluation from `systems/maven-central.md` §2.5), no amount of circuit-breaking on our side will unblock us — only Sonatype direct contact will. *Mitigation*: have someone email `mavencentral@sonatype.com` as soon as the rollout begins.

**The single most likely reason this candidate fails to fix the problem**: W2 lands but doesn't reduce per-resource latency as much as the model predicts, because the actual bottleneck is a different per-request constant (cooldown evaluate, NegativeCache lookup, MicrometerMetrics phase recording, MDC context propagation, ContextualExecutor hops) that I haven't separated out in the model. The model assumes 80–200 ms of write-then-read is the dominant component; if it's actually 30–60 ms and there's another ~100 ms elsewhere I haven't found, W2 still helps but doesn't hit the 15 s target.

Diagnostic that would shift this: dump `proxy_phase_duration_seconds_bucket` from the M6 perf-gate dashboard for a representative reproduction run. If `cache_first_flow` is < 40% of the wall-clock, the dominant phase is elsewhere and W2 alone is insufficient.

---

## Candidate 2 — Rebuild the hot path

**Scope**: replace `BaseCachedProxySlice` with a unified `ProxySlice` primitive that wraps storage, tee, dedup, circuit breaker, metadata cache, and cooldown as a single composable pipeline. Keep storage backends (file, S3, DispatchedStorage) and admin/UI code unchanged. Make storage redirect-capable so the byte path can leave the JVM when an object-store + signed-URL backend is configured.

**Approach**:

1. **All of Candidate 1's W1–W5**, plus:
2. **New `ProxySlice` primitive** (`pantera-core/.../http/proxy/ProxySlice.java`). Composition-based, not inheritance:
   ```
   ProxySlice = NegativeCacheGate
              ∘ CooldownGate
              ∘ SingleFlightGate
              ∘ CircuitBreakerGate
              ∘ StreamThroughCache
              ∘ UpstreamHttpClient
   ```
   Each layer is a tiny `Slice` decorator (~50–150 LOC). Adapters configure them via a fluent builder — no template-method, no abstract hooks. Maven, npm, etc. provide `digestAlgorithms()`, `cacheable(path)`, `sidecars(path, digests)` as small lambdas.
3. **Storage redirect path (`SignedUrlStorage`)**. New `Storage` decorator: when configured with a signed-URL provider, returns a 302 redirect to a temporary signed URL pointing at the object store. The byte path never enters the JVM after the first fetch. Falls back to streaming pass-through when no signed-URL provider is configured.
4. **Maven adapter as the reference port**. Migrate `CachedProxySlice` to compose `ProxySlice` instead of extending `BaseCachedProxySlice`. The current Track 4/5 work translates 1:1 to the new pipeline.
5. **Other adapters ported one at a time**. Each gets ~50 LOC of composition wiring + format-specific hooks. No `BaseCachedProxySlice` deletion until the last adapter migrates.

**Workstream breakdown**:

```
W1–W5 — same as Candidate 1
W7 — ProxySlice primitive + layered decorators (foundation)
   Files:  pantera-core/.../http/proxy/ProxySlice.java + 6 decorator classes
   Effort: L
   Risk:   medium (architectural; lots of design decisions)
   Tests:  unit per decorator + integration of the composed pipeline
   Acceptance: a synthesised proxy slice (in-memory storage, mock upstream) returns
   expected results for all of: cache hit, cache miss, negative cache, cooldown
   block, circuit breaker trip, single-flight collapse, conditional refresh

W8 — Storage redirect path
   Files:  pantera-storage/.../SignedUrlStorage.java + adapter glue
   Effort: M
   Risk:   medium (signed URL semantics + auth interactions)
   Tests:  itcase against MinIO / LocalStack
   Acceptance: a configured proxy serves 302 redirects for cached binaries;
   the bytes do not pass through the JVM

W9 — Maven port (Maven primary path → ProxySlice composition)
   Files:  maven-adapter/.../CachedProxySlice.java (rewrite, retain class name)
   Effort: M
   Risk:   medium (regression surface is the full Maven test suite)
   Tests:  full existing maven-adapter test pack passes

W10 — Per-adapter ports (npm, pypi, composer, go, docker, helm, debian, gem)
   Effort: L
   Risk:   medium-high (adapter-by-adapter regression risk)
   Tests:  per-adapter pack
   Acceptance: parity with current behaviour + perf gate green

W11 — Decommission BaseCachedProxySlice
   Effort: S
   Risk:   low (last)
```

**Expected cold-miss latency for the reproduction**:

- Without throttling: **~13 s** (matches direct). Removing G7 closes the per-resource overhead; redirect path means client-perceived latency for cached bytes is ~1 RTT to the object store, which is < 10 ms on warm CDN.
- With throttling: **~15–18 s**. Circuit breaker absorbs the 429 storm; only the first-failure burst pays a small cost.

**Risk**: medium-high.

**Effort**: L–XL (3–5 months of engineering at one full-time + reviewers).

**Workstream confidence (calibrated)**:

| Workstream | Closes | Confidence it ships correctly | Confidence it moves the number |
|---|---|---|---|
| W1–W5 | as in Candidate 1 | as in Candidate 1 | as in Candidate 1 |
| W7 (ProxySlice primitive) | G1 (structural) | 50% | 60% (much depends on the layered composition not adding its own overhead) |
| W8 (SignedUrlStorage) | mass-mirror redirect pattern | 55% | 40% (only helps when an object store + signed-URL provider is configured; many deployments are file-storage) |
| W9 (Maven port) | G1 | 55% | 65% |
| W10 (other adapter ports) | G1 universal | 35% | 55% (each adapter is a separate risk) |
| W11 (decommission) | tech debt | 70% | 0% |

**Risk register**:
1. **The composed-decorator pipeline is slower than the template-method base.** Each decorator hop adds an allocation + a CompletableFuture chain. With 6 layers and 150 resources per build, that's 900 extra allocations + 900 thenCompose hops. JVM C2 should inline this but the cold-start path may pay measurable µs. *Mitigation*: JMH benchmarks before/after each decorator addition; bail out of any decorator that costs > 1 µs amortised.
2. **W10 stalls.** Each adapter port is a smaller version of W2 from Candidate 1, but on top of W7's new structure. Risk of "ship Maven, the rest never lands" is non-zero given organisational reality. *Mitigation*: schedule the unpopular adapters first (composer, gem) so the high-traffic ones (npm, maven) aren't bottlenecked.
3. **W8 is a configuration knob, not a default.** Most deployments will not enable redirect mode in the first 6 months. The latency win for them is only the Candidate-1 fixes. *Mitigation*: clear documentation; default the managed-service deployment to redirect mode.
4. **The reproduction is throttling-driven, not architecture-driven.** Same as Candidate 1's risk 4.

**Single most likely failure**: W10 stalls — adapter ports drag on and the un-ported adapters remain on `BaseCachedProxySlice` indefinitely. The Maven path is fixed; the other adapters degrade relative to a moving "best case" but stay the same as today. Effectively we'd have shipped Candidate 1 plus an unused W7 framework.

---

## Candidate 3 — Full greenfield 2.2.0

**Scope**: build a brand-new proxy plane from scratch following `canonical-architecture.md` exactly. Object-storage-first byte path with 302 redirect as default. New HTTP/2-native client (or Jetty 12 with new wiring). New unified cache primitive. Cooldown ported as a pluggable policy layer. All adapters re-wired against the new primitive. The current `pantera-main` / `pantera-core` / per-adapter modules are kept for API/admin surface but the byte path through them is replaced.

**Approach**:

1. **Greenfield `pantera-proxy-v2` module** with the canonical architecture as the design contract.
2. **Object-storage-first**: every proxy repo is backed by an S3/GCS/Azure/Backblaze bucket. Local file storage is for dev only.
3. **CDN-native** for the read path: when deployed in the managed service, Pantera fronts a Cloudflare bucket; clients are redirected to Cloudflare for cached bytes. Self-hosted deployments can configure their own CDN or fall back to streaming pass-through.
4. **Database migration is incremental**: the existing PostgreSQL schema continues to drive admin/UI/cooldown/events. The byte-path lookups move to a new index DB structure (or stay in PostgreSQL with a new schema that includes content-addressed indirection).
5. **Adapter rewiring is the long pole**: each format gets a new adapter against the new primitive. The old adapters are retired one by one.
6. **Cooldown is implemented as a policy layer above the cache**, not interleaved. The policy decides per request; the cache is pure mechanism.

**Workstream breakdown** (highly indicative; this is the riskiest plan):

```
W7 — ProxySlice primitive (as in Candidate 2)
W8 — SignedUrlStorage + CDN integration (as in Candidate 2, but expanded for managed-service Cloudflare)
W12 — New index DB schema (content-addressed)
W13 — Cooldown policy layer extracted
W14 — Per-adapter ports (Maven, npm, ..., 12+ adapters)
W15 — Migration tooling for existing repos
W16 — Decommissioning old code
```

**Expected cold-miss latency for the reproduction**:

- Without throttling: **~11–12 s** (matches direct mvn within 1–2 s).
- With throttling: **~14–15 s** (circuit breaker plus Cloudflare offload absorb most of the tail).

**Risk**: high (but bounded by the major-version freedom — the user is willing to make breaking changes for 2.2.0).

**Effort**: XL (6–12 months at one+ full-time engineer).

**Workstream confidence (calibrated)**:

| Workstream | Closes | Confidence it ships correctly | Confidence it moves the number |
|---|---|---|---|
| W7 (ProxySlice + decorators) | G1 | 45% | 70% |
| W8 (CDN-native byte path) | mass-mirror pattern | 40% | 80% (when enabled; most powerful single lever) |
| W12 (index DB) | G9 | 35% | 30% (not a latency lever, more an operational lever) |
| W13 (cooldown policy layer) | Phase C §5 | 60% | 5% (clean-up; small perf win) |
| W14 (12+ adapter ports) | G1 universal | 20% (12 adapters × ~90% each ≈ 28%) | 70% (when complete) |
| W15 (migration tooling) | operational | 50% | 0% |

**Risk register**:
1. **Six-month-plus project drags on, ships in 18 months, by which time priorities have shifted.** Greenfield projects historically miss their dates. *Mitigation*: schedule monthly visible milestones (one adapter ported per month) so progress is measurable.
2. **The greenfield project lands but is buggy in production for months.** No matter how thorough the testing, a system serving 12+ formats has edge cases that only emerge under real traffic. *Mitigation*: ship behind an opt-in "preview" mode... again, no feature flags per durable instruction, so this means deploying to a small staging cohort first.
3. **The team learns mid-flight that one of the canonical axioms doesn't fit Pantera's deployment model.** For example: object-storage-first doesn't work for the air-gapped customers; CDN-native doesn't work for the managed service because we don't have a Cloudflare contract. *Mitigation*: validate the axioms against customer deployment topologies as the first sprint.
4. **The cooldown extraction loses a subtle behaviour.** With 6+ adapters each having format-specific cooldown semantics, the policy layer's API is hard to design. Existing customers rely on current quirks. *Mitigation*: a behaviour-preservation test suite ported from the existing cooldown integration tests.
5. **Maven Central permanent-block hazard.** As in C1/C2. Greenfield doesn't help on its own; the proxy still has to talk to Maven Central on the cold path.

**Single most likely failure**: time. The architecture is right; the team-month cost is large; competing priorities derail it.

---

## Comparison table

| Property | Candidate 1 | Candidate 2 | Candidate 3 |
|---|---|---|---|
| Expected cold-miss (no throttling) | ~15 s | ~13 s | ~11–12 s |
| Expected cold-miss (with throttling) | ~20 s | ~15–18 s | ~14–15 s |
| Effort | L (4–8 wk) | L–XL (3–5 mo) | XL (6–12 mo) |
| Risk | medium | medium-high | high |
| Touches admin/UI? | no | no | mostly no |
| Requires object storage? | no | optional | yes (default) |
| Cooldown preserved? | yes, with §5.1 micro-fix | yes, same | yes, refactored into a policy layer |
| Throttling problem fixed? | 75% (circuit breaker) | 80% | 85% (CDN-native absorbs more) |
| Slowness problem fixed? | 60% (to ~15 s; not all-the-way) | 70% (to ~13 s) | 70% (to ~12 s) |
| Both problems fixed to target | 45% | 55% | 60% |

The "both problems" confidence is the joint probability that *both* the throttling tail and the per-resource overhead come in at the expected target. It is correctly lower than either single number.

## Headline confidences (calibration discipline)

Reading the previous-attempt failure mode that motivated this prompt: previous plans anchored at 85%, looked approvable, and then didn't move the numbers. The honest numbers for this round, with intervals:

- **Throttling (Problem 1) — resolved to target by Candidate 1**: 60–80%. The 20% tail risk is Maven Central's escalating per-org throttling (the "longer or permanent blocks" path documented at `systems/maven-central.md` §2.5) that no client-side change can unblock.
- **Slowness (Problem 2) — resolved to target by Candidate 1**: 50–65%. The 35% downside is that the per-resource model under-estimates the residual constants (cooldown evaluate, MDC context, ContextualExecutor hops, metric phase records). If true the surgical fixes land but don't hit the 15-s target.
- **Both resolved to target by Candidate 1**: 30–50%. The joint probability.

By Candidate 2:
- **Throttling**: 70–85%.
- **Slowness**: 60–75%.
- **Both**: 45–60%.

By Candidate 3:
- **Throttling**: 75–90%.
- **Slowness**: 65–80%.
- **Both**: 55–70%.

These are wide intervals on purpose. The previous round's failure was anchoring without acknowledging the structural uncertainty.

## Recommendation

**Candidate 1 ("Close the gap, preserve the structure")**, with two caveats:

1. **Before any code change, pull the M6 perf-gate dashboard** for a representative reproduction run and confirm which `proxy_phase_duration_seconds` buckets are dominant. If the dominant phase is `cache_first_flow` for non-Maven repos and `cooldown_and_fetch_miss` for Maven cold-miss runs, the model is correct and W2 + W1 are the right priorities. If the dominant phase is something else (e.g., `cache_load`, `metadata_load_on_hit`), the model is wrong and the priorities shift. **Action**: 1–2 hours of dashboard reading before scheduling W1–W5.
2. **Email `mavencentral@sonatype.com` in parallel with W1**. Pantera ships to many customers; if Sonatype's per-org evaluation has us on a longer block, no client-side fix will move the wall-clock until they unblock us. The 2024 "Beyond IPs" post explicitly invites infrastructure operators to make contact.

The reason Candidate 1 wins over Candidate 2 or 3:

- The dominant gains come from W1 + W2 (circuit breaker + stream-tee generalisation), both of which are within Candidate 1's scope.
- The marginal gain of Candidate 2 (~2 s lower steady-state, primarily from SignedUrlStorage when configured) does not justify 3× the effort and 2× the risk for the typical deployment.
- Candidate 3's gains are heavily contingent on the CDN-native byte path, which only applies to the managed-service deployment that Auto1 may or may not be on a path to ship.

**Evidence that would change the recommendation to Candidate 2**: if W2 lands and the latency number does NOT move as the model predicts (within 30% of the predicted bucket), the model is wrong and structural surgery is needed. The decision point is at the end of Candidate 1's W2.

**Evidence that would change the recommendation to Candidate 3**: if Auto1 commits to a managed-service deployment with Cloudflare contract within the planning horizon, the CDN-native byte path becomes the dominant lever and Candidate 3's investment pays back. Without that commitment, Candidate 3 is overkill.

---

## Sequencing for Candidate 1

If approved, the suggested sequence (each box is roughly one week of contributor time):

```
Week 1: Read M6 dashboard; confirm model. Email Sonatype.
Week 2: W1 implementation (circuit breaker).
Week 3: W1 itcase + rollout. W5 (cooldown SF).
Week 4: W3 (MetadataCache conditional + SWR).
Week 5: W4 (RepoBulkhead slice-path wiring).
Week 6: W2 — Maven non-primary paths first (metadata refresh, sidecar fetches).
Week 7: W2 — npm tarball cache write.
Week 8: W2 — pypi, composer, go, docker, helm, debian, gem (one each at half a day, expect 2–3 to slip).
```

The reproduction's wall-clock is measurable at the end of every week. Stop and re-evaluate if a week's work does not move the number as expected.
