# Changelog — v2.2.0

Target-architecture alignment release. Ships **nine work items** of the v2.2 plan (`docs/analysis/v2.2-target-architecture.md` §12): WI-00 (queue/log hotfix), WI-01 (Fault + Result sum types), WI-02 (full RequestContext + Deadline + ContextualExecutor), WI-03 (StructuredLogger 5-tier + LevelPolicy + AuditAction), **WI-04 (GroupResolver replaces GroupSlice at every production site)**, WI-05 (SingleFlight coalescer), WI-07 (ProxyCacheWriter + Maven checksum integrity), plus the Wave-3 additions WI-post-05 (retire RequestDeduplicator) and WI-post-07 (wire ProxyCacheWriter into pypi/go/composer). WI-06, WI-06b, WI-08, WI-09, WI-10 are deferred to follow-on v2.2.x trains — see `docs/analysis/v2.2-next-session.md` for the exact task list.

Also in v2.2.0: a **P0 production-readiness pass against the Opus 4.7 audit 2026-04-18** (Groups A–H). Twelve cross-cutting hardenings covering cancel propagation, cache 2-tier layout for auth + group-metadata-stale, Hikari fail-fast defaults, hot-path allocation removal, zero-copy artifact chunks, StAX-streamed Maven metadata merging, fail-fast cooldown registry lookups, `DbArtifactIndex` saturation policy, optional HTTP/3 PROXY protocol, and resource-leak fixes on legacy RPM/Debian streams. See the "Production-readiness hardening" section below for the group-level summary; operator-facing docs in `docs/admin-guide/v2.2-deployment-checklist.md`.

## Highlights

