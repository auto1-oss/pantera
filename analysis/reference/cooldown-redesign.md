# Cooldown feature — preservation analysis

Written 2026-05-14 after reading the cooldown API, the inspector, and the maven adapter's cooldown integration. The prompt frames this as a redesign — the conclusion below is that cooldown does **not** need a structural redesign post-M5/Track 5. What it needs is two small refinements. The user's "preserve, don't kill" framing is the right one.

## 1. User-visible contract

Cooldown is an admin-configured policy that delays the availability of freshly-published upstream artifacts for some window (typically 1–7 days). When a Maven, npm, PyPI, Composer, Docker, or Go client requests an artifact that has been published "too recently":

- The proxy returns **HTTP 403 Forbidden** with a per-format body explaining the block (per-adapter `CooldownResponseFactory`).
- The `maven-metadata.xml` response for the package has the blocked versions **stripped from the `<versions>` list and `<latest>` / `<release>` rewritten downward** so client tools (mvn, gradle, npm, pip) don't pick a blocked version as a resolution candidate in the first place.
- Admins can:
  - List currently-active blocks (`activeBlocks(repoType, repoName)`).
  - Manually unblock a specific version (`unblock(repoType, repoName, artifact, version, actor)`).
  - Manually unblock all (`unblockAll`).
- A separate "all versions blocked" state is persisted for packages whose every version is currently within the cooldown window (used to surface a meaningful response when no non-blocked alternative exists).

The intent is to give Auto1's security/compliance posture time to react to bad releases (malware, accidental publishes, breaking changes) before they enter internal builds. The feature is a soft gate, not a hard ban: once the cooldown window elapses, the artifact serves normally.

## 2. Current implementation

The components, in dependency order:

