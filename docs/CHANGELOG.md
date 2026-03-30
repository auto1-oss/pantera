# Pantera -- Release History

---

## v2.0.7 (March 2026)

### Security

- **JWT JTI allowlist** — every issued token now has its UUID persisted in `user_tokens`. Validation checks the DB on every request; forged tokens (even with the correct HMAC secret) are rejected with 401 because they carry a JTI that was never issued. Closes the privilege-escalation vector that existed when the default secret was known.
- **Public UI settings endpoint** — new `GET /api/v1/settings/ui` serves only the Grafana URL and requires no authentication, so all users can see the Grafana link without exposing writable settings.

### Fixed

- **Auth redirect loop** — `localStorage` was used in the Pinia auth store but `sessionStorage` in the Axios request interceptor and `redirectToLogin` helper. Every API call went out without an Authorization header, got 401, and redirected to login. All three references in `client.ts` are now `localStorage`, consistent with `auth.ts`.
- **Cross-tab session loss** — JWT was kept in `sessionStorage` (per-tab) so opening a URL in a new tab required a fresh login. Moved to `localStorage` so the session is shared across tabs and survives browser restart.
- **Dashboard zeros for non-admin users** — dashboard statistics were fetched inside the same `Promise.all` as `GET /settings`, which requires admin. A single 403 failed the whole chain, leaving all stat cards at zero. Stats and settings are now fetched independently; stats always display regardless of role.
- **PHP Composer download 500 → 404** — `DownloadArchiveSlice` propagated `ValueNotFoundException` as an unhandled exception when an artifact was not found at the URL-derived path (upload path ≠ storage path, which includes `artifacts/` prefix). The exception now converts to a proper 404. The `+`-to-space fallback path also returns 404 instead of 500 when the fallback lookup misses.
- **Per-repo cooldown overrides** — `cooldown.duration` in repository YAML was stored in the management UI but never read by the backend. `JdbcCooldownService` now applies a three-tier priority: per-repo-name override > per-type > global. `RepositorySlices` registers overrides from each repo's config at startup.

### Tests

- `CooldownSettingsTest` — 7 unit tests covering global defaults, per-type overrides, per-repo-name overrides, and idempotent updates.
- `JdbcCooldownServiceTest` — 3 integration tests: per-repo duration blocks within window, per-repo disabled beats global enabled, override does not affect other repos.
- `GroupSliceIndexRoutingTest` — verifies index routing decisions (`locateByName` vs direct fanout) via `RecordingIndex`.
- `DownloadArchiveSliceTest` — added `returnsNotFoundWhenArtifactMissing` to assert 404 (not 500) on missing artifacts.

---

## v2.0.6 (March 2026)

### Added

- **Theme switcher** — three-way System / Dark / Light selector in the Profile view, persisted to `localStorage`. The Tailwind `dark:` utilities now follow the `.dark` CSS class so toggling the theme applies instantly without a page reload.
- **Artifact sorting** — artifacts in the repository browser are now sorted alphabetically by name.

### Fixed

- PrimeVue components (Card, Input, Select, DataTable, Dialog, Breadcrumb) were defaulting to dark backgrounds regardless of active theme; now they respect the selected mode.
- Breadcrumb black background in dark mode and missing background in light mode; height stabilised.
- Dashboard stat-card accent top border disappeared in dark mode.
- "Top Repositories" dashboard section had hardcoded dark colours.
- Artifacts card layout shifted when navigating into subdirectories.

---

## v2.0.5 (March 2026)

### Fixed

