# Release 2.1.0

## Authentication & Authorization
 - feat: RS256 asymmetric JWT signing replaces the previous shared-secret scheme
 - feat: access + refresh + API token architecture with configurable lifetimes
 - feat: multi-node token revocation via blocklist with cluster-wide propagation
 - feat: JTI ownership validation and token-type scope enforcement
 - feat: admin UI for auth settings and per-user token revocation
 - feat: schema-driven provider configuration UI for Okta and Keycloak
 - feat: provider lifecycle (create, enable, disable, delete) takes effect at runtime without restart
 - feat: mandatory `local` and `jwt-password` providers, protected from deletion
 - feat: priority-driven provider ordering with deterministic chain evaluation
 - feat: group-to-role mapping for SSO providers, independent from access-control gate
 - feat: default admin account bootstrapped on fresh installs with mandatory password change
 - feat: unified password complexity policy (server-side + client-side), minimum 12 characters
 - feat: self-service password change from user profile for local accounts
 - feat: admin password reset without requiring the target user's current password
 - feat: per-request user-enabled check in JWT filter — disabled users lose all access immediately
 - feat: token revocation on user disable (access tokens via blocklist, refresh/API tokens via DB)
 - feat: Group → Role mapping dropdown populated from the Pantera role catalog
 - fix: credential cache invalidation is now cluster-wide (L1 + L2) on every password change
 - fix: authentication chain respects provider authority for local users
 - fix: SSO-provisioned accounts remain eligible for SSO sign-in
 - fix: persistent inline error messaging on sign-in and SSO callback views
 - fix: generic, non-disclosing error messages across all sign-in failure paths
 - fix: SSO callback view no longer auto-redirects on failure
 - fix: axios interceptor no longer forces page reload on failed auth-boundary requests
 - fix: wrong current password on change-password no longer hangs the UI indefinitely

## Search
 - feat: structured search query syntax — `name:`, `version:`, `repo:`, `type:`, AND/OR, parentheses
 - feat: server-side search, sort, and pagination for users and roles
 - fix: typed SortField enum prevents injection on sort parameter
 - fix: facet aggregations computed only on the first page
 - fix: fallback total count on deep empty pages
 - fix: hard cap on effective offset (MAX_OFFSET=10,000)
 - fix: permission-aware SQL filter replaces overfetch pattern
 - fix: scoped statement_timeout on FTS aggregation path
 - fix: type aggregation pushed into SQL with suffix merging in application code

## PyPI PEP 503 / 691 Compliance
 - feat: PEP 691 JSON Simple API with PEP 700 upload-time metadata
 - feat: PEP 503 full data attributes on hosted-repo HTML indexes
 - feat: dual-format index persistence — HTML and JSON written side-by-side on upload
 - feat: self-healing JSON cache for legacy packages without JSON index
 - feat: self-healing sidecar metadata from storage file timestamps for pre-upgrade artifacts
 - feat: yank/unyank API endpoints (PEP 592) and UI controls in artifact detail dialog
 - feat: sidecar metadata files for requires-python, upload-time, yanked status
 - feat: one-time metadata backfill CLI for existing packages
 - feat: uv-based E2E test project verifying PEP 691/700 with exclude-newer
 - fix: proxy cache serves JSON with correct Content-Type on cache hits
 - fix: proxy cache rejects JSON responses with empty `files` array (prevents phantom package claims in groups)
 - fix: relative URLs in JSON index prevent hostname-resolution errors
 - fix: PEP 691 yanked field encoding corrected to string|false per spec
 - fix: .pypi metadata directory excluded from repo-level package index
 - fix: non-blocking async in self-healing cache path (no .join on event loop)
 - fix: HTML body tag typo in dynamic index generation

## File Version Detection
 - feat: version inference from dotted artifact names for file/file-proxy repos
 - feat: version repair CLI (`--mode version-repair`) for bulk-fixing UNKNOWN versions

## Natural Version Sort
 - feat: stored `version_sort bigint[]` generated column for natural ordering
 - feat: covers dates, v-prefixed versions, integers, and git hashes

## GroupSlice Performance
 - perf: index-miss fanout restricted to proxy-type members only
 - feat: hosted-first cascade — index-targeted queries try hosted members before proxies
 - fix: 404 log noise reduced — per-member 404s at DEBUG, aggregate miss at WARN

