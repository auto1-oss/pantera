# Phase 2 — Diff triage: master → HEAD

Branch: `2.2.0`. Diff against `master` (= v2.1.4): 328 commits, 1 125 files,
+87 310 / –15 452 LOC. Hot-path commits triaged below. PMD-cleanup, license-
header, docs, and UI commits omitted: they cannot affect outbound request
amplification or upstream latency.

## Verdict legend

- **AMPLIFIES** — landed code that raises the upstream request count or
  rate.
- **MITIGATES** — landed code that should reduce upstream traffic. Note:
  several "mitigates" commits ship a partial fix that does not close the
  vulnerability they target.
- **NEUTRAL** — landed code that touches the hot path but does not affect
  request count (refactor, observability, error-message change).
- **INTRODUCES BUG** — landed code that broke something measurable.

## Triage table

| Commit | Date | Title | Verdict | Notes |
|---|---|---|---|---|
| `62b1d50fa` | 2026-05-04 | `feat(prefetch): MavenPomParser — direct deps with scope/optional filter` | **AMPLIFIES** | New parser that extracts N direct deps from a cached POM. Each becomes a speculative upstream GET. Recursion bounded only by negative cache + in-flight dedup. |
| `bd1c8200a` | 2026-05-04 | `fix(prefetch): MavenPomParser skips <dependencyManagement>` | NEUTRAL | Bug fix on the new parser. Pre-fix, dependencyManagement deps were also enqueued, multiplying further. |
| `1361d79d4` | 2026-05-04 | `feat(prefetch): NpmPackageParser with metadata-cache lookup` | **AMPLIFIES** | Same shape for npm. Each cached `package.json` produces transitive prefetches via `CachedNpmMetadataLookup`. |
| `b19ad660a` | 2026-05-04 | `feat(prefetch): Coordinate + PrefetchTask records` | NEUTRAL | Plumbing. |
| `63380be76` | 2026-05-04 | `feat(prefetch): PrefetchMetrics — 24h sliding window` | NEUTRAL | Observability for the new subsystem. |
| `f332840c8` | 2026-05-04 | `feat(prefetch): PrefetchCircuitBreaker auto-disable on drop spike` | MITIGATES | Trips when drop rate spikes; auto-re-enables. Threshold is queue-full drops; does NOT trip on upstream 429 / 503. |
| `72db1aafa` | 2026-05-04 | `feat(prefetch): PrefetchCoordinator — semaphores, cooldown, breaker` | **AMPLIFIES** | Globals: 64 concurrent, 16/host. **No requests-per-second cap.** Workers (default 8) drain a 2 048-deep queue. |
| `e48302d44` | 2026-05-04 | `fix(prefetch): per-host semaphores, future timeouts, lastFetchAt outcome alignment` | NEUTRAL | Bug fix on the new subsystem. |
| `8cfb242ff` | 2026-05-04 | `feat(prefetch): PrefetchDispatcher — repo flag + parser registry` | **AMPLIFIES** | Hook fired by `ProxyCacheWriter`/`BaseCachedProxySlice` after every successful primary cache write. **Default-on**. |
| `4f8fab9dc` | 2026-05-04 | `feat(cache): BaseCachedProxySlice onCacheWrite extension point` | NEUTRAL | Plumbing for the hook. |
| `e083b3927` | 2026-05-04 | `feat(prefetch): wire PrefetchDispatcher into BaseCachedProxySlice via boot DI` | **AMPLIFIES** | Enables the subsystem in production. Registers `PrefetchDispatcher::onCacheWrite` as the shared `CacheWriteCallbackRegistry` callback. |
| `7a7e7932e` | 2026-05-04 | `fix(prefetch): re-mint system JWT per call + skip temp-file materialisation when no callback` | NEUTRAL | Bug fix. |
| `a7cfb89ab` | 2026-05-04 | `feat(settings): RepoConfig.prefetchEnabled (default true) replaces *-proxy heuristic` | **AMPLIFIES** | **Default true means every proxy repo prefetches by default.** Pre-fix the heuristic was implicitly limited to `*-proxy` types only; post-fix it is opt-out. |
| `fc3b6d285` | 2026-05-05 | `perf(cache): drop synchronous fsync on regenerable cache writes` | NEUTRAL | Helped local write latency. Does not affect upstream. |
| `973ffcff5` | 2026-05-05 | `perf(cache): drop speculative md5/sha256/sha512 fetches from proxy cold path` | MITIGATES | **Was** 4 sidecars per primary; now only `.sha1` is blocking + MD5 is non-blocking + sha256/sha512 are not fetched eagerly. **Halves** the proxy-cache-write sidecar amplification. |
| `0a14ee1ae` | 2026-05-05 | `perf(cache): parallelise primary + .sha1 fetch on cache miss` | **AMPLIFIES** (mild) | Fires `.sha1` upstream alongside the primary, in parallel. Net upstream count unchanged (the client would have asked for `.sha1` anyway), but the **burst-rate** to the upstream is doubled per primary fetch. Maven Central's per-IP rate-limit fires on burst, not on cumulative. |
| `b31369af5` | 2026-05-05 | `feat(group): sequential-only fanout, remove parallel mode (BREAKING)` | MITIGATES | Member fanout is now serial → only the winning member is hit. Reduces upstream by ~50% in well-organised configs. |
| `73a5ea4df` | 2026-05-05 | `feat(group): maven-metadata.xml fanout becomes sequential-first` | MITIGATES | Same shape for metadata. |
| `17a163e68` | 2026-05-05 | `feat(group): packages.json fanout becomes sequential-first` | MITIGATES | Same shape for composer/php. |
| `e9eb477c7` | 2026-05-05 | `feat(prefetch): wire NpmMetadataLookup and register npm-proxy parser` | **AMPLIFIES** | npm prefetch parser now active end-to-end. |
| `5d72d3ec9` | 2026-05-05 | `fix(prefetch): wire npm CacheWriteEvent so speculative prefetch fires` | **AMPLIFIES** | Final wiring step to actually fire npm prefetches. |
| `621b311e8` | 2026-05-05 | `feat(prefetch): per-ecosystem perUpstream concurrency override` | **AMPLIFIES** (config surface) | Adds per-ecosystem cap override. Default values: maven 16, gradle 16, npm 32 (after `867474393`). |
| `b053f4d4f` | 2026-05-05 | `fix(prefetch): async dispatch off the cache-write response thread` | NEUTRAL | Moved parser invocation off response thread to a dedicated executor. Improves response latency, doesn't change upstream count. |
| `6525ebe43` | 2026-05-05 | `perf(prefetch): storage zero-copy passthrough for npm cache_write hook` | NEUTRAL | Optimisation for the dispatch path. Does not change upstream count. |
| `e23c267e8` | 2026-05-05 | `perf(npm): tee upstream stream to disk + client; parallelise meta + data writes` | MITIGATES | Reduces npm save-then-reload latency. |
| `1f3675d95` | 2026-05-05 | `perf(npm): speculative packument prefetch on the cold-cache critical path` | **AMPLIFIES** | New `NpmPackumentParser` reads packument JSON, fires speculative prefetch for every direct dep's packument. The amplification multiplier for npm. |
| `867474393` | 2026-05-05 | `perf(prefetch): raise npm per-upstream cap default 4 -> 32` | **AMPLIFIES** | **Per-host concurrency for npm jumped 8×.** The CHANGELOG notes 73/108 dispatches were being dropped at cap=4 — at cap=32, those 73 *now hit upstream*. |
| `4032a10c0` | 2026-05-11 | `chore(release): merge master into 2.2.0` | NEUTRAL | Merge. |
| `3c596984a` | 2026-05-12 | `fix(proxy): zero upstream I/O on cache hit (Tracks 1-5 v2.2.0 architecture)` | MITIGATES | The big Track 5 commit. Moved the cooldown gate to the cache-miss branch so cache **HITS** no longer fire `MavenHeadSource` HEAD. Eliminates the dominant per-cache-hit upstream HEAD. |
| `fc1416605` | 2026-05-12 | `fix(proxy): Track 5 follow-ups — extractors for all proxy types, Phase 3C contract pin` | MITIGATES | Per-ecosystem `Last-Modified` extractor registration; pinned the Phase 3C contract (`CACHE_ONLY` mode for cache-hit paths). |
| `a5fc6139d` | 2026-05-12 | `fix(prefetch): MavenPomParser no longer parses cached .jar files` | MITIGATES | Added `appliesTo(".pom")` filter. Pre-fix, every Maven primary write triggered an XML parse attempt + WARN log. 80+ WARNs per cold mvn build. |
| `2a21f982c` | 2026-05-13 | `perf(maven): RCA-3/4/5/6 cold maven_group cleanup pass` | MITIGATES | **Deletes `MavenSiblingPrefetcher`** (a separate, older sibling-fetch background worker that fired post-commit and held an **unbounded LinkedBlockingQueue**). Sweeps stale Vert.x tmp dirs at startup. Logs group fallthrough. |
| `d64e743c1` | (date) | `feat(metrics): pantera_http2_negotiated_total` | NEUTRAL | Observability. |
| `c7ebd287e` | (date) | `feat(http-client): JettyClientSlices accepts HttpTuning for ALPN h2 negotiation` | MITIGATES | HTTP/2 multiplexing reduces TCP handshake overhead. |
| `cbfc05122` | (date) | `fix(group): SEQUENTIAL executeProxyFanout wraps 5xx in AllProxiesFailed` | NEUTRAL | Error mapping. |
| `0e703f84e` / `b8622d091` / `8748e77b0` | (date) | ArtifactIndexCache (Caffeine + negative + coalescing for `locateByName`) | MITIGATES | Reduces DB round-trips on group resolution. Does not affect upstream. |

