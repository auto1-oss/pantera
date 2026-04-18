# Design Spec: UI Repo Type Completeness + Cooldown Metadata Filtering

**Date:** 2026-04-17
**Scope:** Two independent items — one UI fix, one deep cooldown enhancement
**Enterprise bar:** 9/10 SOLID score, matching the v2.2.0 group/proxy refactor standard

---

## Item 1: UI Repo Creation — Type Gaps + Group Member UX

### Problem

1. UI offers 27 repo types; backend supports 31. Missing: Go (local), Go (group), Gradle (local/proxy/group). Hex sends `hex` but backend expects `hexpm`.
2. Group member selection is free-text input — no autocomplete, no validation, no discovery of existing repos.

### Fix 1A: Complete the repo type list

Add to `pantera-ui/src/utils/repoTypes.ts` `REPO_TYPE_CREATE_OPTIONS`:

| Label | Value | Category |
|---|---|---|
| Go (Local) | `go` | new |
| Go (Group) | `go-group` | new |
| Gradle (Local) | `gradle` | new |
| Gradle (Proxy) | `gradle-proxy` | new |
| Gradle (Group) | `gradle-group` | new |
| PHP (Group) | `php-group` | new (verify if missing) |

Change existing Hex entry: `value: "hex"` to `value: "hexpm"`, keep label "Hex (Local)".

Verify `RepoConfigForm.vue` computed properties (`isGroup()`, `isProxy()`, `isLocal()`) handle the new types correctly via the `*-group` / `*-proxy` / bare naming convention. Adjust form field visibility per type (group shows member selector, proxy shows upstream URL, local shows storage config).

### Fix 1B: Group member selector — searchable dropdown + inline create

Replace the free-text `InputText` per group member with a **PrimeVue `AutoComplete`** component.

**Behaviour:**

1. **On mount**, fetch compatible repos via `GET /api/v1/repositories` filtered by type. Compatibility rule: strip `-group` from the group type to get the base → compatible types are `{base}` and `{base}-proxy`. Example: `maven-group` → compatible = `maven`, `maven-proxy`.

2. **Dropdown shows** each repo as `name [type-badge]` (e.g. "libs-release-local [maven]", "maven-central [maven-proxy]"). Filters client-side as user types — no extra API call per keystroke.

3. **Duplicate prevention** — members already added are greyed out in the dropdown.

4. **Ordering preserved** — keep Up/Down arrow buttons for resolution-priority reorder.

5. **"Create new member" button** — opens a modal dialog containing a stripped-down `RepoConfigForm` pre-filtered to compatible types only. On successful creation, the new repo is automatically added to the members list and the dropdown refreshes.

### Testing (Item 1)

- Unit: `repoTypes.ts` exports all 31+ types with correct values.
- Component: `RepoConfigForm` renders the AutoComplete for group types, free-text for non-group.
- E2E (manual or Playwright): create a `maven-group` → dropdown shows only `maven` + `maven-proxy` repos → select one → reorder → save.
- E2E: create a `go-group` → "Create new member" → mini-form offers only `go` + `go-proxy` → create → new repo appears in the member list.

---

## Item 2: Cooldown Metadata Filtering — All 7 Adapters

### Architecture: Two-layer enforcement

**Layer 1 — Metadata filtering (soft block):**
Intercept metadata responses from upstream/cache, strip versions still in cooldown. Client never sees blocked versions. If ALL versions blocked → empty metadata → client gets "version not found" naturally.

**Layer 2 — Direct-request 403 (hard block):**
Client bypasses metadata, requests a specific artifact file for a blocked version → 403 Forbidden + `Retry-After` header + adapter-native minimal error body (`error`, `blocked_until`).

Both layers already exist in the codebase (`CooldownMetadataServiceImpl` for Layer 1, `BaseCachedProxySlice.evaluateCooldownAndFetch()` for Layer 2). The work is implementing per-adapter metadata parsers/filters/rewriters, hardening performance, formatting 403s per adapter convention, and auditing the existing admin/invalidation flow.

