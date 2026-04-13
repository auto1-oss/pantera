# Changelog

## Version 2.1.2

### 🔧 Bug fixes

- API listener fails ALB health checks when `meta.http_server.proxy_protocol: "true"` is enabled. ALB does not emit PROXYv2 (it terminates L7 and adds `X-Forwarded-For` instead), so plain `GET /` health-probe bytes were being misparsed by Pantera's PROXY decoder and the connection closed with `HAProxyProtocolException`. The target group then marked the API port unhealthy with no useful Pantera log entry. Fixed by introducing a per-listener PROXYv2 toggle for the API port — see `meta.http_server.api_proxy_protocol` below.
  ([@aydasraf](https://github.com/aydasraf))

### 🌟 New features

- New `meta.http_server.api_proxy_protocol` flag controls PROXYv2 on the API listener (typically port 8086) independently from the main + per-repo listeners. Defaults to the value of `meta.http_server.proxy_protocol` for backward compatibility — pre-2.1.2 deployments that set a single `proxy_protocol: true` keep their existing behaviour. Operators with a mixed topology (NLB → main port + ALB → API port) set `api_proxy_protocol: "false"` to keep PROXYv2 on for the NLB-fronted listeners while disabling it on the ALB-fronted API port.
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