## Observability
 - feat: distributed tracing with B3 (openzipkin) and W3C Trace Context support
 - feat: trace.id, span.id, span.parent.id in all log entries per SRE convention
 - feat: SRE2042 validation — malformed/all-zero trace/span IDs regenerated with W3C version byte check
 - feat: traceparent response header on all HTTP responses (both public and API ports)
 - feat: B3 + W3C header injection into all upstream calls (all proxy adapters via JettyClientSlice, SSO, Okta)
 - feat: Okta OIDC client injects trace headers on all 6 HTTP call sites
 - feat: UI propagates traceparent from Elastic APM RUM or generated fallback
 - feat: startup and background job trace context (before HTTP processing)
 - feat: MDC propagation across all 46 `executeBlocking` worker-thread callsites via `MdcPropagation`
 - feat: trace context middleware on API port (AsyncApiVerticle) — MDC for trace.id, span.id, client.ip
 - feat: artifact audit logging at INFO level — upload, download, delete, resolution events
 - feat: audit logger reads repo/package context from MDC (no more empty fields)
 - feat: artifact resolution audit events wired into PyPI index responses
 - feat: dedicated `artifact.audit` logger with ECS-structured fields
 - feat: Proxy Protocol v2 support for AWS NLB on all ports (main, API, per-repo)
 - fix: auth failure log levels reclassified — wrong password is WARN, system errors stay ERROR
 - fix: Okta userinfo endpoint failures reclassified from WARN to ERROR (upstream system error)
 - fix: ECS-compliant HTTP access logging with structured fields
 - fix: package.release_date no longer logged as non-date literal
 - fix: malformed Authorization header returns 401 instead of 500
 - fix: url.original includes full path + query string, sanitized (extended: password, secret, client_secret)
 - fix: hot-path INFO logging downgraded to DEBUG (MemberSlice rewrite, cache hits, slow fetches, FORBIDDEN)

## Cooldown
 - fix: expired cooldown blocks now invalidate the metadata cache (L1 + L2)
 - fix: previously, expired versions remained missing from metadata until container restart

## Dependency Security
 - security: UI dependencies pinned to exact versions (supply-chain hardening)
 - security: .npmrc enforces save-exact, package-lock, engine-strict
 - security: vite upgraded to patched release, clearing dev-server advisories
 - security: eslint, vue-tsc, happy-dom upgraded to clear transitive advisories
 - security: npm audit reports zero vulnerabilities
 - security: Java dependencies refreshed to current stable within major lines
 - security: passwords hashed with bcrypt

## Database Migrations
 - feat: Flyway V100–V116 — all auth, provider, user-lifecycle, and cooldown schema
 - feat: pg_cron job definitions for materialized view refresh

## Breaking Changes
 - All previously issued tokens are invalidated (signing scheme changed)
 - `meta.jwt.secret` replaced by `meta.jwt.private-key-path` + `meta.jwt.public-key-path`
 - Login and callback endpoints return `{ token, refresh_token, expires_in }`
 - Fresh installs bootstrap a default admin account requiring password change on first sign-in
 - `local` and `jwt-password` auth providers are mandatory and cannot be removed
 - UI dependencies pinned to exact versions — developers must use `npm ci`