### Per-adapter metadata filter implementations

Each adapter needs five components registered at startup:

| Component | Interface | Responsibility |
|---|---|---|
| Parser | `MetadataParser<T>` | `byte[] → T` (typed model) |
| Filter | `MetadataFilter<T>` | Remove blocked versions from `T` |
| Rewriter | `MetadataRewriter<T>` | `T → byte[]` |
| Request detector | `MetadataRequestDetector` | Is this path a metadata request? |
| Response factory | `CooldownResponseFactory` | `CooldownBlock → adapter-native 403 Response` |

**Per-adapter specifics:**

| Adapter | Metadata endpoint | Format | Parser type `T` | Notes |
|---|---|---|---|---|
| Maven | `maven-metadata.xml` | XML | DOM Document | Remove `<version>` nodes; update `<latest>`, `<lastUpdated>` |
| npm | `GET /{package}` | JSON | Jackson ObjectNode | Delete keys from `versions` object; update `dist-tags.latest`; update `time` |
| PyPI | `/simple/{package}/` | HTML | List of link records | Remove `<a>` elements for blocked versions |
| Docker | `/v2/{name}/tags/list` | JSON | Jackson ObjectNode | Remove strings from `tags` array |
| Go | `/{module}/@v/list` | Plain text | `List<String>` | Remove lines for blocked versions |
| PHP/Composer | `/packages/{vendor}/{pkg}.json` | JSON | Jackson ObjectNode | Delete version keys from `packages.{name}` map |
| Gradle | Same as Maven | XML | Shares Maven's parser/filter/rewriter | Reuses Maven components |

**Request detector patterns:**

| Adapter | Metadata path | Artifact path |
|---|---|---|
| Maven | Ends with `maven-metadata.xml` | Everything else |
| npm | `/{scopedOrUnscoped}` without `/-/` segment | Contains `/-/` |
| PyPI | Starts with `/simple/` | Everything else |
| Docker | Matches `/v2/{name}/tags/list` | Matches `/v2/{name}/blobs/` or `/v2/{name}/manifests/` |
| Go | Ends with `/@v/list` | Ends with `/@v/{version}.zip` / `.info` / `.mod` |
| PHP | Matches `/packages/` or `/p2/` | Everything else |
| Gradle | Same as Maven | Same as Maven |

### 403 response formatting per adapter

All 403 responses carry: `Retry-After: <seconds>` + `X-Pantera-Cooldown: blocked` headers.

| Adapter | Content-Type | Body format |
|---|---|---|
| Maven | `text/plain` | `Artifact blocked by cooldown policy. Blocked until: <ISO8601>` |
| npm | `application/json` | `{"error": "version in cooldown", "blocked_until": "<ISO8601>"}` |
| PyPI | `text/plain` | `Version blocked by cooldown policy. Blocked until: <ISO8601>` |
| Docker | `application/json` | `{"errors": [{"code": "DENIED", "message": "Tag in cooldown", "detail": {"blocked_until": "..."}}]}` |
| Go | `text/plain` | `version blocked by cooldown policy until <ISO8601>` |
| PHP | `application/json` | `{"error": "version in cooldown", "blocked_until": "<ISO8601>"}` |
| Gradle | Same as Maven | Same as Maven |

### Performance hardening — under 100ms at 1000 req/s

Five changes to the existing `CooldownMetadataServiceImpl` + `FilteredMetadataCache` + `CooldownCache`:

**H1 — Pre-warm version release-date cache on upstream metadata fetch.**
When metadata arrives from upstream (cache miss), bulk-extract release dates from the metadata itself (npm `time` field, PyPI file timestamps, etc.) and eagerly populate `CooldownCache` L1. Subsequent 50-version evaluation hits only L1 (sub-ms total).

**H2 — Parallel version evaluation with bounded concurrency.**
Evaluate the bounded version list (max 50) via `CompletableFuture.allOf()` on a dedicated small executor (4 threads, `ContextualExecutorService`-wrapped). Each evaluation is an L1 hit after H1 pre-warming.

