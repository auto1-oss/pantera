# Changelog — v2.2.0

Target-architecture alignment release. Ships the first four work items of the v2.2 plan (`docs/analysis/v2.2-target-architecture.md` §12): WI-00 (queue/log hotfix), WI-01 (Fault + Result sum types), WI-05 (SingleFlight coalescer), and WI-07 (ProxyCacheWriter + Maven checksum integrity). WI-02, WI-03, WI-04, WI-06, WI-06b, WI-08, WI-09, WI-10 are deferred to the follow-on v2.2.x trains — see `docs/analysis/v2.2-next-session.md` for the exact task list.

## Highlights

- **Maven `ChecksumFailureException` storms stopped at the source.** The `oss-parent-58.pom.sha1` class of cache-drift bug (primary bytes and the sidecar they're verified against diverging across stale-while-revalidate refetches) can no longer produce a committed cache entry. The new `ProxyCacheWriter` is a single write path that fetches primary + every sidecar in one coupled batch, recomputes all four digests (MD5, SHA-1, SHA-256, SHA-512) over the streamed primary bytes, and rejects the whole write if any sidecar disagrees. A companion `scripts/pantera-cache-integrity-audit.sh` heals pre-existing drift with `--dry-run` / `--fix`.
- **The v2.1.3 503 burst and 2.4M/12h WARN flood are closed.** Every bounded-queue write on a request-serving path migrated from `queue.add()` (throws on overflow) to `queue.offer()` (returns false, increments `pantera.events.queue.dropped` counter). Access-log level policy redowngraded 404/401/403 from WARN to INFO — the three status codes driving ~95% of the pre-cutover WARN noise per the forensic analysis. The production regression of `IllegalStateException("Queue full")` escaping into 503 cascades is now architecturally impossible in the migrated sites.
- **Three hand-rolled request coalescers collapsed into one.** The `inFlightFanouts` (GroupSlice), `inFlightMetadataFetches` (MavenGroupSlice), and `RequestDeduplicator` (CachedNpmProxySlice) implementations were each independently solving the same problem with slightly different race guards. `SingleFlight<K,V>` is the one utility for the whole codebase, Caffeine-backed, with stack-flat follower completion (the v2.1.3 `StackOverflowError` at ~400 concurrent followers cannot recur), explicit zombie eviction via `CompletableFuture.orTimeout`, and per-caller cancellation isolation.
- **Fault taxonomy and Result sum types introduced as vocabulary, no behaviour change yet.** `pantera-core/http/fault/` now contains a sealed `Fault` hierarchy, a `Result<T>` with `map`/`flatMap`, a `FaultClassifier` for `Throwable → Fault` fallback, and a `FaultTranslator` that is the single decision point for "what HTTP status does this fault produce". No existing slice has been rewired yet — WI-04 does that. This release establishes the types + the 40-test contract, so every later WI can land without retyping the worked-examples table.
- **Architectural-preparation scope only for adapters other than Maven.** Composer, Go, PyPI and npm cached-proxy slices carry `TODO(WI-post-07)` markers pointing at the future `ProxyCacheWriter` wiring. The Maven adapter is wired end-to-end; the others keep their pre-v2.2.0 behaviour verbatim this release.

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
- **`pantera-core/http/context/RequestContext` (scaffold).** Minimal record with `traceId`, `httpRequestId`, `repoName`, `urlOriginal`. Explicitly marked scaffold in its Javadoc — WI-02 will expand to the full ECS-native field set per target-architecture §3.3 (transactionId, spanId, userName, clientIp, userAgent, artifact, deadline, etc.). The class name and package are fixed so WI-02 can add fields without breaking imports.

## Changed

- **Coalescer fields in GroupSlice / MavenGroupSlice / CachedNpmProxySlice are now `SingleFlight` instances.** Field names `inFlightFanouts` / `inFlightMetadataFetches` / `deduplicator` retained for minimal diff; only the type changed. Call-site semantics preserved: the leader/follower flag pattern (`isLeader[]` array captured inside the loader bifunction) is the same; followers still re-enter their respective fanout / metadata-fetch / origin-response paths once the shared gate resolves.
- **Idle-connection events logged at DEBUG, not ERROR** (`JettyClientSlice`). See Fixed.
- **Bounded-queue enqueue semantics: `offer()`, not `add()`.** Every request-serving path that writes to a `LinkedBlockingQueue<ProxyArtifactEvent>` / `LinkedBlockingQueue<ArtifactEvent>` now uses `offer()` and routes overflow through `EventsQueueMetrics.recordDropped(repoName)`. Sites unbounded by design (ConcurrentLinkedDeque used for append-only drains) keep `add()` with an explicit `// ok: unbounded ConcurrentLinkedDeque` comment so the intent is auditable.
- **Access-log level policy for 404/401/403** downgraded to INFO. See Fixed.
- **Maven-adapter cached-proxy slice.** On primary-artifact cache miss (`.pom`, `.jar`, `.war`, `.aar`, `.ear`, `.zip`, `.module`) the request is routed through `ProxyCacheWriter.writeWithSidecars(...)` instead of the legacy split primary/sidecar writes. Cache-hits, maven-metadata.xml flow (stale-while-revalidate via `MetadataCache`), and non-primary sidecar paths are unchanged. Integrity failure returns 503 to the client with `X-Pantera-Fault: upstream-integrity` rather than committing the bad pair.

## Deprecated

Nothing removed in this release; the following are on the v2.3.0 removal path. A future work item (tracked as **WI-post-05** in `docs/analysis/v2.2-next-session.md`) carries out the deletions.

- **`pantera-core/http/cache/RequestDeduplicator`** — last in-tree caller is `BaseCachedProxySlice`; once it migrates to `SingleFlight`, `RequestDeduplicator.java` + `RequestDeduplicatorTest.java` + `DedupStrategy` enum are deleted and `FetchSignal` is promoted to a top-level `pantera-core/http/cache/FetchSignal.java` (currently a nested enum inside `RequestDeduplicator`).
- **The `TODO(WI-post-07)` markers in `composer-adapter`, `go-adapter`, `pypi-adapter`, `npm-adapter`'s cached-proxy slices** — followup work item wires them through `ProxyCacheWriter` so they receive the same integrity guarantee Maven now has.

## Security / compliance

None. No CVE fixes, no permissions model changes, no credential-handling changes, no PII-scope changes. Integrity verification on proxy caches (WI-07) is a correctness hardening, not a security fix — the trust boundary (upstream declares a digest, we verify it) has not moved.

## Migration notes

**No operator action required.** All changes are drop-in for v2.1.3 deployments:

- The `queue.add → queue.offer` migration is internal; no YAML change, no CLI flag, no API change. Overflow events were previously stack-trace flooding; they now increment a counter and WARN once per drop.
- The access-log level policy change is internal to `EcsLogEvent`; operators who filtered dashboards on `log.level: WARN AND http.response.status_code: 404` will see those panels empty after cutover. That is the intended outcome — noise elimination — not a regression. Kibana panels that need 404 volume should switch to `log.level: INFO AND http.response.status_code: 404` (or simply filter by status code).
- The `ProxyCacheWriter` path in maven-adapter activates only when a file-backed `Storage` is present; deployments that inject a lambda-`Cache` in tests keep the pre-v2.2.0 code path verbatim.
- The `SingleFlight` coalescers use their own dedicated Caffeine `AsyncCache` instances with a 5-minute in-flight TTL and 10K max keys; heap growth is bounded and does not require tuning.
- `scripts/pantera-cache-integrity-audit.sh` is additive — a zero-impact no-op unless explicitly invoked. Running with `--dry-run` against a production cache is safe.

**Version-string reminder.** The root `pom.xml` still reports `2.1.3` on this branch. Operators building from source should bump to `2.2.0` (or tag `v2.2.0` at release time) before producing an RC image; the Maven reactor output reads `pantera-main-2.1.3.jar` today.

## Under the hood

This release lands the foundation for the remaining seven WIs in the v2.2 target-architecture plan:

- **WI-01's `Fault` taxonomy and `FaultTranslator` are ready for WI-04** (the `GroupResolver` rewrite) to consume. WI-04 is what turns these types from vocabulary into behaviour — every slice returns `Result<Response>`, and the `FaultTranslator` becomes the single site where "what HTTP status" is decided. The worked-examples table in target-architecture §2 is already test-locked via `FaultAllProxiesFailedPassThroughTest` so WI-04 cannot regress the status-policy contract.
- **WI-05's `SingleFlight` is ready for WI-post-05** to migrate `BaseCachedProxySlice` from `RequestDeduplicator` and retire the three-file legacy coalescer. That migration is ~20 LoC and mechanically identical to the `CachedNpmProxySlice` change in this release.
- **WI-07's `ProxyCacheWriter` is ready for WI-post-07** to wire npm / pypi / go / docker / composer cached-proxy slices. Each adapter inherits the same primary+sidecar integrity guarantee with a thin `fetchPrimary` / `fetchSidecar` pair per adapter.
- **`RequestContext` as a named-but-not-yet-full scaffold is ready for WI-02** to expand (transactionId, spanId, userName, clientIp, userAgent, artifact ref, deadline, url.path). No import changes required at expansion time.
- **The `EcsLogEvent` level-policy matrix is ready for WI-03** (five-tier StructuredLogger) to consume as the default policy for Tier-1 access logs; WI-03 replaces the call sites, not the policy.

See `docs/analysis/v2.2-next-session.md` for the explicit agent-executable task list for each remaining WI, with file paths, test requirements, DoD commands, and dep-graph ordering.

## Testing

| module          | tests | failures | errors | skipped |
|-----------------|-------|----------|--------|---------|
| pantera-core    |   820 |        0 |      0 |       7 |
| npm-adapter     |   191 |        0 |      0 |       0 |
| maven-adapter   |    86 |        0 |      0 |       1 |
| pantera-main    |   929 |        0 |      0 |       4 |
| pypi-adapter    |   252 |        0 |      0 |       1 |
| go-adapter      |    19 |        0 |      0 |       0 |
| docker-adapter  |   444 |        0 |      0 |       1 |
| helm-adapter    |   124 |        0 |      0 |       0 |
| rpm-adapter     |    77 |        0 |      0 |       0 |
| hexpm-adapter   |    54 |        0 |      0 |       3 |
| nuget-adapter   |   334 |        0 |      0 |       0 |
| composer-files  |    25 |        0 |      0 |       0 |

Commands used for acceptance (each returns `BUILD SUCCESS`):

```
mvn -T8 install -DskipTests
mvn -T8 -pl pantera-core test
mvn -T8 -pl npm-adapter test
mvn -T8 -pl maven-adapter test
mvn -T8 -pl pantera-main -am test
mvn -T8 -pl pypi-adapter,go-adapter,docker-adapter,helm-adapter,rpm-adapter,hexpm-adapter,nuget-adapter,composer-adapter test
```

Acceptance greps (each returns 0 matches):

```
rg 'queue\.add\(' --glob '*.java' | rg -v test | rg -v '// ok:'
rg 'inFlightFanouts|inFlightMetadataFetches' --glob '*.java' | rg -v test | rg -v '// deprecated' | rg -v 'SingleFlight'
rg 'Co-Authored-By' .git
```
