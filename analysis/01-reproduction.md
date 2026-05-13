# Phase 1 — Reproduction and instrumentation

Branch: `opus47-rootcause-20260513`. Date: 2026-05-13.

## Live reproduction (not performed in this environment)

This analysis environment does not have docker-compose, the Pantera test
stack, or external network access to `repo1.maven.org` configured. **I did
not re-run the user's reproduction command from this environment.** The
operating principles allow this: "If you cannot reproduce Problem 2 locally,
say so plainly and continue with code-level analysis. Do not fabricate
numbers."

What I do have:

- Code at the current `2.2.0` HEAD (`2a21f982c`, 2026-05-13).
- A 10-iteration cold-bench artifact in `performance/results/cold-bench-10x.md`
  captured at HEAD `1f3675d95` on 2026-05-05.
- The full `CHANGELOG.md` describing the per-phase perf history and explicit
  RCA-7 / Track 5 notes from the team that confirms the diagnosis on their side.

## Existing benchmark evidence (recorded by the team)

**`performance/results/cold-bench-10x.md`** (2026-05-05, HEAD `1f3675d95` —
this is the state at which speculative npm prefetch was active and the npm
per-upstream cap was already at 32):

```
Command: mvn dependency:resolve -Dartifact=org.codehaus.mojo:sonar-maven-plugin:4.0.0.4121
Through: maven_group -> remotes -> maven_proxy -> Maven Central
Per-run cold reset: TRUNCATE artifacts, clear m2 + maven_proxy/groovy fs, restart pantera

p50:   13.34s
mean:  13.47s
stdev: 0.62s
p95:   14.23s
max:   14.23s
```

**`CHANGELOG.md` line 4-7** (current 2.2.0 HEAD, 2026-05-13):

> Cold maven_group resolve: 38 s → target +2 s overhead vs direct
> Maven Central — five RCAs fixed in one pass. Measured before this
> pass: `mvn dependency:resolve -Dartifact=…:sonar-maven-plugin:4.0.0.4121 -U`
> in **9.58 s direct** vs **37.57–39.07 s through `maven_group`** (clean
> ~/.m2 + cold maven_proxy disk + cold groovy disk + purged
> `artifacts` DB). The +28 s gap reproduces on every run.