- **Cooldown unblock cache invalidation** — unblocking or bulk-releasing artifacts via the API did not evict the cached block decision, so clients continued to receive 404s until the TTL expired. `CooldownMetadataService.invalidate()` is now called from `CooldownHandler` after every unblock/unblockAll DB write.
- **Maven 500 for repo names with dots** — repository names such as `atlassian.com` or `build.shibboleth.net` did not match `RepositorySlices.PATTERN` because the pattern excluded `.`. Relaxed character class from `[^/.]` to `[^/]`.
- **Proxy 4xx passed through as 503** — when an upstream returned any 4xx response, `BaseCachedProxySlice` emitted an `ERROR` signal, causing clients to receive 503. 4xx responses now emit a `NOT_FOUND` signal and are returned to the client as 404.
- **Grafana URL persistence** — the Grafana URL entered in Settings was only written to the in-memory Pinia store. On page reload, `config.json` overwrote it. The URL is now persisted via `PUT /api/v1/settings/ui` and read back from the DB on mount.
- **Cooldown remaining time display** — durations under one hour showed `0h` instead of the actual minutes. The UI now displays `40m`, `15m`, etc. using the exact `blocked_until` timestamp.

### Added

- **pg_cron cleanup job** — hourly `DELETE` for expired cooldown rows to prevent unbounded table growth. A partial index (`idx_cooldowns_status_blocked_until`) on `artifact_cooldowns` accelerates cleanup queries and the status check hot path.
- **Dashboard storage tiers** — TB and PB tiers added to the storage size display on the dashboard.

### Security

- commons-fileupload bumped to 1.6.0, fixing active CVE-2025-48976 (DoS via crafted multipart headers).
- happy-dom bumped to 20.x, fixing GHSA-37j7-fg3j-429f (CVSS 10.0 RCE via JavaScript `eval`; disabled by default in test environments).

### Dependencies

- log4j 2.25.3, postgresql driver 42.7.7, Jetty 11.0.26, commons-lang3 3.20.0, assertj 3.27.3

### Documentation

- Fixed 9 broken README links pointing to non-existent paths.

---

## v2.0.0 -- The Pantera Release (March 2026)

The debut release of Pantera Artifact Registry. Everything that was Artipie is now Pantera -- new name, new identity, same battle-tested core, massively expanded capabilities.

**+78% Docker proxy throughput. +14% npm throughput. Zero errors at 200 concurrent clients.**

### What's New

#### Enterprise Management UI

A full Vue.js management interface ships with Pantera for the first time. Dark-theme dashboard with real-time statistics, a tree-based repository browser with inline artifact preview, full-text search across all repositories, one-click artifact download, and a cooldown management panel. SSO login via Okta and Keycloak is built in. Admin panels for user, role, and repository management are permission-gated -- read-only users never see them.

#### Database-Backed Configuration

Repository definitions, users, roles, storage aliases, and auth provider settings are now persisted in PostgreSQL. The REST API is the primary management interface -- create, update, and delete repositories without touching YAML files or restarting the server. Settings propagate across HA cluster nodes automatically via Valkey pub/sub.

#### Fully Async REST API

60+ management endpoints rebuilt on Vert.x async handlers, replacing the legacy synchronous REST layer. New capabilities include dashboard statistics, HMAC-signed browser download tokens (60-second TTL), artifact and package deletion, auth provider toggling, and long-lived API token management with custom expiry.

#### High-Performance Caching Pipeline

Every proxy adapter now shares a unified 7-step caching pipeline: negative cache fast-fail, local cache check, cooldown evaluation, request deduplication, NIO streaming to temp file, incremental digest computation, and sidecar generation. Two-tier negative cache (Caffeine L1 + Valkey L2) returns instant 404s for known-missing artifacts. Request deduplication coalesces concurrent fetches for the same artifact into a single upstream call.

#### High Availability Clustering

Run multiple Pantera nodes behind a load balancer with shared state. PostgreSQL-backed node registry with heartbeat liveness detection. Cross-instance Caffeine cache invalidation via Valkey pub/sub -- when one node updates a cache entry, all others evict it within milliseconds. Quartz JDBC job store ensures scheduled tasks run exactly once across the cluster.

#### Full-Text Artifact Search

PostgreSQL tsvector with GIN indexes replaces the previous Lucene-based search. Always consistent, no warmup required. Search API supports full-text queries with relevance ranking, artifact location across repositories, on-demand reindex, and index statistics. Search tokens are auto-generated from artifact paths -- dots, slashes, dashes, and underscores are split into searchable terms.

#### Performance at Scale

