# Mass-scale package mirrors — reference study

## TL;DR

Every package mirror that operates at mass scale (PyPI/Warehouse, npm/npmmirror, Maven Central, USTC, jsDelivr/unpkg, Goproxy.cn) has converged on the same handful of architectural moves:

1. **The origin never serves bytes.** A dedicated CDN (Fastly, Cloudflare, Bunny, GCore, ISP-local caches) sits in front and answers 95–99% of requests. Origin only ever sees the cache-miss tail.
2. **Tarballs are content-addressed and served from object storage via redirect.** Origin returns `302` to a permanent URL (`files.pythonhosted.org/packages/<hash>/...`, `registry.npmmirror.com/...`, etc.) so the origin's PHP/Python/Java/Node process is never on the byte path.
3. **Cacheability is structural, not policy.** Versioned tarball URLs are immutable forever (`max-age=31536000, immutable`); index/manifest URLs are surrogate-keyed and explicitly purged on publish events. There is no TTL guesswork on hot paths.
4. **Pull-through is the default upstream story.** Lazy fetch on first access; only cnpmcore retains an optional "sync everything" mode. Even that is layered behind the pull-through proxy.
5. **Heavy users are throttled at the *organization* level.** Maven Central in 2024 published evidence that 1% of IPs consumed 83% of bandwidth and is now rate-limiting whole orgs, not just IPs. The downstream lesson: a proxy that does not coalesce identical concurrent requests will be one of those 1%.

For a single-region Maven proxy at 4–5× the latency of reference: the gap is almost always (a) origin-on-bytepath when reference is CDN-on-bytepath, (b) per-request upstream fetches when reference deduplicates them, (c) synchronous metadata revalidation when reference treats versioned URLs as immutable.

## Sources

Cited inline below. All URLs are public and free.

- cnpm/cnpmcore source — https://github.com/cnpm/cnpmcore — fetched and read directly
- Warehouse (PyPI) architecture — https://github.com/pypi/warehouse, https://warehouse.pypa.io/
- Donald Stufft, "Powering the Python Package Index" (caremad, 2016) — https://caremad.io/posts/2016/05/powering-pypi/
- Dustin Ingram, "What does it take to power the Python Package Index?" (2021) — https://dustingram.com/articles/2021/04/14/powering-the-python-package-index-in-2021/
- Fastly + Python Software Foundation case study — https://www.fastly.com/customers/python-software-foundation
- npm blog, "New npm Registry Architecture" (2014) — https://blog.npmjs.org/post/75707294465/new-npm-registry-architecture.html
- Bojie Li, "How is the USTC Open Source Software Mirror Made?" (2013) — https://01.me/en/2013/09/how-ustc-mirror-works/
- USTC mirrors ZFS rebuild post-mortem (iBug, 2024) — https://ibug.io/blog/2024/10/ustc-mirrors-zfs-rebuild/
- TUNA / Tsinghua mirror — https://mirrors.tuna.tsinghua.edu.cn/
- Sonatype, "Maven Central: Addressing the Tragedy of the Commons" — https://www.sonatype.com/blog/maven-central-and-the-tragedy-of-the-commons
- Sonatype, State of the Software Supply Chain 2024 — https://www.sonatype.com/state-of-the-software-supply-chain/2024/scale
- Sonatype, "Beyond IPs: Addressing Organizational Overconsumption in Maven Central" — https://www.sonatype.com/blog/beyond-ips-addressing-organizational-overconsumption-in-maven-central
- jsDelivr network page — https://www.jsdelivr.com/network
- unpkg.com README + Kent C. Dodds writeup — https://github.com/unpkg/unpkg, https://kentcdodds.com/blog/unpkg-an-open-source-cdn-for-npm
- Goproxy.cn — https://github.com/goproxy/goproxy.cn
- Aliyun Maven mirror page — https://developer.aliyun.com/mirror/maven
- Tencent Cloud mirror — https://mirrors.cloud.tencent.com/, https://www.tencentcloud.com/document/product/213/8623
- Bandersnatch (PyPI mirror client) — https://github.com/pypa/bandersnatch
- CRAN mirror HOWTO — https://cran.r-project.org/mirror-howto.html

