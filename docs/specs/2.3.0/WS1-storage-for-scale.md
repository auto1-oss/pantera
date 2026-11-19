# WS1 — Storage for Scale (S3 disk-primary)

- **Status:** 📝 DRAFT
- **Depends on:** none (foundational)
- **Blocks:** WS3 (shares `ProxyCacheWriter` write path), the release load-test gate
- **Decision-gated:** no
- **Size:** XL — the single largest item in 2.3.0. Split into phases WS1.1–WS1.6; each phase is a separately-shippable agent task.

## 1. Problem & goal

S3-as-backend cannot sustain 1000 req/s reads or writes today. The 2.2.0 `DiskCacheStorage` is a *body-only* cache grafted onto an S3-authoritative hot path: it eliminates the S3 body GET on a hit but still pays **1–2 synchronous S3 HEADs on every hit**, hosted S3 reads have no cache at all, and writes are synchronous S3-PUT-through with whole-artifact heap buffering.

**Goal:** a JFrog/Artifactory-class layering where **the object store is durability, a local metadata index answers existence/metadata/list with zero store round-trips, and artifact bytes are served either by presigned direct-download (recommended) or from a local disk tier** — sustaining **≥1000 req/s reads AND writes** with **zero blob-store round-trip on a cache hit**. This is the headline claim of 2.3.0 and must be demonstrated by load test, not asserted.

**Two design commitments added after review (2026-07-24):**
- **Blob-store agnostic, not S3-only.** The backend is an abstract `BlobStore` supporting S3 **and similar object stores** (see §3.I).
- **Presigned direct-download is a first-class read strategy**, not a deferred nicety (see §3.B2) — for cloud deployments it is the primary way to serve artifact bytes and is what actually makes 1000 req/s of byte reads trivial, because Pantera stops streaming bytes at all for hits.

## 2. Current state (evidence)

Effective proxy read stack: `FromStorageCache → SubStorage → DispatchedStorage → DiskCacheStorage → S3Storage`.

- `DiskCacheStorage extends Storage.Wrap` overrides **only** `value/save/move/delete` (`DiskCacheStorage.java:207,276,283,290`). `exists()`, `metadata()`, `list()` fall through `Storage.Wrap` to `S3Storage` (`S3Storage.exists` = S3 HEAD, `S3Storage.java:268`).
- `FromStorageCache.load` calls `exists()` **first** (`FromStorageCache.java:56`) → S3 HEAD #1 on every hit.
- `validate-on-read=true` (default, `S3StorageFactory.java:117`) → `DiskCacheStorage.matchRemoteAsync → super.metadata` → S3 HEAD #2 (`DiskCacheStorage.java:225-238,437-450`).
- Hosted S3 GET: `S3ArtifactSlice` is a TODO (`StorageArtifactSlice.java:126`) → falls to `GenericArtifactSlice` → `exists()`+`value()` = HEAD+GET every request (`StorageArtifactSlice.java:245,252`).
- Writes: hosted PUT = synchronous S3 write-through (client waits for durable ack). Size-unknown uploads spool whole body to a temp file then re-read (`EstimatedContentCompliment.java:84-115`). `ProxyCacheWriter.commitStreamed`/`commitVerified` do `Files.readAllBytes(tempFile)` (whole artifact into heap) before `cache.save` (`ProxyCacheWriter.java:681,1020`).
- `DiskCacheStorage.save` = write-around: invalidates the **local** disk entry and writes only to S3 (`:276-280`); no cross-node disk invalidation (`CacheInvalidationPubSub` covers only Caffeine caches; disk namespace `sha1(identifier)` is per-node, `DiskCacheStorage.java:128`).
- No single-flight on cold disk fills (`:221,311`) → N readers = N S3 GETs.
- Eviction = O(n) `Files.walk` every 5 min, no in-memory size accounting (`:530-618`); disk-full risk between runs.
- delete = HEAD+DELETE (`S3Storage.java:507-528`); `list()` = full paginated `ListObjectsV2` (`:317-343`).
- **No per-S3-op metrics** — storage not wrapped in `MicrometerStorage` (`RepoConfig.java:62-65`).

