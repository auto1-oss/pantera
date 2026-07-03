# Pantera -- Release History

---

## v2.2.0 (May 2026)

### Performance

- **Per-host upstream circuit breaker + per-repo bulkhead.** Every upstream now has a state-machine circuit breaker in front of the Jetty client: closed → open on 5xx / 401 / 407 / non-rejection exceptions (429 stays the rate-limiter's responsibility), Fibonacci backoff with a 30 s seed and 60 min cap, daemon HEAD probe at expiry. While the breaker is open the client sees a synthesised `502` (`X-Pantera-Fault: circuit-breaker-open`) and the broken upstream is left alone. In parallel, every `*-proxy` repo has its own bounded semaphore (defaults: 200 concurrent, 1000 queue, 1 s `Retry-After`). Refusals return `503` and increment `pantera_bulkhead_overflow_total`. State and counters are exposed as `pantera_circuit_breaker_state`, `pantera_circuit_breaker_trips_total`, `pantera_circuit_breaker_fastfail_total`.
- **Stream-through cache for npm-shape adapters.** PyPI, Composer, Go, and the existing npm path all now stream upstream bytes to the client and the on-disk cache simultaneously, with integrity verification on stream completion before the cache commits. Maven keeps its sidecar-verifying path (primary + digest sidecar are atomically validated as a pair). Docker, files-proxy and local-only adapters retain their existing serve paths — documented in the migration matrix as deliberate exceptions.
- **Conditional GET + stale-while-revalidate on Maven metadata.** `MetadataCache` persists `ETag` / `Last-Modified` validators and emits `If-None-Match` / `If-Modified-Since` on refresh — a `304` bumps `lastVerified` without rewriting the blob. Within the soft TTL (default 30 s) reads are pure cache; between soft TTL and hard TTL (default 2 h) the cached bytes serve immediately while a single-flighted background refresh runs. Soft and hard TTL are tunable per repo via `cache.metadata.soft_ttl` / `cache.metadata.hard_ttl`.
- **Single-flight cooldown evaluation.** Concurrent `evaluate()` calls for the same `(repoType, repoName, artifact, version)` tuple now share one downstream inspector lookup with a 30 s TTL on top of the existing 3-tier cooldown cache. Burst cache-miss patterns no longer fan a hundred lookups onto the publish-date registry for the same tuple.
- **Cold-bench perf gate in CI.** New nightly + per-PR workflow runs the cold-bench against a fixed Maven coordinate, fails on median > 20 s or p95 > 25 s, and additionally asserts circuit-breaker invariants (zero trips, no breaker left open at end of run) on top of the existing M3-M4 amplification checks.

### Added

- **Per-repo anonymous-access controls.** A new `anonymous_read` / `anonymous_write` flag per repo decides whether unauthenticated requests get a `401` + `WWW-Authenticate: Basic realm="pantera"` or pass through to downstream auth. **Deny-by-default for every repo type** (proxy / group / hosted) — an admin explicitly opts in to anonymous reads on a curlable OSS-mirror proxy by setting `anonymous_read: true`. The admin UI exposes both flags as checkboxes on the per-repo Access card and a bulk-update action on the repository-management page.
- **Observability pack for the perf surface.** Two Grafana dashboards under `pantera-main/src/main/resources/grafana/`: `upstream-circuit-breaker.json` (per-host state, trip frequency, fast-fail rate, time-since-last-trip) and `proxy-phase-latency.json` (stacked p99 of `proxy_phase_duration_seconds` per `(phase, repo)`). Prometheus recording-rule alerts and four runbooks cover the 2.2.0 perf-pack — `bulkhead-overflow.md`, `low-conditional-get-hit-rate.md`, `upstream-429-sustained.md`, `upstream-circuit-breaker-open.md`.
- **`ContextualExecutor` and trace propagation.** A new helper restores MDC + APM transaction context across any `CompletableFuture` continuation, RxJava `Maybe`/`Flowable` boundary, Quartz job execution, or pub/sub envelope. Pub/sub messages now carry a versioned envelope (v2 with trace context; v1 still parsed for rolling-deploy compatibility). Non-Jetty outbound HTTP injects `traceparent` + `X-B3-*` via `TraceHeaders.httpClientHeaders()`.

### Fixed

- **`V130` migration parser failure.** The `COMMENT IS` string in V130 used a multi-line `||` concatenation that Flyway's parser tripped over on fresh installs; collapsed onto a single literal.
- **Settings-layer integration test now uses `TRUNCATE`.** The v2.2.0 immutability triggers on `audit_log` refuse `DELETE` by design, so `SettingsLayerIntegrationTest` truncates between cases instead.

### Security

- **Path-traversal guard at the proxy entry.** `PathTraversalGuard.canonicalise(...)` is wired into `BaseCachedProxySlice.response()` and rejects (with `400`) raw `..`, percent-encoded `%2e%2e` / `%252e`, NUL bytes, ASCII control characters, Windows-style backslash probes, and malformed percent-encoding. Returns the URL-decoded canonical form on the safe path so downstream `KeyFromPath` keeps the existing contract. No per-adapter shortcut can bypass.
- **Authorization stripping pinned.** `BaseCachedProxySlice.upstreamHeaders()` forwards only `User-Agent` + `Accept`; inbound `Authorization`, `Cookie`, `X-API-Key`, `X-Auth-Token`, `Proxy-Authorization` are dropped before the upstream call. `LogSanitizer.SENSITIVE_HEADERS` masks the same five names in every emitted log. Both behaviours are now pinned by an explicit test.
- **PGP signature verifier + keyring (scoped subset).** `PgpVerifier` (Bouncy Castle LTS), `KeyringStore` (`JdbcKeyringStore` over the new `pgp_keyring` table from V131, `InMemoryKeyringStore` for tests / no-DB boots). Five-state `Result` (`VERIFIED` / `TAMPERED` / `UNTRUSTED_KEY` / `MISSING_SIGNATURE` / `MALFORMED`) so callers map each branch onto an HTTP + audit outcome. The admin REST endpoint to upload keys is deferred to a follow-up.
- **Insert-only audit log with DB-enforced immutability.** V129 adds `details JSONB` / `success` / `ip_address` columns to `audit_log` plus BEFORE UPDATE / BEFORE DELETE triggers that raise `feature_not_supported`. The `AuditEvent` / `AuditService` abstractions are wired into admin endpoints (cooldown unblock + unblock-all, repo CRUD, negative-cache invalidate). Audit entries inherit the originating HTTP request's `trace.id`.
- **Hardened response headers on every Pantera HTTP response.** `SecurityHeadersSlice` injects HSTS (TLS listeners only), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, a baseline CSP, and `Permissions-Policy`. The slice yields to any value the inner slice has already emitted, so UI routes that need `SAMEORIGIN` and per-route CSP overrides still win.
- **TLS 1.2+ + Mozilla "intermediate" cipher suites enforced on every endpoint.** SSLv2 / SSLv2Hello / SSLv3 / TLS 1.0 / TLS 1.1 rejected at the handshake stage on both inbound listeners and outbound Jetty client. Excluded suites: RC4, 3DES, NULL, EXPORT, anonymous. Hostname verification is explicitly enabled on the outbound side. See [TLS Configuration](security/tls.md).
- **Logging audit closes the secret-adjacent perimeter.** Every `EcsLogger` emission now carries `log.source` (`audit` / `application` / `http`) for shipper-side index routing; ECS schema conformance brought to 100% (no non-ECS extension fields shipped to Elastic); the bootstrap default-credential string no longer leaks into a WARN body; `YamlSettings` / `JwtPasswordAuth` / `Login` / `OAuthLoginSlice` / `AdminAuthHandler` all route error messages through `LogSanitizer`. Browser-side telemetry (`authError.ts`, OAuth callback view) sends `{ status, code }` instead of dumping raw `AxiosError` / IdP payloads. Previously-swallowed exceptions are surfaced at `WARN` or `ERROR` with `event.outcome: failure` — operators may see a brief WARN/ERROR uptick after deploy; this is intentional.

### Documentation

- New section on `log.source` (audit / application / http) in [Logging](admin-guide/logging.md); ECS-only field policy; audit-log immutability and retention; trace / span / transaction ID contract; note that swallowed exceptions are now surfaced.
- [Monitoring](admin-guide/monitoring.md) lists the new circuit-breaker + bulkhead metrics and links the new dashboards.
- [Performance Tuning](admin-guide/performance-tuning.md) documents `BulkheadLimits` defaults, circuit-breaker backoff envelope, and how to tune them.
- [Security](security/) covers TLS 1.2+, the anonymous-access matrix, audit-log rotation under the new immutability triggers, and the scoped PGP verifier.
- [Runbooks](runbooks/) — four runbooks for the 2.2.0 alert set landed, linked from [the admin-guide runbook index](admin-guide/runbooks.md).
- [Developer Guide](developer-guide/) — new `ContextualExecutor` requirement for async hops on the request path, the pub/sub v2 envelope, and the `TraceHeaders.httpClientHeaders()` helper for non-Jetty outbound transports.

### New Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pantera_circuit_breaker_state` | Gauge | `upstream_host` | 1 = open, 0 = closed |
| `pantera_circuit_breaker_trips_total` | Counter | `upstream_host` | Incremented on every closed → open transition |
| `pantera_circuit_breaker_fastfail_total` | Counter | `upstream_host` | Incremented on every synthesised 502 |
| `pantera_bulkhead_overflow_total` | Counter | `repo_name` | Incremented on every 503 from the per-repo semaphore |
| `proxy_phase_duration_seconds` | Histogram | `phase`, `repo` | Per-phase latency in `BaseCachedProxySlice` |

### Database Migrations

- `V129` — `audit_log` hardening: `details JSONB`, `success BOOLEAN`, `ip_address TEXT`; BEFORE UPDATE / BEFORE DELETE triggers raising `feature_not_supported`; covering indexes
- `V130` — `artifact_vulnerabilities` + `artifact_scan_status` tables (reserved; the scanner plumbing was removed before release)
- `V131` — `pgp_keyring` table for the PGP verifier

---

## v2.1.0 (April 2026)

### Performance

- **GroupSlice proxy-only fanout on index miss** — when the artifact index cannot resolve a name (metadata endpoints, unknown paths), the fallback fanout is now restricted to proxy members of the group. Hosted (local) members are skipped because an absent index entry means the artifact was never uploaded there. This eliminates unnecessary connections and 404 log noise from hosted members on every group metadata request.

### Added

- **Structured search query syntax** — the `GET /api/v1/search` `q` parameter now accepts field-prefixed filters:
  - `name:value` — case-insensitive substring match on artifact name
  - `version:value` — case-insensitive substring match on version
  - `repo:value` — exact match on repository name
  - `type:value` — prefix match on repository type (strips `-proxy`/`-group`)
  - Combine with `AND` / `OR` and parentheses: `name:pydantic AND (version:2.12 OR version:2.11)`
  - Fully backward-compatible: plain text queries (no prefixes) work as before.
  - Implemented by `SearchQueryParser` (`pantera-main/.../index/SearchQueryParser.java`).

- **Server-side search, sort, and pagination for users and roles** — `GET /api/v1/users` and `GET /api/v1/roles` now accept:
  - `q` — case-insensitive substring filter on username/email (users) or role name (roles)
  - `sort` — sort field: `username`, `email`, `enabled`, `auth_provider` (users); `name`, `enabled` (roles)
  - `sort_dir` — `asc` or `desc`
  - Filtering and sorting run as SQL queries using `COUNT(*) OVER()` window functions for single-pass pagination.

### Fixed

- **Search backend scalability** (7 fixes in `DbArtifactIndex` / `SearchHandler`):
  1. `SortField` enum replaces raw `String` in `buildOrderBy` — prevents SQL injection on sort parameter.
  2. Facet aggregations only computed on page 0 (`includeFacets` flag) — avoids expensive `GROUP BY` on every page change.
  3. Fallback `COUNT(*)` when result is empty and offset is non-zero — correctly reports total on deep pages.
  4. `MAX_OFFSET = 10,000` — caps the effective SQL `OFFSET`; requests exceeding this return `400 Bad Request`.
  5. Permission-aware SQL filtering — passes `repo_name = ANY(?)` to PostgreSQL instead of overfetching and filtering in Java.
  6. `SET LOCAL statement_timeout` on the FTS aggregation path — prevents runaway facet queries.
  7. `GROUP BY repo_type` in SQL, suffix merging in Java — reduces `GROUP BY` cardinality for facet counts.
  - Bonus: `getStats()` reads the `mv_artifact_totals` materialized view instead of `COUNT(*)` on the full table.

- **Smart 404 log levels in GroupSlice** — individual 404 responses from group members during fanout are now logged at `DEBUG` instead of `INFO`. A single member miss is not actionable and was generating high log volume at busy installations. Server errors (5xx) remain at `WARN`.

- **ECS-compliant HTTP request logging** — `EcsLoggingSlice` now emits proper ECS fields (`http.request.method`, `url.original`, `http.response.status_code`) instead of a raw `"GET /path 404"` message string. This fixes Elasticsearch field-type conflicts when ingesting Pantera logs.

- **`package.release_date` ECS field fix** — four package processors (Composer, Go, PyPI proxies and Go `CachedProxySlice`) emitted the string `"unknown"` as the `package.release_date` ECS field when the release date was unavailable. Elasticsearch rejected these as invalid date values. The field is now omitted entirely when no release date is known.

### Security

- **RS256 asymmetric JWT signing** — replaced the HS256 shared secret (which was publicly visible in the OSS repo) with RS256 asymmetric key pairs using the [Auth0 java-jwt](https://github.com/auth0/java-jwt) library. The private key signs tokens; the public key verifies them. Even if the public key is exposed, tokens cannot be forged. This is a **breaking change** — all existing tokens are invalidated on upgrade. See the [upgrade guide](admin-guide/upgrade-procedures.md) for migration steps.
- **Unified auth handler** — `UnifiedJwtAuthHandler` replaces both `JwtTokenAuth` (port 80) and the raw Vert.x `JWTAuthHandler` (port 8086) with a single code path. The management API (token generation, user management, settings) is now protected by the same JTI + username validation as the artifact proxy. Previously, forged tokens without a JTI could access the management API.
- **JTI + username ownership check** — the token validation query now checks `WHERE id = ? AND username = ? AND revoked = FALSE` (previously missing the `username` clause). An attacker who obtained a valid JTI from one user's token could no longer embed it in a forged token with a different `sub` claim.
- **Token type scope enforcement** — every token carries a mandatory `type` claim (`access`, `refresh`, or `api`). Refresh tokens are only accepted on `/auth/refresh`. API tokens are only accepted on the artifact proxy (port 80), not the management API. This prevents a leaked CI/CD token from being used to generate more tokens.

### Added

- **Access + Refresh + API token architecture** — industry-standard OAuth 2.0 pattern:
  - **Access tokens** (default 1 hour, configurable) — used for all requests, verified by signature + revocation blocklist only (zero DB hit).
  - **Refresh tokens** (default 7 days, configurable) — used to silently obtain new access tokens. Stored in DB with JTI. Rotated on each refresh.
  - **API tokens** (user-chosen TTL, max 90 days, or permanent with `expiry_days: 0`) — for CI/CD pipelines. Stored in DB with JTI.
- **Multi-node revocation blocklist** — immediate access token revocation across all Pantera nodes. When Valkey is available, uses pub/sub for near-instant propagation (reuses existing `CacheInvalidationPubSub` infrastructure). Falls back to DB polling (5-second interval) for single-node / no-Valkey deployments. Supports both JTI-level and username-level revocation.
- **Admin auth settings (UI + API)** — new "Authentication Policy" section in the admin settings page. Configurable values: access token TTL, refresh token TTL, API token max TTL, and whether permanent tokens are allowed. Settings stored in the `auth_settings` DB table, editable at runtime without redeployment.
- **Admin user revocation** — `POST /api/v1/admin/revoke-user/:username` immediately invalidates all tokens for a user across all nodes (DB revocation + blocklist broadcast). Available in the admin UI as a "Revoke All Tokens" action.
- **Backend search filtering and sorting** — search filtering (by type, repo) and sorting (by name, version, date, relevance) now run as PostgreSQL queries instead of client-side JavaScript. Sidebar facet counts are computed via DB `GROUP BY` aggregations. Replaced separate `COUNT(*)` queries with `COUNT(*) OVER()` window functions to halve GIN index scans. Version sorting handles non-numeric suffixes (`-SNAPSHOT`, `-jre`). Natural numeric sort in the repository tree browser (6.2 before 6.10). (#22)
- **PEP 691 JSON Simple API** — Pantera now serves the PEP 691 JSON format when clients request `Accept: application/vnd.pypi.simple.v1+json`. Includes `upload-time` per PEP 700, which fixes `uv lock --exclude-newer` failing against Pantera proxy repos. For proxy repos, JSON is fetched from upstream PyPI and cached with rewritten URLs. For hosted repos, JSON is generated from sidecar metadata.
- **PEP 503 full compliance for hosted repos** — hosted PyPI repo index pages now include `data-requires-python`, `data-yanked`, and `data-dist-info-metadata` attributes on file links. Metadata is extracted from wheel `METADATA` / sdist `PKG-INFO` at upload time and stored in sidecar JSON files (`.pypi/metadata/{package}/{filename}.json`).
- **Yank/unyank API and UI** — `POST /api/v1/pypi/:repo/:package/:version/yank` and `/unyank` endpoints for PEP 592 compliance. Yank/unyank buttons in the artifact browser UI with confirmation dialogs and optional reason field.
- **PyPI metadata migration CLI** — `java -jar pantera-backfill.jar --mode pypi-metadata --storage-root <path> --repos <repo1,repo2>` backfills sidecar metadata for existing packages. Extracts `Requires-Python` from archives and sets `upload-time` to file last-modified. Supports `--dry-run`.

### Changed

- **Login/callback response format** — `POST /api/v1/auth/token` and `POST /api/v1/auth/callback` now return `{token, refresh_token, expires_in}` instead of `{token}`. The UI handles both formats during migration.
- **`POST /api/v1/auth/refresh`** — now requires a refresh token (not an access token) in the `Authorization` header. Returns a new token pair (access + rotated refresh).
- **`POST /api/v1/auth/token/generate`** — validates `expiry_days` against admin-configured limits. Permanent tokens (`expiry_days: 0`) are only allowed when `api_token_allow_permanent` is `true` in auth settings.
- **Configuration** — `meta.jwt.secret` is removed. Replaced with `meta.jwt.private-key-path` and `meta.jwt.public-key-path` (PEM files, support `${ENV_VAR}` syntax). Startup fails fast with an actionable error if the old `secret` field is present.

### New Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/admin/auth-settings` | GET | Returns all auth settings |
| `/api/v1/admin/auth-settings` | PUT | Updates auth settings |
| `/api/v1/admin/revoke-user/:username` | POST | Revokes all tokens for a user |
| `/api/v1/pypi/:repo/:package/:version/yank` | POST | Yank a PyPI package version (PEP 592) |
| `/api/v1/pypi/:repo/:package/:version/unyank` | POST | Unyank a PyPI package version |

### Database Migrations

- `V105` — adds `token_type` column to `user_tokens` (values: `api`, `refresh`)
- `V106` — creates `revocation_blocklist` table for DB-polling fallback mode
- `V107` — creates `auth_settings` table with default token policy values

### New Dependencies

- `com.auth0:java-jwt:4.4.0` — battle-tested JWT library for RS256 signing and verification

### Documentation

- Updated admin guide: RS256 key management, migration from HS256, auth settings, user revocation
- Updated user guide: session behaviour, API token management, migration notice
- Updated developer guide: JWT architecture section, testing auth, adding protected endpoints
- Updated REST API reference: all changed/new endpoints with request/response examples
- Updated configuration reference: new YAML fields, environment variables, auth_settings table

---

## v2.0.8 (March 2026)

### Added

- **Quick Setup page** — guided per-technology setup instructions in the UI. Accessible at `/setup`, with a technology picker (npm, Maven, Docker, PyPI, etc.) and step-by-step, copy-friendly configuration snippets with the correct registry URL pre-filled. A `REGISTRY_URL` environment variable controls the base URL shown in instructions; defaults to the app's own origin if not set. (#20)

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