**H3 — Stale-while-revalidate on FilteredMetadataCache.**
When a cached filtered-metadata entry expires, serve stale bytes immediately, trigger background re-evaluation. Client never waits for re-evaluation. Trade-off: one-request staleness window — acceptable.

**H4 — Increase FilteredMetadataCache L1 capacity.**
Default 5K → 50K entries. ~100MB heap at ~2KB average filtered metadata per package. Well within 14GB heap.

**H5 — Fix CooldownCache inflight-map memory leak.**
Add `.whenComplete((v, err) -> inFlight.remove(key))` to cover exceptional completions. Matches the `SingleFlight` pattern from WI-05.

### Package structure — enterprise SOLID layout

```
pantera-core/src/main/java/com/auto1/pantera/cooldown/
├── api/                                    # Public contracts
│   ├── CooldownService.java                # evaluate(request) → CooldownResult
│   ├── CooldownInspector.java              # releaseDate(artifact, version)
│   ├── CooldownRequest.java                # record
│   ├── CooldownResult.java                 # record
│   ├── CooldownBlock.java                  # record
│   └── CooldownReason.java                 # enum
│
├── cache/                                  # 3-tier caching layer
│   ├── CooldownCache.java                  # L1 Caffeine + L2 Valkey + L3 DB
│   ├── CooldownCacheConfig.java            # per-tier config
│   └── CooldownCacheMetrics.java           # counters
│
├── metadata/                               # Metadata filtering engine
│   ├── MetadataFilterService.java          # orchestrator: parse → evaluate → filter → cache
│   ├── FilteredMetadataCache.java          # 2-tier + SWR
│   ├── MetadataParser.java                 # interface<T>
│   ├── MetadataFilter.java                 # interface<T>
│   ├── MetadataRewriter.java               # interface<T>
│   ├── MetadataRequestDetector.java        # interface
│   └── VersionComparators.java             # semver, maven, lexical
│
├── response/                               # 403 formatting
│   ├── CooldownResponseFactory.java        # interface
│   └── CooldownResponseRegistry.java       # repoType → factory
│
├── config/                                 # Settings + wiring
│   ├── CooldownSettings.java              # duration config
│   ├── CooldownCircuitBreaker.java         # transient failure wrapping
│   └── InspectorRegistry.java              # repoType → inspector
│
├── metrics/                                # Observability
│   └── CooldownMetrics.java               # Micrometer
│
└── impl/                                   # Internal implementations
    ├── DefaultCooldownService.java
    ├── CachedCooldownInspector.java
    └── NoopCooldownService.java

each *-adapter/src/main/java/.../cooldown/
├── {Adapter}CooldownInspector.java          # EXISTS for 7 adapters
├── {Adapter}MetadataParser.java             # NEW
├── {Adapter}MetadataFilter.java             # NEW
├── {Adapter}MetadataRewriter.java           # NEW
├── {Adapter}MetadataRequestDetector.java    # NEW
└── {Adapter}CooldownResponseFactory.java    # NEW
```

**SOLID enforcement:**

| Principle | How |
|---|---|
| S — Single Responsibility | Each class has ONE job. Parser parses. Filter filters. Rewriter serializes. |
| O — Open/Closed | New adapter = implement 5 interfaces. Core engine unchanged. |
| L — Liskov Substitution | Every `MetadataParser<T>` substitutable — engine calls `parser.parse(bytes)` regardless of T. |
| I — Interface Segregation | Inspector separate from Parser — adapter can support hard-block without soft-block. |
| D — Dependency Inversion | Core depends on interfaces. Adapters implement them. Registration at startup. |

### Audit + harden existing admin + invalidation (review, not rebuild)

The manual-unblock UI, allowlist DB, override API, and cache invalidation chain already exist. This work **reviews and hardens** them to the v2.2.0 enterprise bar.

**7A — Review the manual unblock flow end-to-end:**
- Trace: admin clicks "unblock" → API handler → DB write → cache invalidation chain. Verify the unblocked version becomes servable on the very next request.
- Verify: invalidation reaches ALL four caches (CooldownCache L1+L2, FilteredMetadataCache L1+L2, NegativeCache if applicable) synchronously before the 200 returns.
- Verify: multi-instance propagation — unblock reaches other JVMs within one L1 TTL window.