SDK config is fine (async Netty, adaptive retry, maxConcurrency 1024, `S3StorageFactory.java:150,165,196`); the problem is round-trip **count**, not thread blocking.

## 3. Target design

North star: **local disk answers the hot path with no S3 contact; a local index answers existence/metadata/list; S3 is written to durably in the background.**

New component: **`CachedS3Storage implements Storage`** — a full storage that composes a **disk tier** (reuse `FileStorage` + `OptimizedStorageCache` NIO), a **local metadata index**, an **S3 cold tier** (`S3Storage`, unchanged), and an **async write-back queue**. It replaces `DiskCacheStorage` for S3 backends. It overrides **every** method so no call silently reaches S3 on the hot path.

### A. Local metadata index (`StorageIndex`)
Embedded KV per storage namespace. Key → `{ size, etag, sha256, lastModified, lastAccess, hits, presentOnDisk: bool, s3State: PRESENT|PENDING_WRITE|ABSENT, negativeUntil: epochMs? }`.
- Backing: an embedded engine (RocksDB or LMDB via JNI, or a per-namespace SQLite through the existing JDBC infra — **pick one in WS1.1; SQLite is lowest-dependency and reuses HikariCP patterns**). Must survive restart and be rebuildable from a disk scan on boot.
- Serves `exists()` (index lookup, no I/O), `metadata()` (index), `list()` (index prefix scan) with **zero S3 contact**.
- Negative entries (`ABSENT` + `negativeUntil`) short-TTL cache S3 misses so `exists()` on a cold-missing key doesn't hit S3 every time.

### B. Read path
- `exists(key)` → index. If unknown, single-flight an S3 `HEAD`, record result (incl. negative), return.
- `value(key)` → index says `presentOnDisk` ⇒ stream from disk via `OptimizedStorageCache` NIO (extend it to recognize the disk tier, not only literal `FileStorage`). Index miss ⇒ **single-flight** S3 GET (dedup at the storage layer — reuse `SingleFlight` from pantera-core), tee to client + disk, update index.
- **Freshness:** trust the local copy for a configurable TTL; verify against S3 **lazily / in a background sweep**, never inline. Drop the per-read HEAD entirely. (Cross-node staleness is bounded by pub/sub invalidation, phase WS1.4, and TTL as a backstop.)

### B2. Presigned direct-download (recommended byte-read path)
For **immutable artifact bytes** (jars, wheels, tarballs, zips, `.mod`/`.zip`, Docker blobs — never metadata), when presign is enabled for the repo and the object is durably in the store, respond with **`302 Found` + `Location: <presigned URL>`** and let the client fetch bytes **directly from the blob store**. Pantera never streams the body. Cost of a redirect = index lookup + **local** SigV4/V4/SAS signing = **zero blob-store round-trip**. This is the primary scale lever: for hits it removes Pantera from the byte path entirely, so the 1000 req/s ceiling for byte reads effectively disappears.

Non-negotiable rules:
- **Metadata is NEVER redirected** — packument, `maven-metadata.xml`, PyPI simple index, `/v2/` manifests+tags, Composer `/p2/`, Go `@v/list`/`@latest`. These are rewritten and cooldown-filtered by Pantera; a redirect would bypass cooldown, filtering, and URL rewriting. Only content-addressed / immutable byte objects are eligible.
- **Fallback is mandatory.** A per-repo policy `download-mode: {redirect | stream | auto}`. `auto` redirects when the object is in the store and the client is redirect-capable, else streams from the disk tier (§B). Locked-down/air-gapped networks (clients can only reach Pantera) run `stream`. A redirect target that the client can't reach must degrade gracefully — prefer `auto` defaulting to `stream` unless a repo opts into `redirect`.
- **Integrity is preserved by the client.** Every format verifies its own checksum/digest against the bytes it receives (Docker content digest, Maven `.sha1`/`.sha512`, npm `integrity`, Go `go.sum`/sumdb, Composer `dist.shasum`, PyPI `#sha256`). Pantera does not see the bytes on a redirect, which is acceptable because the client verifies.
- **Client support (all follow HTTP 301/302/307 on downloads):** Docker/OCI (the distribution spec explicitly permits blob-GET redirects; this is how S3-backed registries serve layers — the canonical case), Maven (resolver/wagon), npm (tarballs), pip/uv (files), Go (GOPROXY fetches), Composer (dist). Metadata endpoints for each stay Pantera-served.
- **Auth/audit:** the redirect is issued behind Pantera auth; the presigned URL grants time-limited (configurable, e.g. 5–15 min) read of that one object. Audit records the redirect issuance (`artifact_access`); the completed byte transfer occurs off-Pantera — per-byte metrics come from the store's access logs, not Pantera. Document this observability tradeoff.
- **Signing is per-backend** via the `Presigner` in §I (SigV4 for S3-compatible, V4 signed URLs for GCS-native, SAS tokens for Azure).