Separated I/O into three named thread pools (READ 4xCPU, WRITE 2xCPU, LIST 1xCPU) so slow uploads never starve fast downloads. Group resolution uses parallel fan-out to all members with first-response CAS -- the fastest member wins, the rest are cancelled. HTTP/2 flow control retuned from 64KB to 16MB stream windows, removing a 1MB/s throughput ceiling on typical LANs. Zero-copy response writing via Netty ByteBuf eliminates double memory copies on the hot path. A critical DB fix replaced reverse LIKE queries (99% CPU on 1M+ rows) with indexed B-tree lookups.

#### Reliability Engineering

Circuit breakers with Fibonacci backoff (1, 1, 2, 3, 5, 8... x base duration) protect against cascading upstream failures -- blocked members return 503 instantly at zero cost. Retry with exponential backoff and random jitter prevents thundering herds. Graceful shutdown drains in-flight requests before stopping. A dead-letter queue archives failed database events to disk for later recovery. A race condition in Docker blob caching that caused ClosedChannelException on large layer pulls was fixed with AtomicBoolean CAS guards.

#### Supply Chain Security

The cooldown system blocks freshly-published upstream artifacts for a configurable quarantine period, giving security teams time to vet new versions before they enter builds. Per-adapter inspectors extract release dates from npm, Maven, PyPI, Docker, Go, and Composer metadata. A 3-tier evaluation cache (in-memory, Valkey, PostgreSQL) keeps the hot path under 1ms. Administrators can review, unblock, or bulk-release artifacts through the UI or API.

#### Enterprise Authentication

Okta OIDC with full MFA support -- TOTP codes and push notifications, with automatic group-to-role mapping. Keycloak OAuth/OIDC with just-in-time user provisioning. JWT-as-Password mode lets clients authenticate with a pre-generated token validated locally in ~1ms, eliminating per-request IdP calls. Authentication providers are evaluated in configurable priority order.

#### S3 Storage Engine

S3 storage with multipart uploads (configurable part size and concurrency), parallel range-GET downloads for large artifacts, server-side encryption (SSE-S3 and SSE-KMS), and a local disk cache with LRU/LFU eviction and watermark-based cleanup. S3 Express One Zone support for ~10x lower latency single-AZ workloads. Full credential chain: static keys, AWS profiles, STS AssumeRole with chaining.

#### Observability

Prometheus metrics on a dedicated port with JVM, HTTP, storage, and thread pool gauges. ECS-structured JSON logging compatible with Elasticsearch and Kibana, with hot-reloadable Log4j2 configuration. Elastic APM integration for distributed tracing. Lightweight health endpoint (`/.health`) returns 200 OK with zero I/O -- suitable for NLB probes at any scale.

#### 15 Package Formats

Maven, Docker (OCI), npm, PyPI, PHP/Composer, Go, Helm, NuGet, Debian, RPM, Conda, Conan, Hex, RubyGems, and generic files. Each supports local hosting, and most support proxy caching and group aggregation.

#### Developer Tools

Backfill CLI for populating the artifact database from existing storage (11 repository types, batch upsert, dry-run mode). OCI Referrers API (Distribution Spec v1.1). Webhook notifications for artifact lifecycle events with HMAC-SHA256 signing and retry.

#### Documentation

Complete rewrite from scratch: Admin Guide (15 pages), User Guide (16 pages with per-format task-oriented guides), Developer Guide, Configuration Reference, and REST API Reference. Covers installation, HA deployment, backup/recovery, upgrade procedures, and the management UI.

### Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21+ (Eclipse Temurin) |
| Vert.x | 4.5.22 |
| Jetty HTTP Client | 12.1.4 |
| PostgreSQL | 17 |
| Valkey | 8.1 |
| Jackson | 2.17.3 |
| Micrometer | 1.12.13 |
| Vue.js | 3 + Vite |

### By the Numbers

- 4,500+ files changed
- 28 new core components
- 60+ REST API endpoints
- 15 package formats
- 0% error rate at 200 concurrent clients

---

## v1.20.12 -- Auto1 Enterprise Fork (February 2026)