**7B — Review event-driven invalidation for completeness:**
- Policy duration change (30d → 7d): does it invalidate cached filtered metadata, or do clients see stale results until TTL?
- New upstream version publish: does it trigger metadata re-evaluation, or does old cached version list persist?
- Race conditions: concurrent request between DB write and cache invalidation re-populating cache with stale blocked state?

**7C — Harden for scale:**
- Unblock action completes under 100ms including all 4 cache invalidations.
- No scattered static helpers — proper `StructuredLogger` tier coverage (Tier-4 WARN on manual override with trace.id).
- Micrometer counters: `pantera.cooldown.admin{action=unblock|reblock|policy_change}`.

**7D — Align with v2.2.0 primitives:**
- `ContextualExecutorService` for async invalidation work (not raw `ForkJoinPool.commonPool()`).
- `StructuredLogger.local()` for admin-action logging (not raw `EcsLogger`).
- `Result<T>` / `Fault` types for admin API failures (DB down → typed fault, not swallowed exception).
- Admin handler uses `HandlerExecutor.get()` pattern from WI-post-03d.
- `FilteredMetadataCache` refresh counter: `pantera.cooldown.metadata.refresh{reason=ttl_expiry|manual_unblock|policy_change}`.

### Testing strategy

**Tier 1 — Unit tests (per adapter, per component): ~35 test classes**

For each of 7 adapters, 5 test classes:
- `{Adapter}MetadataParserTest` — real-world metadata fixtures; edge cases: empty, malformed, 1000+ versions.
- `{Adapter}MetadataFilterTest` — 3 blocked of 10 → assert 3 removed. ALL blocked → empty. NONE blocked → unchanged.
- `{Adapter}MetadataRewriterTest` — round-trip: parse → filter → rewrite → parse. Assert valid output format.
- `{Adapter}CooldownResponseFactoryTest` — 403 body matches native format; headers present.
- `{Adapter}MetadataRequestDetectorTest` — metadata paths detected; artifact paths not.

**Tier 2 — Integration tests (engine level): ~4 test classes**

- `MetadataFilterServiceTest` — end-to-end with fake parser/filter/rewriter. Test SWR.
- `FilteredMetadataCacheSWRTest` — expire → stale served → background refresh → fresh on next.
- `CooldownCacheInflightLeakTest` — failing evaluations clean up inflight map (H5).
- `VersionEvaluationParallelTest` — 50 versions in parallel, all complete under 50ms after H1 pre-warm.

**Tier 3 — Chaos tests (@Tag("Chaos")): ~3 test classes**

- `CooldownValkeyStalenessTest` — disable Valkey mid-test; L1 serves without interruption.
- `CooldownHighCardinalityTest` — 10K packages; L1 eviction is LRU, no OOM.
- `CooldownConcurrentFilterStampedeTest` — 100 concurrent requests for same uncached package; parser runs exactly once.

---

## Implementation order

| Phase | Scope | Depends on |
|---|---|---|
| **Phase 1** | Item 1: UI repo-type fix + group member dropdown + inline create | Independent — can ship immediately |
| **Phase 2** | Cooldown package restructure to SOLID layout (move existing classes, no behaviour change) | Independent |
| **Phase 3** | Performance hardening H1-H5 (pre-warm, parallel eval, SWR, L1 capacity, inflight fix) | Phase 2 |
| **Phase 4** | Per-adapter metadata parser/filter/rewriter for all 7 adapters | Phase 2 |
| **Phase 5** | Per-adapter 403 response factories | Phase 4 |
| **Phase 6** | Admin/invalidation audit + hardening (7A-7D) | Phase 3 |
| **Phase 7** | Testing (Tier 1 + 2 + 3) | Phase 4 + 5 + 6 |

Phase 2-4 can partially overlap (restructure doesn't block adapter implementations once interfaces are defined).