---

## 1. npmmirror / cnpmcore

**Origin model:** hybrid. The cnpmcore engine supports five sync modes (`app/common/constants.ts`):

```
export enum SyncMode {
  none = 'none',
  admin = 'admin',
  proxy = 'proxy',
  exist = 'exist',
  all = 'all',
}
```

`registry.npmmirror.com` itself runs in `all` mode — a full mirror that syncs the upstream `_changes` stream and pre-fetches everything. Self-hosters typically run `proxy` mode (lazy pull-through, the closest analogue to what Pantera does for Maven) or `exist` (sync on first published-elsewhere request).

In the `proxy` SyncMode the tarball download path (`app/port/controller/package/DownloadPackageVersionTar.ts:65-103`) does the following on miss:

1. Look up the storage key `/packages/${fullname}/${version}/${filename}.tgz`.
2. If the backing object store (OSS, S3, or local NFS) can produce a URL via `nfsAdapter.getDownloadUrl(storeKey)`, **return a 302 redirect** instead of streaming through the Node origin. The HTTP call to the origin completes within a few ms; bytes flow client → OSS → client.
3. Only when the storage backend cannot produce a URL (local FS in dev) does the origin actually stream bytes.
4. On a true miss the request proxies upstream synchronously, pipes the body through a `PassThrough` stream to the client, *and* — via `ctx.runInBackground` — enqueues a `PackageSyncerService` task that will fetch and store the artifact properly so the next request hits step 2.

This is the single biggest architectural decision in cnpmcore and worth quoting (`app/port/controller/package/DownloadPackageVersionTar.ts:53-58`):

```js
if (this.config.cnpmcore.syncMode === SyncMode.all && downloadUrl) {
  // try nfs url first, avoid db query
  this.packageManagerService.plusPackageVersionCounter(fullname, version);
  ctx.redirect(downloadUrl);
  return;
}
```

The counter increment is fire-and-forget; the DB query for package metadata is *skipped entirely* when the cache redirect is satisfiable. Pantera's `BaseCachedProxySlice` runs the 7-step pipeline (negative-cache → preprocess → cacheability → cache-first → cooldown → dedup → store) on *every* tarball request even though tarballs are content-addressed and infinitely cacheable; cnpmcore short-circuits to a single object-store URL call.

**Cache hierarchy.** Three layers, but the hot one is the object store:
- **L1 — Redis** (`config.redis`, default 127.0.0.1:6379). Holds package manifest ETags + bodies (`app/core/service/CacheService.ts:47-67`, keyed `${fullname}|full:etag` / `${fullname}|abbr:manifests`). Manifests, not tarballs, are what hit Redis.
- **L2 — Database** (MySQL or PostgreSQL, configured per `config/config.default.ts:67-108`). Holds package + version metadata + cache pointers (the `ProxyCache` table).
- **L3 — Object store** (`oss-cnpm`, `s3-cnpmcore`, or NFS — `config/config.default.ts:135-177`). Holds tarballs + manifest snapshots. This is what 302s land on. Default `Cache-Control: max-age=0, s-maxage=60` for OSS uploads (`config/config.default.ts:151`) — so the upstream CDN caches 60s of an immutable artifact, deliberately short so manifests don't go stale, but `s-maxage` only applies to shared caches, not to the object's permanent URL.

Manifest cache has explicit CDN headers (`config/config.default.ts:50-51`):
```
cdnCacheControlHeader: 'public, max-age=300',
cdnVaryHeader: 'Accept, Accept-Encoding',
```
Five-minute CDN cache for the *package manifest* (which is mutable: new versions get published constantly). The CDN itself plus per-package surrogate keys handle invalidation on publish; this is the same trick Warehouse uses with Fastly.

**Upstream handling.** Upstream fetches go through `ProxyCacheService.getProxyResponse` (`app/core/service/ProxyCacheService.ts:322-348`):

```js
const res = await this.httpClient.request(url, {
  timing: true,
  followRedirect: true,
  retry: 7,
  dataType: 'stream',
  timeout: 10_000,
  compressed: true,
  ...
});
```