## Commit-cluster verdict

**The prefetch subsystem (`62b1d50fa` … `e9eb477c7` / `1f3675d95`, May 4–5)
is the principal new outbound-traffic generator.** It landed as a 13-commit
cluster + 3 fixes, was enabled by default on every proxy-type repo
(`a7cfb89ab`), and has no rate cap — only a concurrency cap.

**The Track 5 fix cluster (`3c596984a` / `fc1416605`, May 12) is real
mitigation for the cache-hit case.** Cached `.pom`/`.jar` requests no
longer fire upstream HEADs. This is the single most important regression
the team correctly identified and fixed. The user's "everything is cached,
why are we hitting Maven Central" diagnosis was right and the Phase 1A
inversion of the cooldown gate is the right fix.

**Track 5 does NOT fix the cold-miss / 429 problem.** The cold-miss path
still:

1. Fires per-fetch `.sha1` alongside the primary (`0a14ee1ae`).
2. Fires up to N speculative prefetches per cached POM
   (`8cfb242ff`/`e083b3927`/`a7cfb89ab`).
3. Fires a `MavenHeadSource` HEAD on first-fetch of any new `(artifact,
   version)` pair (Phase 3C: this was deliberately retained — see CHANGELOG
   line 300-318).
4. Concurrent client requests for the same uncached path all fire upstream
   (the misplaced single-flight in `BaseCachedProxySlice.fetchAndCache` —
   present in master too, but its impact is now compounded by the prefetch
   amplification).