### C. Write path — async durable write-back
- `save(key, content)` → write bytes to disk + index synchronously (client acked from **local** durability), set `s3State=PENDING_WRITE`, enqueue an S3 upload on a **persistent, disk-backed, restart-surviving** write-ahead queue.
- A bounded pool of uploader workers drains the queue to S3 with retry/backoff; on success set `s3State=PRESENT`.
- **Backpressure:** when the queue exceeds a high-water mark, `save` returns `503 + Retry-After` (mirror the `RepoBulkhead`/`HandlerExecutor` AbortPolicy philosophy) rather than growing unbounded.
- **Durability knob:** an opt-in per-repo `write-through` mode keeps the current synchronous-S3-before-ack semantics for repos that require it (e.g. compliance). Default = write-back.
- Digest is computed once on the disk write (reuse `ProxyCacheWriter` digest logic / `DigestedFlowable`) and stored in the index; no re-read.

### D. Eviction
- Index-driven: an **in-memory running size counter** updated incrementally on write/evict — no `Files.walk` for sizing.
- LRU/LFU with high/low watermarks (keep the existing policy semantics), plus **hard admission control**: if a write would exceed `maxDiskBytes`, evict synchronously (or reject) before writing — disk can never exceed the bound between sweeps.
- Never evict a key whose `s3State=PENDING_WRITE` (would lose the only durable copy).
- Shard cache dirs with a 2-level hex fan-out to avoid huge directories.

### E. Cross-node coherence
- On write-back commit (and on delete/invalidate), publish `key + etag` over the existing `CacheInvalidationPubSub` on a new `storage` channel; peers drop or refresh their disk+index entry. This replaces per-read validation HEADs with event-driven invalidation.

### F. Hosted-read slice
- Route hosted S3 reads through `CachedS3Storage` (kill the `GenericArtifactSlice` `exists()`+`value()` double round-trip). Either implement the `S3ArtifactSlice` TODO to consult the index, or make hosted serving go through the same storage path as proxy so it inherits the cache.

### G. Metrics (feeds WS7)
Wire a metrics decorator into the blob-store tier (reverse the `RepoConfig` "no MicrometerStorage" decision): store GET/HEAD/PUT/LIST count + latency + error/throttle; disk hit ratio; **redirect-vs-stream ratio**; presign issuance rate; write-back queue depth + oldest-pending age; eviction bytes/sec. Use the transfer SLO ladder already defined in `VertxMain`.

### I. Blob-store abstraction (S3 and similar)
Generalize the backend from concrete S3 to a **`BlobStore`** interface (get/head/put/delete/list) + a pluggable **`Presigner`** (issue a time-limited read URL for a key). Two implementation tiers:
- **S3-API-compatible — one impl, config only.** AWS S3, **MinIO, Cloudflare R2, Backblaze B2, Wasabi, Ceph/RADOS Gateway, and GCS via its S3-interoperability endpoint**. The existing async S3 SDK covers all of these with: custom `endpoint`, `region`, path-style-vs-virtual-host toggle, credentials, and SigV4 presign. Most self-hosted/enterprise object stores land here.
- **Native — separate impls behind the same interface (pluggable).** Google Cloud Storage (native API + **V4 signed URLs**) and Azure Blob Storage (native API + **SAS-token** "presign"). Behind `BlobStore`/`Presigner` so the index, write-back, eviction, coherence, and redirect logic are **backend-agnostic** — they never see S3 specifics.