- 10-second timeout. Aggressive for what's nominally a CDN-fronted upstream (`registry.npmjs.org` is itself behind Fastly).
- Seven retries with redirect counting. cnpmcore explicitly notes "once redirection is also count as a retry" in the inline comment.
- Body is returned as a `stream`, never buffered in Node memory before being piped to the client.
- Auth tokens are looked up per registry, allowing one cnpmcore to fan in to multiple upstreams.

For manifest refreshes there is a separate `SyncProxyCacheWorker` and `CheckProxyCacheUpdateWorker` (`app/port/schedule/`) that run only when `syncMode === SyncMode.proxy`. They scan the `ProxyCache` table for entries past their staleness window and re-fetch the rewritten manifest in background. Crucially these refresh requests *do not block clients* — the client gets the cached manifest with 5-minute CDN max-age; the worker quietly replaces the object-store copy before the CDN entry expires.

There is also a `taskQueueHighWaterSize: 100` (`config/config.default.ts:23`) and `syncPackageWorkerMaxConcurrentTasks: 10` (`config/config.default.ts:26`). Sync is intentionally bounded — when 100 tasks are queued, new sync requests fail fast rather than pile up. This is the exact "do not amplify upstream when in trouble" lesson the Pantera M3 rate-limit work was about.

**Per-version-file locking.** For unpkg-style file extraction (which involves unpacking a tarball), cnpmcore uses a distributed Redis lock (`app/core/service/PackageVersionFileService.ts:86-92`):

```js
const lockName = `${pkgVersion.packageVersionId}:syncFiles`;
const lockRes = await this.cacheAdapter.usingLock(lockName, 60, async () => { ... });
```

Same pattern as Pantera's `RequestDeduplicator`, but using Redis for cross-instance coordination (Pantera does in-process `ConcurrentHashMap` + cross-instance via `CacheInvalidationPubSub`).

**Reported scale.** I could not find a precise current QPS number that I trust. Indirect signals:
- Chinese-language sources cite "20 billion downloads since 2014" cumulative for npm packages from npmmirror; this is *cumulative*, not per-day, and aged.
- npm itself: Sonatype 2024 report puts npm at "the largest contributor" to a 6.6 trillion total cross-ecosystem downloads in 2024, of which 1.5T was Maven. npm therefore comfortably exceeds 1.5T/year, i.e. ~50k downloads/sec average, much higher peak.
- The cnpmcore code itself shows `BackgroundTaskHelper` and Egg.js's background queue used aggressively — registry.npmmirror.com runs as a fleet of cnpmcore processes, with reads dominating writes; this is a `web` + `worker` split visible in the Egg.js config.

**Source-code observations (cite path:line):**
- `app/port/controller/package/DownloadPackageVersionTar.ts:50-58` — redirect-first pattern, the keystone optimisation.
- `app/common/adapter/NFSAdapter.ts:72-84` — `getDownloadUrl` then `getStream` fallback. The proxy *prefers* delegating bytes to the storage backend's HTTP server (S3/OSS) and only falls back to streaming through Node when the backend can't produce a URL.
- `app/port/controller/package/DownloadPackageVersionTar.ts:122-141` — true upstream miss: stream-pipe to client *and* enqueue a background sync. The user-visible response is the upstream's stream; the local cache fills asynchronously.
- `app/core/service/CacheService.ts:47-67` — manifest-level Redis cache, NOT tarball cache. Tarballs never enter Redis.
- `app/core/service/ProxyCacheService.ts:322-348` — single canonical upstream-fetch function: 10 s timeout, 7 retries, stream dataType, gzip-compressed, follows redirects. One choke-point function, easy to instrument and tune.
- `config/config.default.ts:22` — `sourceRegistrySyncTimeout: 180_000` — three-minute hard cap on a single sync task. Beyond that, give up.
- `config/config.default.ts:23` — `taskQueueHighWaterSize: 100` — bounded back-pressure.
- `config/config.default.ts:26-28` — `syncPackageWorkerMaxConcurrentTasks: 10` — per-instance concurrency cap on outbound upstream syncs.

