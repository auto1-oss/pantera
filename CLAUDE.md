# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Update discipline

This file is the durable operating reference for agents and humans. Rules for the file itself:

- Update it **in the same PR** as any change that invalidates it (commands, ports, workflows, endpoints, conventions).
- Commands must be exact and copy-pasteable — no placeholders that don't exist, no improvised variants.
- Only what is true of the tree right now. Never add development narrative, session history, TODOs, or aspirational content.
- When you find a statement here that contradicts the code, the code wins — fix this file in your PR.

## Project

Pantera is a multi-format binary artifact registry (Maven, Docker, npm, PyPI, Go, Composer, Helm, NuGet, Debian, RPM, Conda, Conan, Hex, Gem, generic files), based on Artipie. Java 21 + Maven multi-module backend, Vue 3 + Vite UI in `pantera-ui/`.

Tech stack: Vert.x 4.5 (HTTP server), Jetty 12 (HTTP client), PostgreSQL + HikariCP + Flyway, Valkey via Lettuce (optional, HA), Quartz, Log4j2 with ECS JSON layout, Micrometer/Prometheus, RxJava inside adapter internals, JUnit 5 + Hamcrest + TestContainers, PMD via `build-tools`.

## Definition of done

A change is done when ALL of the following hold — do not open or update a PR before:

1. `mvn clean install -T8` is **fully green** (all unit tests + PMD + license check in one reactor session). Add `-Dexec.skip=true` to skip the pom-bound docker buildx (CI does).
2. If the change touches the UI: `cd pantera-ui && npm run type-check && npm run lint && npm test && npm run build` all green.
3. If the change touches runtime behavior (serving, caching, metrics, auth): verified against the running local stack, not just tests.
4. Documentation consistency rule below is satisfied (docs + CHANGELOG updated in the same PR).
5. New/changed metrics have dashboards; new state transitions have logs (see Observability).

## Common commands

**Use these exact commands. Do not substitute or improvise.**

| Task | Command |
|---|---|
| Build (no tests) | `mvn clean install -T8 -DskipTests=true` |
| Build + all tests | `mvn clean install -T8` (CI adds `--fail-at-end -ntp -Dexec.skip=true`) |
| Single test class | `mvn test -pl pantera-core -Dtest=NegativeCacheTest` |
| Integration tests | `cd test_images && ./build.sh` then `mvn clean verify -Pitcase -T 1C` (needs Docker) |
| Valkey-gated tests | `VALKEY_HOST=localhost mvn test -pl pantera-core` |
| License headers | `mvn license:format` |
| UI dev server | `cd pantera-ui && npm install && npm run dev` |
| UI checks | `cd pantera-ui && npm test && npm run lint && npm run type-check && npm run build` |
| Version bump | `./bump-version.sh <new-version>` (all modules + compose tags + Dockerfile in one shot — never by hand) |

Local stack — `cd pantera-main/docker-compose/` first, then `docker compose up` / `docker compose down`. To run new code: rebuild the image first:
`docker build -t pantera:<version> --build-arg JAR_FILE=pantera-main-<version>.jar -f pantera-main/Dockerfile pantera-main`, then down/up.

**Do NOT read `.env` files** — they hold local secrets that must not be inspected by tooling.

## Local dev stack playbook

- Repo traffic through nginx: `http://localhost:8081/test_prefix/api/<repo_name>/<path>`. **nginx caches artifact responses** — when verifying server behavior, bypass it and hit the backend directly at `http://localhost:8088/test_prefix/api/<repo_name>/<path>`.
- REST API / UI backend: `:8086`. Prometheus metrics: `http://localhost:8087/metrics/vertx`. Grafana: `:3000`. Prometheus: `:9090`. UI dev server: Vite default.
- Anonymous reads are **deny-by-default** (2.2.0). An unauthenticated GET returning 401 is correct behavior. Dev credentials for manual requests live in the committed sample projects (`pantera-main/docker-compose/pantera/artifacts/*/test.sh`) — use those, never `.env`.
- Authorization is DB-backed (`CachedDbPolicy`: users/roles/user_roles tables), not filesystem YAML policy.
- Grafana dashboards are provisioned from `pantera-main/docker-compose/grafana/provisioning/dashboards/`. Quantile (p95/p99) panels need `PANTERA_METRICS_PERCENTILES_HISTOGRAM=true` (set in the dev compose).
- The docker-compose sample projects under `pantera-main/docker-compose/pantera/artifacts/` are test fixtures for exercising the registry per format — they are deliberately excluded from dependabot; their dependency alerts get dismissed as `not_used`.

