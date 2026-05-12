# Changelog

## Version 2.2.0

- **Track 5: zero upstream I/O on cache hit, architectural fix across
  every proxy adapter.** User reported sudden 429 storms from Maven
  Central starting 2026-05-11 and the diagnostic question that named the
  root cause: *"we have the jar and pom cached, why are we hitting Maven
  all the time?"* The cache-hit path was firing `MavenHeadSource` HEAD
  requests on Maven Central via the cooldown inspector's network
  fallback. Under Cloudflare-backed Maven Central rate limiting (added
  Q1-Q2 2025 alongside the Central Portal migration), the per-IP budget
  fell below what Pantera's per-request HEAD pattern needed. Same shape
  in the composer adapter for packagist.org. Track 5 closes the design
  loophole permanently — no flags, every fix replaces the broken path
  in place.

  **Phase 1A — invert the cooldown gate ordering.**
  `CachedProxySlice.verifyAndServePrimaryGated` (maven) wrapped both
  cache-hit and cache-miss in `evaluateCooldownOrProceed`, which goes
  through the cooldown inspector → `RegistryBackedInspector` →
  `PublishDateRegistries` → L1 → L2 → `MavenHeadSource` fetch. Every
  cached `.jar` / `.pom` request created an upstream HEAD when L1
  evicted (100k cap, no TTL) or L2 lacked the entry. Fix: move the gate
  INSIDE `verifyAndServePrimary`, after `storage.exists`. Cache hit is
  now pure-local. Same shape applied to composer: removed
  `evaluateMetadataCooldown` from `serveCachedMetadata` so cached
  packages.json no longer triggers `PackagistSource`. Go adapter was
  already correct (`cache.load` check at line 294, cooldown only on
  miss). Trade-off documented: a cooldown rule applied AFTER an artifact
  was first cached takes effect on the next miss; the admin's tool for
  blocking already-cached versions is cache eviction.

  Files: `maven-adapter/.../CachedProxySlice.java` (deleted
  `verifyAndServePrimaryGated`; cooldown moved inline at the cache-miss
  branch), `composer-adapter/.../proxy/CachedProxySlice.java`
  (`serveCachedMetadata` cooldown call removed).

  **Phase 1B — restore publish_date pre-population on cache write.**
  Track 4's stream-through path passed `Headers.EMPTY` to
  `enqueueEventForWriter` so `lastModified` was always
  `Optional.empty()`. The DB consumer fell back to `now()` as the
  publish_date, making subsequent cooldown evaluations resolve to "just
  published" — and on L1 eviction the inspector fell through to
  `MavenHeadSource` to re-resolve. Fix: `UpstreamBody` record now
  carries the upstream `Headers`; `enqueueEventForWriter` takes
  `(key, upstreamHeaders, size)` and threads them into
  `buildArtifactEvent`, which extracts the RFC 1123 `Last-Modified`
  via the existing `extractLastModified` helper. Every cached primary
  artifact now has the authoritative upstream publish_date in the
  registry, so subsequent cooldown lookups are pure-local forever.

  **Phase 2A — `PublishDateRegistry.Mode` (NETWORK_FALLBACK / CACHE_ONLY).**
  Defence-in-depth: callers on the cache-hit hot path that still want
  to consult the registry can pass `CACHE_ONLY`, which short-circuits
  the L1+L2 lookup with `Optional.empty()` on miss and physically
  forbids the source-fetch step. Old 3-arg `publishDate(...)` stays
  abstract so the test stub in `PublishDateRegistries.installDefault`
  (a lambda) keeps compiling; 4-arg default delegates to the 3-arg.
  `DbPublishDateRegistry` overrides the 4-arg to honour CACHE_ONLY
  with a `cache_only_miss` outcome metric.
  `RegistryBackedInspector` grows a 3-arg constructor that selects the
  mode; the legacy 2-arg constructor keeps NETWORK_FALLBACK so existing
  wiring is byte-for-byte unchanged.

  **Phase 2B — HEAD requests served from local storage.** The Maven
  client routinely fires HEAD before GET to check artifact metadata,
  and pre-Track-5 every HEAD proxied to `repo.maven.apache.org` even
  when the artifact was already cached — a second class of upstream
  traffic the user explicitly named. `HeadProxySlice` (maven, go) now
  takes `Optional<Storage>`; on cache hit it synthesises a 200 with
  `Content-Length` from `Meta.OP_SIZE` and never touches the upstream
  client. Cache miss falls through to the pre-Track-5 pass-through.
  Storage wired in via `MavenProxySlice` and `GoProxySlice`.

  **Phase 2D — full cross-adapter audit.** Spike audit across every
  proxy adapter (maven, composer, go, pypi, npm, file, nuget, conan,
  hex, gem, docker, helm, debian, rpm, conda) using a dedicated
  Explore subagent. Findings: maven (broken pre-Track-5, fixed in
  1A/2B), composer (broken pre-Track-5, fixed in 1A), go (already
  correct — `cache.load` first at line 294), pypi/npm/file
  (already cache-first), helm/debian/rpm/conda/nuget/conan/hex/gem
  (no proxy mode with cooldown — confirmed clean). Docker HEAD for
  blobs/manifests flagged for a follow-up: digest-keyed manifests are
  immutable so safe, but tag-resolved manifest HEAD still proxies.

  **Phase 3A — `SwrMetadataCache<K, V>` primitive.** Generic
  stale-while-revalidate cache in pantera-core encapsulating the
  pattern maven-adapter `MetadataCache` already does correctly. Soft
  TTL: serve cached, no upstream. Past soft within hard: serve cached
  IMMEDIATELY (non-blocking) and fire one background refresh
  (dedup'd via a `ConcurrentHashMap` key set so 10 concurrent
  soft-stale requests collapse to one upstream call). Past hard: treat
  as miss, await loader. Metrics: `pantera_metadata_swr_hit_fresh`,
  `_hit_stale`, `_miss`. Counted, tagged by `cacheName`. Primitive +
  5 unit tests landed; per-adapter migration of composer/pypi/go onto
  the primitive is the natural follow-up — the existing per-adapter
  ad-hoc SWR is async (off hot path) so the user-facing 429 storm is
  already addressed by Phases 1A/2A/2B.

  **Phase 3B — `PublishDateExtractor` SPI.** Each repo-type's
  publish-date extraction pattern was scattered across adapter helpers
  (maven's `extractLastModified`, composer's `extractReleaseDate`,
  go's `parseLastModified`). The Track 4 `Headers.EMPTY` regression
  was a symptom of that scattering. The new SPI in
  `com.auto1.pantera.publishdate.PublishDateExtractor` +
  `PublishDateExtractors` registry centralises extraction keyed by
  repo-type. Maven's extractor registered in `VertxMain` at boot
  (RFC 1123 `Last-Modified` parse). Other adapters fall through to the
  registry's NO_OP for now — additive rollout, no regression. Non-maven
  registrations move incrementally.

  **Test coverage.** 16 new unit tests across the diff:
  - `CacheHitNoUpstreamTest` (2): cache-hit on .jar/.pom serves
    local bytes with zero upstream calls AND zero inspector calls.
  - `HeadProxySliceCacheFirstTest` (3): HEAD on cached artifact is
    pure-local; HEAD on miss delegates; no-storage variant is
    pass-through.
  - `RegistryBackedInspectorCacheOnlyTest` (2): CACHE_ONLY constructor
    forwards mode; default forwards NETWORK_FALLBACK.
  - `PublishDateExtractorsTest` (4): unregistered repo-type yields
    NO_OP; registered extractor returned; re-registration replaces;
    null arguments rejected.
  - `SwrMetadataCacheTest` (5): fresh hit no loader; soft-stale serves
    cached + async refresh; hard-stale awaits loader; concurrent
    soft-stale dedups to one refresh; absent → load → cached.

  Existing `verifyAndServePrimaryBlocksEvenWhenCacheHasVersion` test
  renamed to `verifyAndServePrimaryCacheHitIsLocalEvenWhenBlockIsActive`
  and rewritten to assert the new contract.

  Acceptance for the user's reported symptom: with a warm cache, a
  `mvn dependency:resolve -U` walk now makes ZERO upstream calls for
  artifacts and HEADs that already landed. Cooldown evaluation happens
  exactly once per (artifact, version) first-fetch. Mutable
  per-package index files (maven-metadata.xml et al.) continue to
  refresh in the background via SWR — the user's "serve stale + refresh
  in background" contract.

- **Track 5 follow-ups: per-repo-type extractors + Phase 3C contract pin.**

  **PublishDateExtractor registrations for every proxy ecosystem.**
  `VertxMain.start` now registers the RFC 1123 `Last-Modified` extractor
  for all six header-emitting ecosystems we proxy (maven, npm, pypi, go,
  composer, gem) — every upstream we care about sets `Last-Modified` on
  artifact GETs, so one lambda handles them all. Ecosystems whose
  publish date lives in the response body (docker manifests, nuget
  catalog, hex registry) fall through to the registry's NO_OP and
  preserve the pre-Track-5 `System.currentTimeMillis()` DB-consumer
  fallback — adding body-aware extractors for those is a separate
  change scoped to whichever adapter needs strict cooldown semantics.

  **Phase 3A "migration of composer/pypi/go onto SwrMetadataCache":
  DELIBERATELY NOT DONE.** On close inspection of those adapters,
  the audit finding that motivated the migration was wrong. Their
  cache-hit paths use `CacheTimeControl.validate` which queries
  `storage.metadata` (Postgres-backed, cross-process) — that is a
  LOCAL DB query, not an upstream HTTP call. Replacing it with the
  in-process Caffeine-timestamp shape of `SwrMetadataCache` would
  trade cross-process consistency (any node sees freshness writes from
  any other node) for in-process speed (each node tracks its own
  freshness window). In a multi-instance Pantera deployment that is a
  net regression: two nodes serving the same package would drift their
  staleness independently. The existing adapter patterns are correct.
  `SwrMetadataCache` stays as the canonical primitive for FUTURE
  adapters that do not have a storage-backed metadata layer.

  **Phase 3C "deletion of MavenHeadSource et al.": DELIBERATELY
  NOT DONE.** The PublishDateSource implementations remain
  load-bearing on the cache-miss branch (NETWORK_FALLBACK mode). The
  cooldown gate runs BEFORE the upstream fetch on cache miss; without
  a network-fallback source, the registry returns
  `Optional.empty()` for any artifact we have never seen before, the
  cooldown evaluator fails open, and a freshly-published version
  inside its cooldown window slips past the gate the first time it is
  requested. The Phase 1A invariant the user reported — "no upstream
  on cache HIT" — does not require deletion of the cache-miss
  fallback; one upstream HEAD per genuinely-new
  `(artifact, version)` pair is amortised over the entire lifetime of
  the artifact in cache and is the minimum cost of preserving the
  "block fresh versions even for the first asker" property.

  Admins who want strictly zero upstream HEAD traffic — at the cost
  of losing first-asker cooldown — can already opt out per-repo by
  setting `cooldown.enabled: false` in the repo YAML. No new toggles
  needed.

  Phase 3C contract is pinned in
  `pantera-main/.../publishdate/DbPublishDateRegistryTest.java` with
  two new tests:
  - `cacheOnlyModeNeverInvokesSource` — L1+L2 miss + CACHE_ONLY
    returns empty without firing the source.
  - `cacheOnlyAfterNetworkFallbackHitsL1OrL2` — first call uses
    NETWORK_FALLBACK and populates L2; subsequent CACHE_ONLY reads
    (even across a registry-instance restart simulating a JVM
    bounce) hit L2 with zero source calls.

  Test count: 1194 unit tests passing (up from 1192). PMD clean.

- **Phase 13.5: raise npm per-upstream prefetch cap default 4 → 32.**
  Phase 12.5 profiling proved foreground requests do not share semaphore
  queueing with prefetch (`tryAcquire()` is non-blocking; foreground
  uses no semaphore at all). The original npm=4 default was based on
  stale "foreground vs prefetch contention" reasoning that doesn't
  apply.

  Phase 13 wired packument prefetch but at cap=4, 73 of 108 dispatches
  dropped on `semaphore_saturated` — 68% of the prefetch budget thrown
  away. Sweep across {4, 16, 32, 64} on warm-JVM single-shot
  `npm install express` (median of 3 runs):

  ```
  cap |  p50  | mean  |  max
   4  | 2.20s | 2.21s | 2.28s
  16  | 1.86s | 1.84s | 1.90s
  32  | 1.75s | 1.73s | 1.76s
  64  | 1.63s | 1.73s | 1.96s
  ```

  Cap=32 chosen for best balance: 20% wall reduction vs cap=4, lowest
  variance (mean=1.73, max=1.76), no upstream pool overload (cap=64
  shows max=1.96s creeping up).

  10-iteration cold-cache `npm install express` (each run restarts
  pantera, so JVM-startup dominates and cap signal is muted):

  ```
                min   p50    mean   p95    max    stdev
  cap=4  (old) 3.10  3.21s  4.20s  12.50  12.50  2.92
  cap=32 (new) 3.32  3.68s  3.63s   3.93   3.93  0.19
  ```

  p50 slightly worse (3.21→3.68); **p95 dramatically better
  (12.50→3.93)** — cap=4 had a bimodal failure mode where one in ten
  cold installs blew up to 12.5 s, eliminated at cap=32. Mean is
  better (4.20→3.63), stdev 15× tighter (2.92→0.19). Acceptance bar
  ≤1.5× direct — both caps fail it on cold-restart benchmark; the JVM
  warmup (≈2 s) sets the floor regardless of prefetch cap. In
  steady-state warm production the sweep median (1.75s = 1.4×
  direct) clears the bar.

  Maven default cap unchanged at 16; maven 10-iter cold-cache:
  p50 13.34s (was 13.63s, within stdev).

  Files: `pantera-main/src/main/java/com/auto1/pantera/settings/runtime/SettingsKey.java`
  (defaultRepr 4→32), `PrefetchTuning.java` (defaults map + fromMap
  fallback), `SettingsKeyTest.java` + `SettingsHandlerRuntimeTest.java`
  + `PrefetchDispatcherTest.java` updated to assert 32.


  critical path.** Phase 12.5 profiling pinpointed the packument
  waterfall (8.45 s of 12.9 s wall = 65 %) as the dominant cold-cache
  npm cost: `npm install` walks each dep's packument serially before
  fetching tarballs, and the existing tarball prefetch (Phase 9) fires
  too late to warm the metadata path. JFrog/Verdaccio do this; pantera
  did not.

  Implementation:
  1. `NpmProxy` gains a second hook (`packumentWriteHook`) fired after a
     successful packument save (`storage.save(NpmPackage)`). Symmetric to
     the existing tarball `cacheWriteHook` (Phase 9).
  2. `NpmCacheWriteBridge.packumentHook()` mirrors `hook()` for tarballs
     — fires `CacheWriteEvent` with `urlPath = packageName` and
     `bytesOnDisk = pathFor(<name>/meta.json)` via `Storage.pathFor`
     (zero-copy passthrough, Phase 11).
  3. New `Coordinate.npmPackument(name)` factory + `NPM_PACKUMENT`
     ecosystem render the canonical packument URL (`<scope>/<name>` —
     no `/-/<file>.tgz` suffix). The coordinator's per-upstream
     concurrency cap and metric labels normalize `NPM_PACKUMENT` →
     `"npm"` so packument prefetches share the same registry.npmjs.org
     budget as tarball prefetches (4-cap by default).
  4. `NpmPackumentParser` reads the packument JSON, picks
     `dist-tags.latest` (falls back to highest stable semver), and
     emits packument coords for each direct dep. devDependencies and
     older versions intentionally ignored.
  5. `NpmCompositeParser` dispatches to the right sub-parser by file
     shape: gzip magic (`0x1F`) → tarball parser; otherwise → packument
     parser. Both events arrive on the same `npm-proxy` repo type.

  10-iteration cold-cache `npm install express`:
  before (Phase 12):  pantera p50 3.02 s (2.44× direct, direct p50 1.24 s);
  after  (Phase 13):  pantera p50 3.39 s (2.70× direct, direct p50 1.25 s).
  108 packument prefetches dispatched per cold install (35 fetched 200,
  73 dropped on `semaphore_saturated`, 24 deduped). Wall change is
  within the per-iteration noise band (stdev 4.27 s — two 13 s outliers
  in 10 runs). Excluding the outliers, pantera p50 ≈ 3.05 s — flat
  vs. baseline. Maven cold-cache (`mvn dependency:resolve
  sonar-maven-plugin`, 10 iter) unchanged: p50 13.63 s.

  Diagnostic finding: per-upstream cap of 4 (npm) means the
  speculative prefetch loses the `tryAcquire` race against foreground
  npm install for most slots, hence the high `semaphore_saturated`
  drop count. Net wall-time benefit on this benchmark is therefore
  negligible. The Phase 13 plumbing is complete and structurally
  sound — addressing the semaphore contention (separate prefetch
  connection pool, or foreground-priority semaphore) is left for a
  follow-up. Above the v2.2.0 ≤1.5× target.

- **Phase 12: tee upstream stream to disk + client; parallelise meta + data writes.**
  Phase 11.5 sub-phase profiling pinpointed two hotspots in the
  `RxNpmProxyStorage` cold-cache path: `save_meta` at ~46 ms/req (sequential
  `concatArray(meta, data)` blocked the larger tarball write on the smaller
  metadata write — directory allocation + atomic move serialised twice) and
  `save → reload` at ~23 ms/req (the post-save `storage.getAsset(path)`
  re-opened the on-disk tarball to serve to the client). Together these
  accounted for ~70 ms/req on the cold critical path × ~74 cache misses for
  a cold `npm install express` ≈ ~5 s of needless wall.

  Two changes:
  1. `RxNpmProxyStorage.save(NpmAsset)` switched from
     `concatArray(meta, data)` to `mergeArray(meta, data)` — both writes
     proceed in parallel; the Completable signals only when BOTH finish, so
     post-save reads still see a complete pair. SingleFlight dedup prevents
     concurrent readers from observing data-without-meta during the race
     window.
  2. New `RxNpmProxyStorage.saveStreamThrough(NpmAsset)` tees the upstream
     `Publisher<ByteBuffer>` so the client receives bytes AS the upstream
     remote delivers them, while the same chunks accumulate in a buffer
     that is persisted to storage on stream completion. The meta sidecar
     is fired immediately (its bytes are known before the body flows). The
     `storage.save(asset).andThen(storage.getAsset(path))` round-trip is
     eliminated entirely. Mirrors the existing `FromStorageCache.teeContent`
     pattern used by the Maven proxy cache. New `NpmProxyStorage`
     interface method has a default implementation falling back to the
     legacy save-then-reload chain for test mocks. The cache-write hook
     fires on stream completion (matches legacy save-then-hook semantics:
     no hook on client disconnect).

  10-iteration cold-cache `npm install express` (10 iter):
  before (Phase 11):  pantera p50 3.62 s (2.60× direct, direct p50 1.39 s);
  after  (Phase 12):  pantera p50 3.02 s (2.44× direct, direct p50 1.24 s);
  16% reduction in cold p50, 6% improvement in ratio. Min dropped from
  ~3.0 s baseline to 2.74 s. Maven cold-cache (`mvn dependency:resolve
  sonar-maven-plugin`, 10 iter) unchanged: p50 14.34 s vs prior 14.51 s
  (within stdev). Still above the v2.2.0 ≤1.5× target — the residual
  ~1.8 s gap above direct npmjs is dominated by upstream RTT to npmjs.org
  + per-upstream-semaphore contention between speculative prefetch and
  foreground installs, NOT by storage write latency.

  Risk-banded change: existing `save(NpmAsset)` semantics preserved
  (Completable still gates on meta + data); new stream-through path is
  opt-in via the new interface method; full test coverage (happy path,
  multi-chunk integrity, upstream-error mid-stream cleanup) added in
  `RxNpmProxyStorageTest`. Existing `NpmProxyTest.getsAsset` updated to
  verify the cache-miss path now uses `saveStreamThrough` (one
  `storage.getAsset` call instead of two; no `storage.save` on the cold
  path).

- **Phase 11: zero-copy storage passthrough for the npm cache_write hook.**
  `Storage.pathFor(Key)` now lets `FileStorage`-backed proxies hand the
  on-disk path directly to the prefetch dispatcher — no read-back, no
  temp-file materialise, no dispatcher snapshot copy. `CacheWriteEvent`
  carries a `callerOwnsSnapshot` flag (true for `ProxyCacheWriter`'s
  caller-deletes-after-fire path; false for storage-owned). For
  non-FileStorage backends (Blob, Memory, etc.), the bridge falls back to
  the legacy materialise-temp-file path. Eliminates 2.12 s of bridge work
  and the 0.67 s `dispatcher_snapshot_copy` budget across 74 cache misses
  on a cold `npm install express` (Phase 10.5 profiler), moved off the
  storage executor pool so it cannot contend with foreground tarball
  writes during burst loads.

  10-iteration cold-cache `npm install express` (10 iter):
  before (Phase 10.5): pantera p50 3.62 s (2.60× direct, direct p50 1.39 s);
  after (Phase 11):    pantera p50 3.79 s (2.67× direct, direct p50 1.42 s);
  difference is within the natural variance band (post stdev 2.97 s — one
  12.97 s outlier in run 6; pre stdev 1.03 s). Cold p50 is dominated by
  network RTT to npmjs.org + storage save (`asset_total` 13.65 s / 252
  reqs in the Phase 10.5 single-run profile), not by the bridge. Maven
  cold-cache (`mvn dependency:resolve sonar-maven-plugin`, 10 iter)
  unchanged: p50 14.61 s vs prior 14.51 s — also within stdev. Maven
  continues to use `ProxyCacheWriter`'s caller-owned-snapshot path
  (`callerOwnsSnapshot=true`); the dispatcher still snapshots there
  because the writer deletes its temp file as soon as `fireOnWrite`
  returns.

---

## Version 2.2.0

Target-architecture alignment release (v2.2 plan §12). Ships nine work items — WI-00 (queue/log hotfix), WI-01 (Fault + Result sum types), WI-02 (full RequestContext + Deadline + ContextualExecutor), WI-03 (StructuredLogger 5-tier + LevelPolicy + AuditAction), WI-04 (`GroupResolver` replaces `GroupSlice` at every production site), WI-05 (SingleFlight coalescer), WI-07 (ProxyCacheWriter + Maven checksum integrity), WI-post-05 (retire RequestDeduplicator), WI-post-07 (wire ProxyCacheWriter into pypi/go/composer). Also lands: **serve-before-commit** across all proxy adapters (writeAndVerify + async cache commit + parallel sidecar saves), **publish-date registry** replacing per-adapter cooldown inspectors with a DB-backed registry + 6 upstream source implementations, **search & browse overhaul** (natural sort, artifact classification, DB-hydrated tree browser), **group RaceSlice priority fix** (2xx > 403 > 5xx > 404), **ECS log field compliance** (488 fields migrated), **preemptive Basic auth** for Maven/Gradle, plus a **P0 production-readiness pass** (Groups A–H) and full admin / developer / user documentation.

### ⚡ Performance pack (cold-cache build wall time)

- **Cold-cache Maven dep-resolve wall time reduced ~80%** vs the h1 + no-prefetch baseline. Median **13.01 s** over 10 cold-cache iterations of `mvn dependency:resolve -Dartifact=org.codehaus.mojo:sonar-maven-plugin:4.0.0.4121` through `maven_group → remotes → maven_proxy → Maven Central` (range 12.56 s–14.58 s; mean 13.18 s; stdev 0.56 s; p95 14.58 s). H1 + no-prefetch baseline on the same hardware and POM: 66 s (Phase 7 cold-bench, commit `aab1d3415`). Direct `mvn` → Maven Central baseline on the same hardware: 9.0 s. Per-iteration cold reset: `TRUNCATE artifacts`, clear local `m2` + `maven_proxy` / `groovy` filesystem cache, `docker compose restart pantera`. The h2-only step alone took the wall from 66 s to 28 s (60% reduction); subsequent prefetch + parallel-sidecar + sequential-fanout work brought it to ~13 s. Per-stage handler-latency profiling (commit `140c1708e`) confirmed the remaining ~4 s above the direct-mvn baseline is dominated by upstream RTT, not pantera CPU. 10-iteration acceptance bench artefacts: `performance/results/cold-bench-10x.csv`, `performance/results/cold-bench-10x.md`.
- **HTTP/2 (h2) over TLS for all upstream proxy traffic, ALPN-negotiated.** `JettyClientSlices` now requests `h2` via ALPN with HTTP/1.1 fallback; pool sizing and multiplexing are runtime-tunable.
- **Speculative direct-dependency pre-fetch** for maven / gradle / npm proxies. On a 200 cache-write the `BaseCachedProxySlice.onCacheWrite` extension fires a `CacheWriteEvent` to `PrefetchDispatcher`; the dispatcher parses direct deps from the just-cached artifact (`MavenPomParser`, `NpmPackageParser`) and queues a bounded fetch via `PrefetchCoordinator`. Per-host semaphores, bounded coordinator queue, and an auto-disabling circuit breaker cap drop spikes. **npm parser** is wired against `CachedNpmMetadataLookup` which resolves npm version ranges (`^x.y.z`, `~x.y.z`, exact, comparator, hyphen, `||` alternatives) against the locally-cached packument under each `npm-proxy` storage — pre-fetch never issues an upstream metadata fetch, range resolution is strictly local-cache-only. semver4j's `Requirement.buildNPM` drives range parsing for full npm-spec coverage; prereleases are excluded unless the requested range explicitly opts in. The npm proxy fires `CacheWriteEvent` via `NpmCacheWriteBridge` on every successful tarball save (`NpmProxy.cacheWriteHook` → `CacheWriteCallbackRegistry`), so the npm-prefetch parser is fully active end-to-end (fix landed Phase 9). **npm parity bench** (10-iteration cold-cache `npm install express ^4.21.0`, ~69 transitive packages, fresh `npm cache clean --force` per run): direct `registry.npmjs.org` p50 **1.26 s**, pantera `npm_group → npm_proxy → registry.npmjs.org` p50 **5.53 s** (ratio 4.37× on the FIRST cold install). The cold ratio increases vs the v2.2.0-WI baseline of ~2× because speculative pre-fetch now actually fires for npm — the dispatcher saturates the per-upstream semaphore with transitive-dep tarballs in parallel with the foreground install, and contends for upstream bandwidth. **Warm-cache** through pantera is **0.55 s** vs direct 1.23 s — pantera is faster than direct npmjs once the cache is populated, which is the steady-state target. The cold-vs-warm tradeoff is intentional: paying once per coordinate to amortise across all subsequent installs in the cluster (which is what an artifact proxy is actually for). Operators concerned about the cold first-install latency can disable npm prefetch per-repo via `RepoConfig.prefetchEnabled = false`.
- **Parallel primary + `.sha1` sidecar fetch on cache miss** (`ProxyCacheWriter`). The previous serial chain cost 1 RTT per miss; this alone took the cold pom-heavy walk from 21 s to 14 s.
- **Speculative `.md5 / .sha256 / .sha512` upstream fetches removed from the cold path.** Maven only verifies against `.sha1` by default; the others are proxied on demand if a client explicitly requests them.
- **Synchronous `fsync` dropped on regenerable cache writes.** Cache files are reproducible from the upstream — durability is not load-bearing for them.
- **Sequential-only group fanout (BREAKING).** The `MembersStrategy` enum is removed; group resolution now matches Nexus / JFrog semantics. Members are tried in declared order; first 2xx wins; subsequent members are only consulted on 404. Eliminates the cold-cache traffic amplification PARALLEL used to cause when a group fanned out to 2+ proxies for every miss. **Operator action required**: order group `members:` lists with the highest-likelihood-of-having-the-artifact member first (typically: hosted before proxy, primary upstream before niche). Configs with `members_strategy: parallel|sequential` are accepted at parse time with a one-time WARN per group; the key is otherwise ignored. See [`docs/admin-guide/group-member-ordering.md`](docs/admin-guide/group-member-ordering.md).
- **Metadata-merge fanout removed for `maven-metadata.xml` and `packages.json` (BREAKING).** The two adapters that still fanned out metadata in parallel and merged a union across all members — `MavenGroupSlice` (maven / gradle) and `ComposerGroupSlice` (php / file) — are now sequential-first, matching the artifact-fanout change above. The first member that returns 200 wins; later members are NOT queried; non-OK members fall through. The cooldown filter is preserved on the single winning response (was previously applied post-merge), and the `metadata-url` / `providers-url` rewrite to the group basePath is preserved on the Composer side so p2 lookups stay inside pantera. **Rationale**: in well-organised configs (the only kind we support) the chance two members both serve the same artifact is near-zero — member namespaces are typically disjoint (proxy mirror vs hosted releases vs hosted snapshots; Packagist mirror vs private hosted). The merge added per-request upstream amplification proportional to N members with no real benefit for the 99 %+ of artifacts that live in a single member; JFrog Artifactory and Sonatype Nexus virtual repos behave the same way. **Migration**: configs that genuinely need union-of-versions across disjoint upstreams should split into separate group repos and have the client query both (e.g. multiple Composer `repositories:` entries). Member ordering matters now — declare the most-likely winner first. The dead `StreamingMetadataMerger` + its 249-line test are removed.
- **npm `CacheWriteEvent` is fired on every successful asset save** so the speculative pre-fetch wired in commit `e9eb477c7` actually dispatches for npm. The npm proxy persists tarballs through `RxNpmProxyStorage` directly — bypassing `BaseCachedProxySlice` / `ProxyCacheWriter`, which is what fires the registry callback for the maven / gradle / pypi / etc. proxies — so the npm-prefetch parser had no firing trigger before this. New `NpmCacheWriteBridge` materialises the freshly-saved bytes into a temp file and fires `CacheWriteCallbackRegistry.instance().sharedCallback().accept(event)` synchronously, mirroring `BaseCachedProxySlice.fireOnCacheWrite` + `ProxyCacheWriter.materialiseCallbackTempFile` contracts (file is alive for the consumer's synchronous duration, deleted after the consumer returns). Verified by `pantera_prefetch_dispatched_total{ecosystem="npm"} > 0` after a single `npm install` against `npm_group → npm_proxy`. The CHANGELOG line above for "Speculative direct-dependency pre-fetch for ... npm proxies" is now fully accurate end-to-end.
- **Per-stage handler-chain profiling for the npm cold path (Phase 10.5 instrumentation).** `DownloadAssetSlice`, `DownloadPackageSlice`, `NpmCacheWriteBridge`, and `PrefetchDispatcher` now emit `pantera_proxy_phase_seconds{repo_name,phase}` histograms tagged with the npm slice's `repoName` so the npm cold-cache wall can be decomposed without bringing `BaseCachedProxySlice` into the structurally-different npm path. Phases emitted: `asset_total`, `asset_cache_check`, `asset_upstream_fetch_and_save` (tarball flow); `packument_total`, `packument_metadata_fetch` (packument flow); `bridge_total`, `bridge_storage_read`, `bridge_temp_write` (cache-write bridge); `dispatcher_snapshot_copy` (PrefetchDispatcher hot-path snapshot). Single-bench profile of `npm install express` cold cache, sorted by `delta_sum`: `asset_total` 13.65 s / 252 reqs (54 ms avg, dominant); `bridge_total` 2.12 s / 74 misses (29 ms avg async); `bridge_storage_read` 0.74 s, `dispatcher_snapshot_copy` 0.67 s, `bridge_temp_write` 0.50 s. Aggregate per-cache-miss bridge work ~96 ms (storage_read + temp_write + dispatcher_snapshot_copy) running on the storage executor pool — eliminating it would require either (a) passing the FileStorage on-disk path directly through `CacheWriteEvent` (storage interface change + contract change) or (b) relaxing the `bytesOnDisk` lifetime contract so the dispatcher can take ownership via `Files.move`; both are larger structural changes than fit Phase 10.5's safety budget. **10-iteration cold-cache `npm install express`** with the instrumentation in place: pantera p50 **3.62 s** (was 3.07 s pre-Phase-10.5; network baseline drift — direct p50 also moved 1.22 s → 1.39 s), ratio **2.60×** direct (target ≤1.5×). The Phase 10.5 ratio is within the natural variance band of the pre-existing Phase 10 number; the residual 2-3× gap is bounded below by `asset_total` (HTTP roundtrip to npmjs.org + storage save + storage reload), not by dispatcher CPU. Per-phase instrumentation ships permanently for future cold-path tuning.
- **Async prefetch dispatch off the response thread (Phase 10 hotfix).** `PrefetchDispatcher.onCacheWrite` previously ran parser + per-dependency packument lookup + `coordinator.submit()` synchronously on the same thread that completed the cache-write — i.e. the response thread for the foreground request. For npm — where the parser does gunzip + tar extract + JSON parse + per-dep packument disk-read (1-5 MB each) + semver walk — this added 5-20 ms of CPU+disk per cache write. With ~280 cache writes during a cold `npm install express`, the synchronous overhead alone accounted for 4-5 s of wall time. Maven was lighter (XMLStream parser) but still synchronous. The dispatcher now (a) snapshots the freshly-cached bytes synchronously to a dispatcher-owned temp file (the source temp file is deleted by the cache writer as soon as the callback returns), then (b) hands the parse + per-dep lookup + submit off to a small bounded `ThreadPoolExecutor` (core 2, max 4, queue 1024, `AbortPolicy` → drop-and-count). The hot-path cost is now: O(1) parser-decision lookups + a single `Files.copy` + an `executor.execute()` call. Drop-on-full surfaces via the `prefetch.dropped_total` field on a WARN log; `PrefetchDispatcher.droppedEventsTotal()` exposes the counter for admin diagnostics. `VertxMain.stop()` drains the dispatch executor (5 s budget, falls back to `shutdownNow`) between `PrefetchCoordinator.stop()` and `RuntimeSettingsCache.stop()`. **npm cold-cache `npm install express` (10 iterations)**: p50 dropped from **5.76 s** (4.74× direct) to **3.07 s** (2.53× direct, direct p50 1.22 s); range 2.86–3.62 s; mean 3.14 s; stdev 0.24 s. Still above the v2.2.0 ≤1.5× bar — the residual 1.85 s gap above direct npmjs is dominated by per-host upstream semaphore contention between the speculative prefetch fan-out and the foreground tarball requests (per-upstream cap is 4 for npm), not by parser or dispatcher CPU. **Maven cold-cache** (`mvn dependency:resolve sonar-maven-plugin`, 10 iterations): p50 14.51 s vs the previous 13.74 s — within the natural variance band (stdev 1.23 s); the synchronous Maven parser was already lightweight so the async-dispatch win is roughly noise-bounded for this ecosystem.

#### Operational tunables (hot-reloadable, admin UI editable)

All settings round-trip through `RuntimeSettingsCache` with PostgreSQL `LISTEN/NOTIFY settings_changed` (Flyway V127 trigger) for cluster-wide hot-reload; no restart required for runtime flips.

| Key                                                            | Default | Notes                                                  |
|----------------------------------------------------------------|---------|--------------------------------------------------------|
| `http_client.protocol`                                         | `h2`    | `h1` to disable HTTP/2 globally                        |
| `http_client.http2_max_pool_size`                              | 4       | Per upstream                                           |
| `http_client.http2_multiplexing_limit`                         | 100     | Streams per connection                                 |
| `prefetch.enabled`                                             | true    | Master switch                                          |
| `prefetch.concurrency.global`                                  | 64      | Coordinator-wide concurrent fetches                    |
| `prefetch.concurrency.per_upstream`                            | 16      | Per-host semaphore (fallback for ecosystems without override) |
| `prefetch.concurrency.per_upstream.maven`                      | 16      | maven per-host cap (overrides global)                  |
| `prefetch.concurrency.per_upstream.gradle`                     | 16      | gradle per-host cap (overrides global)                 |
| `prefetch.concurrency.per_upstream.npm`                        | 4       | npm per-host cap — lower so foreground install wins    |
| `prefetch.queue.capacity`                                      | 2048    | Bounded coordinator queue                              |
| `prefetch.worker_threads`                                      | 8       | Dedicated worker pool                                  |
| `prefetch.circuit_breaker.drop_threshold_per_sec`              | 50      | Drops/s above which the breaker disables prefetch      |
| `prefetch.circuit_breaker.window_seconds`                      | 10      | Sliding window for drop-rate                           |
| `prefetch.circuit_breaker.disable_minutes`                     | 5       | Cool-down on auto-disable                              |

Per-repo override: `RepoConfig.prefetchEnabled` (default true) — toggleable from the repo edit page.

#### Notable internals

- New `RuntimeSettingsCache` with PostgreSQL `LISTEN/NOTIFY` hot-reload (Flyway V127 trigger).
- `JettyClientSlices` now ALPN-negotiates HTTP/2 with HTTP/1.1 fallback.
- `BaseCachedProxySlice.cacheResponse` fires an `onCacheWrite(CacheWriteEvent)` extension point consumed by the prefetch dispatcher.
- `ProxyCacheWriter` parallelises primary + `.sha1` sidecar fetch on cache miss (was sequential — saves 1 RTT per miss).
- Speculative `.md5 / .sha256 / .sha512` upstream fetches removed from the cold-cache path; they're proxied on demand if a client asks.
- Per-host upstream semaphores + bounded coordinator queue with auto-disabling circuit breaker on drop spikes.
- `GroupResolver` parallel branch + outcome-aggregation helpers (`handleProxyMember*`, `completeProxyIfAllExhausted`, `handleFanoutMemberResponse`, `completeFanoutIfAllExhausted`, `handleTargetedMemberResponse`, `completeTargetedIfAllExhausted`) deleted; `querySequentially` is the only fanout primitive.
- `RepositorySlices.warnIfLegacyMembersStrategy` logs the one-time tolerated-`members_strategy` WARN; one entry per group repo per process.

#### Operator notes

- To disable HTTP/2 globally: `PATCH /api/v1/settings/runtime/http_client.protocol` with `{"value":"h1"}` (admin token).
- To disable pre-fetch globally: `PATCH /api/v1/settings/runtime/prefetch.enabled` with `{"value":false}`.
- To disable pre-fetch for a single repo: PUT the repo config with `settings.prefetch: false`.
- Performance Tuning admin UI page exposes all of the above.

### Per-ecosystem prefetch concurrency tunables (new)

Three new runtime settings let operators tune `prefetch.concurrency.per_upstream`
per ecosystem. The global key remains as the fallback for ecosystems without
an explicit override.

| Setting                                    | Default | Effect                                                                       |
|--------------------------------------------|---------|------------------------------------------------------------------------------|
| `prefetch.concurrency.per_upstream.maven`  | 16      | Cap on in-flight maven prefetch GETs to one upstream host                    |
| `prefetch.concurrency.per_upstream.gradle` | 16      | Same, gradle                                                                 |
| `prefetch.concurrency.per_upstream.npm`    | **4**   | Lower default — npm install issues bursty parallel tarball requests; reserving most upstream slots for the foreground keeps cold-install latency competitive with direct npmjs |

Lookup at submit-time uses the lower-cased ecosystem name (`Coordinate.Ecosystem.name()`);
unknown ecosystems fall back to the global `prefetch.concurrency.per_upstream`. The
per-host semaphore is created with the ecosystem-specific cap on first sight of a
host, so two upstreams of different ecosystems get different caps and two upstreams
of the same ecosystem share the same cap value (with distinct semaphore instances).

Cold-cache `npm install express` (10-iteration median, 2026-05-05):
- Before this change (npm prefetch with global 16): 5.53 s p50 (4.37× direct)
- After (npm = 4, this commit): 5.76 s p50 (4.74× direct) — within run-to-run noise
- After (npm = 2, manual override): 5.09 s p50 (3.92× direct)
- After (npm = 1, manual override): 5.17 s p50 (3.95× direct)
- Direct registry.npmjs.org baseline: 1.21 s p50

The acceptance bar of ≤1.5× was **not met** at any setting. Lowering the npm
per-upstream cap below 4 produced no further improvement (3.92× at 2; 3.95× at
1). This rules out upstream pool contention from prefetch as the dominant
cause of the cold-cache npm regression — at npm=1 the dispatcher can issue
at most one in-flight prefetch per host, leaving 99 % of the upstream pool
free for the foreground install, and the ratio still sits at 3.95×. The
residual latency must therefore be in the per-request handler chain (group
fanout, single-flight dedup, cooldown filter, cache writer) for npm
metadata + tarball requests, not in the prefetch coordinator. The keys are
shipped at the npm=4 default anyway; they cost nothing when the bottleneck
is elsewhere and they are immediately useful the day a prefetch-vs-foreground
pool fight does become the dominant cost.

Maven `sonar-maven-plugin` cold bench unchanged (10-iteration median): 13.74 s
(maven default still 16; range 13.34–15.53 s, mean 14.00 s, stdev 0.66 s).
Within run-to-run noise of the baseline 13.01 s — no regression.
Bench artefacts: `performance/results/cold-bench-npm-10x.{csv,md}`,
`performance/results/cold-bench-10x.{csv,md}`.

### 🏗️ Architectural changes

- **Circuit breaker: rate-over-sliding-window replaces consecutive-count.** The pre-2.2.0 design tripped at any `N` consecutive failures regardless of total volume — a cold-start burst of 3 TCP timeouts on a novel upstream would open the circuit for 40 s, producing silent 503s for every in-flight client during that window. Reproduced against `gradle_proxy` during a Gradle smoke test. Replaced with the Hystrix / Resilience4j pattern: each remote keeps a ring buffer of per-second buckets over a configurable window; the breaker opens only when `failures/total ≥ failureRateThreshold` AND `total ≥ minimumNumberOfCalls`. The minimum-volume gate is what makes cold-start bursts harmless — a handful of failures at startup is below the gate and never trips. Sustained failure (≥50% across 20+ calls in 30s by default) trips as fast as before. Flaky upstreams running below the rate threshold never trip, regardless of duration. Fibonacci back-off on repeat trips (initial → 2x → 3x → 5x → … → cap at `maxBlockDuration`) is preserved. Settings are DB-persisted in `auth_settings` under `circuit_breaker_*` keys (Flyway V122), fall through to `PANTERA_CIRCUIT_BREAKER_*` env vars, fall through to `AutoBlockSettings.defaults()` as the last fallback. Admin UI exposes all 5 tunables at `SettingsView` → Circuit Breaker panel; the `PUT /api/v1/admin/circuit-breaker-settings` endpoint round-trips proposed values through the `AutoBlockSettings` record constructor for invariant validation before persisting. In-memory loader cache invalidates on PUT so next outcome across every upstream picks up the new values — no restart. Defaults: `failureRateThreshold=0.5`, `minimumNumberOfCalls=20`, `slidingWindowSeconds=30`, `initialBlockDuration=20s` (halved from the prior 40s), `maxBlockDuration=5min` (unchanged). 17 registry tests + 5 member-slice tests cover trips, recovery, Fibonacci, concurrent writers, min-volume gate, rate-boundary semantics, and block cap.
  ([@aydasraf](https://github.com/aydasraf))
- **`GroupResolver` is now the sole production group-resolution engine** (WI-04). The deprecated `GroupSlice` (1338 LOC) and its four dedicated test classes are deleted. All four wiring sites in `RepositorySlices.java` (npm-group, file/php-group, maven-group, generic group-adapter) now instantiate `GroupResolver`; `MavenGroupSlice` and `ComposerGroupSlice` receive it as their `Slice` delegate. A new convenience constructor on `GroupResolver` accepts the legacy `(SliceResolver, memberNames, port, ...)` shape so call-sites didn't balloon. `GroupSliceMetrics` is renamed `GroupResolverMetrics`; stale javadoc / inline-comment references across 15 production files updated. Status surface unchanged (200/404/500/502 — no 503/504 from group resolution per spec). Behavioural improvements: index-hit 404 falls through to proxy fanout (TOCTOU fix, A11), `AllProxiesFailed` passes the winning proxy's response through (was synthetic 502), and 5xx responses carry `X-Pantera-Fault` + `X-Pantera-Proxies-Tried` headers.
  ([@aydasraf](https://github.com/aydasraf))
- **`CooldownResponseRegistry` is the mandatory cooldown-403 path.** The deprecated `CooldownResponses.forbidden(...)` helper had 12 production callers across 6 adapters and one pantera-core fallback; all migrated to `CooldownResponseRegistry.instance().getOrThrow(repoType).forbidden(block)` and the legacy class is deleted. The former silent `.orElseGet(() -> CooldownResponses.forbidden(block))` fallback in `BaseCachedProxySlice` now throws `IllegalStateException` on a missing factory — factory registration is a startup-time hard requirement. `CooldownWiring` adds response-factory aliases (`npm-proxy`, `pypi-proxy`, `docker-proxy`, `go-proxy`, `php`, `php-proxy`) so every repoType resolves.
  ([@aydasraf](https://github.com/aydasraf))
- **Sealed `Fault` hierarchy + `Result<T>` + `FaultTranslator`** introduced as the single decision point for "what HTTP status does this fault produce" (WI-01). `Fault` variants: `NotFound`, `Forbidden`, `IndexUnavailable`, `StorageUnavailable`, `AllProxiesFailed`, `UpstreamIntegrity`, `Internal`, `Deadline`, `Overload`. `FaultClassifier.classify(Throwable, String)` is the fallback for `.exceptionally(...)` handlers. 99% instructions / 97% branches coverage on the `fault` package; exhaustive-switch guard test locks the contract.
  ([@aydasraf](https://github.com/aydasraf))
- **`SingleFlight<K,V>` is the one coalescer in the codebase** (WI-05 + WI-post-05). Consolidates the former hand-rolled `inFlightFanouts` (GroupSlice), `inFlightMetadataFetches` (MavenGroupSlice), and `RequestDeduplicator` (CachedNpmProxySlice, BaseCachedProxySlice). Caffeine `AsyncCache`-backed with stack-flat follower completion (the v2.1.3 `StackOverflowError` at ~400 concurrent followers cannot recur), explicit zombie eviction via `CompletableFuture.orTimeout`, and per-caller cancellation isolation. `RequestDeduplicator.java`, `RequestDeduplicatorTest.java`, and the `DedupStrategy` enum are gone; the nested `FetchSignal` enum is promoted to a top-level type at `pantera-core/http/cache/FetchSignal.java`.
  ([@aydasraf](https://github.com/aydasraf))
- **`RequestContext` full ECS/APM envelope** (WI-02). 13-field record covering every ECS key Pantera emits (`trace.id`, `transaction.id`, `span.id`, `http.request.id`, `user.name`, `client.ip`, `user_agent.original`, `repository.name`, `repository.type`, `package.name`, `package.version`, `url.original`, `url.path`) plus an end-to-end `Deadline`. A four-arg backward-compat ctor is retained. `ContextualExecutor.contextualize(Executor)` propagates the `ThreadContext` snapshot + APM span across `CompletableFuture` boundaries — wired at `DbArtifactIndex`, `GroupResolver` drain executor, `BaseCachedProxySlice` SingleFlight, `CachedNpmProxySlice` SingleFlight, and `MavenGroupSlice` SingleFlight.
  ([@aydasraf](https://github.com/aydasraf))
- **`StructuredLogger` 5-tier facade** (WI-03). `access()` (Tier-1 client→pantera), `internal()` (Tier-2 pantera→pantera 500), `upstream()` (Tier-3 pantera→remote), `local()` (Tier-4 local ops), `audit()` (Tier-5 compliance, INFO, non-suppressible). Central `LevelPolicy` enum encodes the §4.2 log-level matrix in one place. Closed `AuditAction` enum enumerates the only four compliance events (`ARTIFACT_PUBLISH`, `ARTIFACT_DOWNLOAD`, `ARTIFACT_DELETE`, `RESOLUTION`) per §10.4. `EcsLoggingSlice` now emits the access log exactly once per request via `StructuredLogger.access().forRequest(ctx)` on the success path (legacy dual-emission removed).
  ([@aydasraf](https://github.com/aydasraf))
- **Auth cache L1/L2 + cluster-wide invalidation** (Group B). New `CachedLocalEnabledFilter` wraps `LocalEnabledFilter` the way `CachedUsers` wraps `Users`: L1 Caffeine + L2 Valkey + `CacheInvalidationPubSub` cross-node eviction. Hit rate is expected >95 %; the per-request JDBC hit is gone. `UserHandler` invalidates on put / delete / enable / disable / alterPassword — admin changes propagate to peer nodes within 100 ms. Driven by `meta.caches.auth-enabled.*` (env `PANTERA_AUTH_ENABLED_*` overrides), no hardcoded cache settings.
  ([@aydasraf](https://github.com/aydasraf))
- **`GroupMetadataCache` stale tier is now 2-tier, aid-not-breaker** (Group C). The former unbounded `lastKnownGood` `ConcurrentHashMap` is replaced by L1 Caffeine (bounded, 30-day TTL) + L2 Valkey (no TTL by default — Valkey `allkeys-lru` owns eviction). Degradation on read is L1 → L2 → expired primary-cache entry → miss. Under realistic cardinality no eviction fires; bounds are a JVM-memory safety net only. **L2 now survives JVM restart** (the old CHM did not), strictly improving availability. Driven by `meta.caches.group-metadata-stale.*` with full env-var override chain.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown package restructured** into SOLID sub-packages `api/`, `cache/`, `metadata/`, `response/`, `config/`, `metrics/`, `impl/`. `CooldownMetadataServiceImpl` renamed to `MetadataFilterService`. `CooldownAdapterBundle<T>` + `CooldownAdapterRegistry` populated at startup; queried on every proxy request. All 7 adapters (Maven, npm, PyPI, Docker, Go, Composer, Gradle aliased to Maven) registered with aliases.
  ([@aydasraf](https://github.com/aydasraf))
- **`PublishDateRegistry` replaces per-adapter `CooldownInspector` implementations.** New `DbPublishDateRegistry` with Caffeine L1 + Postgres L2 (`artifact_publish_dates` table, Flyway V125) + pluggable `PublishDateSource` fallback. Six source implementations query canonical upstream registries: Maven Solr (`search.maven.org`), npm (`registry.npmjs.org`), PyPI JSON API, Go proxy, Composer/Packagist, RubyGems. `MavenHeadSource` fetches `Last-Modified` via HEAD to Maven Central; `JFrogStorageApiSource` provides `/api/storage/` fallback for JFrog mirrors; `ChainedPublishDateSource` composes primary+fallback. `RegistryBackedInspector` is the single cooldown inspector wired at startup — all former per-adapter `CooldownInspector` classes deleted. Cooldown timeout reduced from 5s to 2s. `pantera.publish_date.lookup` metric replaces the former `cooldown_inspector` cache metrics.
  ([@aydasraf](https://github.com/aydasraf))

### ⚡ Performance

- **`ProxyCacheWriter` — atomic primary + sidecar write with digest verification** (WI-07). The `oss-parent-58.pom.sha1` class of cache-drift bug (primary bytes and the sidecar they're verified against diverging across stale-while-revalidate refetches) can no longer produce a committed cache entry. Streams the primary into a NIO temp file (bounded chunk size, no heap scaling with artifact size) while updating four `MessageDigest` accumulators (MD5, SHA-1, SHA-256, SHA-512) in one pass; pulls sidecars concurrently; compares trimmed-lowercased hex bodies against the computed digest; saves primary-first-then-sidecars only on agreement. Mismatch → `Result.err(Fault.UpstreamIntegrity(...))`; nothing lands in the cache. Wired into maven / pypi / go / composer (WI-post-07).
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown filter performance hardenings** (H1-H5). Pre-warm release-date cache (`MetadataParser.extractReleaseDates()` SPI bulk-populates `CooldownCache` L1 with `false` for versions older than the cooldown period — avoids DB/Valkey round-trip for the majority). Parallel bounded version evaluation via `CompletableFuture.allOf()` on a dedicated 4-thread executor, bounded to 50 versions per request. Stale-while-revalidate on `FilteredMetadataCache` with 5-minute grace. L1 capacity increased to 50K entries (`PANTERA_COOLDOWN_METADATA_L1_SIZE`). `CooldownCache` inflight-map memory leak fixed — guaranteed removal on success, error, and cancellation via `whenComplete` + 30 s `orTimeout` zombie safety net.
  ([@aydasraf](https://github.com/aydasraf))
- **Zero-copy `ArtifactHandler` chunks** (Group E.1). Download paths replace per-chunk `new byte[] + buf.get(bytes) + Buffer.buffer(bytes)` with `Buffer.buffer(Unpooled.wrappedBuffer(buf))`. At 1000 req/s × 5 MB bodies × 64 KB chunks that was ~80 000 byte[] allocations/second straight to garbage — now zero. Vert.x releases on write completion.
  ([@aydasraf](https://github.com/aydasraf))
- **StAX streaming Maven metadata merge** (Group E.2). `MavenGroupSlice` delegates to a new `StreamingMetadataMerger` (hardened against XXE) that accumulates only the deduplicated `<version>` `TreeSet` and newest-wins scalars. Peak memory is `O(unique versions)`, not `O(sum of member body sizes)`. Malformed or truncated member bodies are skipped with a WARN; remaining members still merge. No size cap is introduced — doing so would synthesize a client-facing 502 for legitimately large metadata. An alert-only histogram `pantera.maven.group.member_metadata_size_bytes` surfaces outliers to ops.
  ([@aydasraf](https://github.com/aydasraf))
- **Hot-path `Pattern.compile` hoisted to `static final`** (Group D). `TrimmedDocker.trim()` and `SubStorage.list()` previously compiled the regex on every call; both now hold a final field compiled once in the ctor. At 1000 req/s this eliminates thousands of compile allocations per second across Docker and storage-list request paths.
  ([@aydasraf](https://github.com/aydasraf))
- **`String.format("%02x", ...)` checksum hex loop replaced with `java.util.HexFormat.of().formatHex(...)`** in `MavenGroupSlice` (Group E.3). Single allocation per request instead of 20 per checksum. Mirrors the existing `ProxyCacheWriter.HEX` idiom.
  ([@aydasraf](https://github.com/aydasraf))
- **`Yaml2Json` / `Json2Yaml` `ObjectMapper` hoisted to `static final`** (Group E.4). Previously allocated a fresh `ObjectMapper` (and `YAMLMapper`) on every call. Admin-plane only, but the reflection warm-up cost is real. Jackson feature configuration applied once at static init — safe under the JMM.
  ([@aydasraf](https://github.com/aydasraf))
- **Serve-before-commit across all proxy adapters.** New `ProxyCacheWriter.writeAndVerify()` returns a `VerifiedArtifact` record after streaming upstream bytes to a verified temp file — but BEFORE committing to storage. The HTTP response streams from the temp file via `contentFromTempFile()` (lazy Flowable with auto-delete disposer) while `commitAsync()` persists to cache in the background. Eliminates the synchronous storage-commit from the client-facing latency path. Wired into Maven, Go, Composer, and PyPI adapters.
  ([@aydasraf](https://github.com/aydasraf))
- **Parallel sidecar saves.** `ProxyCacheWriter.saveSidecars()` changed from sequential `thenCompose` chain to `CompletableFuture.allOf()` — all 4 checksum sidecars (MD5, SHA-1, SHA-256, SHA-512) save concurrently instead of serially.
  ([@aydasraf](https://github.com/aydasraf))
- **Preemptive Basic auth for Maven/Gradle.** Maven settings template adds `<usePreemptive>true</usePreemptive>` to server configuration, eliminating the 401 challenge-response round-trip on every artifact fetch. Previously every Maven request hit Pantera unauthenticated, received 401, then retried with credentials — doubling request count.
  ([@aydasraf](https://github.com/aydasraf))

### 🔧 Bug fixes

- **Search & Browse overhaul (bugs A–F).** (a) Search date sort now honours `sort_dir` — `SearchHandler` emitted `"date"` as wire value, not recognized by `DbArtifactIndex.toSortField`, silently degrading to `RELEVANCE`. (b) Natural name sort via `natural_sort_key(text)` SQL function (V123 migration) — `pkg-10` now sorts after `pkg-2`. (c) Metadata/checksum/signature noise excluded — V124 `artifact_kind` STORED column via `classify_artifact(text)`; all search paths append `AND artifact_kind = 'ARTIFACT'`. (d) Search UI shows upload date with relative-time tooltip. (e) Repo browser rewritten with DB-hydrated file rows (size, `created_at`, `artifact_kind`), server-side `sort=name|date|size` + `sort_dir=asc|desc`, three-column `ArtifactTreeTable` UI component with header-click sort. (f) Conan uploads now index via `ArtifactEvent` queue threading. (g) DELETE artifacts DB-fallback for UI-created repos. (h) Dead SQL constants + legacy search helpers deleted; single filtered search code path.
  ([@aydasraf](https://github.com/aydasraf))
- **Maven adapter event queue (`BaseCachedProxySlice.java`) now increments the `pantera.events.queue.dropped` counter on overflow**, matching the npm adapter pattern. The previous `offer()` call ignored the boolean return value — under burst load the bounded queue silently dropped events with zero signal. Regression: `BaseCachedProxySliceQueueFullTest`.
  ([@aydasraf](https://github.com/aydasraf))
- **Group RaceSlice priority-based outcome selection.** `RaceSlice` now picks the winning response by priority: 2xx > 403 > 5xx > 404. Previously the first non-404 response won, so a member returning 500 could shadow a healthy member returning 200 that completed slightly later. Race now continues past 5xx and exceptions, falling through to the next member.
  ([@aydasraf](https://github.com/aydasraf))
- **Upstream 4xx propagated as 404 to RaceSlice for correct fallback.** All proxy adapters (Go, PyPI, Composer, npm, generic group) now map non-404 upstream 4xx responses to `NOT_FOUND` so `RaceSlice` falls through to the next member instead of treating a single member's 4xx as the final answer.
  ([@aydasraf](https://github.com/aydasraf))
- **502 not 503 for upstream failures (spec alignment).** Group and Maven adapters return 502 Bad Gateway when an upstream proxy fails, reserving 503 for Pantera-internal overload conditions per HTTP spec.
  ([@aydasraf](https://github.com/aydasraf))
- **ECS log field compliance (6-commit migration).** 488 non-compliant log fields across all modules migrated to valid ECS namespaces. Fields that were wrongly inlined (`repo.name`, `cache.hit`, etc.) moved to `pantera.*` custom namespace or folded into `message` text. Valid ECS fields (`http.request.method`, `url.path`, etc.) that were accidentally inlined are restored to their proper ECS keys. `process.name` field in `EventsProcessor` inlined into message.
  ([@aydasraf](https://github.com/aydasraf))
- **Cache-write failures now logged before surfacing as 503.** `ProxyCacheWriter` and `BaseCachedProxySlice` emit structured WARN with `event.action=cache_write_failure` before returning the error response, closing a diagnostic gap where 503s had no application-layer context.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown evaluation errors fail open in `ProxyCacheWriter` gate.** If the cooldown `evaluate()` call throws, the artifact is served rather than returning 500. Matches the fail-open policy used elsewhere in the cooldown stack.
  ([@aydasraf](https://github.com/aydasraf))
- **`ComposerGroupSlice` ClassCastException on `providers` field.** Packagist returns `"providers": []` (empty JSON array) for repositories with no providers, but `ComposerGroupSlice.mergePackagesJson` called `json.getJsonObject("providers")` unconditionally — producing a `ClassCastException` → 500 on every `php_group/packages.json` request when any member returned the array form. Added `instanceof JsonObject` type guard, matching the existing pattern for the `packages` field.
  ([@aydasraf](https://github.com/aydasraf))
- **PyPI `PypiJsonHandler` constructor regression.** `jsonApiUpstream` parameter was incorrectly threaded through a refactor, breaking the JSON API endpoint. Restored as a separate constructor argument.
  ([@aydasraf](https://github.com/aydasraf))
- **Group lookup returned 404 for Maven artifacts with unconventional extensions (e.g. `.graphql`, `.tar.gz`).** Three code sites decide "is this file a Maven artifact?" — the upload path (`maven-adapter/.../UploadSlice.isPrimaryArtifactPath`), the group-lookup parser (`pantera-main/.../ArtifactNameParser.parseMaven`), and the backfill scanner (`pantera-backfill/.../MavenScanner`). Upload and lookup both use structural detection (`filename.startsWith(artifactId + "-")`), but the scanner carried a hardcoded extension whitelist `{.jar, .war, .aar, .zip, .pom, .module}`. When the scanner rebuilt the index after any subsequent run, legitimately-uploaded artifacts whose only extension was outside that list — observed against `wkda/common/graphql/retail-classifieds-content-gql/1.0.0-628-202510161022/retail-classifieds-content-gql-1.0.0-628-202510161022.graphql` served by `libs-release-local` but 404'd by `libs-release` — were silently dropped from the DB index, so `GroupResolver`'s index lookup returned "no members" and the 5-path decision tree's index-miss branch proxy-only fanout skipped the hosted member that actually had the file. Replaced the whitelist with the same structural invariant the other two sites use (accepts both `-` and `_` as legacy internal separators) — any file under a Maven-layout path whose name starts with its `artifactId-` prefix is indexed, regardless of extension. Blocklist still catches hidden files, `.md5` / `.sha1` / `.sha256` / `.sha512` / `.asc` / `.sig` / `.pantera-meta.json` / `maven-metadata.xml*`. Newly-uploaded `.graphql` files were never affected (the upload path was already structural) — this only restores backfill consistency. Regression: `MavenScannerTest#indexesUnconventionalExtensions`, `#rejectsFilesNotMatchingArtifactId`, `#skipsMavenMetadataXmlByName`.
  ([@aydasraf](https://github.com/aydasraf))
- **Silent 503 emissions across three proxy-path code sites.** `CircuitBreakerSlice.response()` at `pantera-core/.../slice/CircuitBreakerSlice.java:57-62` emitted `503` without any `EcsLogger` call when `AutoBlockRegistry.isBlocked(remoteId)` tripped. Similarly `BaseCachedProxySlice.signalToResponse()` at `:608-617` (SingleFlight `FetchSignal.ERROR` fallback) and `FaultTranslator.translate(Fault.Overload)` at `:116-119` returned `503` with no structured log. Operators investigating "why did this client get 503?" had no way to tell the circuit breaker from a coalescer error from a bulkhead overload — the access log showed the status but no paired `event.action` or `event.reason`. Observed during a Gradle smoke test against `gradle_group/com/github/ben-manes/gradle-versions-plugin/.../*.module` where every 503 had exactly 3 log lines total for the trace (auth success x2 + access log 503) with zero application-layer context. All three sites now emit structured logs: `CircuitBreakerSlice` at WARN with `event.action=circuit_breaker_open` + `remote.id` + `url.path`; `AutoBlockRegistry.recordFailure` at WARN on CLOSED→OPEN transition with `failure.count` / `failure.threshold` / `block.duration_ms`; `AutoBlockRegistry.recordSuccess` at INFO on PROBING→ONLINE recovery; `AutoBlockRegistry.isBlocked` at INFO on BLOCKED→PROBING transition; `BaseCachedProxySlice.signalToResponse` at WARN with `event.action=proxy_fetch_coalesced_error`; `FaultTranslator` at INFO with `event.action=fault_translated` + `fault.resource`. No behaviour change — the 503s and state transitions are identical, they just stop being invisible.
  ([@aydasraf](https://github.com/aydasraf))
- **Maven / Gradle cooldown metadata filter was never invoked on `maven-metadata.xml` responses.** `MavenMetadataFilter` existed and `CooldownAdapterRegistry` registered it at startup (CHANGELOG v2.2.0 § "Cooldown package restructured" claimed all 7 adapters covered), but `CachedProxySlice.handleMetadata()` cached the upstream XML and returned it verbatim — the filter was never called. Observed with a Gradle build using `implementation 'com.google.guava:guava:latest.release'` resolving to `33.6.0-jre` (released 8 days earlier, against a 14-day cooldown) — the `<versions>` list and `<latest>` / `<release>` tags went through unfiltered, so Gradle's `latest.release` resolution picked the fresh version and fetched it. Fix: inject `CooldownMetadataService` into `CachedProxySlice` + `MavenProxySlice` + `MavenProxy` constructor chain, thread from `RepositorySlices` on the `maven-proxy` / `gradle-proxy` case. `handleMetadata()` now drains the cached upstream bytes, invokes `filterMetadata(repoType, repoName, packageName, bytes, new MavenMetadataParser(), new MavenMetadataFilter(), new MavenMetadataRewriter(), Optional.of(inspector))`, returns the filtered XML; fails open on any non-`AllVersionsBlockedException` error (availability > strictness, matches npm behaviour); returns 403 when every version is blocked. Metadata cache stores UNFILTERED upstream bytes so the filter re-evaluates per request — caching filtered output would produce stale decisions as versions age out of the cooldown window. Regression: `CachedProxySliceTest#handleMetadataInvokesCooldownFilterWhenServiceProvided` + `#handleMetadataPassesThroughWhenNoCooldownMetadataService`.
  ([@aydasraf](https://github.com/aydasraf))
- **Go cooldown was a no-op for every `v`-prefixed version** (the Go canonical form in `/@latest` JSON and `/@v/list` lines). `GoCooldownInspector.releaseDate()` built `/%s/@v/v%s.info` with a hardcoded leading `v`, so a caller passing `"v1.2.3"` produced `/…/@v/vv1.2.3.info` — upstream 404 → no `Last-Modified` → empty release date → fail-open. `GoLatestHandler` and `GoListHandler` both fed `v`-prefixed strings straight in, so the rewrite was failing, not the enforcement — users saw `go get <mod>` resolve to a fresh version that immediately 403'd on the subsequent `.info` fetch (the artifact path regex happened to strip the `v`). Normalized once at the inspector boundary via `stripLeadingV(version)`; every caller now hits the correct URL regardless of prefix convention. Regression: `GoCooldownInspectorTest#normalisesLeadingVInVersion` captures every upstream path and asserts no `/@v/vv` signature.
  ([@aydasraf](https://github.com/aydasraf))
- **PyPI `upload-time` emitted with 9-digit nanosecond precision**, violating PEP 700 (`yyyy-mm-ddThh:mm:ss.ffffffZ` — max 6 fractional digits). Source: Linux filesystem `creationTime()` has nanosecond resolution; `Instant.toString()` faithfully emits all 9 digits. Python's `datetime.fromisoformat` rejects >6 fractional digits on every CPython version through 3.13 (the datetime object can't hold nanoseconds), so `pip`'s `parse_links` aborted the whole package-index parse on any one non-compliant entry — reported from a datasci `pip3 install` pinned to Python 3.9 with `Invalid isoformat string: '2026-03-24T19:38:57.874672722+00:00'`. Truncate to microseconds at every source point (`PypiSidecar` read/write + self-heal fallback, `WheelSlice` upload timestamp, `SimpleJsonRenderer` emission as defense-in-depth) so `Instant.toString()` naturally emits ≤6-digit `Z`-suffixed strings. **Operators: delete cached `.pypi/<pkg>/<pkg>.json` indices** on upgrade so `SliceIndex`'s self-healing path regenerates them through the fixed renderer.
  ([@aydasraf](https://github.com/aydasraf))
- **Docker cooldown was a no-op for every multi-arch tag** (`ubuntu:latest`, `python:3.12`, `nginx:stable`, etc.). Multi-arch tags resolve to an OCI image index / Docker v2 manifest list, which has no `config` blob and no `layers` — `CacheManifests.releaseTimestamp` bailed early with `Optional.empty()`. That flowed through `inspector.releaseDate()` → `JdbcCooldownService.shouldBlockNewArtifact` → "No release date found - allowing" → fail-open. Observed against `ubuntu:latest` inside a 14-day cooldown window where the proxy served the 6-day-old manifest with no 404. There is no "fallback to older digest" behaviour in `DockerManifestByTagHandler` — a successful build proves the freshness check evaluated as "not blocked", not that it swapped digests. Fix: on `isManifestList()` fetch the first child (typically `linux/amd64`), descend into its config blob, read `.created`. All children of a tag share a build run so any one is representative. Cost bounded to ≤ 1 extra upstream GET pair per novel `(image, tag)` per 24h by the inspector's Caffeine cache (`maximumSize=10_000`, `expireAfterWrite=24h`). Child-fetch failure falls through to the existing "empty release date → allow" path — no new 5xx surface.
  ([@aydasraf](https://github.com/aydasraf))
- **Read-only users locked out of all token-TTL options except 30 / 90 days.** `AppHeader.vue`'s token-generation dialog fetched `api_token_max_ttl_seconds` and `api_token_allow_permanent` via `GET /admin/auth-settings` on mount; non-admins received 403, the catch block swallowed it, and the expiry dropdown fell back to a hardcoded `[30, 90]` list. `/api/v1/auth/me` now embeds the two public auth-settings fields in an `auth_settings` object; `AppHeader.vue` reads them from the auth store via a reactive `watch({ immediate: true })` — no extra network call, no admin gate, and a late `/auth/me` resolution (e.g. silent JWT refresh) still updates the dropdown. The write-path (`PUT /admin/auth-settings`) remains admin-only.
  ([@aydasraf](https://github.com/aydasraf))
- **Admin `api_token_allow_permanent=false` and `api_token_max_ttl_seconds` were cosmetic — server never enforced them.** `AuthHandler.generateTokenEndpoint` ignored the permanent toggle entirely, so a user could `POST /api/v1/auth/token/generate` with `{"expiry_days": 0}` and mint a permanent token regardless of the admin setting. Separately, the endpoint read the legacy `max_api_token_days` key for the TTL cap while the admin UI wrote `api_token_max_ttl_seconds` — the two never met, so flipping the slider in `SettingsView` had no server-side effect. Both gaps closed: permanent requests now return `400 PERMANENT_TOKENS_DISABLED` when the toggle is off; the UI-managed `api_token_max_ttl_seconds` takes precedence, falling back to the legacy key only when unset. Regression tests + a new `AsyncApiTestBase.sharedDs()` accessor so DAO-seeding tests can manipulate DB state. **Existing permanent tokens are NOT retroactively invalidated** when the toggle flips — validation checks `user_tokens.expires_at`, not the current setting. To revoke already-issued permanent tokens use `DELETE /api/v1/auth/tokens/{id}`, `POST /api/v1/admin/revoke-user/{username}`, or a one-off `UPDATE user_tokens SET expires_at = created_at + INTERVAL '1 year' WHERE expires_at IS NULL AND token_type = 'api' AND revoked = FALSE`.
  ([@aydasraf](https://github.com/aydasraf))
- **UI Docker image failed to build on any cold clone (missing PNG assets).** `pantera-ui/src/views/auth/LoginView.vue` and `pantera-ui/src/components/layout/AppHeader.vue` reference `/pantera-128.png` and `/pantera-banner.png`; `index.html` references `/favicon.png` and `/apple-touch-icon.png`. Repo-level `.gitignore` had `*.png` with only a single `!docs/pantera-banner.png` exception, so the four `public/` brand assets were never tracked. Every previous build worked because the files sat in local working trees; every build on a cold CI runner failed at `vite build` with unresolved-import errors and produced a broken Docker image. Whitelisted `pantera-ui/public/*.png` and committed the four assets. Verified with `docker buildx build --platform linux/amd64,linux/arm64 -t pantera-ui:2.2.0 pantera-ui --no-cache --load`.
  ([@aydasraf](https://github.com/aydasraf))
- **Client-disconnect propagation** (Group A). `VertxSliceServer` now registers `closeHandler` on `request.connection()` and `exceptionHandler` on both request and response; captures the reactive-streams `Subscription` via `doOnSubscribe` and cancels it on any disconnect signal. `ArtifactHandler` captures the `Disposable` on both download paths and disposes on response `closeHandler` / `exceptionHandler`. `StreamThroughCache`, `DiskCacheStorage`, and `VertxRxFile.save` gain `doOnCancel` cleanup matching existing `doOnError` — channel closed + temp file deleted on mid-flight disconnect. `Http3Server` enforces a per-stream buffer cap via `PANTERA_HTTP3_MAX_STREAM_BUFFER_BYTES` (default 16 MiB). Resolves the class of "bytes keep streaming into a dead socket until the next write organically fails" that wasted upstream bandwidth and held file descriptors at 1000 req/s with any disconnect churn.
  ([@aydasraf](https://github.com/aydasraf))
- **`DbArtifactIndex` saturation now returns typed fault, not an EL-thread JDBC stall** (Group H.1). The executor's `RejectedExecutionHandler` switches from `CallerRunsPolicy` to `AbortPolicy` — under queue saturation, submissions no longer execute inline on the Vert.x event loop. **BEHAVIOR CHANGE:** saturation surfaces as `Fault.IndexUnavailable`, which `FaultTranslator` returns as `500` with `X-Pantera-Fault: index-unavailable`. The follow-up `locateByName` body is wrapped so the synchronous `RejectedExecutionException` from `CompletableFuture.supplyAsync` is always observed via a failed future, never raw-propagated up the event-loop stack. Chaos test `DbArtifactIndexSaturationTest` locks the policy.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown registry lookups now fail fast** (Group G). `CooldownResponseRegistry.getOrThrow(repoType)` replaces `.get(repoType).forbidden(...)` at all 11 production adapter sites (files / npm / pypi / composer / go / docker) and in `BaseCachedProxySlice`. Missing factory registration surfaces immediately as `IllegalStateException("No CooldownResponseFactory registered for repoType: <type>")` at first request — wiring omissions are caught at canary time instead of NPE'ing on an arbitrary request later.
  ([@aydasraf](https://github.com/aydasraf))
- **Resource-leak fixes on legacy RPM / Debian streams** (Group F). `XmlPrimaryChecksums` and `FilePackageHeader` previously opened `InputStream`s eagerly in their constructors; if the consuming method was never invoked the stream leaked. Both now store only the `Path` and open inside the consuming method under try-with-resources. RPM `Gzip.unpackTar` wraps `GzipCompressorInputStream` in the same try-with as `TarArchiveInputStream` so the native `Inflater` is released if the tar wrapper ctor throws. Debian `MultiPackages.merge` wraps both GZIP streams in try-with-resources; caller-owned outer streams are protected by a non-closing wrapper adapter.
  ([@aydasraf](https://github.com/aydasraf))
- **Queue overflow cascade on npm `DownloadAssetSlice`** (forensic §1.6 F1.1/F1.2). Bounded `LinkedBlockingQueue<ProxyArtifactEvent>` writes on both the cache-hit (line 198) and cache-miss (line 288) paths called `AbstractQueue.add()`, which throws `IllegalStateException("Queue full")` on overflow. A burst of 11,499 such throws in a 2-minute window in prod surfaced as 503s to clients because the exception escaped the serve path. Both call-sites migrated to `queue.offer(event)`; the `ifPresent` enqueue lambda is wrapped in `try { ... } catch (Throwable t) { log at WARN; continue; }` on both paths so background-queue failure can NEVER escape into the response. Verified by `DownloadAssetSliceQueueFullTest`.
  ([@aydasraf](https://github.com/aydasraf))
- **Access-log WARN flood from 4xx client probes** (forensic §1.7 F2.1/F2.2). `EcsLogEvent.log()` emitted every 4xx at WARN, including 404 (Maven probe-and-miss + npm metadata scans), 401 (unauthenticated health checks), 403 (policy deny) — 2.4 M WARN lines in 12 h post-deploy; client-driven, not Pantera fault. Level policy now 404/401/403 → INFO; other 4xx WARN, 5xx ERROR, slow >5 s WARN unchanged. Contract tests lock the matrix.
  ([@aydasraf](https://github.com/aydasraf))
- **`StackOverflowError` class in GroupSlice follower chain** (commit `ccc155f6` / anti-pattern A9). When the leader fanout completed synchronously each follower's `thenCompose(...)` ran on the leader's stack — ~400 followers overflowed. Replaced the bespoke `ConcurrentHashMap<String, CompletableFuture<Void>>` coalescer with `SingleFlight<String, Void>`, which dispatches all follower completions via the configured executor. Regression guard: `stackFlatUnderSynchronousCompletion` (500 followers, synchronous leader, no SOE).
  ([@aydasraf](https://github.com/aydasraf))
- **Upstream sidecar/primary drift in Maven cache** (target-architecture §9.5, production `oss-parent-58.pom.sha1` symptom). Previously `storage.save(primary)` and `storage.save(sidecar)` were independent Rx pipelines; SWR refetch could update the `.pom` without re-pulling `.pom.sha1`, and eviction could drop one without the other — every mode produced the same `ChecksumFailureException` for Maven client builds. `ProxyCacheWriter.writeWithSidecars(...)` is the single write path; mismatch → `Result.err(Fault.UpstreamIntegrity(...))`, nothing lands. Regression test `ProxyCacheWriterTest.ossParent58_regressionCheck` reproduces the exact production hex.
  ([@aydasraf](https://github.com/aydasraf))
- **Jetty client idle-close logged as request failure** (forensic §1.7 F4.4). "Idle timeout expired: 30000/30000 ms" is a connection-lifecycle event, not a request error. 20 ERROR entries per 12 h, all one cause. `JettyClientSlice.isIdleTimeout(Throwable)` identifies the specific `TimeoutException` (up to 5 hops) and downgrades that case to DEBUG. Other HTTP failures still log at ERROR.
  ([@aydasraf](https://github.com/aydasraf))
- **"Repository not found in configuration" at WARN** (forensic §1.7). Client-config error (stale repo URL in a pom.xml), not a Pantera fault. ~1,440 WARN lines per 12 h. Downgraded to INFO.
  ([@aydasraf](https://github.com/aydasraf))
- **Stale `MdcPropagation` text references removed.** The class was deleted from pantera-core in WI-02 but three test files (`CooldownContextPropagationTest`, `ContextualExecutorIntegrationTest`, the now-deleted `GroupSliceFlattenedResolutionTest`) plus `docs/analysis/v2.2-next-session.md:73` still mentioned it textually. All updated to `ContextualExecutor` / `TraceContextExecutor` terminology. Zero `MdcPropagation.` references remain in production code or live tests.
  ([@aydasraf](https://github.com/aydasraf))

### 🧹 Cleanup

- **Legacy `GroupSlice` deleted** (1338 LOC) plus four obsolete test classes (`GroupSliceTest`, `GroupSliceFlattenedResolutionTest`, `GroupSliceIndexRoutingTest`, `GroupSlicePerformanceTest`). Rename `GroupSliceMetrics` → `GroupResolverMetrics`.
  ([@aydasraf](https://github.com/aydasraf))
- **`CooldownResponses` class deleted.** All 12 production callers migrated to `CooldownResponseRegistry.getOrThrow(repoType)`.
  ([@aydasraf](https://github.com/aydasraf))
- **`RequestDeduplicator` / `RequestDeduplicatorTest` / `DedupStrategy` deleted** (WI-post-05). `FetchSignal` promoted to top-level type.
  ([@aydasraf](https://github.com/aydasraf))
- **Dead `api-workers` `WorkerExecutor` removed** (Group H.2). `AsyncApiVerticle` created a `WorkerExecutor` that no route ever referenced.
  ([@aydasraf](https://github.com/aydasraf))
- **UI: legacy `'hex'` repo-type key purged in favour of `'hexpm'`** across `repoTypes.ts`, `techSetup.ts`, `SettingsView.vue`. `SettingsView` now emits `hexpm-proxy` instead of `hex-proxy` — matches the canonical family key `ApiRoutingSlice` normalises to. `SearchView.vue`'s `startsWith('hex')` prefix match is retained since it still matches `hexpm`.
  ([@aydasraf](https://github.com/aydasraf))

### 🆕 Added

- **`pantera-core/http/fault/` sum types** — `Fault` sealed hierarchy, `Result<T>` with `map`/`flatMap`, `FaultClassifier.classify(Throwable, String)` for `.exceptionally(...)` handlers, `FaultTranslator.translate(Fault, RequestContext)` as the single HTTP-status decision point.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera-core/http/resilience/SingleFlight<K,V>`** — unified per-key request coalescer backed by Caffeine `AsyncCache`, with zombie eviction and stack-flat follower dispatch. 14 property-style tests including N=1000 coalescing, 100-caller cancellation isolation, 500-follower synchronous-completion stack-safety regression.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera-core/http/cache/ProxyCacheWriter`** + `IntegrityAuditor` + `scripts/pantera-cache-integrity-audit.sh` (exit 0 clean/fixed, 1 mismatch in dry-run, 2 CLI error). Companion CLI `pantera-main/tools/CacheIntegrityAudit`.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera-core/http/context/RequestContext` / `Deadline` / `ContextualExecutor`** — full ECS/APM envelope, monotonic wall-clock deadline with `remainingClamped`, Executor wrapper that propagates `ThreadContext` + APM `Span`.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera-core/http/observability/StructuredLogger`** (5-tier) + `LevelPolicy` (log-level matrix) + `pantera-core/audit/AuditAction` (closed enum of compliance events).
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera-main/auth/CachedLocalEnabledFilter`** — Caffeine + Valkey + `CacheInvalidationPubSub` decorator.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera-main/group/merge/StreamingMetadataMerger`** — StAX-based, XXE-hardened, `ComparableVersion`-ordered.
  ([@aydasraf](https://github.com/aydasraf))
- **Optional HTTP/3 PROXY protocol v2 support** — `PANTERA_HTTP3_PROXY_PROTOCOL=true` prepends Jetty's `ProxyConnectionFactory` to the Quiche connector (Group H.3). Default `false` — zero behavior change. Emits an INFO log `event.action=http3_proxy_protocol_enabled` when activated.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera-core/metrics/EventsQueueMetrics`** — shared callback emits one WARN per `queue.offer()` false-return and bumps `pantera.events.queue.dropped{queue=<repoName>}`.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera-core/http/cache/ProxyCacheWriter.VerifiedArtifact`** — public record exposing `contentFromTempFile()` (lazy Flowable with auto-delete) and `commitAsync()` for the serve-before-commit pattern.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera-main/cooldown/publishdate/`** — `PublishDateRegistry` + `PublishDateSource` interfaces, `DbPublishDateRegistry` (Caffeine L1 + Postgres L2), `RegistryBackedInspector`, six source implementations (Maven Solr, npm, PyPI, Go, Composer, RubyGems), `MavenHeadSource`, `JFrogStorageApiSource`, `ChainedPublishDateSource`. Flyway V125 `artifact_publish_dates` table.
  ([@aydasraf](https://github.com/aydasraf))
- **Search infrastructure** — V123 `natural_sort_key(text)` SQL function + `name_sort` generated column; V124 `classify_artifact(text)` SQL function + `artifact_kind` generated column. `ArtifactTreeTable.vue` three-column component with header-click sort.
  ([@aydasraf](https://github.com/aydasraf))

### 🔄 Changed

- **`EcsLoggingSlice` emits the access log exactly once per request** via `StructuredLogger.access()`. The former dual emission was removed to halve access-log volume in Kibana. Only the `.exceptionally(...)` error path still uses `EcsLogEvent` (one call-site; scheduled for migration).
  ([@aydasraf](https://github.com/aydasraf))
- **Hikari fail-fast defaults** (Group B). `connectionTimeout` tightened from `5000` to `3000` ms; `leakDetectionThreshold` from `300000` to `5000` ms. Operators may see leak WARNs that were silent before — each is a real held-connection bug to triage. Canary ramp documented in `docs/admin-guide/database.md`.
  ([@aydasraf](https://github.com/aydasraf))
- **Three hot-path executors wrapped via `ContextualExecutor.contextualize(...)`** — `DbArtifactIndex.DbIndexExecutorService`, `GroupResolver` drain executor, and all three SingleFlight-backed call sites. Every hot-path thread hop contextualised.
  ([@aydasraf](https://github.com/aydasraf))
- **Bounded-queue enqueue semantics: `offer()`, not `add()`.** Every request-serving path that writes to a `LinkedBlockingQueue<*Event>` now uses `offer()` and routes overflow through `EventsQueueMetrics.recordDropped(repoName)`.
  ([@aydasraf](https://github.com/aydasraf))
- **Coalescer fields in `GroupResolver` / `MavenGroupSlice` / `CachedNpmProxySlice` / `BaseCachedProxySlice` are now `SingleFlight` instances.** Field names retained for minimal diff; only the type changed.
  ([@aydasraf](https://github.com/aydasraf))
- **All proxy adapters (Maven, Go, Composer, PyPI) now use `writeAndVerify()` + `commitAsync()`** — the serve-before-commit pattern. HTTP response streams from a verified temp file while cache commit runs asynchronously. Previously `writeWithSidecars()` blocked the response until storage commit completed.
  ([@aydasraf](https://github.com/aydasraf))
- **Per-adapter `CooldownInspector` implementations replaced by `RegistryBackedInspector`.** `MavenCooldownInspector`, `NpmCooldownInspector`, `PypiCooldownInspector`, `GoCooldownInspector`, `ComposerCooldownInspector`, `DockerCooldownInspector` deleted. Single inspector wired at startup with pluggable `PublishDateSource` chain.
  ([@aydasraf](https://github.com/aydasraf))
- **`pom.xml` versions bumped 2.1.3 → 2.2.0** on the root reactor and all 30 modules. Docker image tags now produce `pantera:2.2.0`.
  ([@aydasraf](https://github.com/aydasraf))

### ⚠️ Deprecated

- **`pantera-core/http/trace/MdcPropagation`** marked `@Deprecated(since="2.2.0", forRemoval=true)`. Replacement is `ContextualExecutor.contextualize(executor)` at pool boundaries + `RequestContext.bindToMdc()` at the request edge. The class has been removed from pantera-core source; approximately 110 production call-sites were migrated in WI-02 / WI-03 / WI-04 / Group A. Do not add new call-sites.
  ([@aydasraf](https://github.com/aydasraf))

### 📊 Observability (log-audit hardening)

- **New 5xx fault signals** via `X-Pantera-Fault: <tag>` response header on every `FaultTranslator`-emitted 5xx: `internal`, `index-unavailable`, `storage-unavailable`, `deadline-exceeded`, `overload:<resource>`, `upstream-integrity:<algo>`. Additive; operator-facing runbook in `docs/admin-guide/runbooks.md`.
  ([@aydasraf](https://github.com/aydasraf))
- **`X-Pantera-Proxies-Tried: <n>`** on `AllProxiesFailed` passes the count of proxy members attempted.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera.maven.group.member_metadata_size_bytes` histogram** (tagged `repo_name`) records per-member Maven metadata body size during group merge. Alert-only — no request is rejected based on this metric.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera.group_metadata_cache.stale_served_from{tier=l1|l2|expired-primary|miss}`** — tiered counter for the stale-fallback read path. `expired-primary` non-zero is an operational signal to resize Valkey.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera.caches.auth-enabled.{hit,miss}`** — Micrometer Caffeine stats for the new auth cache.
  ([@aydasraf](https://github.com/aydasraf))
- **`pantera.publish_date.lookup{source,outcome}`** — timer for publish-date source queries. Replaces the former `cooldown_inspector` cache metrics. Tags: `source` (maven-solr, npm, pypi, go, composer, rubygems, maven-head, jfrog-storage), `outcome` (hit, miss, error).
  ([@aydasraf](https://github.com/aydasraf))

### 🔒 Security / compliance

No CVE fixes, no permissions model changes, no credential-handling changes, no PII-scope changes. Integrity verification on proxy caches (WI-07 + WI-post-07) is a correctness hardening, not a security fix — the trust boundary (upstream declares a digest, we verify it) has not moved. The new audit logger emits to a dedicated `com.auto1.pantera.audit` logger (see Migration notes).

### 📚 Documentation

- **Admin guide** — `docs/admin-guide/cache-configuration.md` (consolidated `meta.caches.*` reference with 3-tier override precedence env→YAML→default), `docs/admin-guide/valkey-setup.md` (`maxmemory-policy=allkeys-lru` requirement, sizing), `docs/admin-guide/database.md` (Hikari canary ramp instructions), `docs/admin-guide/environment-variables.md` (auth / stale-cache / HTTP/3 / scheduler env vars; Hikari defaults updated), `docs/admin-guide/deployment-nlb.md` (HTTP/3 proxy-protocol flag), `docs/admin-guide/runbooks.md` (new 5xx signals), `docs/admin-guide/v2.2-deployment-checklist.md` (pre/during/post-deploy gating).
  ([@aydasraf](https://github.com/aydasraf))
- **Developer guide** — `docs/developer-guide/caching.md` (canonical L1 Caffeine + L2 Valkey + pub/sub pattern; "cache is an aid, never a breaker"), `docs/developer-guide/fault-model.md`, `docs/developer-guide/reactive-lifecycle.md` (three-terminal-path contract — complete/error/cancel — with `CachingBlob.content` as canonical example), `docs/developer-guide/cooldown.md`.
  ([@aydasraf](https://github.com/aydasraf))
- **User guide** — `docs/user-guide/response-headers.md` (`X-Pantera-Fault`, `-Proxies-Tried`, `-Stale`, `-Internal`), `docs/user-guide/error-reference.md`, `docs/user-guide/streaming-downloads.md`. New repository pages: `gradle.md` + `go-group`/`go-proxy` sections in `go.md` + "Adding Members to a Group Repository" section in `ui-guide.md` covering the AutoComplete group-member picker and inline "Create new" modal.
  ([@aydasraf](https://github.com/aydasraf))

### ✅ Testing

- **4,926+ tests across pantera-main + pantera-core** at release time, 0 errors, 0 failures. `mvn -T8 test` green.
- **New tests** (highlights): 54 under `http/context/` + `http/observability/` (ContextualExecutor / Deadline / RequestContext / all five StructuredLogger tiers / LevelPolicy), 6 integrity tests under pypi / go / composer, 14 property-style tests for `SingleFlight`, 4 `BaseCachedProxySliceDedupTest` regression tests, 7 `StreamingMetadataMergerTest` fixtures (disjoint + overlapping versions, max-scalar semantics, malformed-member skip), 4 `GroupMetadataCacheStaleFallbackTest` tier-degradation cases, `CooldownResponseRegistryGetOrThrowTest`, `CachedLocalEnabledFilterTest` (7 cases), `DbArtifactIndexSaturationTest` (chaos), `CooldownValkeyStalenessTest` (chaos), `CooldownHighCardinalityTest` (chaos), `CooldownConcurrentFilterStampedeTest` (chaos), `PolicyChangeInvalidationTest`, `UpstreamPublishReEvalTest`.
- **New tests** (additions): `ProxyCacheWriterTest` — `writeAndVerifyReturnsTempFile`, `commitAsyncPersistsToStorage`, `writeAndVerifyRejectsOnMismatch`, `saveSidecarsWritesAll`. Live integration tests against real Maven Central and JFrog for publish-date sources. Search/browse: `createdAtSortHonoursDirection`, `nameSortIsNatural`, `searchExcludesNonArtifactKinds`. `BaseCachedProxySliceQueueFullTest` for Maven event queue overflow.

### 🧭 Migration notes

No operator action required for functional rollout — all changes are drop-in for v2.1.3 deployments.

- The `queue.add → queue.offer` migration is internal; no YAML change, no CLI flag, no API change.
- The access-log level policy change is internal to `EcsLogEvent` / `StructuredLogger.access`; Kibana panels filtering `log.level: WARN AND http.response.status_code: 404` will empty — intended outcome. Filter by status code instead.
- The `ProxyCacheWriter` path activates only when a file-backed `Storage` is present; tests injecting a lambda-`Cache` keep the pre-v2.2.0 code path.
- The `SingleFlight` coalescers use dedicated Caffeine `AsyncCache` instances with a 5-minute in-flight TTL and 10K max keys; heap growth is bounded, no tuning required.
- `scripts/pantera-cache-integrity-audit.sh` is additive — zero-impact no-op unless invoked; `--dry-run` is safe against production.
- **Kibana `user_agent` sub-fields**: operators who queried `user_agent.name` / `.version` / `.os.name` need to query `user_agent.original` directly (that's what `RequestContext` emits) or wait for the follow-up WI that re-lifts the parser. No data loss — only parsed sub-fields are unavailable this release.
- **Audit-log level**: `StructuredLogger.audit()` writes to logger `com.auto1.pantera.audit`. The bundled `log4j2.xml` inherits from `com.auto1.pantera` at INFO — "non-suppressible" is by convention. Add a dedicated `<Logger name="com.auto1.pantera.audit" level="info" additivity="false">` in production overrides to enforce.
- **Hikari canary ramp** (`docs/admin-guide/database.md`): start the first week at `PANTERA_DB_CONNECTION_TIMEOUT_MS=10000` and `PANTERA_DB_LEAK_DETECTION_MS=30000`; drop to defaults (3000 / 5000) after zero leak WARNs observed.
- **Valkey `maxmemory-policy`**: required `allkeys-lru` for the stale-metadata L2 to behave correctly under memory pressure — see `docs/admin-guide/valkey-setup.md`.

---

## Version 2.1.4 (Hotfix)

### 🔧 Bug fixes

- **Read-only users locked out of all token-TTL options except 30 / 90 days.** `AppHeader.vue`'s token-generation dialog fetched `api_token_max_ttl_seconds` and `api_token_allow_permanent` via `GET /admin/auth-settings` on mount, but that endpoint is admin-gated — non-admin users got 403, the catch block swallowed it, and the expiry dropdown silently fell back to a hardcoded `[30, 90]` list. `/api/v1/auth/me` now embeds the two public auth-settings fields in the response under an `auth_settings` object; `AppHeader.vue` reads them from the auth store via a `watch` on `auth.user?.auth_settings` — no extra network call, no admin gate. The write-path (`PUT /admin/auth-settings`) remains admin-only.
  ([@aydasraf](https://github.com/aydasraf))
- **Auth-settings toggle was cosmetic — server never enforced it.** `AuthHandler.generateTokenEndpoint` ignored `api_token_allow_permanent` entirely, so a user could `POST /api/v1/auth/token/generate` with `{"expiry_days": 0}` and mint a permanent token even with the admin toggle off. Separately, the endpoint read the legacy `max_api_token_days` key for the TTL cap while the admin UI wrote `api_token_max_ttl_seconds` — the two never met, so flipping the slider in SettingsView had no server-side effect. Both gaps closed: permanent requests now return `400 PERMANENT_TOKENS_DISABLED` when the toggle is off; the UI-managed `api_token_max_ttl_seconds` takes precedence, falling back to the legacy key only when the UI key is unset. **Existing permanent tokens are NOT retroactively invalidated** when the toggle flips — tokens are validated against `user_tokens.expires_at`, not against the current setting. To revoke already-issued permanent tokens, use `DELETE /api/v1/auth/tokens/{id}` or `POST /api/v1/admin/revoke-user/{username}`.
  ([@aydasraf](https://github.com/aydasraf))

## Version 2.1.3

### 🔧 Bug fixes

- `.yaml` (and every other non-whitelisted file extension) Maven artifacts returned 502/404 from group repositories. `ArtifactNameParser.parseMaven` gated on a hardcoded extension whitelist (`jar|pom|xml|war|aar|ear|module|sha1|sha256|sha512|md5|asc|sig`); `.yaml`, `.json`, `.zip`, `.properties`, `.tgz`, and any future type produced a mangled artifact name that missed the index, causing full proxy fanout that couldn't find the locally-uploaded artifact. Replaced with structural detection: Maven URLs always follow `{groupId}/{artifactId}/{version}/{artifactId}-{version}[-classifier].ext`, so if the final segment starts with `{artifactId}-` it's a filename. Validated against 451,673 production artifacts including non-digit versions (Spring release trains `Arabba-SR10`, git SHAs, word versions) and Scala cross-version artifactIds (`chill_2.12`).
  ([@aydasraf](https://github.com/aydasraf))
- Nested group leaf repos (e.g. `groovy-plugins-release` inside `remote-repos` inside `libs-release`) were unreachable via index hit when Pantera had no explicit repo config for the leaf. `buildLeafMap`/`collectLeaves` silently dropped unconfigured leaves, so the `leafToMember` lookup produced an unmappable name, `targeted` came back empty, and the request fell to proxy-only fanout — skipping hosted members that actually had the artifact. Replaced the static map with `GroupMemberFlattener` which enumerates leaves at construction time and lets `locateByName()` return repo names that match the flattened member list directly. No runtime nested-group recursion, no mapping table to drift.
  ([@aydasraf](https://github.com/aydasraf))
- Circuit breaker at the group level manufactured false 5xx responses. When the index returned `groovy-plugins-release` for an artifact but that member's circuit was OPEN, the resolver skipped the member — even though the bytes were local — and returned 503 to the client. 7,733 such circuit-open/503 entries were observed in 30 minutes of production logs. Circuit breaker now only runs on the fanout path (protects upstreams from thundering herd); the targeted local read path always queries the member the index points to.
  ([@aydasraf](https://github.com/aydasraf))
- `DbArtifactIndex.locateByName` returned `List.of()` for both "row not found" and "DB error", so a transient database outage made every group request fall to proxy-only fanout and return 404 for artifacts that exist in hosted members. Return type changed to `CompletableFuture<Optional<List<String>>>` — `Optional.empty()` on `SQLException` triggers full two-phase fanout as a safety net, `Optional.of(List.of())` is the confirmed-miss case that still goes proxy-only.
  ([@aydasraf](https://github.com/aydasraf))
- `locateByName` SQL had no statement timeout. Under DB pressure or missing-index pathology the query could hang indefinitely, starving the index connection pool at 250+ req/s. Added `SET LOCAL statement_timeout = '500ms'` (configurable via `PANTERA_INDEX_LOCATE_TIMEOUT_MS`) using the same transaction-guard pattern as `searchWithLike`. Timeout surfaces as `SQLException` which already maps to `Optional.empty()` → full fanout safety net.
  ([@aydasraf](https://github.com/aydasraf))
- 3,345 "Internal server error" log entries per 30 minutes had zero stack traces, no `user.name`, no `client.ip`, no `trace.id` — admins saw a generic error message with no way to diagnose or attribute it. All error-path logging in `GroupSlice` now uses `EcsLogger.error(...).error(throwable)` to capture `error.type`/`error.message`/`error.stack_trace`, and MDC fields (user/IP/trace) propagate across async `thenCompose`/`whenComplete` callbacks via new `MdcPropagation` wrappers (CompletableFuture callbacks previously ran on pool threads with empty MDC).
  ([@aydasraf](https://github.com/aydasraf))
- Internal group-to-member fanout queries emitted 105,796 access log entries per 30 minutes — 26% of all log volume, indistinguishable from real client requests but with no `user.name`/`client.ip`/`trace.id`. `GroupSlice` now adds an `X-Pantera-Internal: true` marker header when dispatching to members; `EcsLoggingSlice` checks the header and skips access log emission (internal routing is still captured as DEBUG application logs in `GroupSlice`). The marker does not leak to upstream — all proxy slices pass `Headers.EMPTY` to the upstream HTTP client.
  ([@aydasraf](https://github.com/aydasraf))
- `event.duration` had inconsistent units — some code paths wrote nanoseconds, others wrote microseconds, others milliseconds. Both `EcsLogger.duration(long ms)` and `EcsLogEvent.duration(long ms)` removed their `* 1_000_000` conversion; every log entry now emits `event.duration` in milliseconds (Pantera convention). See logging admin guide §event.duration for the Kibana query migration (`> 5000000000` → `> 5000`).
  ([@aydasraf](https://github.com/aydasraf))
- `event.category` values used throughout the codebase (`repository`, `group`, `cache`, `cooldown`, `pypi`, `storage`, `scheduling`, etc.) were not in the ECS allowed-values list, causing dashboards filtering on ECS categories to return empty. 488 call sites across 121 files migrated: repository/http/server/docker/group/pypi/npm/maven → `web`, cache/cooldown/search/index → `database`, storage → `file`, scheduling/metrics → `process`, cluster/system → `host`, user/admin → `iam`, security → `authentication`, webhook → `network`, factory → `configuration`. See the migration table in the logging admin guide.
  ([@aydasraf](https://github.com/aydasraf))
- `DRAIN_EXECUTOR` queue overflow (4 threads, 200-entry bounded queue) logged dropped tasks at DEBUG level — silent in production where DEBUG is disabled. Each dropped drain is a potential member-response body leak. Now logged at WARN with a `DRAIN_DROP_COUNT` atomic counter exposed via `drainDropCount()` for metrics integration.
  ([@aydasraf](https://github.com/aydasraf))

### ⚡ Performance

- **Regex patterns hoisted to static finals in `ArtifactNameParser`.** Composer, Helm, Hex, Gem, PyPI filename parsers and `normalizeType` were calling `Pattern.compile(...)` / `String.replaceAll(regex, ...)` inside method bodies — on every `GroupSlice.locateByName` lookup. At 1000 req/s across mixed repo types this was roughly 500–600 ms CPU/s per core (~6–7 % CPU tax). All 6 sites replaced with `private static final Pattern` constants (three callers share a single `^(.+)-\d` pattern); behaviour verified by the full 155-case `ArtifactNameParserTest` suite.
  ([@aydasraf](https://github.com/aydasraf))
- **Jackson `ObjectMapper` / `JsonFactory` singletons in the Conda adapter.** `MergedJson.Jackson`, `JsonMaid.Jackson`, `MultiRepodata.Unique`, `CondaRepodata.Remove`/`Append`, and `AstoMergedJson` constructed `new ObjectMapper()` / `new JsonFactory()` inside request-handling loops (8 call sites). Mapper construction costs ~1–10 ms plus transient allocation for module loading and type factory setup — non-trivial on repodata paths. Replaced with `static final` singletons (Jackson documents mappers as thread-safe once configured); `JsonFactory` exposed as a constant on the `CondaRepodata` interface and reused across `MultiRepodata` and `AstoMergedJson`.
  ([@aydasraf](https://github.com/aydasraf))
- **PyPI cache-hit artifact path streams directly — no `readAllBytes()`.** `ProxySlice.afterHit` materialised every cached artifact into a `byte[]` via `stream.readAllBytes()` before wrapping it in `Content.From(data)` for the response — a 700 MB `torch` wheel hit the heap per request on cache-serve. The dead `remoteSuccess=true` save-to-storage branch (all callers pass `false`) and the `ContentAndCoords` helper are removed; the streaming `Content` is passed to `ResponseBuilder.body(...)` directly, with `Content-Length` taken from `content.size()`. Validated by the 19-case `ProxySliceTest` plus the 124-case full pypi-adapter suite. The remote-fetch leg is unchanged; it already persists inside `cache.load(...)`.
  ([@aydasraf](https://github.com/aydasraf))
- **Pre-warmed shared Jetty clients at startup.** `SharedClient.client()` called `startFuture.join()` to wait for async Jetty initialisation (SSL context, socket setup; ~100–500 ms). When the first request per upstream arrived on a Vert.x event-loop thread, the join blocked that loop for the full init — starving thousands of other requests sharing it. Added `SharedJettyClients.awaitAllStarted(Duration)` and `RepositorySlices.warmUp(Duration)`; `VertxMain` calls `slices.warmUp(Duration.ofSeconds(30))` after config load and before the first `listenOn`, so every configured repo's SharedClient is fully started before traffic is accepted. Runtime repo additions via the UPSERT event still take the lazy path; a follow-up will route that through `executeBlocking`.
  ([@aydasraf](https://github.com/aydasraf))

### 📊 Observability (log-audit hardening)

Driven by a structured audit of the production container log stream (~33K entries across `http.access`, `artifact.audit`, and application loggers). Target: no log dropped by Elasticsearch, every HTTP request correlatable end-to-end, every audit entry queryable in Kibana.

- **`event.category` normalised to ECS array on every emitter.** The codebase had two emission shapes — typed `.eventCategory(value)` (array `["web"]`) and raw `.field("event.category", value)` (string `"web"`). In the sampled logs, 8,719 of 32,865 entries (26 %) used the string shape — all from `AuditLogger`, `SpanContext.SRE2042`, and the duplicate `event.outcome` override in `OperationControl`. Elasticsearch's dynamic mapping binds the field to whichever type indexes first; the minority type then fails with `mapper_parsing_exception` and is dropped. All remaining raw-string sites switched to typed `.eventCategory(...)`. `OperationControl` also had a duplicate `.field("event.outcome", "allowed"/"denied")` overwriting `.eventOutcome(success/failure)` with non-ECS values — the allowed/denied detail is preserved as `event.reason`.
  ([@aydasraf](https://github.com/aydasraf))
- **`event.action` emitted on every `http.access` entry.** All 535 sampled access-log entries had `event.action: null` because `EcsLogEvent`'s constructor set `event.category` and `event.type` but not action. Default `event.action: "http_request"` now set in the constructor, with an overridable `action(String)` builder for specific cases (health probes, admin endpoints). Makes Kibana saved queries like `event.action: "group_lookup_miss"` usable against access logs.
  ([@aydasraf](https://github.com/aydasraf))
- **MDC propagation across `cooldown.metadata` + `npm` adapter async boundaries.** `CooldownMetadataServiceImpl.computeFilteredMetadata` and `DownloadAssetSlice.checkCacheFirst` crossed async boundaries (`CompletableFuture.supplyAsync`, RxJava `Maybe.map`) without restoring MDC. Result in sampled logs: `com.auto1.pantera.cooldown.metadata` had 0 % `trace.id` coverage on 1,459 entries; `com.auto1.pantera.npm` had 30 % on its 4,579 `cache_hit` entries. Added `MdcPropagation.withMdcSupplier(Supplier)` and `MdcPropagation.withMdcRxFunction(io.reactivex.functions.Function)` wrappers — both capture the caller's MDC and reinstall it around the callback on the worker thread. Applied at 3 continuations in `CooldownMetadataServiceImpl` and 2 in `DownloadAssetSlice`.
  ([@aydasraf](https://github.com/aydasraf))
- **SAX parser no longer leaks `[Fatal Error]` to stderr.** `UploadSlice.fixMetadataBytes` parses `maven-metadata.xml` via `jcabi-xml`'s `XMLDocument` which used the default SAX handler — that handler prints `[Fatal Error] :1:1: Content is not allowed in prolog.` to stderr before the caller's `IllegalArgumentException` fallback catches and logs the structured WARN. Two such lines were observed in sampled container stderr. Replaced with a helper that parses via `DocumentBuilder` with a silent `ErrorHandler`, then wraps in `XMLDocument`. DOCTYPE + external-entity expansion disabled as a defensive measure (XXE / billion-laughs). Catch widened to cover `SAXException` / `IOException` / `ParserConfigurationException`.
  ([@aydasraf](https://github.com/aydasraf))
- **`artifact_resolution` audit events now carry `package.name`.** `AuditLogger.resolution()` took no arguments and relied on MDC for every field; the 3 call sites in `pypi/SliceIndex` fire during RxJava render pipelines where MDC is detached from the request scope, so 6 of 8,719 sampled audit entries had null `package.name`/`repository.name`/`user.name`. Signature changed to `resolution(String packageName)`; method short-circuits when the name is empty (repo-level index queries are not audited). Call sites pass the already-in-scope `packageName` variable.
  ([@aydasraf](https://github.com/aydasraf))
- **Audit log entries now inherit the originating HTTP request's `trace.id`.** Audit events are emitted by `DbConsumer` on a background scheduler thread with a fresh MDC, so in sampled logs the 575 distinct `trace.id`s had ZERO overlap between `http.access` (535 entries) and `artifact.audit` (93 trace-carrying entries) — joining an artifact upload to its HTTP session in Kibana was impossible. Added `ArtifactEvent.traceId()` that auto-captures `MDC.get(TRACE_ID)` at construction time (zero change across the ~40 `new ArtifactEvent(...)` call sites in adapters). `DbConsumer.logArtifactPublish` restores the captured trace.id into MDC around the `AuditLogger.publish` call, with `try/finally` to leave no residue on the pooled DB-consumer thread.
  ([@aydasraf](https://github.com/aydasraf))
- **`package.checksum` (SHA-256) populated on Maven publish audits.** Previously 0 of 8,719 audit entries had `package.checksum`. `UploadSlice.generateChecksums` now returns the SHA-256 hex instead of `Void` while still writing all 4 sidecar files. `ArtifactEvent.withChecksum(String)` produces an immutable copy with the digest attached, and `AuditLogger.publish` emits the hex as `package.checksum` when non-null. Other adapters remain on the existing code path (checksum null for their publishes); extending each is a follow-up.
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- **Stale-while-revalidate for proxy artifact binaries.** Enabled by default. When a proxy member's upstream fails (timeout, 5xx, connection refused) and the cached bytes are within `staleMaxAge` (default 1 hour), the proxy serves the cached artifact with `200 OK` + `X-Pantera-Stale: true` + `Age: <seconds>` (RFC 7234) headers. Age is tracked via a new `savedAt` timestamp in the metadata sidecar JSON — backwards-compatible with pre-2.1.3 sidecars (missing `savedAt` is treated as fresh on first read). Operators disable per-repo via `cache.stale_while_revalidate.enabled: false` in YAML.
  ([@aydasraf](https://github.com/aydasraf))
- **Negative cache for group proxy fanout.** Prevents thundering-herd: when all proxy members return 404 for a missing artifact, the `(group, artifactName)` pair is cached for a short TTL (default 5 minutes) so subsequent requests return 404 instantly without a second fanout. Two-tier L1 Caffeine + L2 Valkey via the existing `NegativeCacheConfig` pattern; configurable per-deployment under `meta.caches.group-negative` in `pantera.yml`. Falls back to in-memory L1 only (matching historical behaviour) when the config section is absent.
  ([@aydasraf](https://github.com/aydasraf))
- **Concurrent request coalescing.** When N requests arrive simultaneously for the same missing artifact, only one fanout runs — the N-1 followers wait on an `inFlightFanouts` gate and, on leader completion, re-enter `proxyOnlyFanout` to hit the freshly-populated negative cache (instant 404) or the cached proxy response. Combined with the negative cache, fully eliminates the thundering herd for missing artifacts.
  ([@aydasraf](https://github.com/aydasraf))
- **`staleMaxAge` enforcement.** `BaseCachedProxySlice.tryServeStale` now computes `age = now - savedAt` from the metadata sidecar and refuses to serve stale bytes older than `ProxyCacheConfig.staleMaxAge()`. Previously the feature was partial — age was not checked, so arbitrarily old cached bytes could be served when upstream was down. Legacy sidecar files without `savedAt` get `Instant.now()` as a fallback (treated as fresh on first read), enabling rolling upgrades without data migration.
  ([@aydasraf](https://github.com/aydasraf))
- **Token expiry dropdown in the avatar menu popup.** The "Generate API Token" dialog in `AppHeader.vue` previously used a numeric input field (0-365 days, 0 = permanent). Replaced with a `<Select>` dropdown matching `ProfileView.vue`'s pattern: 30 / 90 / 180 / 365 days / Permanent. Respects admin settings: `api_token_max_ttl_seconds` gates the numeric options, `api_token_allow_permanent` gates the Permanent option.
  ([@aydasraf](https://github.com/aydasraf))

### 🏗️ Architectural changes

- **Group resolution redesigned around a 5-path decision tree.** Previous code had tight coupling between parser accuracy, the `leafToMember` map, and the circuit breaker — any single failure produced false 5xx. New flow:
  1. **Unparseable URL** (metadata endpoints, root paths) → full two-phase fanout (hosted first, then proxy cascade).
  2. **Index DB error** → full two-phase fanout (safety net; we don't know what's in the index).
  3. **Index confirmed miss** → proxy-only fanout (hosted repos are fully indexed, so absence from index = absence from hosted). Checks negative cache first.
  4. **Index hit** → targeted local read against the member(s) the index returned. No circuit breaker (the bytes are local; skipping a known-good location creates false 5xx). No fallback fanout on 5xx (no other member has the bytes).
  5. **Index-hit orphan** (index returned a repo name not in the flattened member list) → full fanout as safety net.

  HTTP status codes: `500` for local read failure (targeted path), `502` for upstream gateway failure (fanout path), `404` for confirmed not-found. **No `503` from group resolution.** Circuit-breaker state is logged as structured application logs, never returned as HTTP status.
  ([@aydasraf](https://github.com/aydasraf))
- **Token generation UI consolidated.** Removed the generation form from the Profile page — token creation is now exclusively available via the avatar-menu popup. The Profile page retains the Active Tokens list (view / revoke existing tokens).
  ([@aydasraf](https://github.com/aydasraf))

### 🧹 Cleanup

- Deleted `leafToMember` map and its `buildLeafMap`/`collectLeaves` helpers in `RepositorySlices` — replaced by construction-time `GroupMemberFlattener.flatten()` which enumerates leaves once with cycle detection.
- Deleted `MAVEN_FILE_EXT` regex whitelist in `ArtifactNameParser` — replaced by structural filename-prefix detection.
- Deleted `RequestContext.addTo()` — a pass-through no-op retained after an earlier MDC cleanup. All 10 call sites simplified from `ctx.addTo(EcsLogger.warn(...)).log()` to direct `EcsLogger.warn(...).log()`. `RequestContext` trimmed from 4 fields to 1 (`packageName`).
- Deleted the legacy `queryAllMembersInParallel` and `queryMember` helpers — dead after the 5-path rewrite.
- Renamed `pantera-core/.../com.auto1.pantera.http.group.GroupSlice` to `RaceSlice` to resolve the naming clash with `pantera-main/.../com.auto1.pantera.group.GroupSlice`. The two classes served different purposes (low-level first-response-wins utility vs. hot-path group resolver); the rename makes intent explicit. 9 files updated (6 proxy adapters + 1 IT case + the class + its test).

### 📚 Documentation

- Logging admin guide (`docs/admin-guide/logging.md`) updated with the new ECS-compliant `event.category` value set, a migration table mapping old Pantera categories to ECS allowed values, and a note that `event.duration` is now in milliseconds (with a Kibana query conversion example — `> 1000000000` for 1s becomes `> 1000`).
- Group resolution redesign spec (`docs/superpowers/specs/2026-04-14-group-resolution-redesign.md`) documents the 5-path flow, the circuit breaker strategy, the negative cache configuration, the stale-while-revalidate design, the ECS category migration, and the deferred items (non-Maven metadata caching, cross-type repo verification).

### ✅ Testing

- `ArtifactNameParserMavenStructuralTest` (47 parameterised tests) covers `.yaml`, `.json`, `.zip`, Spring release trains, git SHAs, Scala cross-version artifactIds, classifiers, checksums, metadata endpoints, and short paths.
- `GroupSliceFlattenedResolutionTest` covers all 5 resolution paths plus the critical "index hit + member 5xx → 500 (no fanout)" invariant, concurrent-miss coalescing, and the X-Pantera-Internal marker propagation.
- `BaseCachedProxySliceStaleTest` covers stale-serve on upstream timeout/5xx, stale refusal beyond `staleMaxAge`, absent-metadata existence fallback, and SWR-disabled propagation.
- `NegativeCacheConfigTest` covers the new `fromYaml(caches, subKey)` overload for per-group config sections.

### ⚠️ Breaking changes

- `event.duration` is now emitted in **milliseconds**, not nanoseconds. Kibana saved queries comparing to nanosecond thresholds (e.g. `event.duration > 5000000000` for 5s) must be updated to the ms equivalent (`> 5000`). See logging admin guide for the migration table.
- `event.category` values migrated to the ECS allowed-values set. Dashboards and alerts filtering on old Pantera-specific categories (`repository`, `group`, `cache`, `cooldown`, `pypi`, etc.) must be updated. Mapping table in logging admin guide.
- `ArtifactIndex.locateByName` signature changed from `CompletableFuture<List<String>>` to `CompletableFuture<Optional<List<String>>>`. External implementers of `ArtifactIndex` must adopt the new return type (`Optional.of(repos)` on success, `Optional.empty()` on DB error).
- No `503` responses from group resolution. Clients that retried on `503` will now see `404` (miss), `500` (local error), or `502` (gateway error). Maven/Gradle build tooling is unaffected — they already retry on 5xx.

---

## Version 2.1.2

### 🔒 Security

- `jwt-password` and `local` auth providers were silently disabled on every request on deployments that never had their rows in the `auth_providers` table. The v2.1.0 changelog promised they are "mandatory and cannot be removed" but no Flyway migration seeded them — so `DbGatedAuth` saw the row absent, returned `false` from `isEnabled()`, and every UI-generated API token used in Basic auth failed verification *before* reaching the RS256 validator. Symptom: `/pypi` and every other main-port repo request returned 401 with a single `Failed to authenticate user` WARN showing `CachedUsers(size=0)` — no indication that `jwt-password` even existed. Fixed with V118 `seed_mandatory_auth_providers.sql` using `ON CONFLICT DO NOTHING` so existing deployments auto-heal on restart without clobbering operator choices.
  ([@aydasraf](https://github.com/aydasraf))
- Conan adapter's `ItemTokenizer` signed and verified per-item tokens with a hardcoded HMAC secret (`"some secret"`, committed to source since the Artipie fork). Anyone with repo access could forge Conan upload/download URL tokens. Migrated to RS256 using the same cluster-wide key pair as the main auth flow — keys are threaded through `RepositorySlices` from the `JwtTokens` instance, so HA nodes that share the pair continue to verify each other's tokens without any additional config.
  ([@aydasraf](https://github.com/aydasraf))
- `jwt-password` auth provider silently validated tokens against a hardcoded fallback HMAC secret. The v2.1.0 switch to RS256 asymmetric signing removed `meta.jwt.secret`, but `JwtPasswordAuthFactory` kept the old HS256 code path — when `secret` came back `null` it fell back to the literal string `"jwt-password-fallback-secret"` and only emitted a WARN. UI-generated API tokens (signed with the real RSA private key) never verified against that fallback, so every Basic-auth attempt using a UI-generated token failed, and the provider's security model was effectively a shared, publicly-known HMAC key. Factory now loads `meta.jwt.public-key-path` and builds an RS256 `JWTAuth` — same key pair as `JwtTokens`, so API tokens the user generates via the UI authenticate correctly. Missing `public-key-path` now fails fast at startup with an actionable error instead of deferring to a broken fallback.
  ([@aydasraf](https://github.com/aydasraf))
- Profile → **Active Tokens** UI leaked the user's refresh-token JTI. Every login / SSO callback / refresh cycle wrote a row to `user_tokens` with `token_type = 'refresh'` and `label = "Refresh Token"`, but `UserTokenDao.listByUser` had no `token_type` filter — so the list returned every type and the UI rendered a revocable "Refresh Token" entry alongside the user's real API tokens. A user could click the trash icon and kill their own session; worse, anyone with access to the DB-facing audit trail could infer refresh JTIs from the response. Filter is now `token_type = 'api'`. The self-service `DELETE /api/v1/auth/tokens/:id` endpoint is hardened with the same scope so the UUID cannot be used to revoke a refresh token even if guessed — refresh revocation remains available via logout and the admin revoke-user path.
  ([@aydasraf](https://github.com/aydasraf))

### 🔧 Bug fixes

- `JwtPasswordAuthFactory` double-nested `cfg.yamlMapping("meta")` but `initAuth()` already passes the `meta` mapping as `cfg`. The factory looked for `meta.meta.jwt` — which doesn't exist — got `null`, and threw `"public-key-path is not configured"` at startup. The catch in `initAuth` swallowed it as a WARN, so `jwt-password` was silently never added to the auth chain and every API-token-as-password request returned 401. Fix: `JwtSettings.fromYaml(cfg)` (no extra nesting).
  ([@aydasraf](https://github.com/aydasraf))
- Version-repair CLI (`--mode version-repair`) crashed on `artifacts_repo_name_name_version_key` unique constraint when the same artifact name had both a `version='UNKNOWN'` row and an already-correct versioned row. The batch UPDATE now includes a `NOT EXISTS` guard that skips conflicting rows instead of aborting the entire batch.
  ([@aydasraf](https://github.com/aydasraf))
- `JwtPasswordAuth` catch-all swallowed every JWT verification failure (wrong signature, expired, key mismatch) with no log. Added DEBUG-level logging with the exception message so operators can diagnose failures via `-Dlog4j.logger.com.auto1.pantera.auth=DEBUG`.
  ([@aydasraf](https://github.com/aydasraf))
- API listener fails ALB health checks when `meta.http_server.proxy_protocol: "true"` is enabled. ALB does not emit PROXYv2 (it terminates L7 and adds `X-Forwarded-For` instead), so plain `GET /` health-probe bytes were being misparsed by Pantera's PROXY decoder and the connection closed with `HAProxyProtocolException`. The target group then marked the API port unhealthy with no useful Pantera log entry. Fixed by introducing a per-listener PROXYv2 toggle for the API port — see `meta.http_server.api_proxy_protocol` below.
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- New `meta.http_server.api_proxy_protocol` flag controls PROXYv2 on the API listener (typically port 8086) independently from the main + per-repo listeners. Defaults to the value of `meta.http_server.proxy_protocol` for backward compatibility — pre-2.1.2 deployments that set a single `proxy_protocol: true` keep their existing behaviour. Operators with a mixed topology (NLB → main port + ALB → API port) set `api_proxy_protocol: "false"` to keep PROXYv2 on for the NLB-fronted listeners while disabling it on the ALB-fronted API port.
  ([@aydasraf](https://github.com/aydasraf))

### 🧹 Cleanup

- Removed `JwtPasswordAuth.fromSecret(Vertx, String)` — the pre-2.1.0 HS256 entry point. Production no longer calls it (the factory now builds an RS256 `JWTAuth` directly), and the pre-2.1.2 test that exercised it was masking the broken-factory regression. `JwtPasswordAuthTest` rewritten against the committed RSA key-pair fixtures so a future sign/verify mismatch cannot hide.
  ([@aydasraf](https://github.com/aydasraf))
- Removed the HS256 `JWTAuthHandler` fallback in `AsyncApiVerticle` (`unifiedAuth == null` branch). Dead in production since 2.1.0, but a latent trap — a misconfigured deploy without RS256 keys now fails fast with an actionable error instead of silently routing every request through an unconfigured HMAC validator.
  ([@aydasraf](https://github.com/aydasraf))
- Swept docs/operator configs still referencing the removed `meta.jwt.secret` / `JWT_SECRET`: `README.md`, `docs/ha-deployment/pantera-ha.yml`, `docs/ha-deployment/docker-compose-ha.yml`, `docs/admin-guide/installation.md`, `docs/admin-guide/upgrade-procedures.md`, `docs/admin-guide/troubleshooting.md`. All now show `private-key-path` / `public-key-path` (and the matching `JWT_PRIVATE_KEY_PATH` / `JWT_PUBLIC_KEY_PATH` env vars). A fresh 2.1.2 deploy following any of these docs no longer fails at startup.
  ([@aydasraf](https://github.com/aydasraf))
- Stale Javadoc on `JwtPasswordAuth` and `JwtPasswordAuthFactory` updated from HS256 / `meta.jwt.secret` wording to the RS256 key-path configuration.
  ([@aydasraf](https://github.com/aydasraf))

### 📚 Documentation

- Configuration reference §1.8 expanded with the new `api_proxy_protocol` key and a topology note explaining why ALB and PROXYv2 are mutually exclusive.
  ([@aydasraf](https://github.com/aydasraf))
- Admin-guide configuration page gained a "Mixed NLB + ALB topology" section walking operators through the symptom (ALB target group reports unhealthy with no Pantera log) and the fix.
  ([@aydasraf](https://github.com/aydasraf))

---

## Version 2.1.1

### 🔧 Bug fixes

- Startup fails with `algid parse error, not a sequence` when the JWT private key is PEM-encoded as PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`). `RsaKeyLoader` now detects the format from the PEM header and wraps PKCS#1 in a PKCS#8 envelope in-memory; PKCS#8 keys continue to load unchanged. Supports 2048- and 4096-bit RSA. The misleading `openssl genrsa` hint in the missing-key error message has been replaced with the PKCS#8-producing `openssl genpkey` form.
  ([@aydasraf](https://github.com/aydasraf))
- `proxy_protocol: true` silently downgraded to plain HTTP because `netty-codec-haproxy` was not on the classpath. Vert.x logged `Proxy protocol support could not be enabled` at startup and then served NLB-wrapped traffic as malformed HTTP, breaking every connection behind a PROXY-v2 load balancer. Added `io.netty:netty-codec-haproxy` to `pantera-main` (version aligned with the `vertx-dependencies` BOM, currently 4.1.132.Final).
  ([@aydasraf](https://github.com/aydasraf))
- Elastic ingest pipeline rejects logs with `Duplicate field 'service.version'`. The `EcsLayout` serializer already emits `service.version`, `process.thread.name`, and the other service metadata fields; three call sites were adding them again via `.field()` and producing duplicate JSON keys. Removed the redundant emits at startup log, scheduler queue log, and blocked-thread diagnostics; the blocked-thread diagnostic now reports the target thread name in the message and under `pantera.blocked_thread.name`.
  ([@aydasraf](https://github.com/aydasraf))

### 📚 Documentation

- Configuration reference now covers scheduled scripts (`meta.crontab`), experimental HTTP/3 support, and repository filter blocks — previously only documented under the admin guide.
  ([@aydasraf](https://github.com/aydasraf))
- Admin-guide configuration page collapsed to a slim overview that defers to the reference for full key lists, eliminating duplicated YAML samples.
  ([@aydasraf](https://github.com/aydasraf))
- Design/planning documents removed from `docs/plans/`.
  ([@aydasraf](https://github.com/aydasraf))

### ✅ Testing

- `RsaKeyLoaderTest` rewritten with committed PKCS#1/PKCS#8 fixture pairs at 2048 and 4096 bits; asserts both formats yield identical key material and that the DER long-form length path is exercised for 4096-bit keys.
  ([@aydasraf](https://github.com/aydasraf))
- `ProxyProtocolV2Test` added: stands up a Vert.x HTTP server with `setUseProxyProtocol(true)`, writes a Netty-encoded PROXYv2 header over a raw socket (TCP4 + TCP6), and asserts the handler sees the client IP from the header rather than the loopback address. Double-guards the classpath — if `netty-codec-haproxy` is ever dropped, the test class itself won't load.
  ([@aydasraf](https://github.com/aydasraf))

---

## Version 2.1.0

### ⚠️ Breaking changes

- All previously issued tokens are invalidated due to signing scheme change
  ([@aydasraf](https://github.com/aydasraf))
- `meta.jwt.secret` replaced by `meta.jwt.private-key-path` + `meta.jwt.public-key-path`
  ([@aydasraf](https://github.com/aydasraf))
- Login and callback endpoints return `{ token, refresh_token, expires_in }`
  ([@aydasraf](https://github.com/aydasraf))
- Fresh installs bootstrap a default admin account requiring password change on first sign-in
  ([@aydasraf](https://github.com/aydasraf))
- `local` and `jwt-password` auth providers are mandatory and cannot be removed
  ([@aydasraf](https://github.com/aydasraf))
- UI dependencies pinned to exact versions — developers must use `npm ci`
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- RS256 asymmetric JWT signing replaces the previous shared-secret scheme
  ([@aydasraf](https://github.com/aydasraf))
- Access + refresh + API token architecture with configurable lifetimes
  ([@aydasraf](https://github.com/aydasraf))
- Multi-node token revocation via blocklist with cluster-wide propagation
  ([@aydasraf](https://github.com/aydasraf))
- JTI ownership validation and token-type scope enforcement
  ([@aydasraf](https://github.com/aydasraf))
- Admin UI for auth settings and per-user token revocation
  ([@aydasraf](https://github.com/aydasraf))
- Schema-driven provider configuration UI for Okta and Keycloak
  ([@aydasraf](https://github.com/aydasraf))
- Provider lifecycle (create, enable, disable, delete) takes effect at runtime without restart
  ([@aydasraf](https://github.com/aydasraf))
- Priority-driven provider ordering with deterministic chain evaluation
  ([@aydasraf](https://github.com/aydasraf))
- Group-to-role mapping for SSO providers, independent from access-control gate
  ([@aydasraf](https://github.com/aydasraf))
- Default admin account bootstrapped on fresh installs with mandatory password change
  ([@aydasraf](https://github.com/aydasraf))
- Unified password complexity policy (server-side + client-side), minimum 12 characters
  ([@aydasraf](https://github.com/aydasraf))
- Self-service password change from user profile for local accounts
  ([@aydasraf](https://github.com/aydasraf))
- Admin password reset without requiring the target user's current password
  ([@aydasraf](https://github.com/aydasraf))
- Per-request user-enabled check in JWT filter — disabled users lose all access immediately
  ([@aydasraf](https://github.com/aydasraf))
- Structured search query syntax — `name:`, `version:`, `repo:`, `type:`, AND/OR, parentheses
  ([@aydasraf](https://github.com/aydasraf))
- Server-side search, sort, and pagination for users and roles
  ([@aydasraf](https://github.com/aydasraf))
- Quick Setup page for first-time configuration
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))
- Registry URL editable from admin settings (DB-persisted, used by Quick Setup)
  ([@aydasraf](https://github.com/aydasraf))
- Sort artifacts by name in repository browser
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))
- Filter and sort on backend for artifact listings
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))
- `Dockerfile.dev`, `docker-compose.dev.yaml`, `Makefile`, `.env.dev` for local development
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))
- PEP 691 JSON Simple API with PEP 700 upload-time metadata
  ([@aydasraf](https://github.com/aydasraf))
- PEP 503 full data attributes on hosted-repo HTML indexes
  ([@aydasraf](https://github.com/aydasraf))
- Dual-format index persistence — HTML and JSON written side-by-side on upload
  ([@aydasraf](https://github.com/aydasraf))
- Self-healing JSON cache for legacy packages without JSON index
  ([@aydasraf](https://github.com/aydasraf))
- Self-healing sidecar metadata from storage file timestamps for pre-upgrade artifacts
  ([@aydasraf](https://github.com/aydasraf))
- Yank/unyank API endpoints (PEP 592) and UI controls in artifact detail dialog
  ([@aydasraf](https://github.com/aydasraf))
- One-time metadata backfill CLI for existing packages
  ([@aydasraf](https://github.com/aydasraf))
- Version inference from dotted artifact names for file/file-proxy repos
  ([@aydasraf](https://github.com/aydasraf))
- Version repair CLI (`--mode version-repair`) for bulk-fixing UNKNOWN versions
  ([@aydasraf](https://github.com/aydasraf))
- Stored `version_sort bigint[]` generated column for natural ordering
  ([@aydasraf](https://github.com/aydasraf))
- Distributed tracing with B3 (openzipkin) and W3C Trace Context support
  ([@aydasraf](https://github.com/aydasraf))
- trace.id, span.id, span.parent.id in all log entries per SRE convention
  ([@aydasraf](https://github.com/aydasraf))
- SRE2042 validation — malformed/all-zero trace/span IDs regenerated with W3C version byte check
  ([@aydasraf](https://github.com/aydasraf))
- traceparent response header on all HTTP responses (both public and API ports)
  ([@aydasraf](https://github.com/aydasraf))
- B3 + W3C header injection into all upstream calls (all proxy adapters via JettyClientSlice, SSO, Okta)
  ([@aydasraf](https://github.com/aydasraf))
- MDC propagation across all 46 `executeBlocking` worker-thread call sites via `MdcPropagation`
  ([@aydasraf](https://github.com/aydasraf))
- Trace context middleware on API port (AsyncApiVerticle) — MDC for trace.id, span.id, client.ip
  ([@aydasraf](https://github.com/aydasraf))
- Artifact audit logging at INFO level — upload, download, delete, resolution events
  ([@aydasraf](https://github.com/aydasraf))
- Dedicated `artifact.audit` logger with ECS-structured fields
  ([@aydasraf](https://github.com/aydasraf))
- Proxy Protocol v2 support for AWS NLB on all ports (main, API, per-repo)
  ([@aydasraf](https://github.com/aydasraf))
- Hosted-first cascade — index-targeted queries try hosted members before proxies
  ([@aydasraf](https://github.com/aydasraf))
- Flyway V100–V117 — all auth, provider, user-lifecycle, cooldown, and sequence repair schema
  ([@aydasraf](https://github.com/aydasraf))
- pg_cron job definitions for materialized view refresh
  ([@aydasraf](https://github.com/aydasraf))

### 🔧 Bug fixes

- Credential cache invalidation is now cluster-wide (L1 + L2) on every password change
  ([@aydasraf](https://github.com/aydasraf))
- Authentication chain respects provider authority for local users
  ([@aydasraf](https://github.com/aydasraf))
- SSO-provisioned accounts remain eligible for SSO sign-in
  ([@aydasraf](https://github.com/aydasraf))
- Persistent inline error messaging on sign-in and SSO callback views
  ([@aydasraf](https://github.com/aydasraf))
- Generic, non-disclosing error messages across all sign-in failure paths
  ([@aydasraf](https://github.com/aydasraf))
- SSO callback view no longer auto-redirects on failure
  ([@aydasraf](https://github.com/aydasraf))
- axios interceptor no longer forces page reload on failed auth-boundary requests
  ([@aydasraf](https://github.com/aydasraf))
- Wrong current password on change-password no longer hangs the UI indefinitely
  ([@aydasraf](https://github.com/aydasraf))
- Typed SortField enum prevents injection on sort parameter
  ([@aydasraf](https://github.com/aydasraf))
- Permission-aware SQL filter replaces overfetch pattern
  ([@aydasraf](https://github.com/aydasraf))
- Proxy cache serves JSON with correct Content-Type on cache hits
  ([@aydasraf](https://github.com/aydasraf))
- Proxy cache rejects JSON responses with empty `files` array (prevents phantom package claims in groups)
  ([@aydasraf](https://github.com/aydasraf))
- Relative URLs in JSON index prevent hostname-resolution errors
  ([@aydasraf](https://github.com/aydasraf))
- PEP 691 yanked field encoding corrected to string|false per spec
  ([@aydasraf](https://github.com/aydasraf))
- Auth failure log levels reclassified — wrong password is WARN, system errors stay ERROR
  ([@aydasraf](https://github.com/aydasraf))
- Okta userinfo endpoint failures reclassified from WARN to ERROR (upstream system error)
  ([@aydasraf](https://github.com/aydasraf))
- Malformed Authorization header returns 401 instead of 500
  ([@aydasraf](https://github.com/aydasraf))
- url.original includes full path + query string, sanitized (extended: password, secret, client_secret)
  ([@aydasraf](https://github.com/aydasraf))
- Hot-path INFO logging downgraded to DEBUG (MemberSlice rewrite, cache hits, slow fetches, FORBIDDEN)
  ([@aydasraf](https://github.com/aydasraf))
- Expired cooldown blocks now invalidate the metadata cache (L1 + L2)
  ([@aydasraf](https://github.com/aydasraf))
- BIGSERIAL sequence repair after bulk backfills (V117)
  ([@aydasraf](https://github.com/aydasraf))
- SAVEPOINT isolation in DbConsumer — single-event failures no longer poison the batch
  ([@aydasraf](https://github.com/aydasraf))
- 404 log noise reduced — per-member 404s at DEBUG, aggregate miss at WARN
  ([@aydasraf](https://github.com/aydasraf))

### 🔒 Security

- UI dependencies pinned to exact versions (supply-chain hardening)
  ([@aydasraf](https://github.com/aydasraf))
- .npmrc enforces save-exact, package-lock, engine-strict
  ([@aydasraf](https://github.com/aydasraf))
- vite upgraded to patched release, clearing dev-server advisories
  ([@aydasraf](https://github.com/aydasraf))
- npm audit reports zero vulnerabilities
  ([@aydasraf](https://github.com/aydasraf))
- Java dependencies refreshed to current stable within major lines
  ([@aydasraf](https://github.com/aydasraf))
- Passwords hashed with bcrypt
  ([@aydasraf](https://github.com/aydasraf))

### 📈 Performance

- Index-miss fanout restricted to proxy-type members only
  ([@aydasraf](https://github.com/aydasraf))

---

## Version 2.0.7

### 🌟 New features

- JWT JTI allowlist — forged tokens rejected even when HMAC secret is known
  ([@aydasraf](https://github.com/aydasraf))
- Per-repo cooldown overrides with three-tier priority (per-repo > per-type > global)
  ([@aydasraf](https://github.com/aydasraf))
- `ArtifactNameParser` drives `locateByName()` for all adapters; `locate()` removed from hot path
  ([@aydasraf](https://github.com/aydasraf))
- Dark/light theme switch with corrected color palette
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))
- Sort artifacts by name in repository browser
  ([@turanmahmudov-auto1](https://github.com/turanmahmudov-auto1))

### 🔧 Bug fixes

- Auth redirect loop — API client aligned to localStorage
  ([@aydasraf](https://github.com/aydasraf))
- Dashboard zeros for non-admin users — stats and settings fetched independently
  ([@aydasraf](https://github.com/aydasraf))
- Grafana URL shown to all authenticated users
  ([@aydasraf](https://github.com/aydasraf))
- PHP Composer `DownloadArchiveSlice` returns 404 instead of 500 when artifact missing
  ([@aydasraf](https://github.com/aydasraf))

---

## Version 2.0.5

### 🔧 Bug fixes

- Cooldown unblock now invalidates metadata cache
  ([@aydasraf](https://github.com/aydasraf))
- Maven 500 for repo names containing dots (e.g. `atlassian.com`)
  ([@aydasraf](https://github.com/aydasraf))
- Proxy returns 404 (not 503) when upstream responds with 4xx
  ([@aydasraf](https://github.com/aydasraf))
- Show minutes in cooldown remaining time when < 1h
  ([@aydasraf](https://github.com/aydasraf))
- Persist Grafana URL via settings API
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- pg_cron hourly DELETE job for expired cooldown rows + partial index
  ([@aydasraf](https://github.com/aydasraf))
- TB and PB tiers in dashboard storage display
  ([@aydasraf](https://github.com/aydasraf))

### 🔒 Security

- log4j 2.25.3, postgresql 42.7.7, jetty 11.0.26, commons-fileupload 1.6.0 (CVE-2025-48976), happy-dom 20.x (RCE fix)
  ([@aydasraf](https://github.com/aydasraf))

---

## Version 2.0.0

### 🌟 New features

- Complete rebrand from Artipie to Pantera — packages, classes, configs, Docker, Grafana
  ([@aydasraf](https://github.com/aydasraf))
- Vue 3 management UI with repository browser, user/role admin, dashboard
  ([@aydasraf](https://github.com/aydasraf))
- PostgreSQL-backed settings with Flyway migrations (replaces YAML-only)
  ([@aydasraf](https://github.com/aydasraf))
- HA clustering with Valkey pub/sub and multi-node state sync
  ([@aydasraf](https://github.com/aydasraf))
- Quartz scheduler for background jobs
  ([@aydasraf](https://github.com/aydasraf))
- ECS-structured JSON logging with Log4j2 EcsLayout
  ([@aydasraf](https://github.com/aydasraf))
- S3 storage optimizations (streaming, multipart upload)
  ([@aydasraf](https://github.com/aydasraf))
- Auth provider renamed from "artipie" to "local" (V102 migration)
  ([@aydasraf](https://github.com/aydasraf))

---

*Prior to v2.0.0, this project was known as [Artipie](https://github.com/artipie/artipie) (releases 0.20–0.23). See the Artipie repository for historical changelogs.*