---

## 2. Aliyun / Tencent mirrors

These are commercial CDN-fronted mirrors. Far less is published about their internals; what is documented:

**Aliyun (maven.aliyun.com / npmmirror).** Aliyun's open-source mirror site lists Maven, npm, Composer, PyPI, Docker, gem, and dozens of OS distributions. The Maven mirror has named buckets such as `public`, `central`, `spring`, `spring-plugin`, `gradle-plugin`, `apache-snapshots`, `grails-core`, `mapr-public` — these are not nine separate proxies, they are nine `mirrorOf` aliases that resolve to one origin (`maven.aliyun.com/repository/<bucket>`). Underlying storage is Aliyun OSS (their S3 analogue), fronted by Aliyun CDN. Aliyun is the operating partner for `registry.npmmirror.com` (cnpmcore in `all` mode, OSS storage via `oss-cnpm`).

**Tencent Cloud mirror (mirrors.cloud.tencent.com / mirrors.tencentyun.com).** Two access paths, public and Tencent-private:
- `http://mirrors.tencent.com` from public internet.
- `http://mirrors.tencentyun.com/` for Tencent VMs over the internal network, free of egress charges. This split is the same trick AWS plays with `s3.amazonaws.com` vs S3 VPC endpoints — make in-network egress free, push customers onto it. For an internal Pantera deployment this would translate to: serve from an S3 VPC endpoint, not from S3 public URLs.
- Backed by Tencent COS (their object store) and Tencent CDN.

Neither provider has published an internal architecture writeup at the level of detail PyPI/Warehouse has. The convergent fact across both: object store + CDN + the `cnpm`/`pulp`/`bandersnatch` engine of their choosing, with no novel components.

**Upstream throttling.** Both are well-known to be silently rate-limited by Maven Central. Aliyun's response, visible in their docs, is to recommend `maven.aliyun.com/repository/public` as the *first* mirror so most artifacts hit local cache; only the long tail goes upstream. This matches the Sonatype-stated reality that 75% of Maven Central traffic comes from hyperscale clouds — the clouds run their own caching mirrors and feed local-network consumers from them.

---

## 3. PyPI / Warehouse

By far the most-documented mass-scale package mirror, and the strongest reference for "how the origin should *not* be on the byte path."

**Architecture (from `warehouse/docs/dev/architecture.md`):** End users → Fastly → Backblaze B2 (primary) → AWS S3 (archive fallback). The Pyramid web app (Warehouse) talks to PostgreSQL, OpenSearch, and Redis, but **never serves package bytes**. Tarball requests are 302-redirected to URLs prefixed `https://files.pythonhosted.org/packages/...` which are CNAMEs into Fastly. From Warehouse's `architecture.md`:

> "We do not show the interactions with storage systems (B2, S3), as responses will direct clients to the storage system directly via URLs prefixed with: `https://files.pythonhosted.org/packages/...` which are served by Fastly and cached."

> "B2 is used as primary storage for cost savings over S3 for egress, as Backblaze has an agreement with Fastly to waive egress fees."

The choice of B2 over S3 is purely an egress-pricing optimisation enabled by Fastly's peering deal with Backblaze. S3 is retained as a "fallback when B2 is either down or missing file."

**Cache eviction at scale.** Warehouse uses Fastly surrogate keys. Worker processes (Celery) emit explicit purge calls on publish/yank events ("Rel(worker, fastly, 'purge URLs')" in the C4 diagram). The hot path uses long-lived caches (tarball URLs are immutable forever); the cold path purges by surrogate key when a package mutates.

> "Once a release is created, it never changes." — encoded as immutability in the data model; the `/simple/<project>/` index does change on new releases and is invalidated by surrogate-key purge.

**Storage.** Backblaze B2 + AWS S3. PostgreSQL for metadata. OpenSearch for the project search index. Redis for short-term cache and Celery task brokering. **No in-process file storage on the web tier.** The Web container's only persistent state is the database.