# Release 0.23
 - 7f9f53b - ci: remove codecov action
   by Kirill <g4s8.public@gmail.com>
 - c1913c2 - test: cover PromuSlice (#979)
   by Olivier B. OURA <baudoliver7@gmail.com>
 - 90c304b - todo: fix puzzle format
   by Kirill <g4s8.public@gmail.com>
 - 133b3b8 - docs(contrib): PR and commit message format
   by Kirill <g4s8.public@gmail.com>
 - f84def2 - feat: collect metrics with prometheus (#975)
   by Olivier B. OURA <baudoliver7@gmail.com>
 - 3878f17 - fix: add prefix repo for Composer and IT (#976)
   by Alexander <38591972+genryxy@users.noreply.github.com>
 - 34af171 - fix: log4j vulnerability CVE-2021-44228 (#974)
   by Evgeny Chugunnyy (John) <53329821+ChGen@users.noreply.github.com>
 - b71952e - test: add unit test for /api/security/users endpoint (#969)
   by Kirill <g4s8.public@gmail.com>
 - d29f6a4 - Merge branch 'master' into 965-get-users
   by Kirill <g4s8.public@gmail.com>
 - 097e3d1 - docs: add baudoliver7 as contributor (#971)
   by Olivier Baudouin OURA <baudoliver7@gmail.com>
 - ee0a1c4 - code review
   by olenagerasimova <olena.gereasimova@gmail.com>
 - 21cc095 - Merge branch 'master' into 965-get-users
   by Alena <olena.gerasimova@gmail.com>
 - 4cd1ced - code review
   by olenagerasimova <olena.gereasimova@gmail.com>
 - 72cefda - Test for `/api/security/users` endpoint
   by olenagerasimova <olena.gereasimova@gmail.com>
 - fdda0c1 - refactor: use instance field for cache (#967)
   by Alexander <38591972+genryxy@users.noreply.github.com>
 - c60549e - test: add more tests for creds cache (#966)
   by Alexander <38591972+genryxy@users.noreply.github.com>
 - fa4ec1a - feat: add cache for credentials configuration (#964)
   by Alexander <38591972+genryxy@users.noreply.github.com>
 - f1f8618 - refactor: extract cache package and logic of reading property value (#963)
   by Alexander <38591972+genryxy@users.noreply.github.com>
 - c9253a0 - feat: use CachedStorages (#962)
   by Alexander <38591972+genryxy@users.noreply.github.com>
 - a48bbf0 - doc: contributing maintainers update (#959)
   by Kirill <g4s8.public@gmail.com>
 - b7d67e5 - feat: add cache for storage configuration (#960)
   by Alexander <38591972+genryxy@users.noreply.github.com>
 - 964612a - Merge pull request #961 from genryxy/863-fix
   by Kirill <g4s8.public@gmail.com>
 - 613df0b - fix: remove printig container logs
   by Aleksandr Krasnov <genryxy.alexandr@yandex.ru>
 - 4361ba2 - Merge pull request #958 from genryxy/863-logger
   by Kirill <g4s8.public@gmail.com>
 - 93b8b7f - refactor: use existed slf4jLogConsumer
   by Aleksandr Krasnov <genryxy.alexandr@yandex.ru>
 - 1cfe0c3 - test: refactored `DockerLocalAuthIT` (#954)
   by Alena <olena.gerasimova@gmail.com>
 - ffe7770 - test: remade smoke test for nuget (#953)
   by Alena <olena.gerasimova@gmail.com>
 - 3929338 - test: fixed several smoke tests (#952)
   by Alena <olena.gerasimova@gmail.com>
 - 245e3ff - refactoring: removes HTTP client singleton (#951)
   by Olivier Baudouin OURA <baudolivier.oura@gmail.com>
 - cde0bf7 - Used Artipie docker image in `MavenMultiProxyIT` (#950)
   by Alena <olena.gerasimova@gmail.com>
 - 7b9c1bd - feat(conda): added quartz scheduler and conda job (#948)
   by Alena <olena.gerasimova@gmail.com>
 - 4be6ecd - ci: bump qulice and enabled for CI pipeline (#949)
   by Kirill <g4s8.public@gmail.com>
 - b567adf - feat(conda): added anaconda configuration (#947)
   by Alena <olena.gerasimova@gmail.com>
 - d88eb75 - ci: use docker container in `HelmITCase` and bumped helm to `v1.1.1` (#945)
   by Alexander <38591972+genryxy@users.noreply.github.com>
 - 52963c2 - env: switch to oracle-17 jdk (#944)
   by Kirill <g4s8.public@gmail.com>
 - cc283c1 - test: updated docker integration test (#940)
   by Kirill <g4s8.public@gmail.com>
 - c9c98c5 - deps: bump gem adapter to v1.w (#942)
   by milkysoft <zuoqinrb@163.com>
 - 75885d4 - test: smoke tests fixed (#939)
   by Kirill <g4s8.public@gmail.com>
 - 0bcd90f - test: finalized conda example, updated adapter and extended `RqPath.CONDA` (#938)
   by Alena <olena.gerasimova@gmail.com>
 - 16fcafd - test(conda): anaconda example (#937)
   by Alena <olena.gerasimova@gmail.com>
 - 30a9382 - test: conda publish integration test (#935)
   by Alena <olena.gerasimova@gmail.com>
 - b4ba083 - test: added test for YamlCacheStorage (#936)
   by Alexander <38591972+genryxy@users.noreply.github.com>
 - 0239ea6 - feat(conda): added conda-adapter dependency and IT for install (#934)
   by Alena <olena.gerasimova@gmail.com>
 - f6eb3fe - feat: add TTL and max size for cache storages in proxy repos (#932)
   by Alexander <38591972+genryxy@users.noreply.github.com>
 - f6389dd - ci: added modernizer plugin into verify pipeline (#931)
   by Kirill <g4s8.public@gmail.com>
 - 97813ff - refactoring: changed pairs implementation in tests (#930)
   by Kirill <g4s8.public@gmail.com>
 - f3cb973 - refactoring: OptionalSlice was moved to http (#929)
   by Alena <olena.gerasimova@gmail.com>
 - eadc435 - doc: removed not actual @todo (#928)
   by Alena <olena.gerasimova@gmail.com>
 - d04ac6c - test: add for auth without section (#927)
   by Alexander <38591972+genryxy@users.noreply.github.com>

# Release 0.22

 - refactor: extracted SettingsFromPath class to obtain artipie settings
 - fix: specify expiration time and refactor CachedAuth
 - feat: added cache for configuration files
 - ci: operations-per-run for stale
 - ci: added stale workflow
 - dep: bump helm to v1.0
 - dep: update version of Helm to v1.0
 - dep: bump management API 0.4.8

# Release 0.21

 - Removed application timeout slice #903
 - Add version endpoint and footer #526 #252

# Release 0.20.1

 - Updated management-api with NPE fix: artipie/management-api#39


# Release 0.20

 - Updated management-api for #896
 - Updated composer and composer-proxy repos for #895