So between 2026-05-05 (13 s) and 2026-05-13 (38 s), the cold-cache cost
**regressed by 25 s** through Pantera. The team's RCA-7 entry pins the
proximate cause: Maven Central started rate-limiting the workstation IP
during testing (`curl repo1.maven.org/…` returned 429). The CHANGELOG's
attempted fix was reverted as too invasive ("the load-bearing assumption
that 'any 4xx from member A means try member B' is wired into the whole
maven-group chain"); the issue is left open and deferred.

The user's reported "40 s – 1 m 9 s" reproduction sits comfortably in the
range the team itself measured after the throttling started.

**Reproduction conclusion:** the slow case is real and reproducible *when
Maven Central is throttling our IP*. The faster ~13 s case is what we get
when our outbound rate is below the per-IP limit. The user's two reported
problems are not independent — they are the same problem (excess upstream
load) measured in two ways (the upstream's rate-limit response, and our
wall-clock to absorb that response).

## Static request-amplification math

For a single cold cache miss of a Maven `.pom` request, count upstream GETs:

| Source | Mechanism | Files | Count |
|---|---|---|---|
| Foreground primary fetch | `CachedProxySlice.fetchPrimaryBody` | maven-adapter `CachedProxySlice.java:754` | 1 (the primary) |
| Foreground sidecar fetch | `CachedProxySlice.fetchSidecar(".sha1")` | maven-adapter `CachedProxySlice.java:692-698` | 1 (the `.sha1`) |
| Cooldown HEAD on cache miss | `RegistryBackedInspector.publishDate(NETWORK_FALLBACK) → MavenHeadSource.fetch` | (cooldown package) | 0 or 1 (one HEAD per `(artifact, version)` first-fetch, per Track 5 Phase 3C CHANGELOG) |
| Speculative prefetch (per cached POM) | `PrefetchDispatcher.onCacheWrite → MavenPomParser.parse → PrefetchCoordinator.submit → UpstreamCaller.get` | `PrefetchDispatcher.java:231`, `PrefetchCoordinator.java:438-501` | **N**, where N = direct compile/runtime deps in the parsed POM |
| Speculative prefetch (per prefetched POM) | same, recursively | same | up to **N²**, **N³**, … bounded by negative cache + in-flight dedup |
| MavenGroupSlice sequential walk | `MavenGroupSlice.tryMembersSequentially` for `maven-metadata.xml` only | `MavenGroupSlice.java:438` | depends on member layout |

For a request set of S unique artifacts where each POM has N≈8 direct deps
(typical for Spring/sonar-style plugin trees), the upper-bound upstream
count for a cold walk is:

```
Σ (1 primary + 1 .sha1 + 1 cooldown-HEAD + N speculative deps)  per artifact
=  3·S + N·S  ≈  (3 + 8)·S = 11·S  upstream GETs
```

Direct mvn does roughly:

```
2·S + ~10% optional sidecars = ~2.2·S  upstream GETs
```

**Static amplification factor: ~5×**. With S=50, that's 550 vs 110.

Empirical correction: deduplication shrinks the actual count because many
deps are shared across the tree (the same `commons-lang3:3.12` ends up as a
dep of multiple parents). After in-flight dedup + negative cache, the
effective amplification observed by Maven Central drops, but it remains
significantly above 1×. The team's own measurements
(CHANGELOG line 30-38 RCA-7) confirm "429 from Maven Central during the
perf session" — which only happens when our outbound rate breaches their
per-IP budget.

## Concurrent-dedup test (static)

Test: 50 concurrent client requests for `foo.pom` (uncached).

`BaseCachedProxySlice.fetchAndCache` (`BaseCachedProxySlice.java:704-756`):

```java
return this.client.response(line, this.upstreamHeaders(headers), Content.EMPTY)  // ① UPSTREAM
    .thenCompose(resp -> {
        ...
        return this.singleFlight.load(key, () -> {                                // ② CACHE WRITE
            return this.cacheResponse(resp, key, owner, store)
                .thenApply(r -> FetchSignal.SUCCESS);
        }).thenCompose(...)
    });
```

**Result by inspection:** 50 upstream GETs (not 1). The `singleFlight.load`
is positioned to coalesce the *cache-write* phase that follows the fetch.
The upstream call is unconditional per caller.

For the Maven primary path (`CachedProxySlice.verifyAndServePrimary` →
`fetchVerifyAndCache`, line 615-743): the same shape — `client.response` is
called per caller; the only coalescer in the chain is `ProxyCacheWriter`'s
internal storage-write atomicity, not the upstream fetch.

For the metadata path (`MavenGroupSlice.mergeMetadata`, line 321-388): a
SingleFlight gate wraps the entire fanout including upstream — concurrent
followers correctly park, retry, hit the cache. This path is correct.
**Only the artifact-fetch path is broken.**

For the GroupResolver proxy-only fanout (`GroupResolver.proxyOnlyFanout`,
line 577-639): a SingleFlight gate wraps the fanout. Correct. But this gate
fires *before* delegating to the member's `CachedProxySlice`, and the
member's slice then re-enters the broken `fetchAndCache`/`fetchVerifyAndCache`.
The group-layer coalescing is therefore degraded by the proxy-layer leak: the
50 concurrent clients collapse to 1 at the group, but that 1 still fires 1
upstream call to the proxy member. So under group, dedup is effectively 1×
(which is what we want). Under direct proxy access (`/maven_proxy/...` URL)
or when a hosted member is the target, dedup is 50× broken.

## Conditional-request check

Search for outbound `If-Modified-Since` / `If-None-Match`:

```
grep -rn 'If-Modified-Since\|If-None-Match' \
    pantera-core/src/main \
    maven-adapter/src/main \
    http-client/src/main
```

**Result:** 0 hits in main code. The proxy slices never set these headers on
upstream fetches. `MetadataCache` uses time-based SWR (soft/hard TTLs) but
does not propagate any cache-validators upstream, so even when our cached
copy is still byte-identical to upstream we re-download.

## Connection reuse check

`JettyClientSlices.create` (line 350-484):

- HTTP/2 + HTTP/1.1 fallback via ALPN.
- `maxConnectionsPerDestination = settings.maxConnectionsPerDestination()`
  (default 64).
- `idleTimeout = settings.idleTimeout()` (default 30 s).
- Shared `ClientConnector` + `ArrayByteBufferPool` between protocols.

`HttpClient` is built once per upstream-config (per repo's `RemoteConfig`,
not per request). Connections are pooled correctly. No fresh-handshake-per-
request smell. **Connection reuse is fine.**

## What this tells us

The throttling is downstream of an outbound request-rate problem. The
mechanisms that multiply our request rate are:

1. Misplaced single-flight on the artifact-fetch path (`fetchAndCache`,
   `fetchVerifyAndCache`).
2. Speculative prefetch firing post-cache-write with per-host concurrency
   16 (Maven) / 32 (npm) and no rate cap.
3. Parallel primary + `.sha1` fetch per cache miss (independent of client
   request pattern).
4. Cooldown HEAD on cache miss via `MavenHeadSource` NETWORK_FALLBACK (one
   per first-fetch — bounded but still on the cold path).
5. No request coalescing for sidecar requests when the client races with
   the foreground primary fetch.

The slowness is downstream of:
- Maven Central rate-limiting us back, with `mvn` then retrying.
- Sequential-only group fanout adding a per-member RTT for misses.
- Cold cache forcing N + 1 sequential dep lookups (foreground walk is
  bounded by mvn's own dep-resolver concurrency, typically 5 workers).

All of these are addressable. Phases 2–5 lay out the specific fixes ranked
by impact.