**Reported scale.**
- "PyPI's Fastly-sponsored CDN had a 99% cache-hit ratio, with 1.2 trillion requests, averaging ~36k requests/second" (Fastly customer page, 2023 figure). 36 kRPS sustained average means the 99th-percentile peak is roughly 4–10× that.
- "PyPI serves nearly 900 terabytes over more than 2 billion requests per day" (older Fastly material). 2 billion req/day ≈ 23 kRPS average; the 36 kRPS number is more recent and probably reflects steady growth.
- Donald Stufft's 2016 caremad post: companies were donating "$35,000 a month worth of services." The Fastly bandwidth alone was estimated at "$1.8 million dollars per month" by 2023. That's the ratio Warehouse's design captures: $1.8M of bandwidth handled by the CDN, $35k of compute on origin. **The architectural goal is to keep that ratio extreme.**

**Upstream handling.** Warehouse is a registry, not a proxy — so there is no upstream. The relevant indirection is the storage tier: when Fastly misses, it goes to B2 first; if B2 fails or is missing the file, Fastly retries against S3. This is configured in Fastly VCL, not in Warehouse application code. Warehouse itself never knows about the storage retry.

**The "pip never sees the origin" contract.** PEP 691 (JSON Simple API, May 2022) explicitly states the design constraint:
> "Due to the nature of how large repositories like PyPI cache responses, this PEP should not introduce a significantly or combinatorially large number of additional unique responses that the repository may produce."

In other words: every new API has to be designed for CDN cacheability from day one. This is a stronger statement than "we'll cache it"; it's a forcing function on protocol design.

---

## 4. USTC / TUNA university mirrors

These are full-mirror (not pull-through) sites that serve hundreds of upstreams via `rsync` and `nginx`. Less directly relevant to a Maven *proxy* but very relevant to understanding the operational profile of a CDN-less mass mirror.

**USTC (mirrors.ustc.edu.cn).** From Bojie Li's 2013 writeup and the 2024 iBug post-mortem:

2013 numbers:
- ~100 upstreams synced.
- >10 TB of data on disk (mostly Linux distributions and language ecosystems).
- "Tens of millions of HTTP accesses per day, daily traffic exceeding 4 TB."
- Hardware: 10 SATA disks, hardware RAID1, 1–2 TB each. Dual-NIC: eth0 to external, eth1 to a directly-connected disk array.

2024 numbers (iBug rebuild post):
- "Average daily egress traffic of some 36 TiB, including 10.3 TiB from rsync" over May–June 2024.
- ZFS pool migration from spinning disk → SSD partial → spinning disk back, because the working set was small enough to be page-cached in RAM and SSD was not worth the cost premium.

The operational insight buried in the 2024 post: **the working set is small.** USTC's *total* repo is hundreds of TB but the 95th-percentile request hits a much smaller set; once page cache is warm, disks matter less than RAM and per-request overhead.

**TUNA (mirrors.tuna.tsinghua.edu.cn).** Hardware sponsor: Huawei Taishan 200 (Kunpeng 920) server. Operationally similar to USTC: `nginx` reverse-cache, rsync-driven synchronization, no CDN. Both publish their sync scripts publicly on GitHub (`ustclug/`, `tuna/tunasync`).

**Synchronization toolchain.** TUNA has open-sourced `tunasync` — a Go daemon that schedules and supervises hundreds of rsync jobs. It is functionally what `bandersnatch` is for PyPI, but generalised to any upstream protocol (rsync, ftpsync, ssh-trigger, ftp-push, git).

**What they do NOT do.** No CDN. No object store. No request collapsing in front of rsync. They are educational/non-profit and accept the operational tradeoffs — including occasional spikes when a popular distro release lands. For a commercial proxy these tradeoffs are unacceptable; the value of USTC/TUNA is as a reference for "what does life look like without a CDN."

**Upstream throttling.** They mirror upstream-of-upstream where possible — e.g. USTC and TUNA peer with each other for some upstreams to avoid hammering the source. Pantera-equivalent move: a downstream Pantera could prefer a regional mirror over Maven Central directly. This is the same idea as configuring `maven.aliyun.com/repository/public` as a Maven mirror — but operationalised as a treaty between mirror operators, not just a config option.

---

## 5. Convergent patterns