Config selects backend + endpoint + credentials + presign TTL per storage/repo. The current `S3Storage` becomes the reference `BlobStore` impl; `S3ExpressStorageFactory` folds in (it only differs by endpoint/config). Ship S3-compatible first (covers the majority incl. MinIO for tests); GCS-native and Azure-native are follow-on impls that don't change the core.

### Reuse vs build
- **Reuse:** `S3Storage` (cold tier), `FileStorage`+`OptimizedStorageCache` (disk tier NIO), `DispatchedStorage`/`StorageExecutors`, `SingleFlight`, `RepoBulkhead`, `CacheInvalidationPubSub`, `ProxyCacheWriter` digest logic.
- **Build:** `BlobStore`/`Presigner` abstraction (I), `StorageIndex` (A), `CachedS3Storage` (rename → `CachedBlobStorage`) full method coverage (B), presigned-redirect read path (B2), persistent write-back queue + uploaders (C), index-driven eviction + admission (D), disk-cache pub/sub invalidation (E), storage metrics decorator (G), index-consulting hosted slice (F).
- **Delete/replace:** `DiskCacheStorage` as-is (the `value()`-only wrapper — architecturally cannot remove the hit-path HEADs).

## 4. Implementation plan (phased)

- **WS1.0 — `BlobStore` + `Presigner` abstraction (I).** Extract the interface; make `S3Storage` the reference impl with configurable endpoint/region/path-style (covers S3 + MinIO/R2/B2/Wasabi/Ceph/GCS-S3-interop); fold in `S3ExpressStorageFactory`. Prereq for everything else.
- **WS1.1 — `StorageIndex` + `CachedBlobStorage` read path (B, A).** New classes in a new `pantera-storage-cache` module (or `pantera-storage-s3`). Full method coverage; index answers exists/metadata/list; single-flight cold fills; drop inline validation HEADs; boot-time index rebuild from disk scan. Wire behind the existing `cache.enabled` config as an opt-in `cache.mode: index` (keep `DiskCacheStorage` selectable until parity proven).
- **WS1.2 — Async durable write-back (C).** Persistent queue + uploader pool + backpressure + `write-through` opt-out. Touches the factory wiring and the save path.
- **WS1.3 — Streaming save (kills heap buffering).** Remove `Files.readAllBytes` in `ProxyCacheWriter.commitStreamed/commitVerified` (save the temp file via a file-backed streaming `Content`); remove the `EstimatedContentCompliment` whole-body temp-spool for size-unknown uploads (chunk-buffer to the multipart threshold only). **Overlaps WS3** — coordinate.
- **WS1.4 — Index-driven eviction + admission control + sharded dirs (D).**
- **WS1.5 — Cross-node pub/sub invalidation (E).**
- **WS1.6 — Storage metrics decorator (G) + hosted-read slice (F).**
- **WS1.7 — Presigned direct-download (B2).** `download-mode: {redirect|stream|auto}` per repo; `302` for eligible immutable byte objects; SigV4 presign via the `Presigner`; per-format wiring so only artifact-byte routes (never metadata) can redirect — Docker blob GET first (canonical), then Maven/npm/pip/Go/Composer artifact routes. Audit the redirect as `artifact_access`.
- **WS1.8 — Native GCS + Azure Blob `BlobStore`/`Presigner` impls (follow-on).** V4 signed URLs (GCS), SAS tokens (Azure). No change to the core; can land after the S3-compatible path is proven.

`S3ExpressStorageFactory` folds into WS1.0 (it duplicates `S3StorageFactory:132-160`).

## 5. Acceptance criteria