The Track 5 commit message frames the diagnosis as a cache-hit problem.
The cold-cache amplification is a separate, larger problem that the team's
own RCA-7 entry (CHANGELOG line 30-58) acknowledges was investigated and
deferred ("the real fix needs separate handling for 'upstream rate-limited
me — back off and retry' vs. 'upstream genuinely doesn't have it'… Defer to
a follow-up").

## Specific concern: `a7cfb89ab` (`RepoConfig.prefetchEnabled = true` default)

```
feat(settings): RepoConfig.prefetchEnabled (default true) replaces *-proxy heuristic
```

By the team's own perf measurements (`performance/results/cold-bench-10x.md`,
2026-05-05) the 10-iter cold benchmark gave p50=13 s **with prefetch
enabled**. The user's later 38 s number is also with prefetch enabled. So
the prefetch system gives no measurable wall-clock win on the team's
chosen benchmark and observably regresses things once Maven Central starts
rate-limiting back.

The npm-specific CHANGELOG entry for Phase 13.5 (line 332-376) admits:
"cap=4 had a bimodal failure mode where one in ten cold installs blew up
to 12.5 s, eliminated at cap=32" — i.e. they raised the cap to mask a
race condition in the speculative prefetch logic rather than fix the race.
Cap=32 means up to 32 concurrent prefetch GETs against `registry.npmjs.org`
on top of foreground traffic, with no per-second cap.

## A note on the RxJava2 retirement (WI-08)

Several commits retire RxJava2 from hot paths in favour of plain
`CompletableFuture` / `Publisher`. This is a legitimate cleanup but it had
a side-effect on the upstream-fetch shape: the old `RequestDeduplicator`
(see master's `BaseCachedProxySlice` at line 482-498) used `deduplicate(key,
() -> cacheResponse(...))` with the same misplacement — outside the upstream
call. The `SingleFlight` migration kept the same misplacement
(`cf7992666`). **This is not new in 2.2.0**, but its impact is now magnified
because the prefetch subsystem multiplies the request count on which the
misplacement operates.