The foundational release. Forked from open-source Artipie v1.20.0 and rebuilt for enterprise production use at Auto1 Group. Every major subsystem was hardened, extended, or replaced.

### What's New

#### Supply Chain Security

Cooldown system blocks package versions published less than a configurable age (default: 72 hours) from being consumed by builds. Inspectors for npm, Maven, PyPI, Docker, Go, and Composer extract upstream release timestamps. Metadata filtering removes blocked versions from package listings so clients never see them. Evaluation results are cached across three tiers (Caffeine in-memory, Valkey shared, PostgreSQL persistent). Administrators manage blocks through the REST API.

#### Enterprise SSO

Okta OIDC integration with MFA -- both TOTP verification codes and Okta Verify push notifications. Group-to-role mapping provisions Pantera RBAC roles automatically from Okta group membership. Keycloak OAuth/OIDC with just-in-time user creation on first login. JWT-as-Password mode: obtain a token once (with MFA), then use it as the password in Maven settings.xml, .npmrc, pip.conf, and Docker login -- every subsequent request is validated locally in ~1ms with zero IdP calls.

#### PostgreSQL Foundation

Metadata, settings, RBAC policies, artifact indexing, cooldown records, and import session tracking all backed by PostgreSQL with Flyway-managed migrations. HikariCP connection pooling with externalized configuration for pool size, timeouts, idle limits, and leak detection. ARM64 Docker image support for Graviton and Apple Silicon.

#### S3 Storage at Scale

S3 storage with multipart uploads (configurable chunk size and parallelism), parallel range-GET downloads for large artifacts, server-side encryption (AES-256 and KMS), and a read-through disk cache with LRU/LFU eviction and high/low watermark cleanup. S3 Express One Zone for latency-sensitive single-AZ deployments. Full AWS credential chain including STS AssumeRole.

#### Adapter Overhaul

npm adapter rebuilt with full CLI compatibility -- install, publish, unpublish, deprecate, dist-tags, search, audit, and security advisories all work. Semver resolution fixes. PyPI adapter implements PEP 503 Simple Repository API. Composer adapter with Satis private package support. Go module proxy with GOPROXY protocol. Docker adapter with streaming optimization for multi-GB layers and multi-platform manifest support. Maven with full checksum validation (MD5, SHA-1, SHA-256, SHA-512).

#### HTTP/2 and HTTP/3

HTTP/2 over TLS (h2) and cleartext (h2c) for AWS NLB compatibility. Experimental HTTP/3 (QUIC) support via Jetty. Upgraded to Jetty 12.1.x with improved connection handling. Fixed Vert.x connection leaks on error paths.

#### Observability Stack

Elastic APM integration for distributed request tracing with transaction and span tracking. Prometheus metrics: request counts, latencies, cache hit rates, cooldown block counts, JVM heap/GC/threads, and thread pool utilization. ECS-structured JSON logging for direct Elasticsearch/Kibana ingestion with configurable levels and hot-reload via Log4j2.

#### Operational Tooling

Dynamic repository creation, update, and deletion via REST API -- no restart required. Group repositories aggregate multiple local and proxy sources under a single URL with first-match resolution. Global URL prefixes support reverse proxy path rewriting. Content-based config watcher avoids unnecessary reloads on file touch without content change. Import CLI for bulk artifact migration from external registries with retry and S3 multipart optimization.

#### Performance Foundations

Reactive streams backpressure for large file transfers prevents memory exhaustion under load. Streaming downloads without full buffering -- files over 2GB transfer correctly. S3 connection pool tuning with configurable concurrency. Removed blocking calls during cache writes. Request deduplication for proxy cache. Bounded event queues (10,000 capacity) prevent OOM from event storms. Zero-copy response writing. 64KB streaming buffer for cache-through operations.

### By the Numbers

- Forked from Artipie v1.20.0
- 15 package formats: local, proxy, and group modes
- 6 cooldown inspectors (npm, Maven, PyPI, Docker, Go, Composer)
- 5 authentication providers (env, native, Keycloak, Okta, JWT-as-Password)
- Production-tested at Auto1 Group