1. **No S3 HEAD on a cache hit.** With a recording S3 client fake, a `value()`/`exists()` on a key present on disk issues **zero** S3 calls. (Regression test with an invocation-counting S3 stub — the CLAUDE.md "invocation counts, not wall-clock" doctrine.)
2. **Single-flight cold fill.** N concurrent `value()` for one cold key issue exactly **one** S3 GET.
3. **Write-back durability.** After `save()` returns, the key is readable locally immediately; the S3 upload completes asynchronously; killing the process before drain and restarting **replays the queue** and the key lands in S3 (persistent-queue test).
4. **Backpressure.** With the uploader pool blocked, once the queue passes the high-water mark, `save()` returns 503+Retry-After rather than OOMing (bounded-queue test).
5. **Eviction bound.** Under a write flood exceeding `maxDiskBytes`, on-disk bytes never exceed the bound (admission-control test); no `PENDING_WRITE` key is evicted.
6. **Cross-node coherence.** A write on node A publishes an invalidation that drops node B's stale disk+index entry (two-instance test with a shared Valkey fake).
7. **Boot rebuild.** Index deleted, process restarted → index rebuilds from the disk scan; existence/metadata correct.
8. **Load test (release gate):** ≥1000 req/s reads and ≥1000 req/s writes against a real (or MinIO) blob backend, p99 within the per-adapter SLO ladder, store GET/HEAD rate near zero on the read hot-set — tested in **both** `stream` (disk-served) and `redirect` (presigned) modes.
9. **Presigned redirect correctness:** in `redirect` mode, a byte-object GET returns `302` to a working presigned URL with **zero** blob-store round-trip on Pantera's side (invocation-count test); a **metadata** GET is **never** redirected (always `200` from Pantera, cooldown-filtered); a Docker blob GET redirect is followed by a real client in an itcase; `auto` mode falls back to `stream` when the object is absent or presign is disabled.
10. **Backend portability:** the full `CachedBlobStorage` unit suite passes against the `BlobStore` interface with an in-memory fake and against a MinIO container (S3-compatible), proving no S3-specific assumptions leak into the cache/write-back/redirect logic. (GCS/Azure impls, WS1.8, get their own suites.)

## 6. Test requirements

- Unit: `StorageIndex` (persistence, rebuild, negative TTL, prefix list), `CachedS3Storage` method-by-method against an invocation-counting in-memory S3 fake, write-back queue (persistence + replay + backpressure), eviction (admission + watermarks + pending-protection), pub/sub invalidation.
- Itcase: an S3-backed repo (MinIO container) proving proxy read-hit/miss and hosted write survive a node restart mid-write-back.
- Load: a repeatable harness (k6/gatling or a JMH-style driver) for the release gate; committed under `test_images/` or `docs/slo/`.
- Follow "never assert wall-clock latency" — prove semantics via invocation counts + latches; the load test is the only place duration is measured, and only as an SLO-ladder check.

## 7. Out of scope

- Prefetch/warming (defer; hooks exist via `CacheWriteEvent`).
- The pure local-`FileStorage` backend (no object store) — already NIO-optimized and unaffected by this work.
- CDN-in-front-of-store configuration (presigned URLs can already point at a CDN edge; explicit CDN integration is a later concern).

## 8. Risks & rollback

- **Highest-risk item in the release.** Mitigate by keeping `DiskCacheStorage`/`cache.mode` selectable so a repo can fall back to the old behavior; ship `cache.mode: index` as opt-in first, flip the default only after the load test passes.
- Write-back introduces a durability window (bytes acked locally, not yet in S3) — bounded by the persistent queue (survives crash) + `write-through` opt-out for repos that can't tolerate it. Document the window explicitly.
- Local index corruption ⇒ rebuild-from-disk on boot must be robust (fuzz the rebuild).

## 9. Docs & observability

- `docs/admin-guide/storage-backends.md` — the supported blob backends (S3 + S3-compatible: MinIO/R2/B2/Wasabi/Ceph/GCS-interop; native GCS/Azure), endpoint/path-style config, new `cache.mode`, `download-mode: {redirect|stream|auto}` + presign TTL + the client-reachability/air-gap guidance, write-back knobs, `write-through` opt-out, durability-window note; remove/adjust the `validate-on-read` guidance.
- `docs/configuration-reference.md` + `environment-variables.md` — every new setting/env var (backend selection, endpoint, presign TTL, download-mode, cache.mode, write-back).
- `docs/user-guide/` — note that with `download-mode: redirect`, clients must be able to reach the blob store; per-format behavior (esp. Docker blob redirects).
- WS7 dashboards for the new storage + presign metrics (redirect-vs-stream ratio, presign issuance); SLO ladder note in `docs/slo/`.
- CHANGELOG under `### ⚡ Performance` + `### 🌟 New features` (blob-store backends, direct-download).