The single most important section. These are the patterns that *every* mass-scale mirror has implemented, which means they are the strongest signals for what a serious Maven proxy must do.

### 5.1 Redirect, do not stream

PyPI (Warehouse) → 302 to `files.pythonhosted.org` (Fastly → B2/S3).
npmmirror (cnpmcore) → 302 to OSS via `nfsAdapter.getDownloadUrl` when storage backend supports it.
unpkg → Cloudflare Workers serve directly from cache; origin only sees first miss per version.
jsDelivr → multi-CDN serves directly; origin manifests only.
Goproxy.cn → CDN serves the `.zip`/`.info`/`.mod` files; origin just generates them on first request.
Maven Central → Cloudflare CDN serves directly; Sonatype origin is on the cache-miss path only.

**Anti-pattern that all of these avoid:** the origin proxy reading bytes from its local file storage and writing them to the response body. This puts your origin's process CPU, GC, and event loop on every byte that flows. PyPI explicitly designed this away in 2013 by moving to S3 + Fastly; cnpmcore designed it away by making `getDownloadUrl` the primary code path; jsDelivr designed it away by running on Cloudflare Workers (so there is no "origin" in the traditional sense).

**Pantera implication:** the `BaseCachedProxySlice` "store + serve" pattern, where the proxy reads bytes from `DispatchedStorage` and writes them through `Slice`'s `Content` publisher, is the wrong primitive at scale. The right primitive for the hot path is "ask the storage backend for a signed URL; redirect the client; never touch the bytes." The slow `DispatchedStorage` read pool starves out under load not because the disk is slow but because every byte goes through a Java thread.

### 5.2 Versioned URLs are immutable forever

PyPI: `/packages/<hash>/<file>` with `Cache-Control: public, immutable, max-age=365000000`.
npm: tarball URLs include version; cnpmcore relies on the fact that "npm does not allow package authors to overwrite a package that has already been published with a different one at the same version number" (Kent Dodds on unpkg).
Maven Central: same — once `1.2.3.jar` is published it is immutable; SNAPSHOTs are the exception and are explicitly opted out of long caching.

**Anti-pattern:** treating tarball/jar URLs as "maybe changes, must revalidate." This forces conditional GETs and round-trips for what is provably a cache-forever resource. Pantera's negative-cache and cooldown logic exists *because* tarball URLs should hit the cache 100% of the time after first fetch. If they aren't, something else is wrong (the URL was wrong, or the cache is being invalidated, or you have an LRU eviction problem).

### 5.3 Surrogate keys + targeted purge for mutable indexes

This is the Fastly trick PyPI uses and Maven Central (now on Cloudflare) uses. The index/manifest URLs (`/simple/<project>/`, `/<package>/`, `maven-metadata.xml`) *do* change. Rather than putting a short TTL on them, give them a long TTL and emit explicit purge calls keyed by surrogate-key on every publish/version event. The cache hit rate on indexes stays high; correctness comes from explicit invalidation, not TTL guesses.

cnpmcore implementation: `CacheService.removeCache(fullname)` deletes all Redis entries for a package on update (`app/core/service/CacheService.ts:100-107`). It's the same pattern at a different layer.

**Pantera implication:** `maven-metadata.xml` is the hot mutable artifact. It should have a long CDN-side TTL and an explicit invalidate-on-publish event, not a short revalidation TTL.

### 5.4 Pull-through is the only mode that scales for proxies

JFrog Artifactory: "Remote repositories…artifacts are not pre-fetched to a remote repository cache. They are only fetched (pulled) and stored (cached) on demand."
cnpmcore proxy mode: same.
Docker Registry pull-through: same.
Athens / Goproxy.cn: cache on first request.

The only system that does full upstream replication is `bandersnatch` for PyPI, which is explicitly for *air-gapped* mirrors that need every package. Even bandersnatch documents that "full syncs likely take hours" and that "if you set api-method to simple, PyPI caches it, so mirrors can only be as recent as 1 hour (as of 2025)." Full sync is not a performance optimisation — it's an offline-availability strategy.

### 5.5 Single-flight / request coalescing is non-optional