- **GroupResolver is now the sole production group-resolution engine** (WI-04). The deprecated `GroupSlice` (1338 LOC) and its four dedicated test classes are deleted. All four wiring sites in `RepositorySlices.java` (npm-group, file/php-group, maven-group, the generic group-adapter case) now instantiate `GroupResolver`; `MavenGroupSlice` and `ComposerGroupSlice` receive it as their `Slice` delegate. A new convenience constructor on `GroupResolver` accepts the legacy `(SliceResolver, memberNames, port, ...)` shape so the call-sites didn't balloon. `GroupSliceMetrics` is renamed `GroupResolverMetrics`; every stale javadoc / inline-comment reference to `GroupSlice` across 15 production files is updated to point at `GroupResolver`. Status surface is unchanged (200/404/500/502 — no 503/504 from group resolution per spec §"No 503 from group resolution"); behavioural improvements documented in the commit series: index-hit 404 falls through to proxy fanout (TOCTOU fix, A11), `AllProxiesFailed` passes the winning proxy's response through (was synthetic 502), and 5xx responses now carry `X-Pantera-Fault` + `X-Pantera-Proxies-Tried` headers.
- **`CooldownResponses` deleted; `CooldownResponseRegistry` is mandatory.** The `@Deprecated(forRemoval=true)` `CooldownResponses.forbidden(block)` helper had 12 remaining callers across 6 adapters and one pantera-core fallback. All 12 are migrated to `CooldownResponseRegistry.instance().get(repoType).forbidden(block)` and the class is deleted. The former silent fallback in `BaseCachedProxySlice` (`.orElseGet(() -> CooldownResponses.forbidden(block))`) now throws `IllegalStateException` on a missing factory — factory registration is now a startup-time hard requirement. `CooldownWiring` adds response-factory aliases (`npm-proxy`, `pypi-proxy`, `docker-proxy`, `go-proxy`, `php`, `php-proxy`) so every repoType string that reaches the registry resolves.
- **UI: legacy `'hex'` repo-type key purged in favour of `'hexpm'`** across `repoTypes.ts`, `techSetup.ts`, `SettingsView.vue`. `SettingsView` now emits `hexpm-proxy` instead of `hex-proxy` (canonical family key, matches the backend's `ApiRoutingSlice` normalization). `SearchView.vue`'s `startsWith('hex')` prefix match is retained — it still matches `hexpm`. New user-guide pages: `docs/user-guide/repositories/gradle.md`, `go-group`/`go-proxy` sections in `go.md`, and a "Adding Members to a Group Repository" section in `ui-guide.md` covering the AutoComplete group-member picker and inline "Create new" modal.
- **Cooldown test coverage closed for Task 15 + Task 19.** Two new unit tests under `pantera-core`: `PolicyChangeInvalidationTest` verifies that a cooldown-duration config change invalidates `FilteredMetadataCache` and flips the block decision for affected versions; `UpstreamPublishReEvalTest` verifies that `invalidate(repoType, repoName, packageName)` after an upstream publish forces a re-parse and the new version is included. Two new chaos tests under `pantera-main/src/test/java/com/auto1/pantera/chaos/`: `CooldownValkeyStalenessTest` (slow/unreachable L2 must not block L1-served reads) and `CooldownHighCardinalityTest` (10× capacity unique keys triggers bounded eviction, no OOM).
- **Stale `MdcPropagation` references removed.** The class was deleted from pantera-core in WI-02 but three test files (`CooldownContextPropagationTest`, `ContextualExecutorIntegrationTest`, the now-deleted `GroupSliceFlattenedResolutionTest`) plus `docs/analysis/v2.2-next-session.md:73` still mentioned it textually. All updated to `ContextualExecutor` / `TraceContextExecutor` terminology. Zero `MdcPropagation.` references remain in production code or live tests.

## Highlights (earlier waves)

- **Maven `ChecksumFailureException` storms stopped at the source.** The `oss-parent-58.pom.sha1` class of cache-drift bug (primary bytes and the sidecar they're verified against diverging across stale-while-revalidate refetches) can no longer produce a committed cache entry. The new `ProxyCacheWriter` is a single write path that fetches primary + every sidecar in one coupled batch, recomputes all four digests (MD5, SHA-1, SHA-256, SHA-512) over the streamed primary bytes, and rejects the whole write if any sidecar disagrees. A companion `scripts/pantera-cache-integrity-audit.sh` heals pre-existing drift with `--dry-run` / `--fix`.
- **The v2.1.3 503 burst and 2.4M/12h WARN flood are closed.** Every bounded-queue write on a request-serving path migrated from `queue.add()` (throws on overflow) to `queue.offer()` (returns false, increments `pantera.events.queue.dropped` counter). Access-log level policy redowngraded 404/401/403 from WARN to INFO — the three status codes driving ~95% of the pre-cutover WARN noise per the forensic analysis. The production regression of `IllegalStateException("Queue full")` escaping into 503 cascades is now architecturally impossible in the migrated sites.
- **Three hand-rolled request coalescers collapsed into one.** The `inFlightFanouts` (GroupSlice), `inFlightMetadataFetches` (MavenGroupSlice), and `RequestDeduplicator` (CachedNpmProxySlice, BaseCachedProxySlice) implementations were each independently solving the same problem with slightly different race guards. `SingleFlight<K,V>` is the one utility for the whole codebase, Caffeine-backed, with stack-flat follower completion (the v2.1.3 `StackOverflowError` at ~400 concurrent followers cannot recur), explicit zombie eviction via `CompletableFuture.orTimeout`, and per-caller cancellation isolation.
- **Fault taxonomy and Result sum types introduced as vocabulary, no behaviour change yet.** `pantera-core/http/fault/` now contains a sealed `Fault` hierarchy, a `Result<T>` with `map`/`flatMap`, a `FaultClassifier` for `Throwable → Fault` fallback, and a `FaultTranslator` that is the single decision point for "what HTTP status does this fault produce". No existing slice has been rewired yet — WI-04 does that. This release establishes the types + the 40-test contract, so every later WI can land without retyping the worked-examples table.
- **`RequestDeduplicator` deleted; `FetchSignal` promoted.** `BaseCachedProxySlice` now coalesces via `SingleFlight<Key, FetchSignal>` identical to `CachedNpmProxySlice`. `RequestDeduplicator.java`, `RequestDeduplicatorTest.java`, and the `DedupStrategy` enum are gone; the nested `FetchSignal` enum is now a top-level type at `pantera-core/http/cache/FetchSignal.java` (WI-post-05).
- **`ProxyCacheWriter` wired into pypi / go / composer adapters** (WI-post-07). Each adapter's cached-proxy slice now routes primary-artifact cache misses through the same coupled primary+sidecar write path Maven received in WI-07. Each carries an atomic cache-integrity regression test. Only the npm adapter retains a `TODO(WI-post-07)` marker — its migration requires the RxJava2 retirement scheduled for WI-08.
- **`RequestContext` expanded to the full ECS/APM envelope** (WI-02). Thirteen fields covering every ECS key Pantera emits (`trace.id`, `transaction.id`, `span.id`, `http.request.id`, `user.name`, `client.ip`, `user_agent.original`, `repository.name`, `repository.type`, `package.name`, `package.version`, `url.original`, `url.path`) plus an end-to-end `Deadline`. A four-arg backward-compat ctor retains the v2.2.0 scaffold signature so existing production call-sites compile unchanged. `ContextualExecutor.contextualize(Executor)` propagates the ThreadContext snapshot + APM span across `CompletableFuture` boundaries — wired at `DbArtifactIndex`, `GroupSlice.DRAIN_EXECUTOR`, `BaseCachedProxySlice` SingleFlight, `CachedNpmProxySlice` SingleFlight, and `MavenGroupSlice` SingleFlight.
- **`StructuredLogger` 5-tier facade introduced** (WI-03). Five tier builders — `AccessLogger` (client→pantera), `InternalLogger` (pantera→pantera 500), `UpstreamLogger` (pantera→remote), `LocalLogger` (local ops), `AuditLogger` (compliance, INFO, non-suppressible) — sitting above a central `LevelPolicy` enum that encodes the §4.2 log-level matrix in one place. A closed `AuditAction` enum enumerates the only four compliance events (`ARTIFACT_PUBLISH`, `ARTIFACT_DOWNLOAD`, `ARTIFACT_DELETE`, `RESOLUTION`) per §10.4. `EcsLoggingSlice` now emits the access log exactly once per request via `StructuredLogger.access().forRequest(ctx)` on the success path (the legacy dual-emission was removed). `MdcPropagation` is retained as `@Deprecated(forRemoval=true)` while WI-06/WI-08 migrate its remaining ~110 production callers.

## Production-readiness hardening

The P0 pass against the Opus 4.7 audit 2026-04-18. Twelve cross-cutting hardenings, grouped A–H, all landed as standalone commits on top of the WI-00…WI-07 work above.

- **Client-disconnect propagation (Group A).** `VertxSliceServer` now captures the reactive-streams `Subscription` via `doOnSubscribe` and cancels it on `closeHandler` / `exceptionHandler`. Cancel propagates end-to-end into Jetty upstream fetches. `ArtifactHandler`'s download paths capture the `Disposable` and dispose on client disconnect. `StreamThroughCache`, `DiskCacheStorage`, and `VertxRxFile.save` gain `doOnCancel` cleanup matching existing `doOnError`. `Http3Server` enforces a per-stream buffer cap via `PANTERA_HTTP3_MAX_STREAM_BUFFER_BYTES` (default 16 MiB).

- **Auth cache + Hikari fail-fast (Group B).** New `CachedLocalEnabledFilter` with L1 Caffeine + L2 Valkey + `CacheInvalidationPubSub` cross-node eviction; hit rate is expected >95% and the per-request JDBC hit is gone. `UserHandler` invalidates on put/delete/enable/disable/alterPassword. **Behavior change: Hikari defaults are now fail-fast** — `connectionTimeout` tightened from `5000` to `3000` ms and `leakDetectionThreshold` from `300000` to `5000` ms. Operators may see leak WARNs that were silent before; each one is a real held-connection bug to triage. See the canary ramp in `docs/admin-guide/database.md`.

- **GroupMetadataCache stale tier (Group C).** The former unbounded `lastKnownGood` `ConcurrentHashMap` is replaced by a full 2-tier cache (L1 Caffeine + L2 Valkey) driven by `meta.caches.group-metadata-stale`. Design principle: **the cache is an aid, never a breaker** — under realistic cardinality no eviction fires; the bounds are a JVM-memory safety net. Degradation is `L1 → L2 → expired primary-cache entry → miss`. **L2 now survives JVM restart** (the old CHM did not), strictly improving availability. `JobDataRegistry` gains a sanity cap (`PANTERA_JOB_DATA_REGISTRY_MAX`, default 10 000) — logs a key prefix on overflow, never silently drops.

- **Hot-path `Pattern.compile` + hex + `ObjectMapper` (Groups D, E.3, E.4).** `TrimmedDocker.trim()` and `SubStorage.list()` hoist `Pattern.compile` to final fields (thousands of compile allocations/second eliminated at 1000 req/s). `Yaml2Json` / `Json2Yaml` hoist their `ObjectMapper` / `YAMLMapper` to static finals. Maven group metadata merging switches from a `String.format("%02x", …)` hex loop to `java.util.HexFormat.of().formatHex(…)` (single allocation per digest).

- **StAX streaming Maven merge (Group E.2).** `MavenGroupSlice` now delegates to a new `StreamingMetadataMerger` that accumulates only the deduplicated `<version>` TreeSet and newest-wins scalars. Peak memory is `O(unique versions)`, not `O(sum of member body sizes)`. Malformed or truncated member bodies are skipped with a WARN; remaining members still merge. No size cap is introduced — doing so would synthesize a client-facing 502 for legitimately large metadata, which is a worse failure mode. An **alert-only** histogram `pantera.maven.group.member_metadata_size_bytes` surfaces outliers to ops.

- **Zero-copy ArtifactHandler chunks (Group E.1).** `ArtifactHandler` download paths replace per-chunk `new byte[] + buf.get(bytes) + Buffer.buffer(bytes)` with `Buffer.buffer(Unpooled.wrappedBuffer(buf))`. At 1000 req/s × 5 MB bodies × 64 KB chunks that is ~80 000 byte[] allocations/s straight to garbage — now zero. Vert.x releases on write completion.

- **Cooldown registry fail-fast (Group G).** `CooldownResponseRegistry.getOrThrow(repoType)` replaces `.get(repoType)` at all 11 production adapter sites (files / npm / pypi / composer / go / docker) and in `BaseCachedProxySlice`. The former silent `.orElseGet(() -> CooldownResponses.forbidden(block))` fallback is deleted. Missing registration now throws `IllegalStateException("No CooldownResponseFactory registered for repoType: <type>")` on first request, surfacing wiring omissions at canary time instead of indefinitely.

- **`DbArtifactIndex` `AbortPolicy` (Group H.1).** The index executor switches from `CallerRunsPolicy` to `AbortPolicy` — under queue saturation, submissions no longer execute inline on the caller (potentially the Vert.x event loop). **BEHAVIOR CHANGE:** saturation surfaces as `Fault.IndexUnavailable`, which `FaultTranslator` returns as `500` with `X-Pantera-Fault: index-unavailable`. Follow-up commit `abee2ec9` wraps `CompletableFuture.supplyAsync` in a try/catch so the synchronous `RejectedExecutionException` is always observed via a failed future, never raw-propagated. Chaos test `DbArtifactIndexSaturationTest` locks the policy.

- **Dead `api-workers` WorkerExecutor removed (Group H.2).** `AsyncApiVerticle` created a `WorkerExecutor` that no route ever referenced. Deleted; no behavior change.

- **HTTP/3 PROXY protocol flag (Group H.3).** `PANTERA_HTTP3_PROXY_PROTOCOL=true` (env only for now — the YAML path is not wired because `Http3Server`'s public ctor does not accept `Settings`) prepends Jetty's `ProxyConnectionFactory` so PROXY-v2 preludes from NLBs are parsed and the real client IP is recovered. Default `false` — zero behavior change. Emits an INFO startup log with `event.action=http3_proxy_protocol_enabled` when activated.

- **Resource leak fixes (Group F).** `XmlPrimaryChecksums` and `FilePackageHeader` previously opened `InputStream`s eagerly in their constructors; if the consuming method was never invoked the stream leaked. Both now store only the `Path` and open inside the consuming method under try-with-resources. RPM `Gzip.unpackTar` wraps `GzipCompressorInputStream` in the same try-with as `TarArchiveInputStream` so the native `Inflater` is released if the tar wrapper ctor throws. Debian `MultiPackages.merge` wraps both GZIP streams in try-with-resources; caller-owned outer streams are protected by a non-closing wrapper adapter.

## Fixed

- **Queue overflow cascade on npm `DownloadAssetSlice`** (forensic §1.6 F1.1/F1.2). Bounded `LinkedBlockingQueue<ProxyArtifactEvent>` writes on both the cache-hit (line 198) and cache-miss (line 288) paths called `AbstractQueue.add()`, which throws `IllegalStateException("Queue full")` on overflow. A burst of 11,499 such throws in a 2-minute window in prod surfaced as 503s to clients because the exception escaped the serve path. Both call-sites migrated to `queue.offer(event)`; the `ifPresent` enqueue lambda is wrapped in `try { ... } catch (Throwable t) { log at WARN; continue; }` on both paths so background-queue failure can NEVER escape into the response. Verified by the new `DownloadAssetSliceQueueFullTest` — pre-saturate the queue, fire 50 concurrent cache-hit GETs, assert 50 × 200 and an advance of the drop counter.
  ([@aydasraf](https://github.com/aydasraf))
- **Access-log WARN flood from 4xx client probes** (forensic §1.7 F2.1/F2.2). `EcsLogEvent.log()` emitted every 4xx response at WARN, including the three status codes driving most of the noise: 404 (Maven probe-and-miss + npm metadata scans), 401 (unauthenticated health checks before auth retry), 403 (policy deny). 2.4M WARN lines in 12h post-deploy; client-driven, not Pantera fault. Level policy now 404/401/403 → INFO, other 4xx → WARN (unchanged), 5xx → ERROR (unchanged), slow >5s → WARN (unchanged). Contract tests `notFoundResponsesLogAtInfoNotWarn` / `unauthorizedResponsesLogAtInfoNotWarn` / `forbiddenResponsesLogAtInfoNotWarn` / `otherFourXxStillLogAtWarn` / `fiveXxStillLogAtError` lock in the matrix.
  ([@aydasraf](https://github.com/aydasraf))
- **`StackOverflowError` class in GroupSlice follower chain** (commit `ccc155f6` / anti-pattern A9 in v2.1.3 architecture review). When the leader fanout completed synchronously, each follower's `thenCompose(...)` ran on the leader's stack — ~400 followers overflowed the stack. Replaced the bespoke `ConcurrentHashMap<String, CompletableFuture<Void>>` coalescer + its 30-line "complete-before-remove" race-comment with `SingleFlight<String, Void>`, which dispatches all follower completions via the configured executor. The regression guard is `stackFlatUnderSynchronousCompletion` (500 followers, synchronous leader, no SOE).
  ([@aydasraf](https://github.com/aydasraf))
- **Upstream sidecar/primary drift in Maven cache** (target-architecture §9.5, production `oss-parent-58.pom.sha1` symptom). Previously `storage.save(primary)` and `storage.save(sidecar)` were independent Rx pipelines; SWR refetch could update the `.pom` without re-pulling `.pom.sha1`, and eviction could drop one without the other. Every mode of drift produced the same user-visible `ChecksumFailureException` in Maven client builds. New `ProxyCacheWriter.writeWithSidecars(...)` streams the primary into a temp file while computing all four digests in one pass, fetches sidecars concurrently, compares byte-by-byte, and saves primary-first-then-sidecars only on agreement. Mismatch returns `Result.err(Fault.UpstreamIntegrity(...))`; nothing lands in the cache. Regression test `ProxyCacheWriterTest.ossParent58_regressionCheck` reproduces the exact production hex.
  ([@aydasraf](https://github.com/aydasraf))
- **Jetty client idle-close logged as request failure** (forensic §1.7 F4.4). "Idle timeout expired: 30000/30000 ms" is a connection-lifecycle event from Jetty's 30s idle policy, not a request error. 20 ERROR entries per 12h in prod, all for this one cause. `JettyClientSlice.isIdleTimeout(Throwable)` identifies the specific `TimeoutException` with "Idle timeout expired" in its message chain (up to 5 hops) and downgrades that case to DEBUG. All other HTTP failures continue to log at ERROR.
  ([@aydasraf](https://github.com/aydasraf))
- **"Repository not found in configuration" at WARN** (forensic §1.7). Client-config error (stale repo URL in a pom.xml somewhere), not a Pantera fault. Emitted ~1,440 WARN lines per 12h. Downgraded to INFO.
  ([@aydasraf](https://github.com/aydasraf))

## Added

- **`pantera-core/http/fault/` sum types.** A sealed `Fault` hierarchy (`NotFound`, `Forbidden`, `IndexUnavailable`, `StorageUnavailable`, `AllProxiesFailed`, `UpstreamIntegrity`, `Internal`, `Deadline`, `Overload`) codifying every failure mode that can reach a slice boundary. `Result<T>` is the discriminated `Ok<T>` / `Err<T>` union with `map`/`flatMap`. `FaultClassifier.classify(Throwable, String)` is the fallback for `.exceptionally(...)` handlers. `FaultTranslator.translate(Fault, RequestContext)` is the single decision point for HTTP status mapping (see §9 of the target-architecture doc for the full policy table, including the `AllProxiesFailed` pass-through worked examples). Coverage 99% instructions / 97% branches on the `fault` package; exhaustive-switch guard test `exhaustiveSwitchCompilesForEveryVariant`. No slice is wired yet — WI-04 does that; this release establishes the vocabulary.
- **`pantera-core/http/resilience/SingleFlight<K,V>`.** Unified per-key request coalescer backed by Caffeine `AsyncCache`. Contract: (a) concurrent `load(k, loader)` calls for the same key invoke the loader exactly once; (b) all callers receive the same value or exception; (c) entry invalidated on completion so the next load is fresh; (d) per-caller cancellation does not cancel the loader; (e) stack-flat follower dispatch via the configured executor; (f) zombie eviction by `CompletableFuture.orTimeout(inflightTtl)` for loaders that never complete. 14 property-style tests including N=1000 coalescing, 100-caller cancellation isolation, zombie-after-TTL, loader-failure-propagation, and the 500-follower synchronous-completion stack-safety regression guard.
- **`pantera-core/http/cache/ProxyCacheWriter`.** Single write-path for `primary + sidecars` that verifies upstream sidecar claims against bytes before the pair lands in the cache. Streams the primary into a NIO temp file (bounded chunk size, no heap scaling with artifact size) while updating four `MessageDigest` accumulators in one pass; pulls sidecars concurrently; compares trimmed-lowercased hex bodies against the computed digest; saves primary-first-then-sidecars only on agreement. Mismatch → `Result.err(Fault.UpstreamIntegrity(...))` and the temp file is deleted; no partial state leaks into the cache. Tier-4 LocalLogger events under `com.auto1.pantera.cache` with `event.action=cache_write` and `event.outcome ∈ {success, integrity_failure, partial_failure}`, plus Micrometer counters `pantera.proxy.cache.integrity_failure{repo, algo}` and `pantera.proxy.cache.write_partial_failure{repo}`.
- **`pantera-core/http/cache/ProxyCacheWriter.IntegrityAuditor` + `scripts/pantera-cache-integrity-audit.sh`.** Static scanner that walks a `Storage`, recomputes digests for every primary artifact (`.pom`, `.jar`, `.war`, `.aar`, `.ear`, `.tgz`, `.tar.gz`, `.whl`, `.zip`), compares against any present sidecar, and in `--fix` mode deletes mismatched pairs so the next client GET repopulates through `ProxyCacheWriter`. CLI lives at `pantera-main/tools/CacheIntegrityAudit`; wrapper shell script is the supported entry point. Exit codes `0` (clean or fixed), `1` (mismatch in dry-run), `2` (CLI usage error).
- **`pantera-core/metrics/EventsQueueMetrics`.** Shared callback invoked when `queue.offer(...)` returns false. Emits one structured WARN on `com.auto1.pantera.scheduling.events` with `event.action=queue_overflow` and bumps `pantera.events.queue.dropped{queue=<repoName>}` on the Micrometer registry when initialised. Exposes `dropCount()` for tests that run without a registry.
- **`pantera-core/http/cache/FetchSignal` (top-level enum).** `{SUCCESS, NOT_FOUND, ERROR}` — promoted from its former nested location inside the now-deleted `RequestDeduplicator`. Used by `BaseCachedProxySlice` and `CachedNpmProxySlice` as the return type of coalesced fetch loaders.
- **`pantera-core/src/test/.../BaseCachedProxySliceDedupTest.java`.** 4 regression tests for the `BaseCachedProxySlice` → `SingleFlight<Key, FetchSignal>` migration: concurrent coalescing invokes the loader once, `NOT_FOUND` propagates to all followers, `ERROR` propagates to all followers, cancellation isolation.
- **`pypi-adapter`, `go-adapter`, `composer-adapter` — `ProxyCacheWriter` wiring + integrity tests.** Each adapter's `CachedProxySlice` constructs a `ProxyCacheWriter` when a file-backed `Storage` is present, routing primary-artifact cache misses through the same coupled primary+sidecar write path Maven received in WI-07. One atomicity test + one digest-mismatch test per adapter.
- **`pantera-core/http/context/RequestContext` (full envelope).** 13-field record: `traceId`, `transactionId`, `spanId`, `httpRequestId`, `userName`, `clientIp`, `userAgent`, `repoName`, `repoType`, `ArtifactRef artifact`, `urlOriginal`, `urlPath`, `Deadline deadline`. Includes `bindToMdc()` → `AutoCloseable` for try-with-resources MDC binding, `fromMdc()` for thread-hop recovery, `minimal(traceId, httpRequestId, repoName, urlOriginal)` factory, `withRepo(name, type, ref)` for post-resolution enrichment, and a nested `ArtifactRef` record with an `EMPTY` sentinel.
- **`pantera-core/http/context/Deadline`.** Monotonic wall-clock deadline record (`expiresAtNanos`) with `in(Duration)` factory, `remaining()` (clamped non-negative), `expired()`, `remainingClamped(max)`, and `expiresAt()` → `Instant`.
- **`pantera-core/http/context/ContextualExecutor`.** Utility that wraps any `Executor` so tasks inherit the caller's Log4j2 `ThreadContext` snapshot + active APM `Span`. Wired at `DbArtifactIndex.DbIndexExecutorService`, `GroupSlice.DRAIN_EXECUTOR`, `BaseCachedProxySlice` SingleFlight, `CachedNpmProxySlice` SingleFlight, `MavenGroupSlice` SingleFlight — every hot-path thread hop contextualised.
- **`pantera-core/http/observability/StructuredLogger`.** 5-tier facade: `access()` (Tier-1, client→pantera), `internal()` (Tier-2, pantera→pantera 500), `upstream()` (Tier-3, pantera→remote), `local()` (Tier-4, local ops), `audit()` (Tier-5, compliance). Each tier exposes a builder that `Objects.requireNonNull`s its required fields at entry. Every builder binds `RequestContext` to `ThreadContext` in try-with-resources so EcsLayout picks up ECS keys automatically.
- **`pantera-core/http/observability/LevelPolicy`.** Table-driven enum encoding the §4.2 log-level matrix: `CLIENT_FACING_SUCCESS` (DEBUG), `CLIENT_FACING_NOT_FOUND`/`CLIENT_FACING_UNAUTH` (INFO), `CLIENT_FACING_4XX_OTHER`/`CLIENT_FACING_SLOW` (WARN), `CLIENT_FACING_5XX`/`INTERNAL_CALL_500`/`UPSTREAM_5XX`/`LOCAL_FAILURE` (ERROR), `AUDIT_EVENT` (INFO), plus DEBUG/INFO/WARN hooks for every tier's success/degraded states.
- **`pantera-core/audit/AuditAction`.** Closed enum of compliance audit events: `ARTIFACT_PUBLISH`, `ARTIFACT_DOWNLOAD`, `ARTIFACT_DELETE`, `RESOLUTION`. Deliberately small to protect the 90-day audit dataset from action-type explosion.
- **`pantera-core` tests: 54 new tests across `http/context/` (ContextualExecutorTest, DeadlineTest, RequestContextTest), `http/observability/` (AccessLoggerTest, AuditLoggerTest, ContextualExecutorIntegrationTest, InternalLoggerTest, LevelPolicyTest, LocalLoggerTest, UpstreamLoggerTest), and `http/cache/BaseCachedProxySliceDedupTest`.** Every tier's required-field contract, level-policy mapping, and APM/MDC propagation is test-locked.
- **Adapter tests: 6 new integrity tests across pypi / go / composer.** `CachedPyProxySliceIntegrityTest`, `CachedProxySliceIntegrityTest` (go), `CachedProxySliceIntegrityTest` (composer) — two tests per adapter covering atomic primary+sidecar commit and digest-mismatch rejection.

## Changed

- **Coalescer fields in GroupSlice / MavenGroupSlice / CachedNpmProxySlice / BaseCachedProxySlice are now `SingleFlight` instances.** Field names `inFlightFanouts` / `inFlightMetadataFetches` / `deduplicator` / `singleFlight` retained for minimal diff; only the type changed. Call-site semantics preserved: the leader/follower flag pattern is the same; followers still re-enter their respective fanout / metadata-fetch / origin-response paths once the shared gate resolves.
- **`RequestContext` expanded from 4 fields (v2.2.0 scaffold) to 13 fields (WI-02 full envelope).** A backward-compat 4-arg constructor is retained verbatim so every production call-site written against the scaffold compiles unchanged; internally it delegates to `minimal(...)` which fills `userName="anonymous"`, `ArtifactRef.EMPTY`, `Deadline.in(30 s)`, and `null` for every other field. The canonical 13-arg constructor is the one new code should prefer.
- **`EcsLoggingSlice` emits the access log exactly once per request via `StructuredLogger.access()`.** The former dual emission (`StructuredLogger.access()` + `new EcsLogEvent(...)` on every success path) was removed to halve access-log volume in Kibana. Only the `.exceptionally(...)` error path still uses `EcsLogEvent` (one call-site, scheduled for migration with the rest). Rich `user_agent.name` / `.version` / `.os.name` parsing is not re-emitted by `StructuredLogger.access` today — operators depending on those sub-fields need to query `user_agent.original` directly or wait for the follow-up WI that re-lifts parsing.
- **Three hot-path executors wrapped via `ContextualExecutor.contextualize(...)`.** `DbArtifactIndex` (via the internal `DbIndexExecutorService` adapter that forwards lifecycle methods to the underlying pool), `GroupSlice.DRAIN_EXECUTOR`, and all three SingleFlight-backed call sites (`BaseCachedProxySlice`, `CachedNpmProxySlice`, `MavenGroupSlice`) now propagate `ThreadContext` + APM span across their `CompletableFuture` boundaries.
- **Idle-connection events logged at DEBUG, not ERROR** (`JettyClientSlice`). See Fixed.
- **Bounded-queue enqueue semantics: `offer()`, not `add()`.** Every request-serving path that writes to a `LinkedBlockingQueue<ProxyArtifactEvent>` / `LinkedBlockingQueue<ArtifactEvent>` now uses `offer()` and routes overflow through `EventsQueueMetrics.recordDropped(repoName)`. Sites unbounded by design (ConcurrentLinkedDeque used for append-only drains) keep `add()` with an explicit `// ok: unbounded ConcurrentLinkedDeque` comment so the intent is auditable.
- **Access-log level policy for 404/401/403** downgraded to INFO. See Fixed.
- **Maven-adapter cached-proxy slice.** On primary-artifact cache miss (`.pom`, `.jar`, `.war`, `.aar`, `.ear`, `.zip`, `.module`) the request is routed through `ProxyCacheWriter.writeWithSidecars(...)` instead of the legacy split primary/sidecar writes. Cache-hits, maven-metadata.xml flow (stale-while-revalidate via `MetadataCache`), and non-primary sidecar paths are unchanged. Integrity failure returns 503 to the client with `X-Pantera-Fault: upstream-integrity` rather than committing the bad pair.
- **pypi / go / composer cached-proxy slices wired to `ProxyCacheWriter`** (WI-post-07). Primary-artifact cache misses (`.whl` / `.tar.gz` / sdist for pypi; `.zip` module archives for go; `.zip` dist archives for composer) now route through `ProxyCacheWriter`. Each adapter uses its native sidecar algorithm set (pypi: SHA-256 + MD5; go: SHA-256; composer: SHA-256).
- **`pom.xml` versions bumped 2.1.3 → 2.2.0** on the root reactor and all 30 modules. Docker image tags from `mvn install` now produce `pantera:2.2.0`. Command used: `mvn -T8 versions:set -DnewVersion=2.2.0 -DgenerateBackupPoms=false -DprocessAllModules=true`.

## Deprecated

- **`pantera-core/http/trace/MdcPropagation`** marked `@Deprecated(since="2.2.0", forRemoval=true)`. The replacement is `ContextualExecutor.contextualize(executor)` at pool boundaries + `RequestContext.bindToMdc()` in try-with-resources at the request edge. Approximately 110 production call-sites remain across `pantera-main/api/v1/*` handlers, `pantera-core/cooldown`, `pantera-main/group/*`, `npm-adapter/DownloadAssetSlice`, and `npm-adapter/NpmProxy` — deleting the class is a follow-up WI (WI-06 / WI-08 / Vert.x-handler contextualisation). Do not add new call-sites.

## Security / compliance

None. No CVE fixes, no permissions model changes, no credential-handling changes, no PII-scope changes. Integrity verification on proxy caches (WI-07 + WI-post-07) is a correctness hardening, not a security fix — the trust boundary (upstream declares a digest, we verify it) has not moved. The new audit logger (`StructuredLogger.audit()`) emits to a dedicated `com.auto1.pantera.audit` logger; see "Migration notes" below for the log4j2 configuration nuance before rolling out to production.

## Migration notes

**No operator action required for functional rollout.** All changes are drop-in for v2.1.3 deployments:

- The `queue.add → queue.offer` migration is internal; no YAML change, no CLI flag, no API change. Overflow events were previously stack-trace flooding; they now increment a counter and WARN once per drop.
- The access-log level policy change is internal to `EcsLogEvent` / `StructuredLogger.access`; operators who filtered dashboards on `log.level: WARN AND http.response.status_code: 404` will see those panels empty after cutover. That is the intended outcome — noise elimination — not a regression. Kibana panels that need 404 volume should switch to `log.level: INFO AND http.response.status_code: 404` (or simply filter by status code).
- The `ProxyCacheWriter` path in maven / pypi / go / composer adapters activates only when a file-backed `Storage` is present; deployments that inject a lambda-`Cache` in tests keep the pre-v2.2.0 code path verbatim.
- The `SingleFlight` coalescers use their own dedicated Caffeine `AsyncCache` instances with a 5-minute in-flight TTL and 10K max keys; heap growth is bounded and does not require tuning.
- `scripts/pantera-cache-integrity-audit.sh` is additive — a zero-impact no-op unless explicitly invoked. Running with `--dry-run` against a production cache is safe.
- **Kibana user_agent sub-fields:** operators who queried `user_agent.name` / `user_agent.version` / `user_agent.os.name` on access-log documents need to either (a) query `user_agent.original` directly (that's what `RequestContext` emits today) or (b) wait for the follow-up WI that re-lifts the parser into `StructuredLogger.access`. No data loss — only the parsed sub-fields are unavailable this release.
- **Audit-log level:** `StructuredLogger.audit()` writes to the logger named `com.auto1.pantera.audit`. The bundled `log4j2.xml` does not yet declare a dedicated appender for that logger — it inherits from `com.auto1.pantera` at INFO. That means "non-suppressible" is by convention; if an operator drops `com.auto1.pantera` to WARN they will suppress audit events. Add a dedicated `<Logger name="com.auto1.pantera.audit" level="info" additivity="false">` block in production overrides to make the non-suppressibility operationally enforced.

## Cooldown Metadata Filtering

Comprehensive two-layer cooldown enforcement across all 7 adapters (Maven, npm, PyPI, Docker, Go, Composer, Gradle). Full design documentation in `docs/cooldown-metadata-filtering.md`.

### Package Restructure (Phase 1)

- Cooldown package reorganised to SOLID sub-package layout: `api/`, `cache/`, `metadata/`, `response/`, `config/`, `metrics/`, `impl/`.
- `CooldownMetadataServiceImpl` renamed to `MetadataFilterService`.
- No behaviour change; all existing tests pass unchanged.

### Performance Hardenings (Phase 2)

- **H1 -- Pre-warm release-date cache.** `MetadataParser.extractReleaseDates()` SPI extracts timestamps from metadata (e.g., npm `time` field) and bulk-populates `CooldownCache` L1 with `false` (allowed) for versions older than the cooldown period. Avoids DB/Valkey round-trip on the hot path for the majority of versions.
- **H2 -- Parallel bounded version evaluation.** Dedicated 4-thread executor pool dispatches version evaluations via `CompletableFuture.allOf()`, bounded to 50 versions per request.
- **H3 -- Stale-while-revalidate (SWR) on FilteredMetadataCache.** Stale bytes returned immediately on TTL expiry; background task re-evaluates. 5-minute SWR grace period.
- **H4 -- L1 cache capacity increased to 50K entries.** Configurable via `PANTERA_COOLDOWN_METADATA_L1_SIZE` env var (default 50,000).
- **H5 -- CooldownCache inflight-map memory leak fixed.** Guaranteed removal on success, error, and cancellation via `whenComplete`. 30-second `orTimeout` zombie safety net.

### Per-Adapter Metadata Implementations (Phase 3)

Metadata parser, filter, rewriter, and request detector implemented for 7 adapters:
- **Maven:** DOM-parses `maven-metadata.xml`; removes blocked `<version>` nodes; updates `<latest>` + `<lastUpdated>`.
- **npm:** Existing implementation verified against renamed interfaces.
- **PyPI:** HTML simple-index parser; removes blocked `<a>` tags.
- **Docker:** JSON `tags/list` parser; removes blocked tag strings.
- **Go:** Plain-text version-per-line parser; removes blocked version lines.
- **Composer:** JSON `packages.json` parser; removes blocked version keys.
- **Gradle:** Reuses Maven components (same metadata format).

235+ adapter unit tests across parser, filter, rewriter, detector, and response-factory test classes.

### Per-Adapter 403 Response Factories (Phase 4)

`CooldownResponseFactory` interface with per-adapter implementations producing format-appropriate 403 responses:
- Maven/Go: `text/plain` with human-readable message + `Retry-After` header
- npm/Docker/Composer: JSON error envelopes matching each ecosystem's error spec
- PyPI: `text/plain`
- `CooldownResponseRegistry` for type-based lookup at runtime; Gradle aliased to Maven.

### Admin/Invalidation Hardening (Phase 5)

- Unblock flow hardened: DB write completes before cache invalidation; `FilteredMetadataCache` L1+L2 and `CooldownCache` L1+L2 both invalidated; all invalidation futures complete synchronously before the 200 response.
- `clearAll()` on policy change flushes all cached filtered metadata.
- Micrometer counters: `pantera.cooldown.admin{action=unblock|reblock|policy_change}`.

### Adapter Bundle Registration + Wiring (Phase 6)

- `CooldownAdapterBundle<T>` record bundles parser/filter/rewriter/detector/responseFactory per adapter.
- `CooldownAdapterRegistry` singleton populated at startup; queried on every proxy request.
- All 7 adapters registered with aliases (Gradle -> Maven).

### Integration + Chaos Tests (Phase 7)

- `MetadataFilterServiceIntegrationTest`: end-to-end with Go adapter format; verifies filtered output, cache hit, SWR behaviour, invalidation.
- `CooldownAdapterRegistryTest`: bundle registration, alias lookup, null rejection, overwrite, clear.
- `CooldownConcurrentFilterStampedeTest` (`@Tag("Chaos")`): 100 concurrent requests for same uncached metadata; parser runs at most 5 times (stampede dedup); all callers get consistent filtered bytes.

## Under the hood

This release lands the foundation for the remaining WIs in the v2.2 target-architecture plan:

- **WI-01's `Fault` taxonomy and `FaultTranslator` are ready for WI-04** (the `GroupResolver` rewrite) to consume. WI-04 is what turns these types from vocabulary into behaviour — every slice returns `Result<Response>`, and the `FaultTranslator` becomes the single site where "what HTTP status" is decided. The worked-examples table in target-architecture §2 is already test-locked via `FaultAllProxiesFailedPassThroughTest` so WI-04 cannot regress the status-policy contract.
- **WI-05's `SingleFlight` is now the only coalescer in the codebase.** WI-post-05 finished the migration by retiring `RequestDeduplicator` / `DedupStrategy` / `RequestDeduplicatorTest.java` and promoting `FetchSignal` to a top-level enum.
- **WI-07's `ProxyCacheWriter` is wired across maven / pypi / go / composer.** Only the npm adapter retains a `TODO(WI-post-07)` marker; its wiring is blocked on RxJava2 retirement (WI-08).
- **`RequestContext` / `Deadline` / `ContextualExecutor` are ready for WI-04 / WI-06 / WI-08.** The 13-field envelope is final; WI-04's `GroupResolver` will thread it through the five-path decision tree, and WI-08's RxJava retirement will let the npm adapter finally delete its `MdcPropagation.withMdc*` call-sites.
- **`StructuredLogger` 5-tier + `LevelPolicy` + `AuditAction` are ready for WI-04 / WI-06 / WI-09.** Every new log emission should prefer the tier-specific builder; `EcsLogger` direct call-sites are acceptable only on the `.exceptionally(...)` error path until the dual-emission removal follow-up lands.

See `docs/analysis/v2.2-next-session.md` for the explicit agent-executable task list for each remaining WI, with file paths, test requirements, DoD commands, and dep-graph ordering.

## Testing

| module          | tests | failures | errors | skipped |
|-----------------|-------|----------|--------|---------|
| pantera-core    |   891 |        0 |      0 |       7 |
| pantera-main    |   929 |        0 |      0 |       4 |
| npm-adapter     |   191 |        0 |      0 |       0 |
| hexpm-adapter   |    19 |        0 |      0 |       0 |
| maven-adapter   |    56 |        0 |      0 |       3 |
| rpm-adapter     |   252 |        0 |      0 |       1 |
| composer-files  |    27 |        0 |      0 |       0 |
| goproxy         |    86 |        0 |      0 |       1 |
| nuget-adapter   |   126 |        0 |      0 |       0 |
| pypi-adapter    |   334 |        0 |      0 |       0 |
| helm-adapter    |    77 |        0 |      0 |       0 |
| docker-adapter  |   444 |        0 |      0 |       1 |
| **TOTAL**       | **3 432** | **0** | **0** | **17** |

Commands used for acceptance (each returns `BUILD SUCCESS`):

```
mvn -T8 install -DskipTests
mvn -pl pantera-core test
mvn -pl pantera-main test -DfailIfNoTests=false
mvn -T4 -pl npm-adapter,maven-adapter,pypi-adapter,go-adapter,composer-adapter,docker-adapter,helm-adapter,rpm-adapter,hexpm-adapter,nuget-adapter test -DfailIfNoTests=false
```

Acceptance greps (each returns the expected count):

```
# WI-post-05
rg 'RequestDeduplicator|class DedupStrategy|RequestDeduplicator\.FetchSignal' --glob '*.java' | rg -v test | wc -l    # 0
rg 'new FetchSignal|FetchSignal\.(SUCCESS|NOT_FOUND|ERROR)' --glob '*.java' | rg -v test | wc -l                       # 11

# WI-post-07
rg 'TODO\(WI-post-07\)' --glob '*.java' | wc -l                                                                        # 1 (npm-adapter)
rg 'new ProxyCacheWriter' --glob '*.java' | rg -v test | wc -l                                                         # 4 (maven/pypi/go/composer)

# WI-02
ls pantera-core/src/main/java/com/auto1/pantera/http/context/                                                          # ContextualExecutor.java Deadline.java RequestContext.java
wc -l pantera-core/src/main/java/com/auto1/pantera/http/context/RequestContext.java                                    # 340

# WI-03
rg 'StructuredLogger\.access\(\)' --glob '*.java' | wc -l                                                              # 14
rg 'enum AuditAction' --glob '*.java' | wc -l                                                                          # 1
rg 'new EcsLogEvent\(\)' pantera-core/src/main/java/com/auto1/pantera/http/slice/EcsLoggingSlice.java | wc -l          # 1 (exception path only)

# Commit-message hygiene
git log c71fbbfe..HEAD --format='%B' | git interpret-trailers --only-trailers | grep -ic 'co-authored-by'             # 0
```