## Architecture: what to know before editing

**Slice pattern.** Every HTTP handler — adapters, middleware, auth, routing — implements `com.auto1.pantera.http.Slice`: `CompletableFuture<Response> response(RequestLine, Headers, Content)`. Slices compose via decorators (`Slice.Wrap`, `LoggingSlice`, `TimeoutSlice`, etc.). Naming: `*Slice` for HTTP handlers, `*Storage` for storage implementations.

**Storage abstraction.** `com.auto1.pantera.asto.Storage` is the universal blob interface; all methods return `CompletableFuture`. Implementations: `FileStorage` (Vert.x NIO), `S3Storage`, `InMemoryStorage` (tests only), `SubStorage` (prefix-scoped). `DispatchedStorage` wraps a backing storage and routes completions to dedicated `StorageExecutors` pools (READ/WRITE/LIST) so slow writes don't starve reads. `DiskCacheStorage` is a read-through on-disk LRU/LFU cache for S3.

**Three repository modes per format.** Wiring switch: `pantera-main/.../RepositorySlices.java`.
- `local` — Pantera is authoritative.
- `proxy` — caching reverse proxy. The maven adapter extends `BaseCachedProxySlice`, a template-method base with a 7-step pipeline: negative-cache fast-fail → pre-process hook → cacheability → cache-first (offline-safe) → cooldown → deduplicated upstream fetch (single-flight) → store + digest (overrides: `isCacheable`, `buildCooldownRequest`, `digestAlgorithms`, `buildArtifactEvent`, `postProcess`, `generateSidecars`). The other formats have bespoke proxy slices (npm: `NpmProxy`/`CachedNpmProxySlice`, pypi: `ProxySlice`, …) with their own stale-while-revalidate metadata refresh; a proxy that serves cooldown-filtered metadata MUST invalidate the shared filtered-metadata envelope when refreshed content lands (npm: `packumentWriteHook`; maven: `MetadataCache` refreshed-content hook → `FilteredMetadataCacheRegistry.invalidateAfterProxyRefresh`).
- `group` — sequential member walk in declared YAML order: first 2xx (or 304/403 — authoritative) wins, 404 falls through to the next member, genuine 5xx records a member failure and continues.

**Supporting cache pieces.** `RequestDeduplicator` / `SingleFlight` (thundering-herd protection), `NegativeCache` (L1 Caffeine + L2 Valkey), `ProxyCacheWriter` (stream-through tee with integrity verification; rollback after partial failure is fire-and-forget by design), group-level request coalescing on same-path bursts.

**Two circuit breakers — do not conflate them.**

| | Group-member breaker | Upstream HTTP breaker |
|---|---|---|
| Class | `AutoBlockRegistry` (pantera-core), used by `MemberSlice` | `UpstreamCircuitBreaker` (http-client), wired in `JettyClientSlices` |
| Keyed by | member repository name | `scheme://host:port` |
| Trips on | failure rate over sliding window (min-calls gated) | failure rate over sliding window (min-calls gated) |
| Open behavior | member skipped in walks; warm cache still probed via internal `X-Pantera-Cache-Only` header | fast-fail 502 with `X-Pantera-Circuit-Open: true` + `Retry-After`; HEAD probe recovery with Fibonacci backoff |
| Settings keys | `circuit_breaker_*` in `auth_settings` | `upstream_breaker_*` in `auth_settings` (V136) |
| Admin UI | "Group Member Circuit Breaker" card | "Upstream HTTP Circuit Breaker" card |

Invariants that must hold (they broke once — see the breaker-cascade fix in 2.2.0):
- Adapters MUST preserve the circuit-open marker through error funnels (`UpstreamCircuitOpenException` in pantera-core carries it); collapsing the marked 502 into a generic status exception convicts healthy members on fabricated evidence.
- `GroupResolver` treats a marker-502 as "member skipped" — walk continues, **no** `recordFailure()`.
- All-members-unavailable answers **503 + Retry-After, never 404** — a 404 would be negative-cached and outlive the outage.

