# Changelog

## Version 2.3.0

### 🌟 New features

- **Docker registries implement the OCI 1.1 Referrers API** (`GET .../referrers/<digest>`, `subject`/`OCI-Subject`), so cosign, notation, `oras attach`/`discover`, and SBOM attachment work against hosted repos. ([@aydasraf](https://github.com/aydasraf))
- **S3-API-compatible object stores** — the S3 backend now targets MinIO, Cloudflare R2, Backblaze B2, Wasabi, Ceph/RADOS, or GCS's S3-interop endpoint via `endpoint`/`region`/`path-style`, with a configurable `storage-class` and a backend-agnostic `BlobStore`/`Presigner` layer. ([@aydasraf](https://github.com/aydasraf))
- **Docker hosted repositories** support manifest/blob `DELETE`, a `docker-group` that aggregates `tags/list`/`_catalog` across members, and multi-chunk `PATCH` blob uploads. ([@aydasraf](https://github.com/aydasraf))
- **npm local repositories** sign published packages (ECDSA, served at `/-/npm/v1/keys`), store `--provenance` attestations (`/-/npm/v1/attestations/<spec>`), and support `npm token`/`profile` and single-version fetches. ([@aydasraf](https://github.com/aydasraf))
- **PyPI modern simple-API surface** — PEP 658 `.metadata`, PEP 700 `versions[]`/`size`, PEP 714 `core-metadata`, the legacy `/pypi/<pkg>/json` API, and RFC 9110 `Accept` q-value negotiation. ([@aydasraf](https://github.com/aydasraf))
- **The Go proxy forwards checksum-database (`sumdb`) lookups** upstream (cached, offline-safe), so `go get` can keep `GOSUMDB` on; local/group repos answer an honest `404`. ([@aydasraf](https://github.com/aydasraf))
- **Maven/Gradle release immutability** (per-repo `releaseImmutable`, re-deploy → `409`) and HTTP `Range`/`206` artifact downloads. ([@aydasraf](https://github.com/aydasraf))
- **Cross-node invalidation for `cache.mode: index`** — a write/delete on one node drops peers' stale disk-cache entries over pub/sub instead of waiting out the TTL. ([@aydasraf](https://github.com/aydasraf))
- **Admin UI for the Maven PGP keyring** — list/add/remove the trusted signing keys backing `verifyPgp`. ([@aydasraf](https://github.com/aydasraf))
- **Presigned direct-download for all formats** — a per-repo `download-mode` (`stream`/`redirect`/`auto`) `302`s the immutable artifact byte to a time-limited object-store URL for Docker, npm, PyPI, conda, Go, Gem, RPM, Helm, Debian, generic files, Maven/Gradle, NuGet, Composer, and Hex; metadata is never redirected, and streaming stays the default and automatic fallback. ([@aydasraf](https://github.com/aydasraf))
- **Forwarded-header trust is now a DB-backed, hot-reloadable admin setting** — `trust_forwarded_headers` (`GET`/`PUT /api/v1/admin/client-base-url-settings`, admin UI Settings page) replaces the old env-only `PANTERA_TRUST_FORWARDED_HEADERS` flag, which is now only the fallback tier; a change applies to the very next request, no restart, broadcast across a cluster. ([@aydasraf](https://github.com/aydasraf))

### ⚡ Performance

- **Opt-in index-accelerated S3 cache (`cache.mode: index`)** — an in-memory `StorageIndex` answers `exists`/`metadata`/`list` and a disk hit's `value()` with zero S3 round-trips (vs 1–2 HEADs per hit), single-flighting cold misses. ([@aydasraf](https://github.com/aydasraf))
- **`cache.mode: index` async durable write-back** — `save()` is acked from local disk and uploaded to S3 by a bounded, retrying, crash-replaying pool (a saturated queue `503`s; `cache.write-through: true` restores synchronous), with write-back/eviction metrics + a `PanteraWriteBackQueueNearCapacity` alert. ([@aydasraf](https://github.com/aydasraf))
- **`cache.mode: index` byte-bounded eviction** — an in-memory byte counter with LRU/LFU watermark eviction (`cache.max-disk-bytes`, `cache.eviction-*`), no `Files.walk`, sharded across a 2-level fan-out. ([@aydasraf](https://github.com/aydasraf))
- **PyPI legacy JSON API and Composer repository root are TTL-cached** with single-flight and serve-stale, so an upstream blip no longer breaks `poetry`/`composer` resolution of cached packages. ([@aydasraf](https://github.com/aydasraf))
- **PyPI `/simple/` cooldown-filtered index is cached, not recomputed per request** — materialised into the shared cooldown cache (self-busting on content change, reusing every existing invalidation hook), so a hot package isn't re-filtered each time. ([@aydasraf](https://github.com/aydasraf))
- **Proxy cache commits and S3 size-unknown uploads stream in bounded chunks** instead of buffering whole artifacts on heap (an OOM vector), with integrity and rollback preserved. ([@aydasraf](https://github.com/aydasraf))

### 🔧 Bug fixes

- **Clustered deployments no longer drop proxy index/audit records** — the per-node index/audit processors (Maven, npm, PyPI, Go, Composer) ran through cluster-shared Quartz and could fire on the wrong node; they now run on each node's own scheduler. ([@aydasraf](https://github.com/aydasraf))
- **Docker manifest `GET`/`HEAD` honour `Accept`** — a `406` when the client accepts none of the manifest's media types (wildcards respected), instead of an unparseable body. ([@aydasraf](https://github.com/aydasraf))
- **Docker proxy caches manifests per negotiated `Accept`-variant** — the client's `Accept` is forwarded upstream and keys the cache, so a multi-variant tag no longer cross-serves the wrong media type. ([@aydasraf](https://github.com/aydasraf))
- **npm cooldown no longer hides an unblocked version behind a stale `304`** — the packument `ETag` is computed from the filtered bytes served, not the raw upstream hash. ([@aydasraf](https://github.com/aydasraf))
- **Go hosted `@latest` resolves the correct version** — by semver, not lexicographic `.info` filename order (so `v0.10.0` > `v0.9.0`). ([@aydasraf](https://github.com/aydasraf))
- **Maven `HEAD` carries `Content-Length`/`Last-Modified`, and group metadata checksums match the served bytes** (recomputed over the served `maven-metadata.xml`). ([@aydasraf](https://github.com/aydasraf))
- **PyPI twine uploads are validated** — client `sha256_digest` verified, re-uploading an existing filename rejected with `409`. ([@aydasraf](https://github.com/aydasraf))
- **Docker pagination and digest errors are spec-correct** — `Link: rel="next"` on truncated listings, `DIGEST_INVALID` on a mismatched PUT-by-digest, `204` on upload cancel. ([@aydasraf](https://github.com/aydasraf))
- **Go docs no longer recommend a no-op flag** — `GOSUMDB=off`/scoped `GOPRIVATE` instead of the removed `GONOSUMCHECK`. ([@aydasraf](https://github.com/aydasraf))
- **npm `dist-tag`, `deprecate`, and single-version `unpublish` work** for published packages (dist-tags persist in a durable sidecar; unpublish removes the version). ([@aydasraf](https://github.com/aydasraf))
- **`npm search` returns real results** — `/-/v1/search` queries the shared artifact index instead of an always-empty in-memory one. ([@aydasraf](https://github.com/aydasraf))
- **Standalone Composer proxy bootstraps `composer install` again** — the root `/packages.json` is served and every upstream URL is rewritten back to Pantera (no cache/cooldown/auth bypass). ([@aydasraf](https://github.com/aydasraf))
- **PyPI yank/unyank is visible to clients** — flipping yank status regenerates the served simple index. ([@aydasraf](https://github.com/aydasraf))
- **Go dependency resolution survives upstream outages** — `@v/list`/`@latest` are TTL-cached and serve last-known-good when upstream is unreachable. ([@aydasraf](https://github.com/aydasraf))
- **Clustered deployments no longer lose artifact-event data** — the per-node event drain no longer runs through cluster-shared Quartz (which could delete another node's job). ([@aydasraf](https://github.com/aydasraf))
- **The proxy cache no longer fetches upstream on a cache hit** — `FromStorageCache`'s fetch supplier is deferred and only runs on a confirmed miss. ([@aydasraf](https://github.com/aydasraf))
- **Hosted `maven-metadata.xml` no longer drops versions under concurrent deploys** (per-GA lock with bounded jittered retry), and proxy artifact responses carry the full conditional/validator header set. ([@aydasraf](https://github.com/aydasraf))
- **npm `ping`, `GET /npm`, `HEAD`, and proxied `search`/`dist-tag ls` work, and `npm audit` is honest** (real zero-vuln / empty bulk-advisory response). ([@aydasraf](https://github.com/aydasraf))
- **Composer catalog endpoints and `HEAD` no longer `404`** — `available-packages.json`/`packages/list.json` served, cold dist fetches single-flighted, conditional `If-Modified-Since` honoured. ([@aydasraf](https://github.com/aydasraf))
- **PyPI proxy no longer drops `data-yanked` or leaks blocked-version metadata** — `data-yanked` carries through the cooldown filter, and `/pypi/<pkg>/<version>/json` is filtered rather than passed through. ([@aydasraf](https://github.com/aydasraf))
- **Go `@v/list` cooldown evaluation is bounded** (newest 50) and module paths are `!`-decoded for the DB/index/audit trail (storage keys stay escaped). ([@aydasraf](https://github.com/aydasraf))
- **npm proxy conditional refresh works and cooldown-filtered metadata stays coherent** — the ETag round-trips, a changed refresh invalidates the filtered envelope, and prerelease tarballs key correctly. ([@aydasraf](https://github.com/aydasraf))
- **Composer bounds cooldown evaluation** (newest 50/package) and returns a bodiless `304` for a client's `If-Modified-Since`. ([@aydasraf](https://github.com/aydasraf))
- **`SingleFlight` no longer risks rejoining a just-finished load** — invalidation and completion happen in one callback, so eviction precedes the caller observing completion. ([@aydasraf](https://github.com/aydasraf))
- **corepack works against npm proxy and group repositories** — `GET /<pkg>/<version>` (and `/<pkg>/<tag>`, including `/latest`) now returns a full version manifest instead of a `{name, modified}` stub that `200`d with no `dist`, which crashed corepack's `dist.tarball` destructuring. ([@aydasraf](https://github.com/aydasraf))
- **npm tarball URLs are rooted at the repository the client addressed** — a group repository no longer hands out its winning member's URLs, so corepack and other clients that reject responses from an unexpected registry now work through npm groups and proxies. ([@aydasraf](https://github.com/aydasraf))
- **An npm proxy repository with no configured `url:` no longer throws on its first request** — repository construction read the throwing config accessor even though the client-facing base is optional there and derives from the request when unset. ([@aydasraf](https://github.com/aydasraf))
- **`GET /<pkg>/latest` on an npm proxy no longer returns raw upstream tarball URLs** — the manifest is rewritten to point back at Pantera, so the download goes through Pantera's cache and audit trail instead of bypassing it (and works in air-gapped deployments). ([@aydasraf](https://github.com/aydasraf))
- **npm scoped-package packuments (`GET /@scope/pkg`) no longer `404` on local repositories** — the single-version route regex ambiguously matched a bare scoped package name as package+version; routing now mirrors the parser's own disambiguation, so `/@scope/pkg` serves the packument and `/@scope/pkg/<version>` still serves the version manifest. ([@aydasraf](https://github.com/aydasraf))
- **`HEAD` on an npm proxy/group tarball no longer poisons the shared negative cache for every subsequent `GET`** — the tarball route now accepts `HEAD`, and a probe response can never write a negative-cache entry, closing a path where any HTTP proxy, scanner, or client issuing a routine existence check durably broke that exact artifact for every other client. ([@aydasraf](https://github.com/aydasraf))
- **`npm ping` and the registry-root endpoint now work on npm proxy and group repositories, not just local** — both answer directly from Pantera without an upstream round-trip; the registry-root route previously matched only the coincidence of a repository literally named `npm`. ([@aydasraf](https://github.com/aydasraf))
- **npm proxy/group `404`s for an unresolved package version carry an honest JSON body** (`{"error":"version not found: ...","package":"..."}`) instead of an empty one, matching local repositories. ([@aydasraf](https://github.com/aydasraf))
- **A `HEAD` probe against any group repository can no longer poison the negative cache for every subsequent `GET`** — `GroupResolver`, the shared resolution path for every group type (Maven, npm, PyPI, Docker, Composer, Go, Gem, generic files), wrote a negative-cache entry off a member's `404` regardless of request method; only a `GET`'s `404` is now trusted, closing the same class of bug already fixed for the npm proxy above. ([@aydasraf](https://github.com/aydasraf))
- **`PANTERA_UPSTREAM_BREAKER_*` env vars are no longer silently shadowed by the migration that seeds their defaults, and are honoured on DB-less boots too** — V136 unconditionally wrote `upstream_breaker_*` rows into `auth_settings`, so the DB row was always present and an operator's env vars were ignored from the moment they upgraded; a follow-up migration removes only the rows still holding their untouched default (an admin's own customised value is left alone), and `UpstreamBreakerSettingsLoader` now installs unconditionally at boot like every other DB→env→default settings loader. ([@aydasraf](https://github.com/aydasraf))
- **`PANTERA_CIRCUIT_BREAKER_*` env vars (the group-member breaker) are no longer silently shadowed by the migration that seeds their defaults, and are honoured on DB-less boots too** — the same V136 bug, but for V122's `circuit_breaker_*` rows: a follow-up migration removes only the rows still holding their untouched default, and `CircuitBreakerSettingsLoader` now installs unconditionally at boot instead of only when a shared `DataSource` is configured. ([@aydasraf](https://github.com/aydasraf))

### 🔒 Security

- **npm registry keys and user/token records are never served over HTTP** — a reserved-key guard `404`s `.registry-keys.json`, `_users/`, and `_tokens/` ahead of any content route, so the registry's ECDSA signing key and user/token records can't be fetched. ([@aydasraf](https://github.com/aydasraf))
- **Internal client-base headers can no longer be spoofed by a client** — `X-Pantera-Client-Base` and `X-Original-Path` are stripped from inbound requests at the edge, and `X-Forwarded-Proto`/`-Host`/`-Prefix` are honoured only when explicitly enabled via the `trust_forwarded_headers` setting (default `false` — see the New features entry above), closing a path where a crafted request could make Pantera emit and cache tarball URLs pointing at an attacker-controlled host. ([@aydasraf](https://github.com/aydasraf))
- **A new `client_base_host_allowlist` admin setting closes the remaining `Host`-header path to the same class of attack** — even with forwarded headers untrusted, a raw client-supplied `Host` was still used verbatim to build cached absolute URLs (e.g. `curl -H 'Host: evil.tld' <repo>/pnpm` poisoning `dist.tarball`); a configured allowlist now rejects a non-matching `Host` exactly like an absent one. Empty/unset stays permissive by design (an existing deployment must not suddenly reject every `Host` on upgrade) and logs a startup `WARN`. ([@aydasraf](https://github.com/aydasraf))
- **Maven/Gradle PGP and checksum verification are enforced, not inert** — `verifyPgp` verifies `.asc` against an admin keyring (`/api/v1/admin/pgp-keys`): proxy fetch fails closed (cache entry removed); a hosted primary is quarantined (unresolvable, excluded from `maven-metadata.xml`) until a matching signature verifies in either upload order; `.sha1`/`.md5` sidecars are verified against the stored primary. ([@aydasraf](https://github.com/aydasraf))
- **Composer proxied dist archives are verified before caching** — SHA-1 checked against the packument `dist.shasum`, a mismatch rejected with `502` and an empty cache; hosted publish writes `dist.shasum` for downstream verification. ([@aydasraf](https://github.com/aydasraf))
- **Docker proxy repositories verify cached blob integrity** — the cache-store path re-hashes the streamed bytes and rejects a digest mismatch. ([@aydasraf](https://github.com/aydasraf))
- **PyPI yank/unyank enforces per-repository authorization** — requires write permission, denies with `403` (fail-closed, no mutation). ([@aydasraf](https://github.com/aydasraf))
- **Token revocation survives a node restart in a cluster** — revocations are DB-durable, hydrated on boot, and the pub/sub broadcast carries the token's real remaining lifetime. ([@aydasraf](https://github.com/aydasraf))
- **Authorization and resilience-threshold changes propagate across the cluster** — role/permission and circuit-breaker/bulkhead edits broadcast to every node with a bounded TTL backstop. ([@aydasraf](https://github.com/aydasraf))
- **Removed a non-functional Go archive integrity claim** — the `.ziphash` sidecar the GOPROXY protocol doesn't define (genuine verification lands with the `sumdb` proxy). ([@aydasraf](https://github.com/aydasraf))

## Version 2.2.4

### 🔧 Bug fixes

- **Maven/Gradle proxy repositories no longer return `502` for directory listings and version-range coordinates.** On the uncacheable-path fetch (`fetchDirect` — taken for directory-style requests with a trailing `/` and Gradle/Ivy dynamic version ranges such as `…/[,7.2084)/…jar`), an upstream `404` subscribed the single-subscriber upstream response body **twice** — once to seed the negative cache, once to build the `404` — throwing `IllegalStateException: JettyContentSourcePublisher is single-subscriber`. The error funnel collapsed that into a `503`, which the group/race layer surfaced to clients as `502`. The body is now drained **once** (mirroring the cache-first `handle404` path): the negative cache is seeded and the `404` returned from a single subscription. This was the single largest source of proxy `502`s in production (present since 2.2.0).
  ([@aydasraf](https://github.com/aydasraf))
- **PyPI and npm proxies no longer emit spurious `404`s / errors when a cached entry is evicted mid-read.** An entry present at the `exists()` check could be evicted (DiskCache LRU / rollback) before the value/metadata read, throwing `ValueNotFoundException` (wrapping `IOException` → `NoSuchFileException`). Three fixes: the shared read-through cache's TOCTOU recovery now walks the full cause chain and recognises this wrapped shape (it previously matched only a top-level or one-level `NoSuchFileException`, mislabelling a recovered race as a read failure); the PyPI simple-index path refetches from upstream on such a race instead of returning `404`; and the npm background package-indexer treats it as a benign skip at `DEBUG` rather than `ERROR` (the download had already been served to the client).
  ([@aydasraf](https://github.com/aydasraf))
- **Authentication-outcome logs are stable and groupable again.** The `Failed to authenticate user` / `Successfully authenticated user` messages embedded the auth filter-chain's `toString()` — including a per-instance object hashcode — directly in the `message` field, defeating log aggregation across a fleet. The message is now a fixed string; the provider detail remains available in the dedicated `event.provider` field.
  ([@aydasraf](https://github.com/aydasraf))
- **Access logs record `http.request.method` on every request.** The field was emitted only on the internal-error path, never on normal responses, so 4xx/5xx access lines (e.g. `405`s) could not be told apart by verb in Kibana. The method is now emitted for all access-log records.
  ([@aydasraf](https://github.com/aydasraf))

## Version 2.2.3

### 🔧 Bug fixes

- **Group repositories no longer serve stale `404`s for packages that exist.** A negative-cache entry recorded during a transient window could outlive the artifact becoming available, so `GET /npm_group/<pkg>` (and other group types) kept returning `404` for a package with versions already in the index until the entry was manually invalidated. Three causes are fixed: (1) package-level **metadata** requests (npm packument, `maven-metadata.xml`, PyPI simple index, Go `@latest`) — whose absence is inherently dynamic — are no longer hard-negative-cached, only immutable versioned coordinates are; (2) the **proxy fetch-and-store** path now invalidates the negative caches when an artifact lands in the index (previously only synchronous hosted publishes did, so proxy-ingested artifacts stayed shadowed by a stale `404`); and (3) an npm proxy that launders an upstream rate-limit (`403`/`429`) into a `404` for its multi-remote fall-through now marks that `404` non-authoritative so a fronting group does not cache it. The artifact-index negative tier is invalidated on the same path.
  ([@aydasraf](https://github.com/aydasraf))

## Version 2.2.2

### 🔧 Bug fixes

- **`docker login` (and every package manager) accepts API tokens as the Basic password again.** Registry and package-manager clients can only submit credentials via Basic auth, so API tokens arrive as the password — and the 2.2.0 authoritative-provider hardening rejected the token string against the account's password hash before any token-aware provider could validate it, locking token-based CI out of Docker, Maven, npm, and PyPI repositories. Token-shaped Basic passwords are now validated as JWTs first (bound to the claimed username, with full revocation/expiry checks) and fall back to the regular password check; the bare Docker `/v2/` ping accepts Bearer tokens consistently with all other registry endpoints; and the blocking credential check is kept off the Vert.x event loop.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown is now exclusively enforced by proxy repositories — groups never run a redundant second filter.** The group-level cooldown metadata filter age-gated every version flowing through Maven/Gradle groups — including releases just published to the organisation's own hosted members, which became unresolvable through the group for the full cooldown window. An initial fix made the group-level filter winner-aware (skip hosted winners), but it still ran a second cooldown pass on proxy winners and recorded any block under the **group's own** repo identity rather than the winning member's — polluting the cooldown admin view with phantom group-level entries. Maven/Gradle groups no longer run cooldown themselves: each `-proxy` member already filters its own metadata and records blocks under its own identity; the group only relays the winning member's response verbatim.
  ([@aydasraf](https://github.com/aydasraf))
- **npm `dist-tags.latest` is no longer rewritten to a version the package author never promoted.** When the cooldown filter blocked fresh versions it recomputed `latest`, and two things went wrong: (a) a fixed keyword list (alpha, beta, rc, …) misclassified unknown prerelease qualifiers — e.g. nx's `23.1.0-pr.36127.e594f53` — as stable, and (b) the surviving pointer was chosen by publish date, so a lower-major backport released minutes after a higher stable (nx's `22.7.6` after `23.0.1`) could win `latest`. Now: an **unblocked upstream `latest` is preserved verbatim** — cooldown only re-points `latest` when its own target is blocked, and then to the highest surviving stable **at or below** it by the format's version comparator (never a newer major the author had not promoted, never a prerelease). Prerelease detection is format-aware — for npm, any dash-suffixed version is a prerelease per SemVer (build metadata `+…` alone is not); Maven keeps the keyword heuristic (dash suffixes are often classifiers like `-jre`/`-android`) and its `<release>` pointer is still recomputed from the filtered list.
  ([@aydasraf](https://github.com/aydasraf))
- **Malformed version-range paths no longer count as group-member failures.** A misconfigured Gradle dependency can request a Maven version range as a literal artifact path (e.g. `graphql-utils-[,7.2079-test-1).jar`); the range metacharacters `[ ] ( )` are never a valid coordinate, but the group walk forwarded them to members, which returned `502` on the unescaped brackets — and the walk then recorded that as a member failure, feeding the group-member circuit breaker on fabricated evidence and inflating all-members-unavailable `503`s. Such paths are now answered with `404` at the group entrypoint, before any member is queried or upstream contacted. Scoped to Maven/Gradle groups only — the same entrypoint is shared by every group type, and a `file`/PHP group upload can legitimately contain these characters (e.g. `backup[v2].zip`).
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown's `latest` recompute could still promote a newer major in a rare edge case.** When a package's designated `latest` was blocked and no earlier release on the same line survived (e.g. the first release on a new maintenance branch), the fallback fell through to the newest unblocked version overall — crossing into a major the author never promoted as `latest`. The ceiling is now absolute in every fallback tier: with nothing to fall back to at or below it, the pointer is left untouched (self-corrects once the block expires) rather than crossed.
  ([@aydasraf](https://github.com/aydasraf))
- **Consolidated Basic-auth's three duplicate thread pools into one, and fixed a token-validation-failure log losing its trace correlation.** `BasicAuthScheme`, `CombinedAuthScheme`, and `CombinedAuthzSlice` each maintained an independent unbounded thread pool for the identical blocking password-check workload. Separately, the warn log emitted when Basic-auth token validation errored ran on an async hop that bypassed this codebase's MDC-propagation convention, so its `trace.id`/`client.ip` could reflect an unrelated request rather than the one that actually failed; both now correctly correlate.
  ([@aydasraf](https://github.com/aydasraf))

### 🔒 Security

- **Cooldown request attribution is no longer read from thread-local MDC.** The metadata filter's per-version cooldown evaluation attributed the requesting user via `MDC.get("user.name")` on a shared pipeline worker thread, which could be stale or unbound and attribute the request to whichever user last used that thread. Attribution now flows from the request-captured owner threaded through the filter context. (The primary `artifact_publish`/`access`/`delete`/`resolution` audit records were already sourced from the request-captured owner and were unaffected — this hardens the secondary cooldown-request attribution.)
  ([@aydasraf](https://github.com/aydasraf))
- **php-proxy repositories now validate credentials.** Requests carrying an `Authorization` header bypassed the deny-by-default anonymous gate (which only challenges credential-less requests) into a chain that never authenticated them — any non-empty credentials could read through a php-proxy. The php-proxy chain now carries the same combined Basic/Bearer authorization wrapper as every other proxy type, with read-permission enforcement.
  ([@aydasraf](https://github.com/aydasraf))

## Version 2.2.1

### 🔧 Bug fixes

- **Directory-listing pages render styled again under the hardened security headers.** The 2.2.0 `Content-Security-Policy: default-src 'self'` blocked the browse pages' own inline CSS/JS, leaving listings unstyled with dead sort controls. Browse responses now declare a per-route CSP that allowlists exactly their inline style/script blocks by SHA-256 hash, and the sort controls bind their listeners CSP-compatibly instead of using inline `onclick` attributes.
  ([@aydasraf](https://github.com/aydasraf))

## Version 2.2.0

### ⚠️ Breaking changes
  
- **Sequential-only group resolution.** Members are tried in declared order; the first 2xx response wins, and remaining members are consulted only on 404. Parallel fanout has been removed — sequential is now the **only** mode (Nexus / JFrog style). The legacy `members_strategy` YAML key is silently ignored regardless of value (a one-time WARN per group is emitted at boot, naming the deprecated key, then the key is dropped from the effective config). Order `members:` lists with the most-likely-to-have-the-artifact entry first (typically hosted before proxy).
  ([@aydasraf](https://github.com/aydasraf))
- **No metadata union-merge across group members.** `maven-metadata.xml` and `packages.json` group lookups now return the first member's 200 response verbatim. Configs that need union semantics should split into multiple group repos.
  ([@aydasraf](https://github.com/aydasraf))
- **Speculative prefetch removed.** The dependency-prefetch subsystem and its admin surface are gone. The prefetch admin UI panel, the `GET /api/v1/repositories/{name}/prefetch/stats` REST endpoint, and every `prefetch.*` runtime setting are removed; a database migration drops any persisted prefetch settings rows.
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- **DB-backed publish-date registry** replaces per-adapter cooldown inspectors. Built-in upstream sources cover Maven Central and Go modules (npm / PyPI / Packagist / RubyGems sources were dropped before final 2.2.0 — see Performance below — because their release dates are already inline in the upstream metadata).
  ([@aydasraf](https://github.com/aydasraf))
- **Outbound-traffic observability.** Per-upstream-host request counts, error counts, and the outbound/inbound amplification ratio are exported as Prometheus metrics. Recording rules and Grafana-compatible alert definitions ship under `pantera-main/docker-compose/prometheus/rules/`.
  ([@aydasraf](https://github.com/aydasraf))
- **Official Docker images on GHCR.** Releases publish multi-arch (`amd64` + `arm64`) images for both the backend (`ghcr.io/auto1-oss/pantera`) and the management UI (`ghcr.io/auto1-oss/pantera-ui`, nginx + static bundle); release artifacts (JAR, full distribution, UI bundle) ship with `SHA256SUMS` and build-provenance attestations.
  ([@aydasraf](https://github.com/aydasraf))
- **Observability pack for the perf surface.** Two new Grafana dashboards ship under `pantera-main/src/main/resources/grafana/` — one for the per-host upstream circuit breaker (state, trip counts, fast-fail rate, time-since-last-trip) and one for proxy-phase latency (stacked p99 by phase and repo, the canonical view for cold-bench debugging). Recording-rule alerts cover the 2.2.0 perf-pack (circuit-breaker-open, bulkhead overflow, sustained upstream 429s, low conditional-GET hit rate) with matching runbooks under `docs/runbooks/`.
  ([@aydasraf](https://github.com/aydasraf))
- **Per-repo anonymous-access controls.** A new `anonymous_read` / `anonymous_write` flag per repo decides whether unauthenticated requests get challenged. **Deny-by-default for every repo type** — admins explicitly opt in (e.g. `anonymous_read: true` on a public OSS-mirror proxy). Absent credentials return `401` with a `WWW-Authenticate: Basic realm="pantera"` header so every package manager prompts. The admin UI exposes both flags as checkboxes on the per-repo "Access" card, plus a bulk-update action on the admin repository-management view for rolling the policy across many repos at once (audit-logged with a shared `bulk_request_id`).
  ([@aydasraf](https://github.com/aydasraf))
- **Trace propagation completed across every async hop on the request path.** A single `trace.id` now connects an inbound request to its outbound HTTP calls, the Valkey pub/sub envelope (v2; v1 still parsed for rolling-deploy compatibility), Quartz job execution, and every internal `CompletableFuture` continuation. `transaction.id` is now a first-class MDC key. Audit-log entries inherit the originating request's `trace.id` so an artifact upload and its HTTP session join in Kibana with a single field.
  ([@aydasraf](https://github.com/aydasraf))
- **Structured artifact audit taxonomy.** Four consistent events — `artifact_publish`, `artifact_access`, `artifact_delete`, `artifact_resolution` — across all 15 format adapters, each carrying `client.ip` and `trace.id`. Cooldown-blocked requests are audited for the first time (`event.outcome=failure`, `event.reason=cooldown_active`), and artifact sizes log as plain integers.
  ([@aydasraf](https://github.com/aydasraf))
- **Both circuit breakers are admin-tunable at runtime.** The outbound HTTP breaker (per upstream `scheme://host:port`) joins the group-member breaker as a DB-backed setting: failure-rate threshold, minimum calls, window length, and backoff seed/cap are editable from the admin Settings page as two clearly separated sections, apply without restart, and fall back to `PANTERA_UPSTREAM_BREAKER_*` env vars or hardcoded defaults when unset.
  ([@aydasraf](https://github.com/aydasraf))
- **Grafana dashboards cover every exposed metric.** Bulkhead permits/overflows, upstream request/latency/429/rate-limit gates, cache operation latency/dedup/errors, storage executor pools, events queues, Jetty connection pool, group resolution latency, and cooldown evaluation latencies all gained panels; stale references to renamed metrics were fixed, and the circuit-breaker and proxy-phase dashboards are now provisioned into the local compose stack.
  ([@aydasraf](https://github.com/aydasraf))
- **Latency quantile panels render.** `PANTERA_METRICS_PERCENTILES_HISTOGRAM` now publishes curated SLO bucket ladders — 16 boundaries up to 30 s for control-plane timers, 18 up to 20 min for transfer timers whose durations include large-artifact body streaming — instead of Micrometer's ~70 auto buckets (~6× fewer series at equal dashboard value). The local compose stack enables the flag so every p95/p99 panel works out of the box.
  ([@aydasraf](https://github.com/aydasraf))
- **Admin UI: unified save bar on System Settings.** One sticky **Save changes (N)** bar replaces nine per-section save buttons; changed sections show as chips flagged hot-reload vs. restart-required, with one-click save-all and discard. The Cooldown admin view gains an in-page refresh that preserves filters and pagination.
  ([@aydasraf](https://github.com/aydasraf))

### ⚡ Performance

- **Maven and Gradle cooldown enforcement.** Cooldown rules now block fresh `.jar`/`.pom`/`.module` admissions and strip blocked versions from `maven-metadata.xml` responses, including rewrite of `<latest>` and `<release>`. SNAPSHOT artifacts are evaluated per-timestamp.
  ([@aydasraf](https://github.com/aydasraf))
- **Per-repo-name and per-repo-type cooldown overrides** (enable/disable + minimum_allowed_age) are honored everywhere with `per-repo-name → per-repo-type → global` precedence. Operator changes via the admin UI take effect for both admission decisions and metadata filtering.
  ([@aydasraf](https://github.com/aydasraf))
- **SNAPSHOT cooldown knob** — separate `snapshots:` block under `cooldown:` (global) and `cooldown.repo_names.<repo>` for setting a stricter (or laxer) cooldown for SNAPSHOT artifacts than for releases. Configurable from the admin Settings page (global) and RepoEditView (per repository), permission-gated on `api_cooldown_permissions:write`.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown filter no longer fires per-version upstream HEAD calls.** Inline release dates are read from the upstream metadata response directly; ecosystems without inline dates (Maven/Gradle) fall back to the cached publish-date registry. The 1.7s per-version upstream timeout that `npm install` fan-outs used to multiply is gone.
  ([@aydasraf](https://github.com/aydasraf))
- **Removed unused publish-date HTTP sources** for npm/PyPI/Composer/Gem. Their release dates are already inline in the upstream metadata response. Go and Maven head-fallback sources are kept.
  ([@aydasraf](https://github.com/aydasraf))
- **Filtered metadata response carries Pantera-computed `ETag` and `Last-Modified`**; upstream validators no longer leak through the transform. Inbound `If-None-Match` matching the computed ETag returns 304.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown configuration is fully DB-driven.** The legacy per-repo YAML `cache.cooldown.enabled` key is no longer consulted; `cooldown.enabled`, `cooldown.repo_types.<type>`, and `cooldown.repo_names.<repo>` from the admin UI / DB are the single source of truth.
  ([@aydasraf](https://github.com/aydasraf))
- **`CachedNpmProxySlice`** no longer emits body-less 200 responses on the tarball metadata-cache hit path.
  ([@aydasraf](https://github.com/aydasraf))
- **Outbound HTTP/1.1 with a keep-alive connection pool (the Nexus / JFrog Artifactory pattern).** The v2.2.0 perf-pack briefly defaulted the upstream client to HTTP/2; during the release stabilisation pass we hit Jetty issue [#12776](https://github.com/jetty/jetty.project/issues/12776) (ByteBufferPool corruption on stream cancel), which manifested as `EOFException: Stream has been reset` bursts against `proxy.golang.org` under multi-module `go get`. 2.2.0 final ships outbound HTTP/1.1 only — every artifact registry Pantera proxies accepts HTTP/1.1 with keep-alive, so HTTP/2 is unnecessary and actively unsafe for this workload. The per-destination keep-alive pool size is sourced from `meta.http_client.max_connections_per_destination` in `pantera.yml`; there is no runtime protocol selector.
  ([@aydasraf](https://github.com/aydasraf))
- **Response-body streaming refactor: eager pre-drain + bounded staging buffer.** `JettyClientSlice` now bridges Jetty's `Content.Source` to Reactive Streams through `JettyContentSourcePublisher`, replacing the old `UnicastProcessor + StreamingDemander` pattern. The bridge pre-drains chunks on the Jetty I/O thread, copies each into a heap `ByteBuffer`, and releases the pooled buffer back to the `ArrayByteBufferPool` immediately — that is what lets HTTP/1.1 keep-alive reclaim connections even when downstream never reads the body. The staging queue caps at 64 chunks; subscribers consume from staging first, then pull further chunks from the source. The previous `UnicastProcessor` had an unbounded internal queue (no end-to-end backpressure) and the per-chunk heap copy through `StreamingDemander` was the single hottest allocation site under load.
  ([@aydasraf](https://github.com/aydasraf))
- **Adaptive per-repo bulkhead.** The fixed-permits bulkhead has been replaced by a controller that grows or shrinks the per-repo permit count from observed p99 latency. Defaults: `initial_permits=40`, `min_permits=5`, `max_permits=100`, `target_p99_ms=500`, `window_seconds=5`, `ramp_up_step=+4`, `ramp_down_factor=×0.5`, master switch `adaptive=true` (burst-friendly defaults via `V135`; manual admin overrides preserved). Every parameter is a DB-backed runtime setting editable from the admin Settings UI — no restart needed, and every edit is written to `audit_log` with old + new values. `pantera_bulkhead_permits_current` joins the existing `_overflow_total` counter for steady-state observability.
  ([@aydasraf](https://github.com/aydasraf))
- **Production-tuned cache profile for 1000 req/s + 5 M artifacts.** A new `pantera-main/docker-compose/pantera/pantera-performance-tuned.yaml` ships alongside the default `pantera.yml`, sized for the `cache.r6g.large` Valkey + 15 vCPU / 20 GiB JVM reference deployment: 500 K `cooldown-metadata` L2, 3 M `repo-negative` L2, 5 M `artifact-index-positive` L2 (full-catalog coverage), HikariCP pool 80/20, `http_client.max_requests_queued_per_destination=4096`, and 60 s idle timeout. `pantera.yml` itself was re-shaped to the new key vocabulary (`repo-negative`, `cooldown-metadata`, `artifact-index-*`, `policy-*`, `filters`) so a fresh deploy picks up the consolidated names; the legacy `negative` / `cooldown` keys still parse for in-place upgrades.
  ([@aydasraf](https://github.com/aydasraf))
- **Per-host outbound rate limit and 429 back-off.** A token-bucket governor caps the rate at which Pantera issues upstream requests. Defaults are conservative (Maven Central 20 req/s, npm public registry 30 req/s) and configurable per host. On an upstream 429 or 503-with-`Retry-After`, Pantera holds back outbound traffic for that host until the deadline passes and propagates the same `Retry-After` to the calling client.
  ([@aydasraf](https://github.com/aydasraf))
- **Per-upstream circuit breaker + per-repo bulkhead.** Every upstream (keyed `scheme://host:port`) has a state-machine circuit breaker in front of the Jetty client: it opens when the failure rate over a 30 s sliding window reaches 50% across at least 10 calls — 5xx / non-rejection exceptions only (401 / 407 are credential failures and `429` stays the rate-limiter's responsibility — none of those trip the breaker) — with Fibonacci backoff from a 2 s seed to a 60 min cap and a daemon HEAD probe at expiry. While the breaker is open the client sees a fast-fail `502` (`X-Pantera-Circuit-Open: true` + `Retry-After`) and the broken upstream is left alone. In parallel, every `*-proxy` repo has its own bounded semaphore (defaults: 10 concurrent / 200 ceiling under adaptive control, with a 1000 queue) so a saturated repo can no longer steal concurrency budget from its neighbours; refusals return `503` with a `Retry-After` header and increment a `pantera_bulkhead_overflow_total` counter. Both surfaces are observable via Prometheus (`pantera_circuit_breaker_state`, `_trips_total`, `_fastfail_total`, `pantera_bulkhead_permits_current`).
  ([@aydasraf](https://github.com/aydasraf))
- **Single-flight coalescing on cache-miss.** Concurrent client requests for the same uncached artifact share one upstream fetch instead of firing independent calls.
  ([@aydasraf](https://github.com/aydasraf))
- **Single-flight cooldown evaluation.** Concurrent `evaluate()` calls for the same `(repoType, repoName, artifact, version)` collapse to a single downstream inspector lookup with a short TTL (30 s) on top of the existing 3-tier cooldown cache. Burst cache-miss patterns no longer fan a hundred lookups onto the publish-date registry for the same tuple.
  ([@aydasraf](https://github.com/aydasraf))
- **Stream-through cache writes.** The client receives the first byte as upstream emits it; integrity verification runs on stream completion before the cache commits. In v2.2.0 the same stream-through path is now applied to PyPI, Composer, Go and any other npm-shape adapter that has no sidecar primary/sig contract. Maven keeps its sidecar-verifying path (the digest pairing is load-bearing). Docker, files-proxy and local-only adapters retain their existing serve paths — documented as deliberate exceptions in the migration matrix.
  ([@aydasraf](https://github.com/aydasraf))
- **Conditional GET + stale-while-revalidate on Maven metadata.** `MetadataCache` now persists `ETag` / `Last-Modified` validators and emits `If-None-Match` / `If-Modified-Since` on refresh — a `304` bumps `lastVerified` without rewriting the blob. Within the soft TTL (default 30 s) hits are served straight from cache; between soft and hard TTL (default 2 h) the cached bytes serve immediately while a single-flighted background refresh runs. After hard TTL the call blocks on the upstream.
  ([@aydasraf](https://github.com/aydasraf))
- **Maven proxy fetches only `.sha1` alongside the primary** on cache-miss. `.md5`, `.sha256`, and `.sha512` are proxied on demand only.
  ([@aydasraf](https://github.com/aydasraf))
- **Maven cooldown is evaluated at response-header time on the single upstream GET.** The per-artifact pre-fetch HEAD probe is gone — one upstream call per cold artifact instead of two. Blocked artifacts still return `403` and never land in storage; cold `mvn dependency:resolve` through a group recovers most of its overhead vs. direct Maven Central.
  ([@aydasraf](https://github.com/aydasraf))
- **Logging hot path retuned.** The logging audit collapsed log-and-rethrow chains, removed duplicate-error sites, sanitised secret-adjacent emissions and migrated the remaining `System.out`/SLF4J writers (backfill CLI included) to the structured `EcsLogger`. The net effect on the request path is fewer allocations per emission and zero stderr leakage from the SAX parser, library bootstrap, or CLI flows.
  ([@aydasraf](https://github.com/aydasraf))

### 🔧 Bug fixes

- **A single upstream 5xx can no longer cascade into group-wide "not found" answers.** The outbound circuit breaker now trips on failure rate over a sliding window (50% of at least 10 calls in 30 s, 2 s initial backoff) instead of a single 5xx, and is keyed per `scheme://host:port` instead of bare hostname, so unrelated registries on one host no longer share a failure domain. Its fast-fail marker (`X-Pantera-Circuit-Open`) now survives the npm and maven adapters, so the group resolver skips the member without convicting its health window on fabricated evidence.
  ([@aydasraf](https://github.com/aydasraf))
- **A group whose members are all temporarily unavailable answers `503` + `Retry-After`, never a negative-cached `404`.** Previously "every member circuit-open" was recorded as "artifact does not exist" in the shared negative cache and outlived the outage by the cache TTL. Circuit-open members also still serve artifacts already in their warm cache before being skipped, and breaker trips, failed recovery probes, and recoveries are now logged.
  ([@aydasraf](https://github.com/aydasraf))
- **Per-member group metrics and artifact counters record again.** `pantera_group_member_requests`/`_latency` lost their recording call sites in the sequential-resolution rewrite, and `pantera_artifact_downloads`/`_uploads`/`_size` never had any — the group and repository dashboards' panels charting them could not render. Group members now record outcome and latency on every walk step (including circuit-open skips), and artifact counts/sizes are recorded alongside the existing per-repo byte counters.
  ([@aydasraf](https://github.com/aydasraf))
- **npm proxy: an unreadable cache entry is now treated as a miss, not a 5xx.** Under concurrent load, a request could observe a cache entry whose stream-through save was still mid-commit (the dedup gate releases followers when the leader's response resolves — before the tee finishes writing); the failed read surfaced as a 502 that fed the group's circuit breaker. The storage layer now degrades that race to a cache miss and refetches from upstream.
  ([@aydasraf](https://github.com/aydasraf))
- **Concurrent same-artifact requests through a group no longer race on cache commits.** Parallel resolves of one artifact (`gradle build`, `mvn dependency:resolve` fan-out) could 500 when stream-through commits clobbered the same path mid-read; the group entrypoint now coalesces concurrent same-path requests into a single resolve.
  ([@aydasraf](https://github.com/aydasraf))
- **Maven/Gradle no longer 5xx on first fetch after enabling cooldown.** The HTTP client's abandoned-body safety timer (5 ms) could fire before the async cooldown evaluation subscribed the response body, failing the cache write; the timer is now 30 s, preserving cleanup of genuinely abandoned bodies.
  ([@aydasraf](https://github.com/aydasraf))
- **PyPI proxy is `uv`-compatible: PEP 691 content negotiation.** JSON and HTML simple-index variants are served per the client's `Accept` header with separate cache keys and `upload-time` preserved (uv's `exclude-newer` works through the proxy); hosted PyPI repos now answer `HEAD` probes; negative-cache keys no longer collide between a package's index and its files, so a single 404 can't poison every wheel of the package.
  ([@aydasraf](https://github.com/aydasraf))
- **PyPI cooldown no longer fail-opens on fresh versions.** Release dates are read from the PEP 691 JSON index (the HTML variant carries no `upload-time`), and a missing inline date falls back to the publish-date registry instead of silently allowing.
  ([@aydasraf](https://github.com/aydasraf))
- **HTTP reason phrases added for 502/503/504.** Upstream Bad Gateway / Service Unavailable / Gateway Timeout responses were logged as `PanteraHttpException: Unknown`, which materially slowed incident diagnosis.
  ([@aydasraf](https://github.com/aydasraf))
- **Proxy audit no longer records an `artifact_publish` on cache-hit re-serves.** Composer, npm, Go, and PyPI proxies emitted a publish event (and a DB index upsert) every time an already-cached artifact was re-downloaded, and dropped `client.ip`/`trace.id` on the way to the audit log. Publish now fires only on a genuine first-time fetch; cache hits, upstream fetches, and cooldown blocks emit `artifact_access` with full request correlation.
  ([@aydasraf](https://github.com/aydasraf))
- **npm cooldown now blocks fresh tarball downloads.** The storage probe and the upstream fetch were fused in a single call, so the cooldown gate only ran after a blocked version had already been fetched, cached, and served with `200`. Blocked versions now return `403` without contacting upstream or touching the cache.
  ([@aydasraf](https://github.com/aydasraf))
- **`artifact_resolution` fires on every metadata listing view.** Cache-hit serves, cooldown-disabled repos, ETag `304` revalidations, parse-failure fallbacks, and all-versions-blocked denials previously produced no audit record. Every metadata view across all proxy formats (Maven/Gradle, npm, PyPI, Go, Composer, Docker) now emits exactly one resolution record per request, and the filtered-version list is preserved across cache hits.
  ([@aydasraf](https://github.com/aydasraf))
- **npm proxy audit records are no longer emitted twice per request.** The request-dedup layer traversed the origin slice twice on the success path (a probe plus a re-fetch), doubling every audit record and metric. The dedup leader now serves its own response — one origin traversal per request, and one upstream round-trip saved.
  ([@aydasraf](https://github.com/aydasraf))
- **Upstream non-2xx responses propagate with correct status.** 429 (with `Retry-After`), 401, 403, and 503-with-`Retry-After` are no longer collapsed to 404. Transient 5xx no longer pollutes the artifact index cache, so a brief upstream outage does not produce long-lived false negatives.
  ([@aydasraf](https://github.com/aydasraf))
- **Group resolver falls through only on genuine 404s.** Transient member errors (5xx, timeouts) retry the next member instead of stopping the walk.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown unblock invalidates the metadata cache** so re-enabled `(artifact, version)` pairs are immediately visible to clients.
  ([@aydasraf](https://github.com/aydasraf))
- **Vert.x temporary cache directories are swept at startup.** Pantera now cleans up stale per-PID `tmp-<uuid>` directories older than one hour before booting, preventing slow accumulation on long-lived hosts.
  ([@aydasraf](https://github.com/aydasraf))
- **`COMMENT IS` migration concatenation collapsed (V130).** The `audit_log` column comment string is now a single literal — Flyway's parser tripped on the previous multi-line `||` concatenation and failed the migration on fresh installs.
  ([@aydasraf](https://github.com/aydasraf))
- **Settings-layer integration test cleanup.** `SettingsLayerIntegrationTest` now `TRUNCATE`s `audit_log` between cases instead of `DELETE` — the v2.2.0 write-once triggers on `audit_log` refuse `DELETE` by design.
  ([@aydasraf](https://github.com/aydasraf))
- **Upstream mid-body body-streaming failures map to 502, not 500.** Any `IOException`-rooted failure on the body-streaming path (Jetty `Content.Source` errors, upstream connection close while we are relaying bytes) is now rendered as `502 Bad Gateway` with `Retry-After: 2`. `VertxSliceServer.ResponseTerminator.fail` walks the cause chain up to 8 hops so wrapped Reactive-Streams / CompletableFuture failures still match. The change makes Go's `cmd/go`, the Maven Resolver, and the Docker daemon apply their built-in idempotent retry instead of treating the failure as fatal. Previously these surfaced as `500 Internal Server Error`, which most package managers treat as terminal.
  ([@aydasraf](https://github.com/aydasraf))
- **Negative cache invalidated on upload across every adapter.** A late artifact upload no longer continues to return `404` from the shared `NegativeCache`. Every upload slice — Maven, npm, PyPI, Helm, Debian, RPM, Conda, Hex, Gem, NuGet, Go, files — now calls `NegativeCacheRegistry.invalidateAfterUpload(...)` after the storage commit; the invalidation broadcasts over `CacheInvalidationPubSub` so peer nodes drop their L1 entries too. Local repos invalidate as well — they were the most common silent-404-after-publish source.
  ([@aydasraf](https://github.com/aydasraf))
- **Filtered metadata cache (`cooldown-metadata`) invalidated on upload and cooldown unblock.** The same registry pattern as negative-cache: every adapter upload slice and the cooldown unblock / unblock-all paths invalidate the filtered-metadata cache through `FilteredMetadataCacheRegistry`, so post-unblock and post-upload reads see the new artifact set instead of the pre-event filtered view.
  ([@aydasraf](https://github.com/aydasraf))
- **Circuit breaker no longer trips on `401` / `407` / `429`.** The breaker exists to protect upstreams from a thundering herd against a genuinely-broken backend; `401` and `407` are credential problems (operator misconfig, not upstream health), and `429` is the rate limiter's job. Only `5xx` and non-rejection exceptions trip the breaker now; `401` / `407` surface to the caller and are recorded in metrics, but the breaker stays closed and the next request reaches the upstream unimpeded.
  ([@aydasraf](https://github.com/aydasraf))
- **Cooldown admission inspector keyed against the actual `repo_type`** in every adapter (maven, npm, PyPI, Go, Composer, files). Previously these slices built their `RegistryBackedInspector` with hardcoded literals that didn't match the suffixed `repo_type` that `DbConsumer` writes to `artifact_publish_dates` (e.g. `gradle-proxy`, `maven-proxy`), so the registry lookup missed and the gate fell through to the no-date path even when a date was on file. Each constructor now flows the slice's `repoType` parameter into the inspector.
  ([@aydasraf](https://github.com/aydasraf))
- **Filtered-metadata envelope cache wipes L2 on cooldown policy changes.** `FilteredMetadataCache.clear()` now deletes both the in-memory L1 (Caffeine) and the Valkey L2 `metadata:*` keys. A new boot-time listener on the `cooldown` settings prefix runs `CooldownSupport.loadDbCooldownSettings` to re-apply the new policy to the in-memory `CooldownSettings` snapshot (the generic `PUT /api/v1/settings/cooldown` path only persists to the DB), invokes `MetadataFilterService.clearAll()`, and broadcasts an `invalidateAll` envelope over `CacheInvalidationPubSub` whenever an admin toggles `enabled`, changes `minimum_allowed_age`, edits a repo-type / repo-name override, or updates the SNAPSHOT knob — so the next metadata fetch re-runs the filter against the new policy instead of serving up to 12 h of stale envelope bytes.
  ([@aydasraf](https://github.com/aydasraf))
- **Publish-date source map keyed by suffixed repo types.** Maven, Gradle, and Go publish-date sources are now registered under both bare and suffixed names (`maven` + `maven-proxy`, `gradle` + `gradle-proxy`, `go` + `go-proxy`). After the 2.2.0 inspector keying fix routed lookups through the actual `repoType` (suffixed), the source map's bare-only keys produced a `source_miss` on every proxy lookup and the admission gate fell through to the no-date branch — so the first-fetch upstream HEAD never ran, regardless of the head-fallback flag. First-fetch cooldown enforcement is now restored.
  ([@aydasraf](https://github.com/aydasraf))
- **Maven `head_fallback` publish-date source defaults to ON.** `PANTERA_PUBLISH_DATE_HEAD_FALLBACK_ENABLED` now defaults to `true` so first-fetch cooldown enforcement works out of the box for Maven and Gradle proxies — without the HEAD source, the first asker of a freshly published version downloads the bytes before any subsequent request sees the publish-date row. Operators with extreme cold-walk concerns can disable via the env var.
  ([@aydasraf](https://github.com/aydasraf))

### 🔒 Security

- **RS256 JWT migration completed.** Every token-validation path requires RS256; HS256 fallback paths removed.
  ([@aydasraf](https://github.com/aydasraf))
- **Authentication-policy settings are server-enforced**, not just UI-displayed. `api_token_max_ttl_seconds` and `api_token_allow_permanent` round-trip through the admin settings API and are honoured at token-mint time.
  ([@aydasraf](https://github.com/aydasraf))
- **Path-traversal guard + Authorization stripping at the proxy entry point.** A canonicalising guard sits at the top of `BaseCachedProxySlice.response()` and rejects (with `400`) raw `..`, percent-encoded `%2e%2e` / `%252e`, NUL bytes, control characters, Windows-style backslash probes, and malformed percent-encoding — no per-adapter shortcut can bypass it. The upstream-header whitelist (User-Agent + Accept only) is now pinned by an explicit test, and `LogSanitizer` masks `Authorization`, `Cookie`, `X-API-Key`, `X-Auth-Token`, and `Proxy-Authorization` in every emitted log.
  ([@aydasraf](https://github.com/aydasraf))
- **TLS 1.2+ enforcement with Mozilla "intermediate" cipher suites** on every inbound TLS listener and every outbound HTTPS call. SSLv2 / SSLv2Hello / SSLv3 / TLS 1.0 / TLS 1.1 are rejected at the handshake stage; the cipher list excludes RC4, 3DES, NULL, EXPORT, and anonymous suites. Hostname verification is explicitly enabled on the outbound Jetty client to prevent a future code change from silently disabling it.
  ([@aydasraf](https://github.com/aydasraf))
- **Hardened response headers on every Pantera HTTP response.** `SecurityHeadersSlice` wraps the outermost server slice and injects HSTS (TLS listeners only), `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, CSP and Permissions-Policy baselines. Routes that need a relaxed value (UI's `SAMEORIGIN`, per-route CSP) keep winning — the slice yields to any header the inner slice has already emitted.
  ([@aydasraf](https://github.com/aydasraf))
- **Insert-only audit log with DB-enforced immutability.** V129 hardens `audit_log` with BEFORE UPDATE / BEFORE DELETE triggers (raise `feature_not_supported`), adds `details JSONB` / `success` / `ip_address` columns, and ships the `AuditEvent` / `AuditService` abstraction. Cooldown unblock, cooldown unblock-all, repo create/update/delete and negative-cache invalidation all write through the pipeline. Entries inherit the originating request's `trace.id`. Operators can no longer rewrite history by mistake — see the admin guide for how to rotate retention by truncating partitions.
  ([@aydasraf](https://github.com/aydasraf))
- **PGP signature verifier + keyring (scoped subset).** `PgpVerifier` (Bouncy Castle LTS), `KeyringStore` interface with `JdbcKeyringStore` (V131 `pgp_keyring` table) and `InMemoryKeyringStore` (tests, no-DB boots). Five-state `Result` (`VERIFIED` / `TAMPERED` / `UNTRUSTED_KEY` / `MISSING_SIGNATURE` / `MALFORMED`) maps cleanly to HTTP + audit outcomes. The admin REST endpoint for keyring management is deferred to a follow-up.
  ([@aydasraf](https://github.com/aydasraf))
- **Logging audit closes the secret-adjacent perimeter.** Every `EcsLogger` emission now carries a `log.source` field (`audit` / `application` / `http`) so the shipper routes each line to the right Elasticsearch index; non-ECS extension fields under `auto1.*` were renamed or dropped (`audit.action` → `event.action`, `audit.actor` → `user.name`, `repository.member` → `member.name`); the bootstrap default-credential string no longer appears in the WARN body. Defensive sanitisation pinned at every credential-bearing log site (`YamlSettings`, `JwtPasswordAuth`, `Login`, `OAuthLoginSlice`, `AdminAuthHandler`). Browser-side telemetry (`authError.ts`, OAuth callback view) sends a `{ status, code }` shape instead of dumping raw `AxiosError` / IdP error payloads. Previously-swallowed exceptions are now surfaced at `WARN` or `ERROR` with `event.outcome: failure` — operators may see a brief uptick post-deploy; this is intentional.
  ([@aydasraf](https://github.com/aydasraf))

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
- **Negative cache for proxy fanout (renamed in WI-06).** Prevents thundering-herd: when all proxy members return 404 for a missing artifact, the `(scope, repoType, artifactName, artifactVersion)` tuple is cached for a short TTL (default 5 minutes) so subsequent requests return 404 instantly without a second fanout. Two-tier L1 Caffeine + L2 Valkey via the existing `NegativeCacheConfig` pattern; configurable per-deployment under `meta.caches.repo-negative` in `pantera.yml` (covers hosted/proxy/group after the WI-06 consolidation). The earlier name `meta.caches.group-negative` is still accepted with a deprecation WARN at boot — admins should rename to `repo-negative` to silence the warning; the legacy key will be removed in a future release. Falls back to in-memory L1 only (matching historical behaviour) when neither config key is present.
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