- **`CooldownService`** (`pantera-core/.../cooldown/api/CooldownService.java`) — interface with `evaluate`, `unblock`, `unblockAll`, `activeBlocks`, `markAllBlocked`. The production implementation is `JdbcCooldownService` (`pantera-main/.../cooldown/JdbcCooldownService.java`); `NoopCooldownService` is the off-state default. `evaluate(request, inspector)` returns `CooldownResult` which carries `blocked()` and `Optional<CooldownBlock>`.
- **`CooldownInspector`** (`pantera-core/.../cooldown/api/CooldownInspector.java`) — small adapter interface: `releaseDate(artifact, version) → Optional<Instant>`, `dependencies(...)` (returns empty in the unified implementation), `releaseDatesBatch(...)` (default parallelises single-item lookups).
- **`RegistryBackedInspector`** (`pantera-core/.../publishdate/RegistryBackedInspector.java`) — the unified inspector. Delegates to a `PublishDateRegistry` with a configurable `Mode` (`CACHE_ONLY` or `NETWORK_FALLBACK`). Cache-miss call sites pass `NETWORK_FALLBACK` (so a brand-new artifact's publish date is fetched from upstream); cache-hit call sites pass `CACHE_ONLY`.
- **`PublishDateRegistry`** — L1 Caffeine + L2 Valkey publish-date cache keyed by `(repoType, artifact, version)`. On miss with `NETWORK_FALLBACK`, falls through to a per-format `PublishDateSource` (e.g., `MavenHeadSource`, which historically did a HEAD against upstream — eliminated in M5 W5b).
- **`CooldownCache`** — L1 in-memory results cache for `evaluate` decisions (key: `(repoType, repoName, artifact, version)`, value: blocked-or-allowed). Short TTL.
- **`CooldownCircuitBreaker`** (`pantera-core/.../cooldown/config/CooldownCircuitBreaker.java`) — DB-level circuit breaker: when the cooldown DB is throwing, evaluate calls fast-fail and the proxy proceeds without cooldown (fail-open).
- **`CooldownAdapterRegistry`** + **`CooldownResponseRegistry`** (`pantera-core/.../cooldown/config/`) — per-format wiring (response factory + inspector pair). Adapters register on boot.
- **`CooldownMetadataService`** (`pantera-core/.../cooldown/metadata/CooldownMetadataService.java`) — the metadata-filter side: given upstream `maven-metadata.xml` bytes, parses, filters out blocked versions, rewrites the index. Always re-evaluates per request (no caching of filtered output — see comment at `CachedProxySlice.java:404-408`).
- **Per-adapter response factories** — `MavenCooldownResponseFactory`, `NpmCooldownResponseFactory`, `PypiCooldownResponseFactory`, `ComposerCooldownResponseFactory`, `DockerCooldownResponseFactory`, `GoCooldownResponseFactory`.

The hot path on a Maven cache miss is:

```
preProcess → isPrimaryArtifact(path) → verifyAndServePrimary
  → storage.exists(key) → false
  → evaluateCooldownOrProceed(headers, path, onAllow)
    → buildCooldownRequest(path, headers) → Optional<CooldownRequest>
    → cooldownService.evaluate(request, inspector)
      → CooldownCache.get((repoType, repoName, artifact, version)) → miss
      → CooldownCircuitBreaker.tripped? → false
      → DB lookup for explicit block
      → inspector.releaseDate(artifact, version)
        → PublishDateRegistry.publishDate(repoType, artifact, version, NETWORK_FALLBACK)
          → L1 Caffeine hit → return
          → L1 miss → L2 Valkey → return on hit
          → L2 miss → MavenHeadSource ... (eliminated in M5 — now returns empty, the cooldown decision proceeds without a publish date)
      → compute decision (publish date ± cooldown window)
      → cache decision in CooldownCache
    → CooldownResult
    → if blocked: return 403 via CooldownAdapterRegistry/CooldownResponseRegistry
    → if allowed: onAllow.get() → coalesceUpstream → fetchVerifyAndCache
```

## 3. Why it was implemented this way

Some reasoning is inferable directly from code comments:

- **Cache-hit short-circuit (Track 5 Phase 1A)**: the comment at `CachedProxySlice.java:600-610` describes why cooldown moved out of the cache-hit branch — pre-Track-5 every cache hit forced a `MavenHeadSource` call, making serve latency dependent on Maven Central reachability and rate-limit budget.
- **Network-fallback inspector mode (Track 5 Phase 2A)**: documented at `RegistryBackedInspector.java:31-36`. Cache-hit sites use `CACHE_ONLY` so the inspector never falls through to the network; cache-miss sites use `NETWORK_FALLBACK` to resolve publish dates for new artifacts.
- **Cooldown HEAD elimination (M5)**: per the commit `07a5ad140` ("feat(proxy): M5 — W6 status fidelity + W5b cooldown HEAD elimination"). The HEAD was a meaningful contributor to outbound amplification once prefetch was removed (M2) and rate-limited (M3).
- **Metadata filter does not cache filtered output**: comment at `CachedProxySlice.java:402-408` — cooldown state changes as versions age out of the window, so caching the filtered XML would produce stale decisions. The unfiltered upstream bytes are cached in `MetadataCache`; the filter runs per request against the latest cooldown state.
- **Fail-open on inspector errors**: comment at `BaseCachedProxySlice.java:672-689` — "Availability > strictness: a broken cooldown evaluator must NOT block legitimate artifact serving." The pattern matches `MetadataFilterService.pass-through-on-error`.
- **No transitive dependency tracking**: comment at `RegistryBackedInspector.java:27-30` — "transitive dependency cooldown propagation is not currently used (see comment in JdbcCooldownService: 'No dependencies tracked anymore')." Earlier versions did dependency-graph propagation; this was deliberately removed.

What is **not documented**:
- The original design doc / RFC for cooldown (if one exists).
- Why the per-format response factory pattern was chosen over a single shared factory with format-specific templates.
- The historical reason for tracking dependencies (and what use case retired it).

## 4. Interaction with canonical architecture

Working through each axiom from `canonical-architecture.md` §0:

1. **Immutable cache (releases never revalidated)** — Cooldown does NOT contradict. Track 5 Phase 1A explicitly preserved this: the cache-hit branch returns without consulting cooldown. The only time cooldown evaluates is on cache miss, which is exactly when we're going upstream anyway.
2. **Origin off the byte path** — Cooldown does NOT touch the byte path. Decision returned before any bytes are fetched.
3. **Single-flight is necessary** — Cooldown's `evaluate` is called once per cache-miss request (not once per concurrent caller, because single-flight collapses callers to one before the evaluate runs — `coalesceUpstream` wraps the entire `evaluateCooldownOrProceed → fetchVerifyAndCache` block in `verifyAndServePrimary`). Wait — re-reading `verifyAndServePrimary`: cooldown evaluation happens *before* `coalesceUpstream`. So N concurrent callers each run their own evaluate. With the `CooldownCache` L1 hit this is cheap (microseconds), but it's not single-flighted. Worth noting.
4. **Circuit breaker** — Cooldown has its own circuit breaker (`CooldownCircuitBreaker`) for DB failures. Aligned in primitive; orthogonal to the upstream-HTTP circuit breaker that the canonical recommends (and Pantera lacks).
5. **Negative cache 404 only** — Cooldown's 403 is correctly NOT written to `NegativeCache`. The 403 is generated by Pantera, not received from upstream; it's not an "artifact absence" signal. Aligned.

The metadata-filter side interacts with two canonical sections:
- §5 (Conditional requests for metadata): the filter caches the unfiltered upstream bytes via `MetadataCache` and re-applies the filter per request. If `MetadataCache` had stale-while-revalidate with conditional GET, the filter cost would still be paid per request but the upstream-fetch cost would amortise. The two are independent improvements.
- §10 (Group resolution): for group repos containing multiple proxy members, each member's `maven-metadata.xml` is fetched + filtered independently. Then the group merges. The filter runs at member level, then merge runs at group level. This is the correct decomposition; the merge does not need to know about cooldown because it operates on already-filtered XML.

**Net assessment**: cooldown fits the canonical architecture cleanly post-Track-5 / M5. There is no structural conflict.

## 5. Refinements (not a redesign)

The two small refinements worth making:

### 5.1 Single-flight the `evaluate` call

Currently each concurrent cache-miss caller runs its own `cooldownService.evaluate(...)` before reaching `coalesceUpstream`. With the `CooldownCache` L1 hit this is cheap, but on a cold L1 (a freshly-bumped repo restart, or a new artifact) N callers do N DB lookups. The fix is to move the cooldown evaluation INSIDE the single-flight gate or to add a `SingleFlight<CooldownKey, CooldownResult>` inside `CooldownService.evaluate`.

**Cost of fix**: small. One additional `SingleFlight` instance scoped per `JdbcCooldownService`. The risk is that an N-second-slow inspector blocks the gate for N seconds, but the inspector circuit breaker already handles that.

**Effort**: S.

### 5.2 Stale-while-revalidate the metadata cache, but not the filter

The current shape — `MetadataCache` holds unfiltered bytes, filter runs per request — is correct for cooldown decisions to stay fresh. The improvement is to add conditional GET (`If-None-Match` + `If-Modified-Since`) when `MetadataCache` refreshes, and to allow `stale-while-revalidate` so the filtered output can be served from a cached unfiltered copy while a background fetch refreshes. The filter still runs per request; the underlying upstream fetch is the part that amortises.

**Cost of fix**: medium. `MetadataCache` needs the ETag/Last-Modified persistence + the SWR scheduling. The filter doesn't change.

**Effort**: M. This is also part of the G5/G8 gap closure; combined with the broader metadata-cache refactor it's a single piece of work.

### 5.3 Per-request cost reduction is not the dominant lever

Cooldown's per-cache-miss cost (post-M5) is dominated by the L1 Caffeine lookup (~µs) plus the L2 Valkey lookup on L1 miss (~1 ms within DC). The DB lookup for explicit blocks is on a different code path (`activeBlocks` is admin-tooling, not hot-path). Even in the worst case of cold L1 + cold L2 + DB lookup, the cost is ~5–20 ms — small relative to the 80–250 ms per-resource write-then-read tax identified in G7. The cooldown path is not the right thing to optimise first; G1/G7 (universal streaming tee) and G6 (circuit breaker) are the bigger wins.

## 6. Cost comparison: keep vs minor refinements

| Dimension | Current (post-M5) | With §5.1 + §5.2 |
|---|---|---|
| Latency on cache hit | 0 ms (Track 5 Phase 1A) | 0 ms |
| Latency on cache miss (warm caches) | ~1 ms (L1+L2 lookup) | ~1 ms (same; single-flighted at N=1) |
| Latency on cache miss (cold caches) | ~5–20 ms × N concurrent | ~5–20 ms × 1 (single-flighted) |
| `maven-metadata.xml` refresh cost | 60–90 ms (full GET) | 0–90 ms (304 fast path when validators stored) |
| Code change | none | ~200–400 LOC for §5.1 + §5.2 |
| Risk | none (existing behaviour) | low (additive; fail-open path preserved) |

## 7. Alternatives if a "redesign" is mandated

Two alternatives, neither recommended, but documented so the trade-off space is visible:

**Alternative A — move cooldown evaluation into the metadata filter only**. Skip cooldown on artifact-fetch entirely; rely on the metadata filter to remove blocked versions from `maven-metadata.xml`. Clients then never request a blocked version because they don't see it in the index. **Trade-off**: this only works for index-driven resolution (Maven, npm metadata, PyPI simple index). It does not work for direct URL requests (`/maven_proxy/org/foo/bar/1.0/bar-1.0.jar`) which bypass the metadata index. Some clients (CI pipelines, automated fetchers, security scanners) DO directly URL artifacts. Dropping the per-fetch gate would silently allow those clients to bypass cooldown. Not acceptable.

**Alternative B — cooldown as a CDN edge policy**. Run cooldown as a Cloudflare Worker or similar at the edge, before reaching Pantera. **Trade-off**: requires distributing the publish-date and block state to the edge, which means Auth0/JWT/edge-side state. Adds an entire new stack. Not aligned with Pantera's deployment model (mostly self-hosted, sometimes managed). Reject.

The right answer remains: **keep cooldown, apply §5.1 + §5.2 refinements, treat them as part of the broader perf work in Phase D**.

## 8. Summary

Cooldown is a real user-visible product feature with a clean current implementation. Post-Track-5 Phase 1A and post-M5 W5b, the hot-path cost is small (microseconds on warm caches, single-digit milliseconds on cold) and it sits orthogonally to the canonical architecture rather than fighting it. Two small refinements (single-flighting the evaluate call, conditional-refresh of the metadata cache) are worth folding into the broader perf work but are not in themselves required for the 4–5× cold-miss problem.

**Preserve the contract; do not redesign. Fold §5.1 and §5.2 into the candidate plans in `plan/v2/PLAN.md`.**
