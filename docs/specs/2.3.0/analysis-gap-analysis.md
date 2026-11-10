but but you # Pantera 2.3.0 — Honest Gap Analysis & Release Plan

*Prepared from six parallel code audits (npm, Maven/Gradle, PyPI, Composer, Go, HA clustering, S3+disk-cache) read against the current `master` tree. Every claim below traces to file:line in the source; this is the compressed decision-grade summary.*

---

## TL;DR — the honest verdict

**What Pantera is today:** a genuinely capable **caching proxy in front of public registries, single-node, at moderate load.** The everyday `npm ci` / `mvn`/`pip install` / `go get` proxy paths work, stream tarballs, dedup concurrent fetches, and apply cooldown filtering. For that shape it's solid.

**What it is *not* yet — and what "bulletproof 2.3.0" has to close:**

1. **Multi-node HA is unsafe today.** With the intended topology (Valkey on) you get **both data loss and security holes**: revoked tokens are re-honored after a node restart, role/permission and breaker-threshold changes apply only on the node that served the admin request, and the async artifact-event pipeline *self-destructs* under normal clustered-Quartz load, silently losing `artifact_publish` audit records and search-index rows. **You cannot run N nodes correctly right now.**
2. **S3-as-backend cannot do 1000 req/s.** The 2.2.0 disk cache is a *body-only* cache bolted onto an S3-authoritative hot path — it still pays **1–2 synchronous S3 HEADs on every cache hit**, hosted S3 reads have no cache at all, and writes are synchronous S3-PUT-through with full-artifact heap buffering. This matches exactly what you saw in 2.0.0.
3. **Hosted (local) mode is the weak leg in every format** — the proxy paths are far more mature than the "Pantera is authoritative" paths.
4. **Several advertised integrity guarantees are inert**, and **whole-artifact memory buffering on the hot path** is a recurring OOM risk at 1000 req/s.

**Readiness scorecard** (🟢 solid · 🟡 works-but-gaps · 🔴 not ready):

| Area | Proxy | Hosted/local | Group | Scale @1000 rps | Multi-node safe |
|---|---|---|---|---|---|
| **npm** | 🟡 dead upstream-304, ~24h filtered-cache staleness | 🔴 dist-tags & search broken | 🟡 first-wins, no union | 🔴 packument buffered in heap per request → OOM | 🔴 shared HA gaps |
| **Maven/Gradle** | 🟢 mature | 🔴 no metadata merge/lock, no checksum verify, mutable releases | 🟡 first-wins | 🟡 `readAllBytes` cache commit | 🔴 |
| **PyPI** | 🟡 hardcoded pypi.org CDN; negative-caches cooldown 404s | 🔴 no overwrite policy / no hash verify | 🔴 no simple-index merge | 🟡 full-repo digest scan per upload | 🔴 |
| **Composer** | 🟡 metadata good; **dist path bypasses stream/integrity/single-flight** | 🟡 no dist shasum | 🟢 has merge slice | 🔴 dist buffered in heap, no single-flight | 🔴 |
| **Go** | 🟢 artifacts solid | 🔴 `@latest` picks wrong version (lexicographic) | 🟡 generic, no merge | 🟡 `@v/list`/`@latest` uncached → upstream-coupled | 🔴 |
| **S3 backend** | — | — | — | 🔴 **not viable** (S3 HEAD per hit) | 🔴 write-around → stale across nodes |
| **HA clustering** | — | — | — | — | 🔴 **data loss + security holes** |

---

## The two release-blocking tentpoles

Everything else is cleanup around these two. Both are substantial builds; they should drive the 2.3.0 timeline.

### Tentpole A — Storage engine for scale (the S3/disk-cache rebuild)

The current disk cache (`DiskCacheStorage`) overrides only `value/save/move/delete`; `exists()`, `metadata()`, `list()` fall straight through to S3. So:
- `FromStorageCache.load` calls `exists()` **first** → 1 S3 HEAD per hit; `validate-on-read=true` (the default) adds a **second** HEAD to re-check freshness. At 1000 hits/s that's 1000–2000 S3 HEADs/s.
- Hosted S3 reads have **no cache and no S3-optimized slice** (`S3ArtifactSlice` is a TODO) → HEAD+GET every time.
- Writes are synchronous S3-PUT-through; size-unknown uploads spool the whole body to a temp file *and* re-read it; `ProxyCacheWriter` does `Files.readAllBytes` (whole artifact into heap) before save.
- Write-around cache + no cross-node disk invalidation → node B serves stale bytes.
- No S3-operation metrics at all → you're blind to the exact signals needed to tune this.