Nginx's `proxy_cache_lock` documentation explicitly names this:
> "If multiple clients request a file that is not current in the cache (a MISS), only the first of those requests is allowed through to the origin server. The remaining requests wait for that request to be satisfied and then pull the file from the cache."

cnpmcore implementation: `cacheAdapter.usingLock` (Redis-based) for file extraction; in `proxy` mode the package sync task is enqueued with `ctx.runInBackground` and the `taskQueueHighWaterSize: 100` plus `syncPackageWorkerMaxConcurrentTasks: 10` bound how many can be in flight.

Pantera implementation: `RequestDeduplicator` in `pantera-core`. The Pantera approach is correct in principle; what's missing is per-key bounded queueing and the explicit "if too many in flight, fail fast" back-pressure that cnpmcore has via `taskQueueHighWaterSize`.

### 5.6 Heavy users are throttled at the organization level, not by IP

Sonatype's "Tragedy of the Commons" piece, with hard numbers:
- "83% of the total bandwidth of Maven Central is being consumed by just 1% of the IP addresses."
- "75% of the total traffic to Central originates from hyperscale cloud customers."
- 2024: ~828B requests in the first 7 months; ~1.5T projected full-year.
- Their countermeasure: throttle by "organizational activity" not by raw IP, because the bad actors spread traffic across "sprawling cloud footprints to bypass IP-based throttling."

The implication for *anyone running a Maven proxy*: you ARE one of those 1%. Either you cache effectively and aggregate your org's traffic into a small Maven-Central footprint, or you become part of the problem and get throttled. **A proxy that doesn't dedupe identical concurrent requests to Central is the worst possible client.** This is the operational case for Pantera's M3 rate-limit work that already shipped.

### 5.7 Bounded queues over unbounded queues

Universally:
- cnpmcore: `taskQueueHighWaterSize: 100`, `syncPackageWorkerMaxConcurrentTasks: 10`.
- Athens Go module proxy: configurable worker pool, default bounded.
- Pull-through Docker registry: per-key in-flight cap.
- Even Nginx `proxy_cache_lock` has `proxy_cache_lock_timeout` — if the in-flight fetch doesn't return in N seconds, *let* the next request go through, don't pile them up forever.

The pattern: **single-flight is great but it's not enough by itself.** You also need (a) a cap on how many distinct keys can be in flight per upstream, (b) a per-key timeout, (c) a way for excess requests to fail fast rather than queue.

### 5.8 Reads dominate; design the writes off the byte path

Every system separates read and write:
- Warehouse: `web` (reads) + `web_uploads` (writes, larger gunicorn timeouts).
- cnpmcore: HTTP controllers (reads) + Egg.js worker schedulers (writes/syncs).
- npm: SkimDB (metadata writes/replication) + FullfatDB + tarball-on-Manta read path.
- Pantera: `pantera-io-read-%d`, `pantera-io-write-%d`, `pantera-io-list-%d` thread pools — same idea, well-implemented; this is the one place Pantera matches the reference architecture cleanly.

### 5.9 Object storage is the durability boundary