**Outbound HTTP stack** (http-client, per upstream): `RateLimitedClientSlice` (reactive 429/503-Retry-After gate) → `CircuitBreakingClientSlice` → raw `JettyClientSlice`. Loopback hosts bypass both. HTTP/1.1 + keep-alive only (HTTP/2 deliberately disabled — Jetty #12776).

**Database.** Single PostgreSQL, Flyway migrations under `pantera-main/src/main/resources/db/migration/` (next free version = highest existing + 1). `ArtifactDbFactory` builds the HikariCP pool. `DbConsumer` is an RxJava batcher (2 s / 200 events) that sorts by `(repo_name, name, version)` before UPSERTing — the sort prevents deadlocks; failed batches dead-letter with backoff. `DbArtifactIndex` does FTS (tsvector + GIN) with ILIKE fallback. `auth_settings` is the generic key/value store for admin-tunable runtime settings. Dashboard counts require the `pg_cron` extension — deployment prerequisite, not a bug.

**Cluster / HA.** `CacheInvalidationPubSub` (cross-instance Caffeine invalidation over Valkey pub/sub, self-message filtering by instance UUID; its constructor blocks for the SUBSCRIBE ack — boot thread only, never event loop), Valkey-backed token-revocation broadcast (`ValkeyRevocationBlocklist`, selected at boot in `VertxMain` with a DB-polling fallback), Quartz JDBC mode (`PanteraScheduler` shared across nodes). Pure single-instance mode uses RAM Quartz and no Valkey.

**JWT auth.** RS256 (Auth0 java-jwt), one key pair, three token types (`access`, `refresh`, `api`) — the `type` claim is mandatory. Access tokens stateless; refresh/api tokens in `user_tokens` with revocation flags; access-token revocation broadcast over Valkey (`pantera:revoke:user:{username}`), 30 s table polling without Valkey. When adding a protected endpoint in `AsyncApiVerticle`, **reuse the shared `jwtAuthHandler` instance** — a new `JWTAuthHandler` skips the blocklist + JTI ownership checks.

**Thread model — the hard rule.** The Vert.x event loop must never block. Never `.join()`/`.get()` on a `CompletableFuture` in code that may run on the event loop. JDBC and sync I/O go through `DispatchedStorage`/`StorageExecutors` (`pantera-io-{read,write,list}-%d`, tunable via `PANTERA_IO_*_THREADS`). `HandlerExecutor` (API worker pool) has a bounded queue + AbortPolicy **by design** — rejection is visible backpressure, don't "fix" it. `BlockedThreadDiagnostics` warns at >5 s event-loop / >120 s worker block.

**Reactive bodies.** `Content` is a `Publisher<ByteBuffer>`. Bodies must always be consumed, even on error paths — ignoring the publisher leaks ByteBuffers (`resp.body().asBytesFuture().thenAccept(b -> {})` to discard deliberately).

**Module map.** `pantera-core` (Slice, Storage interface, cache, security framework, cluster bus, audit), `pantera-storage/{core,vertx-file,s3}`, `pantera-main` (entry `VertxMain`, REST API `AsyncApiVerticle`/`AdminAuthHandler`, DB layer, Flyway, Quartz, `RepositorySlices` wiring, `GroupResolver`), `vertx-server` (Vert.x HTTP adapter), `http-client` (`JettyClientSlices` + rate limit + breaker), `pantera-backfill` / `pantera-import-cli` (standalone CLIs), `*-adapter` (one per format), `pantera-ui` (Vue 3 + PrimeVue + Pinia), `build-tools` (PMD ruleset jar), `test_images/` (client images for integration tests).

## Logging — strict requirements

All logging goes through `EcsLogger` (`com.auto1.pantera.http.log.EcsLogger`) emitting ECS JSON. Never raw SLF4J/Log4j pattern logging in new code. There are two log sources, distinguished by the mandatory `log.source` field:

**1. Application logs** (`log.source=application`) — operational events. Requirements:
- `event.category` must be a valid ECS value (`network`, `web`, `database`, `configuration`, `authentication`, `file`, `host`, `process`), `event.action` snake_case, `event.outcome` `success`/`failure` where applicable.
- Field names must be valid ECS: `url.full`, `url.path`, `http.response.status_code`, `destination.address`, `event.reason`, `repository.name`, `error.message` (via `.error(throwable)`). Custom non-ECS fields are dropped by strict ingest pipelines — don't invent them; extra detail goes in `message`.
- Every operationally significant **state transition must be logged**, not just counted: breaker trips/probe failures/recoveries, all-members-unavailable, config reloads, degraded modes. Counters alone are invisible during incident response.

**2. Audit logs** (`log.source=audit`, logger `artifact.audit`, class `com.auto1.pantera.audit.AuditLogger`) — the compliance trail. Four events, wired across all 15 format adapters:

| `event.action` | When | `event.outcome` |
|---|---|---|
| `artifact_publish` | genuine first-time upload/fetch-and-store | success/failure (`checksum_mismatch`, `storage_unavailable`) |
| `artifact_access` | artifact serve: cache hit, cache-miss fetch, or cooldown block | success/failure (`cooldown_active`, `not_found`, `upstream_unavailable`) |
| `artifact_delete` | any repo mode | success/failure |
| `artifact_resolution` | proxy/group metadata listing view (filtered-version visibility) | success |

Non-negotiable audit fields — every audit record MUST carry: **user** (`user.name`/owner), **client IP** (`client.ip`), **trace correlation** (`trace.id`), and **package identity** (`package.name`, `package.version`, `package.size` as integer, `repository.name`, `repository.type`). This is enforced structurally: `AuditContext` takes `traceId`/`clientIp` as compulsory constructor parameters — never read them from MDC in audit code. Capture the context **before any async hop** (`captureAuditContext(headers)` at the top of the slice), because MDC does not survive worker-thread hops.

**Correlation across async hops:** `EcsLoggingSlice` stamps internal headers `X-Pantera-Ctx-Trace-Id` / `X-Pantera-Ctx-Client-Ip` on inbound requests; downstream slices restore MDC on worker threads via `RequestContextHeaders.bindToMdc(headers)` inside their async callbacks. `ArtifactEvent`/`ProxyArtifactEvent` auto-capture `traceId`/`clientIp` at construction; `DbConsumer` restores them before audit emission. One `trace.id` must connect the inbound request, outbound calls, pub/sub envelopes, Quartz jobs, and the audit record.

Metadata-only requests (a `maven-metadata.xml` GET, a PyPI simple-index render) are **never** `publish`/`access` — only `resolution` covers metadata.

## Observability conventions

- Metric names `pantera.<area>.<thing>` (Micrometer dots → Prometheus underscores; counters gain `_total`). Tags must be bounded: repo tags are capped by `RepoNameMeterFilter` (default 50); never add an unbounded tag (paths, versions, artifact names).
- Every recording call is guarded by `MicrometerMetrics.isInitialized()`.
- **A metric without a panel is invisible; a panel without a metric is a lie.** If you add a metric, add its Grafana panel in the same PR (`pantera-main/docker-compose/grafana/provisioning/dashboards/`), and verify the exact exposed name + tags against a live scrape of `:8087/metrics/vertx` before writing the query — several past panels charted metric names that never existed.
- Timers publish NO histogram buckets unless `PANTERA_METRICS_PERCENTILES_HISTOGRAM=true`, which applies two curated SLO ladders (defined in `VertxMain`): **transfer** timers (`pantera.http/proxy/upstream.request.duration`, `pantera.storage.operation.duration`, Vert.x server response time — durations include body streaming) get 18 boundaries up to 20 min; **control-plane** timers get 16 boundaries up to 30 s. Panels for bucket-less timers must chart `sum/count` averages, never `histogram_quantile`.
- Recording cost is bucket-count-insensitive; the cost axis is series count at scrape/TSDB time. Bucket shapes are fixed at meter creation — ladder changes need a restart (unlike DB-backed settings).

## CI & release

Exactly two workflows (`.github/workflows/`):

**ci.yml** — triggers: every PR commit (per-ref concurrency cancels superseded runs) and manual dispatch — deliberately NO push-to-master trigger: branch protection requires the three CI checks green (plus a PR) before merge, so a post-merge rebuild would duplicate an already-green tree. Jobs: cheap static gates first (ECS log-schema drift, nginx CVE guard via `scripts/check-nginx-cve-2026-42945.sh`, git-diff change detection) → backend and UI build/test jobs, path-filtered on PRs. The backend step is a single reactor session — `mvn clean install -T8 --fail-at-end -ntp -Dexec.skip=true` — because standalone `mvn pmd:check` cannot resolve the `build-tools` ruleset jar or sibling-module jars on a clean runner. Do not split it.

Security posture: external contributors' workflow runs require maintainer approval **on every push** (`all_external_contributors`); default workflow token is read-only; only GitHub-owned, SHA-pinned actions (org policy blocks third-party actions — this is why the workflows use `gh` CLI and raw `docker buildx` instead of marketplace actions).

**release.yml** — manual dispatch only (write access), from the Actions tab or `gh workflow run release.yml`. Reads the version from the root POM (`bump-version.sh` is the single source of truth), refuses existing tags, BUILDS without re-testing (`mvn clean install -U -T8 -DskipTests=true -Dexec.skip=true` — the released commit already passed the PR gate), then creates the `v<version>` tag and GitHub release with `pantera-<v>.jar`, `pantera-<v>-dist.tar.gz` (jar + `dependency/`), `pantera-ui-<v>.tar.gz`, `SHA256SUMS`, and publishes multi-arch (amd64+arm64) images **`ghcr.io/auto1-oss/pantera`** and **`ghcr.io/auto1-oss/pantera-ui`** (`:latest` withheld on RC/beta/alpha), all with build-provenance attestations.

Dependabot: weekly grouped updates (maven, npm in `pantera-ui/`, github-actions). Docker-compose sample projects are deliberately excluded; their alerts are dismissed as `not_used`.

## Testing doctrine

- Unit tests (`*Test.java`, surefire) should not require Docker/network/DB — use `InMemoryStorage`. (Some legacy `*Test.java` use TestContainers; do not add new ones.) Service-dependent tests are `*IT.java`/`*ITCase.java` under `-Pitcase` (failsafe), backed by the 10 client images in `test_images/`. No workflow runs the integration suite automatically — run it on demand: `cd test_images && ./build.sh` then `mvn clean verify -Pitcase -T 1C` (build.sh is Linux-only — GNU sed).
- **Never assert absolute wall-clock latency.** Bounds measured on an idle laptop are scheduler noise on shared CI runners — this class of assertion produced seven distinct red builds before being eradicated. Prove semantics instead:
  - *Ordering*: latches — e.g. "foreground `load()` returns while the refresh loader is still parked" proves background refresh; a `@Timeout` converts a blocking regression into a deterministic failure.
  - *Behavior*: invocation counts — "`calls == 1`" proves no upstream call; recording fakes prove propagation.
  - *Regression guards*: if a duration bound is genuinely wanted, set it ≥10× the idle-laptop value (order-of-magnitude guard) and say so in a comment.
  - *Shared resources*: retry briefly on transient contention (the bounded `HandlerExecutor` queue, freshly-started listeners/TLS handshakes, fire-and-forget cleanup) — poll for the eventual state instead of asserting instantly.
- JUnit 5 + Hamcrest with **matcher objects, not static factories**: `new IsEqual<>(y)`, not `Matchers.equalTo(y)`. Single assertion: no reason string; multiple assertions: every one gets a reason string.
- No `Files.createFile` in tests — use `@TempDir`.
- Vert.x API tests share server state across methods in a class (rate-limiter windows, pools) — write tests tolerant of pre-consumed shared state.

## PMD and style — non-obvious rules that fail the build

Ruleset: `build-tools/src/main/resources/pmd-ruleset.xml`; `printFailingErrors=true` — any violation breaks `mvn verify`.

- **No public static methods** except `main(String...)`. Private static helpers are fine.
- **Only one constructor initializes fields**; secondary constructors delegate via `this(...)`.
- **Cyclomatic complexity** ≤15/method, ≤80/class; cognitive ≤17 — extract instance methods or strategy objects.
- **No unused imports/parameters** (PMD catches both).
- GPL-3.0 header from `LICENSE.header` on every Java file; `mvn license:format` adds them.

## Documentation — types and consistency

Documentation is part of the change, not an afterthought. **Update the affected docs in the same PR**; a PR that changes behavior without touching its documentation is incomplete. The doc types and their audiences:

| Type | Location | Audience / contract |
|---|---|---|
| User guide | `docs/user-guide/` | Consumers of repositories: client setup per format, search, troubleshooting client errors. No server internals. |
| Admin guide | `docs/admin-guide/` | Operators: installation (images + release artifacts), configuration, env vars, monitoring, HA, upgrade procedures, UI deployment. Commands must be runnable verbatim. |
| Developer guide | `docs/developer-guide/`, `docs/developer-guide.md`, `docs/frontend-developer-guide.md` | Contributors: build, architecture deep-dives, module internals. |
| Contribution | `CONTRIBUTING.md`, `AGENTS.md` | Workflow, commit conventions, agent operating notes. Keep consistent with this file — conflicts between CLAUDE.md/AGENTS.md/CONTRIBUTING.md are bugs. |
| Reference | `docs/configuration-reference.md`, `docs/rest-api-reference.md`, `docs/admin-guide/environment-variables.md` | Exhaustive tables. Every new env var / setting key / endpoint lands here in the same PR. |
| Changelog | `CHANGELOG.md` | Public release record. House sections only: `### ⚠️ Breaking changes` / `### 🌟 New features` / `### ⚡ Performance` / `### 🔧 Bug fixes` / `### 🔒 Security`. One concise attributed bullet per user-visible change (`([@handle](https://github.com/handle))`). **No internal process narrative, no dev-log content, never disclose unpatched weaknesses.** Historical entries are immutable — never rewrite past releases. |
| Runbooks | `docs/runbooks/` | One page per alert: what fired, what it means, what to do. New alerts ship with runbooks. |

Consistency requirements: docs must never contradict each other or the code (e.g. default values, header names, ports). When renaming/removing things, grep `docs/`, `README.md`, `AGENTS.md`, `CONTRIBUTING.md` and this file for stale references in the same PR.

## Extension recipes

**Add a DB-backed admin setting** (mirror `upstream_breaker_*` / `UpstreamBreakerSettingsLoader`):
1. Flyway migration seeding the keys into `auth_settings` (`ON CONFLICT DO NOTHING`, comment documenting defaults).
2. Loader implementing `Supplier<YourConfig>`: DB → env (`PANTERA_<KEY>`) → hardcoded default per field; `AtomicReference` cache; `invalidate()`; static `install(dao)` from `VertxMain` + `activeSupplier()` fallback for DB-less boots.
3. Consumers read **through the supplier** on each decision so changes apply without restart (re-allocate any size-dependent state on change).
4. `AdminAuthHandler`: GET/PUT with a key whitelist and validation by round-tripping the config constructor before writing; `loader.invalidate()` after write.
5. `pantera-ui` `SettingsView.vue`: new card + SECTION_META entry + save-bar/dirty/discard wiring; label it so it cannot be confused with sibling settings. Update `configuration-reference.md` + `environment-variables.md`.

**Add a metric + panel**:
1. Record via `MicrometerMetrics` (guarded by `isInitialized()`), bounded tags only.
2. If it's a latency timer, decide its SLO ladder (transfer vs control-plane) in `VertxMain`.
3. Drive the code path against the local stack; scrape `:8087/metrics/vertx`; copy the exact name/tags into the panel query.
4. Panel in `grafana/provisioning/dashboards/` following the file's conventions (datasource shape, `{job="pantera"}` filter, unique id, gridPos). `sum/count` avg for bucket-less timers.

**Add a protected API endpoint**:
1. Route in `AdminAuthHandler` (admin) or `AsyncApiVerticle` (user-facing) — reuse the shared auth handler instance.
2. Handler body: validate → `CompletableFuture.supplyAsync(..., HandlerExecutor.get())` for any blocking work — never on the event loop.
3. Audit-log admin mutations (`event.category=configuration`, action snake_case). Document in `rest-api-reference.md`.

**Add a format adapter (proxy mode)**:
1. Extend `BaseCachedProxySlice`; override `isCacheable`, `buildCooldownRequest`, `digestAlgorithms`, `buildArtifactEvent`, `postProcess` (+`generateSidecars` if the format has checksum sidecars).
2. Preserve the circuit-open marker in any custom error funnel (`UpstreamCircuitOpenException`).
3. Wire all three modes in `RepositorySlices`; emit the four audit events with a captured `AuditContext`.
4. Add itcase coverage (client image in `test_images/` if the format has a CLI client), user-guide page, and sample project under docker-compose artifacts.

## Conventions for changes

- Branches off `master`; PRs into `master`. Conventional Commits with the existing scope vocabulary (`fix(breaker):`, `feat(settings):`, `fix(grafana):`, `fix(npm):`, `test(maven):`, `ci:`, `build(deps):`, `docs(changelog):`) — match, don't invent.
- All production code under `com.auto1.pantera.<module>`.
- Configuration is YAML (`pantera.yml`, `${ENV_VAR}` substitution at load time). Runtime tuning env vars use the `PANTERA_` prefix. `ConfigWatchService` hot-reloads repository configs; main settings need a restart unless DB-backed.
- No feature flags for settled changes — ship full replacements; rollback is `git revert`, not a runtime toggle.
- Never commit secrets; throwaway test keys are generated, never checked in.