**Target (JFrog/Artifactory-class):** local disk is the authority for the hot path; S3 is durability + cold tier; **zero S3 round-trip on a hit** (body, existence, *and* freshness). Concretely: a `CachedS3Storage` that overrides **all** storage methods and answers `exists/metadata/list/value` from a **local metadata index** (embedded KV — RocksDB/LMDB or per-namespace SQLite); single-flighted cold fills; **async durable write-back** to S3 via a persistent (restart-surviving) queue with backpressure; index-driven eviction with hard admission control; cross-node coherence over the existing Valkey pub/sub; per-op metrics. Reuse `S3Storage`, `FileStorage`+`OptimizedStorageCache`, `SingleFlight`, `RepoBulkhead`, `CacheInvalidationPubSub`, `ProxyCacheWriter` digest logic. **This is the single biggest engineering item in the whole release.**

### Tentpole B — HA correctness

Three things MUST be fixed before "production HA" is truthful:
1. **Token revocation in the Valkey path** — `ValkeyRevocationBlocklist` writes to Valkey but never reads it back or hydrates on boot, and it's the impl the HA topology selects. Fix: hydrate on boot + read-through on local miss, or make revocation DB-durable and use Valkey only as an accelerator; carry the real token TTL in the pub/sub payload.
2. **De-cluster the artifact-event pipeline** — under clustered Quartz, node B fires node A's `EventsProcessor` trigger, finds the node-local `JobDataRegistry` entry missing, and `deleteJob`s it from the shared tables; node A's `DbConsumer` then never drains. This is why `scheduler.clear()`-on-boot exists (a band-aid). Fix: drain each node's events on a per-node `ScheduledExecutor` (or a DB queue with `FOR UPDATE SKIP LOCKED`); remove `clear()` and the self-destruct. **Biggest correctness fix.**
3. **Propagate authorization + admin-settings cross-node** — the policy (roles/permissions) cache has a pub/sub *receiver* but no *publisher* (and it's `expireAfterAccess`, so a hot entry never expires on peers → unbounded stale authz); the breaker/bulkhead settings loaders `invalidate()` locally only → peers stay stale until restart. Fix: wrap the policy cache as a `PublishingCleanable("policy", …)` (receiver already exists) and broadcast settings-loader invalidations.

Plus hardening: a **real readiness probe** on the traffic port (DB `SELECT 1`, S3 HEAD-bucket, Valkey PING) + a pre-stop readiness gate so rolling deploys don't drop LB-routed requests; a graceful `DbConsumer` flush on shutdown (today even a *clean* shutdown loses the last ~2s of audit records); download-token hardening (shared secret fail-closed, constant-time compare, real single-use).

*Not needed for 2.3.0:* leader election (pg_cron + `FOR UPDATE SKIP LOCKED` already cover singleton work once B#2 lands), Valkey **Cluster** (Sentinel failover is the right call — the code uses global keys + one pub/sub channel, so sharding adds nothing), and the `pantera_nodes` heartbeat registry (nice for visibility, not a correctness prerequisite).

---

## Cross-cutting patterns — fix the engine, not the symptom

Most of the ~60 findings are instances of **six systemic patterns**. Fixing the pattern closes many format-specific gaps at once:

1. **Whole-artifact / whole-metadata heap buffering on the hot path.** npm buffers 30–40 MB packuments per request (with per-follower re-buffering); `ProxyCacheWriter.commitStreamed` does `Files.readAllBytes` (Maven, Go); Composer dist downloads run through `ProxyDownloadSlice` which buffers the whole archive; PyPI re-digests full artifacts to build the index. → **Streaming cache-writer + streamed/bounded metadata serving** kills the OOM-at-1000-rps class across formats.

2. **Advertised integrity that isn't enforced.** The `ProxyCacheWriter` sidecar-integrity design was ported to Go (`.ziphash`) and Composer (`.sha256`) with sidecar URLs **that don't exist in those protocols** — so verification is inert while the Javadoc claims "Maven-equivalent integrity." Maven `.asc` GPG verification is dead code (V131 table, no callers). PyPI/Composer hosted uploads store no verified hash. → **Wire real per-format integrity (Go `h1:` dirhash, Composer `dist.shasum` from the packument, PyPI twine `sha256_digest`, Maven client checksums) or delete the claim.** Don't ship inert guarantees.

3. **Hosted (local) mode is under-built vs proxy.** Maven metadata is client-trusted with no server-side merge or lock (concurrent/stale `mvn deploy` silently drops versions); no upload checksum verification; releases are mutable. PyPI has no overwrite policy and no hash verify. npm `dist-tag`/`--tag` and `npm search` are effectively non-functional. Go hosted `@latest` sorts lexicographically. → **A hosted-write hardening pass**: read-modify-write metadata under a per-coordinate lock, verify checksums on store, reject release re-deploys, fix the format-specific bugs.

4. **Group mode is first-2xx-wins, not union-merge** (npm, Maven, PyPI, Go). A package present in two members exposes only one member's version list — not Nexus/Artifactory parity. Composer *does* merge. → **Product decision:** is union-merge a 2.3.0 goal or an accepted limitation? (Recommend: defer to 2.3.x unless a concrete internal use case needs it.)

5. **Cooldown's caching semantics have sharp edges.** PyPI **negative-caches a cooldown-induced 404** → a package embargoed by cooldown stays invisible for hours *after* it should be installable. npm's filtered-metadata cache isn't invalidated on proxy refresh → new upstream versions hidden up to ~24h. Go evaluates cooldown for *every* version in `@v/list` unbounded; Composer does the same on large Satis roots. → **A cooldown-cache coherence pass**: never negative-cache a cooldown 404, invalidate filtered metadata on refresh, cap per-request cooldown fan-out.

6. **Upstream revalidation is dead/absent → re-downloads and availability coupling.** npm extracts the upstream ETag then drops it, so every 12h TTL boundary re-downloads full packuments instead of a cheap 304. Composer stores `Last-Modified` but never sends `If-Modified-Since`. Go's `@v/list`/`@latest` are never cached, so `go get` breaks on any upstream blip even when every artifact is already cached. → **Restore conditional revalidation and TTL-cache the resolution surfaces.**

A seventh, quieter theme: **observability blind spots** — no per-S3-op metrics, storage not wrapped in `MicrometerStorage`. You can't prove or tune "1000 req/s" without them, and the project's own rule is "a metric without a panel is invisible."

---

## Per-area quick reference

Full detail per area is in the six audits; the top must-fixes:

- **npm** — (M) persist upstream ETag → revive 304; (M) unify local dist-tags with the per-version layout; (M) invalidate filtered-cache on refresh; **(L) stream/bound packument serving** (the real scale fix, structural); (S) fix prerelease tarball cooldown parse; (S) wire local search. *Biggest risk: packument memory buffering → OOM.*
- **Maven/Gradle** — **(L) server-side metadata merge under a per-GA lock**; (M) checksum verify on store; (M) streaming cache commit; (M) Range/`206` + (S) `304` on artifacts; (S/M) release immutability; (M) wire or delete PGP `.asc`. *Biggest risk: hosted `maven-metadata.xml` corruption under concurrent deploys.*
- **PyPI** — **(S) stop negative-caching cooldown 404s** (cheapest, highest-value); (S) upload overwrite policy + digest verify; (M) configurable/mirror-aware artifact CDN (drop the `files.pythonhosted.org` hardcode); (M) memoize file digests + incremental index; (M) group simple-index merge. *Biggest risk: negative-cache poisoning of cooldown 404s (silent).*
- **Composer** — **(L) route dist through streaming + single-flight** (the good path is dead in wiring); (M) verify `dist.shasum` + write it on publish; (M) resolve the root `metadata-url` rewrite ambiguity (proxy-bypass risk); (S) serve `available-packages.json`; (M) `source.url` support. *Biggest risk: dist path buffers every archive in heap + no integrity.*
- **Go** — (S) fix hosted `@latest` semver sort; (M) TTL-cache `@v/list`/`@latest` (offline-safe) + single-flight; (M) wire real `h1:` zip integrity or delete the sidecar claim; (S) cap `@v/list` cooldown eval; (M) decode `!`-escaping for DB/index. *Biggest risk: resolution surfaces upstream-coupled → `go get` breaks on upstream blip.*
- **HA** — the trio above + readiness/drain + download-token. *Biggest risk: can't run N nodes without data loss + security holes.*
- **S3/disk-cache** — Tentpole A. *Biggest risk: not viable at 1000 req/s today.*

---

## 2.3.0 scope — the honest cut

Trying to ship *everything* (JFrog parity across all formats + full HA + S3-at-scale + group union-merge + complete npm API surface) in one release is not realistic without a long timeline. Recommended cut for a defensible **"production-grade, honestly bulletproof for the paths we support"** release:

### MUST-HAVE (release-blocking)
- **WS1 — Storage-for-scale (Tentpole A).** Disk-primary `CachedS3Storage` + local index + async write-back + eviction + storage metrics + hosted-read slice. **[XL]**
- **WS2 — HA correctness (Tentpole B).** Revocation fix, event-pipeline de-cluster, authz + settings propagation, readiness probe + pre-stop drain, `DbConsumer` graceful flush, download-token hardening. **[L]**
- **WS3 — Streaming & memory.** Streaming cache commit (fixes Maven/Go/Composer), bounded/streamed npm packument serving, Composer dist streaming + single-flight, PyPI digest memoization. **[L]**
- **WS4 — Hosted-write correctness & integrity.** Maven metadata merge+lock+checksum-verify+release-immutability; PyPI overwrite policy + hash verify; npm dist-tags + search; Go `@latest` fix; decide/wire-or-remove the inert integrity claims (Go `.ziphash`, Composer `.sha256`, Maven `.asc`). **[L]**
- **WS5 — Cooldown-cache coherence.** PyPI negative-cache guard, npm filtered-cache invalidation + prerelease parse, Go/Composer fan-out caps. **[M]**

### SHOULD-HAVE (strongly recommended, cut first if timeline slips)
- **WS6 — Upstream efficiency & resolution availability.** npm ETag→304, Composer `If-Modified-Since`, Go TTL-cache `@v/list`/`@latest` + single-flight. **[M]**
- Observability: per-format + storage dashboards for the new metrics (also a Definition-of-Done requirement).

### DEFER (2.3.x / 2.4)
- **Group union-merge** across npm/Maven/PyPI/Go (product decision). **[L]**
- **Missing npm API surface**: `npm access`/`owner`/`profile`, token CRUD, `/-/npm/v1/keys` (`npm audit signatures`), provenance/attestations, HEAD. **[L]**
- Composer `source.url`; PyPI PEP 658 hosted; cross-node disk-cache pub/sub invalidation; node registry + heartbeats; Valkey Sentinel; presigned-URL direct downloads; expanded itcases (uv, poetry). **[M each]**

---

## Recommended sequencing

1. **Foundations first (parallelizable):** WS1 (storage) and WS2 (HA) are the long poles and largely independent — start both early. WS1 gates the "1000 req/s on S3" claim; WS2 gates the "run more than one node" claim.
2. **WS3 (streaming)** overlaps WS1 (the storage-writer changes touch the same `ProxyCacheWriter`) — sequence it right after/with WS1's write path.
3. **WS4 + WS5** are per-format and can run in parallel by a second track once the storage/streaming primitives land.
4. **WS6** last; it's efficiency, not correctness.
5. **Gate the release** on the standard bar: `mvn clean install -T8` green, the itcase suite green (expand it — uv/poetry/gradle coverage is thin), and a **real load test at ≥1000 req/s R+W against an S3 backend** proving the storage rebuild (the whole point of 2.3.0).

---

## A candid note on scope, and decisions I need from you

**Candor:** WS1 alone is a multi-week rebuild (a metadata index + async durable write-back queue is real infrastructure), and WS2#2 (de-clustering the event pipeline) touches the audit/index correctness core. For a small team this is a **months-long major release**, not a sprint. Two viable shapes:

- **(a) Full 2.3.0** — everything in MUST-HAVE. Longest, but ships the story the blog tells.
- **(b) Split** — 2.3.0 = HA correctness + streaming + hosted-write + cooldown (make single-node and *small* clusters genuinely bulletproof, and label S3-backend "not yet for high scale"); then **2.4 = the S3 disk-primary rebuild** as its own focused, load-tested release. This de-risks by not coupling the two biggest builds, and lets the blog ship after 2.3.0 with an honest "S3-at-scale is next" line.

I lean toward **(b)** unless the S3-at-scale story is a hard requirement for the blog's launch.

**Decisions that change the plan:**
1. **Is literal JFrog parity the bar, or "correct and scalable for the formats/paths we actually run"?** (Recommend the latter — it's honest and shippable.)
2. **One release (a) or split (b)?**
3. **Is multi-node HA a 2.3.0 requirement, or is single-node-with-a-warm-standby acceptable for now?** (If HA can wait, WS2 shrinks to the security fixes only and the timeline drops a lot.)
4. **Group union-merge — needed, or is first-2xx-wins an accepted limitation?**
5. **Tier-1 formats:** are all five (npm/Maven/PyPI/Composer/Go) equal priority, or do npm + Maven lead? (Affects what WS4/WS5 must cover for 2.3.0 vs. defer.)

Tell me the answers to 1–5 and I'll turn this into a concrete, sized 2.3.0 milestone with issues per workstream.