Object store (S3, OSS, COS, Backblaze B2) is the universal answer for "where the bytes actually live." Local file storage is only used by:
- Single-instance Pantera deployments (correctly, for dev).
- USTC/TUNA (because they are non-profit and don't pay for S3).
- Verdaccio (for the use case where you only have one machine).

Anything serving more than a few hundred RPS uses an object store. The reason: the object store has its own HTTP server, supports signed URLs, integrates with the CDN's egress-free peering deals (Fastly–B2, Cloudflare–R2, AWS–CloudFront within the same account). When you write through Pantera's local file storage you've taken on a job the object store wanted to do for you.

---

## Non-obvious findings

- **cnpmcore's `runInBackground(syncTask)` after a proxy stream miss** (`DownloadPackageVersionTar.ts:122-141`) is subtle: the client gets the upstream's bytes *immediately* via a `PassThrough` stream; the local cache fills *asynchronously*. So the first download has upstream latency but the second download from any client hits the local cache. Pantera's current behaviour is to serialise: fetch + store + then serve. Reversing this — serve the upstream stream directly while filing it in the background — is a big latency win for the first-miss case, and matches the cnpmcore pattern exactly.

- **The OSS `Cache-Control: max-age=0, s-maxage=60` default** (`config/config.default.ts:151`) for *uploaded* tarballs is counterintuitive. Why a 60-second CDN cache for an immutable artifact? Two reasons: (1) consistency — if the manifest revision points to a new tarball hash, you want the CDN to refresh within a minute, not a year, *for the manifest path* even though the tarball URL itself is permanent. (2) defense in depth — if someone manages to corrupt the upload, you don't want it stuck in CDN forever. PyPI's design solves this differently: tarballs are content-addressed by hash, so a corrupted tarball produces a different URL, and you don't need short TTLs.

- **Maven Central's 75% "hyperscale cloud" traffic.** This is the inverse of the "Pantera is 4–5x slower than reference" framing: it suggests reference *is* a hyperscale cloud's regional cache. So Pantera competing on raw speed against `repo1.maven.org` or `maven.aliyun.com` directly is a category error — those *are* the CDN. Pantera is competing against a local Artifactory/Nexus that runs *inside* the same VPC, where the upstream RTT is single-digit milliseconds and the CDN warmup cost has already been paid.

- **PEP 691's "do not introduce combinatorially many cache entries" constraint** is a design principle that translates directly to Maven: avoid query parameters or headers that create unique cache keys for what is structurally a static resource. Anything that varies the cache key reduces hit rate. The Pantera `cdnVaryHeader: 'Accept, Accept-Encoding'` (cnpmcore default) is correct — those two are necessary varies — but adding any more is dangerous.

- **The npm registry transition from CouchDB → Postgres + Cloudflare** is not fully documented publicly. The 2014 blog post describes CouchDB; the 2021 GitHub-owned reality is much more Postgres + a CDN. The interesting takeaway: even npm couldn't make CouchDB scale to billions of requests; it had to add an explicit CDN front and move bytes to object storage.

- **USTC's 2024 ZFS rebuild** explicitly chose spinning disk over SSD because RAM page cache dominates. For a Maven proxy serving frequently-requested artifacts, the same logic applies: there is a small hot set that should live in OS page cache, a medium warm set that lives on local SSD/object store, and a cold set that re-fetches from upstream. Throwing SSD at the cold set is wasted money.

- **jsDelivr's multi-CDN with DNS-level failover** is overkill for a regional Maven proxy but the underlying idea — "the CDN itself can fail; don't make your origin a single point of failure" — translates to having more than one upstream Maven mirror configured. If `repo1.maven.org` is slow, the proxy should be able to fall over to `repo.maven.apache.org` or `maven.aliyun.com` without restart.

---

## What I could not determine

- **Exact request volumes for `registry.npmmirror.com`.** Chinese-language sources cite cumulative numbers (e.g. 20 billion since 2014) but no current per-day or per-second figures. The cnpmcore repo doesn't publish operational metrics. I am confident it is >10kRPS sustained based on its position in the global npm ecosystem but cannot cite a precise number.

- **Maven Central's CDN cache hit ratio.** Sonatype published the *traffic* numbers (1.5T/year, 1% IP / 83% bandwidth) but not the CDN hit ratio. Inferred from the Cloudflare integration that it is comparable to PyPI's 99%, but this is inference.

- **Whether Aliyun or Tencent's Maven mirrors do request coalescing internally.** Their docs don't describe their proxy implementation; given they recommend Nginx + their CDN in user-facing docs, my best guess is "Nginx `proxy_cache_lock` plus CDN-level coalescing" but I cannot confirm.

- **Whether Maven Central's switch from Fastly to Cloudflare is complete or partial.** Their public status page lists Cloudflare; older Sonatype docs mention Fastly. They may run both for redundancy. No public post-mortem of the transition is available.

- **Operational cost / latency budget at npmmirror.** cnpmcore is open-source but Aliyun's deployment of it includes Aliyun-internal customisation (OSS integration, internal CDN, internal monitoring) that isn't in the public repo. I can describe the algorithm; I cannot quote the SLO.
