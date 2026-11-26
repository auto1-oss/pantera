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

Tech stack: Vert.x 4.5 (HTTP server), Jetty 12 (HTTP client), PostgreSQL + HikariCP + Flyway, Valkey via Lettuce (optional, HA), Quartz, Log4j2 with ECS JSON layout, Micrometer/Prometheus, RxJava inside adapter internals, JUnit 6 + Hamcrest + TestContainers, PMD via `build-tools`.

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

**Slice pattern.** Every HTTP handler — adapters, middleware, auth, routing — implements `com.auto1.pantera.http.Slice`: `CompletableFuture<Response> response(RequestLine, Headers, Content)`. Slices compose via decorators (`Slice.Wrap`, `LoggingSlice`, `TimeoutSlice`, etc.). Naming: `*Slice` for HTTP handlers, `*Storage` for storage implementations. `VertxSliceServer` answers a method that `RqMethod` does not know with `405` + `Allow` before any slice runs.

**Storage abstraction.** `com.auto1.pantera.asto.Storage` is the universal blob interface; all methods return `CompletableFuture`. Implementations: `FileStorage` (Vert.x NIO), `S3Storage`, `InMemoryStorage` (tests only), `SubStorage` (prefix-scoped). `DispatchedStorage` wraps a backing storage and routes completions to dedicated `StorageExecutors` pools (READ/WRITE/LIST) so slow writes don't starve reads. `DiskCacheStorage` is a read-through on-disk LRU/LFU cache for S3. For a prefix too large to hold in memory use `listPage(prefix, after, limit)` instead of `list`: it takes a PATH prefix (`maven` never matches `maven-proxy/…`; S3 `list(Key)` still matches by raw string prefix), returns keys in UTF-8 byte order (`Utf8Order`), and resumes from a key string (`ListPage.next()`, S3 `StartAfter`) that stays valid across restarts and deletions; `FileStorage`/`VertxFileStorage` walk one sorted directory at a time (`SortedKeyWalk`). `deleteBatch(keys)` is idempotent (a missing key is success): S3 sends quiet `DeleteObjects` of ≤1000 keys and fails with `StorageThrottledException` on SlowDown, the file storages prune emptied directories once per batch; `abortIncompleteUploads(prefix)` aborts S3 multipart uploads. A new `Storage` decorator MUST forward all three — the interface defaults fall back to `list` + per-key `delete`.

**Three repository modes per format.** Wiring switch: `pantera-main/.../RepositorySlices.java`.
- `local` — Pantera is authoritative. Per-repo `immutable` flag (flat `repo.immutable`, missing ⇒ true; `RepoConfig.immutable()` passed into each hosted adapter) forbids overwriting a stored artifact; docker ignores it (tag moves need the `overwrite` action). `DELETE /<repo>/<path>` goes through `DeleteRoutingSlice` → `RepoDeleteSlice` → `ArtifactDeletion`, the same delete + cascade (search index, `FormatDeleteHooks`, tree-view cache) as `DELETE /api/v1/repositories/:name/artifacts`; native protocol deletes (npm unpublish, helm `/charts`, nuget `/package`, hex release revert, pypi/rpm/deb, docker `/v2`) stay with the adapter; pypi/rpm/deb get the search-index cascade through `NativeDeleteCascadeSlice`.
- `proxy` — caching reverse proxy. The maven adapter extends `BaseCachedProxySlice`, a template-method base with a 7-step pipeline: negative-cache fast-fail → pre-process hook → cacheability → cache-first (offline-safe) → cooldown → deduplicated upstream fetch (single-flight) → store + digest (overrides: `isCacheable`, `buildCooldownRequest`, `digestAlgorithms`, `buildArtifactEvent`, `postProcess`, `generateSidecars`). The other formats have bespoke proxy slices (npm: `NpmProxy`/`CachedNpmProxySlice`, pypi: `ProxySlice`, …) with their own stale-while-revalidate metadata refresh; a proxy that serves cooldown-filtered metadata MUST invalidate the shared filtered-metadata envelope when refreshed content lands (npm: `packumentWriteHook`; maven: `MetadataCache` refreshed-content hook → `FilteredMetadataCacheRegistry.invalidateAfterProxyRefresh`).
  A checksum sidecar is served from storage; when it is missing, `BaseCachedProxySlice.keepChecksum` stores the one fetched from the upstream, so the upstream is asked at most once per sidecar. A sidecar path produces no artifact event (`MavenSlice.ARTIFACT` does not match it) and the reindex scanner skips it, so the search index gets no row.
  A bespoke proxy slice maps "the upstream gave no content" through `UpstreamMiss` (pantera-core), never to a bare 404: upstream 404/410 → 404; another upstream 4xx → 404 carrying `NegativeCache.SKIP_HEADER` (never negative-cached); 429 or 503 with `Retry-After` → 503 + `Retry-After`; other 5xx and a failed call → 502; a circuit-open/bulkhead refusal keeps its marker. Where the internal API only returns "found or not" (docker `Layers`/`Manifests`), the upstream answer travels as `UpstreamStatusException` and a failed source is never read as "absent".
  A `DELETE` on a non-docker proxy path evicts the cached copy (`ProxyEvictSlice` + `ProxyPathCaches`), never contacting the upstream.
- `group` — sequential member walk in declared YAML order: first 2xx (or 304/403 — authoritative) wins, 404 falls through to the next member, any other member 4xx except 429 falls through without conviction (walk marked unverified, so the terminal 404 is not negative-cached), genuine 5xx/429 records a member failure and continues. At the end of a walk nobody won, an unreachable proxy does not fail the group: when at least one member said 404 and every member that failed or was skipped (circuit open) is a proxy, the group answers 404 carrying `NegativeCache.SKIP_HEADER` (`WalkState.onlyProxiesUnreachable`), with the failure as `event.reason`; it keeps its 5xx when a hosted member failed or was skipped, when no member said 404, when a proxy's bulkhead refused, and for an index-hit read (the file exists). A member named by a `routing` rule of the group is asked only for the paths its rules match (`RoutedMembers`, applied where `RepositorySlices` hands a group its member resolver — pass `routedMembers(cfg)`, never `this::slice`, to a new group type). A Maven/Gradle group answers `maven-metadata.xml` itself (`MavenGroupSlice`, same rules): it caches the winner's bytes and remembers a miss per node for 10 minutes (`GroupMetadataCache`), a miss only after a walk in which every member answered an authoritative 404. Both are dropped by `FilteredMetadataCacheRegistry` package events (all nodes, channel `cooldown-package`), so every path that stores a `maven-metadata.xml` in a hosted repository MUST call `FilteredMetadataCacheRegistry.invalidateAfterUpload` with the dotted package of `MavenMetadataCoordinates` after the save — a deploy uploads its metadata after the artifact files.

**Repository identity, rename, delete.** A repository has a name (URLs, config, permissions, logs, metrics, every in-memory structure) and an immutable key, `repositories.repo_key` / `RepoConfig.repoKey()`: the directory of its data in its storage and the value of `artifacts.repo_name`. Name and key are equal until a rename. `RepoKeys` (pantera-main, `RepoKeys.shared()`, backed by `DbRepositories`; identity without a database) translates at the two boundaries that use the key: the storage prefix (`RepoConfig.from(..., name, key, ...)`, `RekeyedStorage` for the management API, which addresses `<name>/<path>`) and SQL on `artifacts` (`DbConsumer`, `DbSyncArtifactIndexer`, `DbArtifactIndex`, `JdbcReindexStore`, dashboard, suggest). New SQL on `artifacts` MUST translate; a name with no key belongs to no repository and its event is dropped. Every other table keys by name and is rewritten in the rename transaction (`RepoLifecycleDao.rename`). With a database, `PUT /repositories/:name/move` and `DELETE /repositories/:name` are single transactions that touch no artifact data: a delete moves the row to `repository_tombstones` and queues a `purge` in `repo_jobs`; `RepoJobRunner` (one per node, started in `VertxMain`) claims a job with a database lease, purges with `Storage.listPage` + `deleteBatch`, checkpoints the last key, and is safe to stop anywhere. All job timing uses the database clock (`NOW()`), never the node's. Without a database `RepositoryHandler` keeps the in-request copy/delete (`RepoData.move/remove`). Nodes also reload the repository list every 30 s (`DbRepositories.onChange`) so a lost cluster event is repaired.

**Supporting cache pieces.** `SingleFlight` (thundering-herd protection — concurrent same-key fills collapse to one upstream fetch + one cache write, held until the commit is durable), `NegativeCache` (L1 Caffeine + L2 Valkey; a 404 is remembered **per file** — `NegativeCacheKey.forFile`, version `<version>/<file>` — never per artifact version: a Maven version has files that do not exist, such as `-site.xml`, and their 404 must not hide the POM; `fromPath` only parses name and version), `ProxyCacheWriter` (stream-through tee with integrity verification; rollback after partial failure is fire-and-forget by design). **No `SCAN`/`KEYS` over Valkey on a request, upload or refresh path** — its cost grows with every key of every cache: keys that must be dropped by package or artifact name are listed in an index SET written atomically with the key (`ValkeyKeyIndex`: `metadata-idx:{package}` for `FilteredMetadataCache`, `negative2-idx:{name}` for `NegativeCache`); a cursor `SCAN` is for administrative actions only. Group serving is **stream-through**: a group relays the winning member's body straight to the client and never buffers it — dedup of concurrent same-key fills lives at the proxy member (`coalesceUpstream`), not the group, and `FileStorage` renames cache files atomically (`ATOMIC_MOVE`) so a concurrent reader never sees a half-written file.

**Two circuit breakers — do not conflate them.**

| | Group-member breaker | Upstream HTTP breaker |
|---|---|---|
| Class | `AutoBlockRegistry` (pantera-core), used by `MemberSlice` | `UpstreamCircuitBreaker` (http-client), wired in `JettyClientSlices` |
| Keyed by | member repository name | `scheme://host:port` |
| Trips on | failure rate over sliding window (min-calls gated) | failure rate over sliding window (min-calls gated) |
| Open behavior | member skipped in walks; warm cache still probed via internal `X-Pantera-Cache-Only` header | fast-fail 502 with `X-Pantera-Circuit-Open: true` + `Retry-After`, from the trip until a recovery probe is answered (not until the block ends); the probe is a `HEAD` of the path that last failed, never `/`; Fibonacci backoff, back to its seed only after 10 windows closed (`PROBATION_WINDOWS`) |
| Settings keys | `circuit_breaker_*` in `auth_settings` | `upstream_breaker_*` in `auth_settings` (V136) |
| Admin UI | "Group Member Circuit Breaker" card | "Upstream HTTP Circuit Breaker" card |

Invariants that must hold (they broke once — see the breaker-cascade fix in 2.2.0):
- Adapters MUST preserve the circuit-open marker through error funnels (`UpstreamCircuitOpenException` in pantera-core carries it); collapsing the marked 502 into a generic status exception convicts healthy members on fabricated evidence.
- `GroupResolver` treats a marker-502 as "member skipped" — walk continues, **no** `recordFailure()`.
- All-members-unavailable answers **503 + Retry-After, never 404**: when no member said "not found", nothing supports a 404. A 404 for an unreachable proxy is allowed only by the rule above (another member said 404) and MUST carry `NegativeCache.SKIP_HEADER` — a remembered 404 would outlive the outage.
- A proxy with several remotes marks its 502 with `X-Pantera-Partial-Upstream-Failure` when one remote failed and another answered (`RaceSlice`, `PartialUpstreamFailure` in pantera-core): the 502 stands, but a group MUST NOT `recordFailure()` on it — blocking the member takes its healthy remotes out of the group with the one that is down.
- A group's 503 for a skipped member notes `RequestReasons.CIRCUIT_OPEN` itself: a member skipped on the group's breaker gives no answer to take a reason from.
- The same three rules hold for a bulkhead refusal (`X-Pantera-Bulkhead-Overflow`). Test for either marker with `LocalRefusal` (pantera-core), never for one header alone: `RaceSlice` relays a local refusal with its status and `Retry-After` instead of answering `502 All upstream remotes failed`, group walks skip the member.

**Per-repo bulkhead.** `RepoBulkhead` + `AdaptivePermitController` (pantera-core): one per `*-proxy` repository, created in `RepositorySlices.getOrCreateBulkhead`, found by name through `RepoBulkheadRegistry` on each request; `BaseCachedProxySlice` (the maven proxy) is what it gates. A request beyond the permits **waits** — oldest first, at most 20 s, at most 1000 waiting — and resumes on the bulkhead's executor under its caller's logging context; only then is it refused (503 + `Retry-After` + the marker). The permit count only grows, from `initial_permits` to `max_permits`: nothing lowers it — a slow or failed request is no evidence of overload, and a cold fetch of a large artifact takes seconds on a healthy upstream. A `http_client.bulkhead.*` change replaces every bulkhead in place (`invalidateBulkheads`); dropping one without a replacement leaves its repository ungated, since the slice is cached and looks the bulkhead up by name.

**Outbound HTTP stack** (http-client, per upstream): `RateLimitedClientSlice` (reactive 429/503-Retry-After gate) → `CircuitBreakingClientSlice` → `RetryingClientSlice` → raw `JettyClientSlice`. Loopback hosts bypass the gate and the breaker. `RetryingClientSlice` re-sends a `GET`/`HEAD` with an empty body, up to two more times, when the upstream closed the connection before answering (`FailureCause` reason `connection_closed`: early EOF, reset, closed channel) — upstreams close kept-alive connections, and Jetty does not repeat a request that crossed the close. It never re-sends a request with a body or another method, never after a timeout / refused connection / TLS failure, and never on a status; it sits inside the breaker so a recovered close is not counted against the upstream. A connection lost mid-body is not retried (the caller is already streaming). HTTP/1.1 + keep-alive only (HTTP/2 deliberately disabled — Jetty #12776). Jetty offers `Accept-Encoding: gzip` and decodes the response transparently, except for a request path ending in `.gz`/`.tgz`: `JettyClientSlice` asks for `identity` and hides any `Content-Encoding` of the response from Jetty, because an upstream that labels a stored gzip file with a gzip content-coding (Apache `AddEncoding`) would otherwise have the archive unpacked and cached as its inner tar.

**Database.** Single PostgreSQL, Flyway migrations under `pantera-main/src/main/resources/db/migration/` (next free version = highest existing + 1). `ArtifactDbFactory` builds the HikariCP pool. `DbConsumer` is an RxJava batcher (2 s / 200 events) that sorts by `(repo_name, name, version)` before UPSERTing — the sort prevents deadlocks; failed batches dead-letter with backoff. `DbArtifactIndex` does FTS (tsvector + GIN) with ILIKE fallback. `auth_settings` is the generic key/value store for admin-tunable runtime settings. Dashboard counts require the `pg_cron` extension — deployment prerequisite, not a bug.

**Cluster / HA.** `CacheInvalidationPubSub` (cross-instance Caffeine invalidation over Valkey pub/sub, self-message filtering by instance UUID; its constructor blocks for the SUBSCRIBE ack — boot thread only, never event loop), Valkey-backed token-revocation broadcast (`ValkeyRevocationBlocklist`, selected at boot in `VertxMain` with a DB-polling fallback), Quartz JDBC mode (`PanteraScheduler` shared across nodes). Pure single-instance mode uses RAM Quartz and no Valkey.

**JWT auth.** RS256 (Auth0 java-jwt), one key pair, three token types (`access`, `refresh`, `api`) — the `type` claim is mandatory. Access tokens stateless; refresh/api tokens in `user_tokens` with revocation flags; access-token revocation broadcast over Valkey (entries under `pantera:revoked:user:{username}` / `pantera:revoked:jti:{jti}`, reloaded at boot, written through to `revocation_blocklist` when a DB is configured), 5 s table polling without Valkey. When adding a protected endpoint in `AsyncApiVerticle`, **reuse the shared `jwtAuthHandler` instance** — a new `JWTAuthHandler` skips the blocklist + JTI ownership checks. In the password chain (`YamlSettings.withProviders`), `ApiKeyOnlyAuth` (pantera-core) sends a credential with the form of an API key (`AuthWorkerPool.jwtShaped`) to the `jwt-password` provider alone and every other credential to the password providers alone: a rejected key MUST NOT reach a provider that asks an external identity provider, where it would count as a failed login of that user. A new password provider goes into the password side of that wrapper. `Authentication.user` answers with a user or nothing; code in the chain that knows why it refused a login (second factor asked, account locked, logins delayed) notes an `AuthRefusal` (pantera-core) for the thread, and the entry point takes it when the chain returns: status, `Retry-After`, text and the `event.reason` of the access record (`FailedLogin`). Every entry point that checks a password MUST call the chain through `PasswordCheck`, which reads the refusal on the chain's thread and leaves nothing behind for the next request on a pooled thread; a chain answers on the thread it was called on, and code that changes that has to carry the refusal over itself. Only an entry point where a person signs in marks the thread with `OktaAuthContext.setInteractive(true)`: the Okta provider starts a push or verifies a code for those alone.

**Failed logins (login delay and account lock).** `auth.failedlogins`: `LoginState` holds the rules (count, delay = first delay × failures², lock, automatic unlock, forget time) and nothing else does; `FailedLoginStore` keeps the counts (`DbFailedLoginStore` on `login_failures`, one transaction per failure under the row lock of the name, database clock; `InMemoryFailedLoginStore` without a database; both pass `FailedLoginStoreContract`); `FailedLogins` applies the policy, which it reads from `FailedLoginsSettingsLoader.activeSupplier()` on every decision. The chain from `YamlSettings.withProviders` is `LockedOut` → `ApiKeyOnlyAuth(CountedKeys(keys), GuardedPasswords(passwords))`. `GuardedPasswords` admits a login before a provider is asked (403 locked, 429 delayed), counts a rejected one, and checks the logins of one name one at a time per node, so parallel guesses reach a provider no more often than the lock limit. A login a provider refused for its own reason (it noted an `AuthRefusal`: `mfa_required`, `identity_provider_unavailable`) MUST NOT be counted: a provider that cannot check a password says so with a refusal, never with a bare empty answer. A lock reaches tokens through `UserEnabledCheck.isLocked` (API keys, sessions; `TokenAuthentication.check` carries the 403 to the repository slices) and `FailedLogins.locked`, which answers from a set loaded every 15 s (`YamlSettings.watchLocks`) because every authenticated request asks it; `CachedUsers.bypassFor` keeps a remembered login from passing for a locked user. A name is counted in lower case. A migration that a running stack already applied is not edited: add a new one.

**Thread model — the hard rule.** The Vert.x event loop must never block. Never `.join()`/`.get()` on a `CompletableFuture` in code that may run on the event loop. JDBC and sync I/O go through `DispatchedStorage`/`StorageExecutors` (`pantera-io-{read,write,list}-%d`, tunable via `PANTERA_IO_*_THREADS`). `HandlerExecutor` (API worker pool) has a bounded queue + AbortPolicy **by design** — rejection is visible backpressure, don't "fix" it. `BlockedThreadDiagnostics` warns at >5 s event-loop / >120 s worker block.

**Reactive bodies.** `Content` is a `Publisher<ByteBuffer>`. Bodies must always be consumed, even on error paths — ignoring the publisher leaks ByteBuffers (`resp.body().asBytesFuture().thenAccept(b -> {})` to discard deliberately). Links inside a served JSON body (npm `dist.tarball`, Composer `dist.url`) are rewritten stream-through with `JsonStringRewrite` (pantera-core) — never by buffering and parsing the document. Never hand-write a `Publisher` over a file: `request()` after the terminal signal must be a no-op (the storage file writer requests ahead while writes are queued), so use `Flowable.using` + `Flowable.generate`; cover a save path with a >1 MB body on `FileStorage`, not only `InMemoryStorage`. A body that fails after the response head was sent is broken off, never ended: `ResponseTerminator.fail` calls `GuardedHttpServerResponse.safeAbort` (connection close on HTTP/1.x, `RST_STREAM` on HTTP/2). An `end()` there leaves a client with a `Content-Length` waiting until the idle timeout and completes a chunked body that is truncated. The terminator writes the one record of such a body: `ERROR` `response_stream` for a server-side failure, `WARN` with `event.reason=client_disconnected` when the client closed the connection or reset the stream first (`ResponseTerminator.clientGone`); the promise then fails with `ReportedFailure`, which the serve stages neither log again nor answer — a failed exchange is one record under the request's `trace.id` (`VertxSliceServer` derives the `SpanContext` once and hands it to `EcsLoggingSlice`).

**Last-Modified.** `LastModifiedSlice` (pantera-core), wired for every non-docker repository in `RepositorySlices.wrapIntoCommonSlices`, dates each `GET`/`HEAD` 200/206 that carries no `Last-Modified`. The date normally costs no storage request: a storage returns the modification time with the bytes (`Content.lastModified()` — `FileStorage` from the attributes it reads for the size, `S3Storage` from the `GetObject` response, the S3 disk cache from its entry sidecar) and the slice takes it from the response body. So **keep the storage's `Content` as the response body**; rebuilding it (`new Content.From(size, publisher)`) drops the date and falls back to one storage metadata read per download (an S3 `HEAD`, bounded at 500 ms, then the request time). `RepoStorageKey` maps path → key for that fallback (none for checksum/signature files). An adapter that dates its own response is left alone; use the `LastModified` header class — never `RFC_1123_DATE_TIME`, which drops the day's leading zero.

**HEAD.** A read route answers `HEAD` wherever it answers `GET`. A slice that only knows `GET` is wrapped in `HeadAsGetSlice` (pantera-core) and routed with `new RtRule.Any(MethodRule.GET, MethodRule.HEAD)`: it returns the `GET`'s status and headers, drains the body without buffering it, and keeps the date the body carries. A route bound to `MethodRule.GET` alone answers 404/405 to `curl -I` for a file that exists (`nuget` and `conan` still route their reads that way).

**Module map.** `pantera-core` (Slice, Storage interface, cache, security framework, cluster bus, audit), `pantera-storage/{core,vertx-file,s3}`, `pantera-main` (entry `VertxMain`, REST API `AsyncApiVerticle`/`AdminAuthHandler`, DB layer, Flyway, Quartz, `RepositorySlices` wiring, `GroupResolver`), `vertx-server` (Vert.x HTTP adapter), `http-client` (`JettyClientSlices` + rate limit + breaker), `pantera-backfill` (per-format storage scanners reused by `POST /api/v1/search/reindex` + the standalone CLI, shaded as the `-cli` jar) / `pantera-import-cli` (standalone CLI), `*-adapter` (one per format), `pantera-ui` (Vue 3 + PrimeVue + Pinia), `build-tools` (PMD ruleset jar), `test_images/` (client images for integration tests).

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

**Every non-2xx says why.** The access record (`http_request`) of a status ≥ 400 carries `event.reason`. The place that knows the cause notes it — `RequestReasons.instance().note(requestHeaders, token)`; `ServerErrorLog` does so for every 5xx cause — and `ReasonSlice`, the outermost wrapper of every repository (`RepositorySlices.wrapIntoCommonSlices`), puts the reason that explains most (`ReasonWeight`) on the answer in the internal header `X-Pantera-Ctx-Reason`; a group notes its members' reasons; `EcsLoggingSlice` writes it and strips the header. `ReasonSlice` also names its repository on every answer (`AnsweringRepository`: `X-Pantera-Ctx-Repo-Name` / `-Type`, a group's stamp replaces its member's); `EcsLoggingSlice` — which sits above the routing and is built without a repository — takes `repository.name` / `repository.type` of the access record from it, `user_agent.original` from the request, and strips the headers. A new non-2xx path that knows more than its status (cache answer, upstream answer, failure of an upstream call) MUST note a reason; a 404 that stands for a failure must never read `not_found`. Tokens are lower-case `[a-z0-9_]`, documented in `docs/admin-guide/logging.md`. For a request that carries `X-Pantera-Ctx-Trace` (only the admin Troubleshoot replay sets it; a client-sent one is dropped), `ReasonSlice` also records one `RequestTrace` step per repository asked: name, type, status, reason, milliseconds.

**Shutdown.** `VertxMain.main` turns Log4j's own shutdown hook off (`log4j2.shutdownHookEnabled=false`) and the Pantera shutdown hook calls `LogManager.shutdown()` as its last step: with Log4j's hook, logging stopped beside the stop sequence and its records were lost. A step added to `VertxMain.stop()` logs its outcome. The stop also closes `RepoJobRunner`, which gives back the job in progress.

**Correlation across async hops:** `EcsLoggingSlice` is the client entry point: it drops every client-sent internal header (`X-Pantera-Internal`, `pantera_login`, `X-Pantera-Ctx-*`) and stamps `X-Pantera-Ctx-Trace-Id` / `X-Pantera-Ctx-Client-Ip` on every inbound request; downstream slices restore MDC on worker threads via `RequestContextHeaders.bindToMdc(headers)` inside their async callbacks (the headers override stale pooled-thread MDC). `ArtifactEvent`/`ProxyArtifactEvent` auto-capture `traceId`/`clientIp` from MDC at construction; local publish paths build their event after async hops, so they bind the request's context explicitly with `ArtifactEvent.withRequestContext(headers)`. `DbConsumer` restores them before audit emission. A future completed by a shared thread crosses back into request code through `new CallerContext().resume(...)` (pantera-core): Valkey reads of the L2 caches, the upstream answer and its body in `JettyClientSlice`, `DispatchedStorage`. The continuation runs under the caller's logging context and the thread gets its own back afterwards, so a context bound there (`bindToMdc`) never stays on a Lettuce / Jetty / `pantera-io-*` thread — a record without `trace.id` is acceptable for request-less work, one with another request's `trace.id` is a bug. For the same reason `EcsLoggingSlice` restores the event loop's context when the synchronous part of the slice chain returns; a record written in a later callback is correlated with `EcsLogger.requestContext(headers)` / `traceContext(...)`, not by what the thread happens to hold. Sign-in records follow the same rule: `PasswordCheck` runs the chain under the context of its own login (the user name being checked, `trace.id` and `client.ip` from the request headers, nothing of the submitting thread) and gives the thread its context back; `UnifiedJwtAuthHandler` validates under the caller's context with `user.name` removed, because the owner of a token is known only once it is verified and `EcsLogger` lets a key of the thread's context win over the field a record names. One `trace.id` must connect the inbound request, outbound calls, pub/sub envelopes, Quartz jobs, and the audit record.

Metadata-only requests (a `maven-metadata.xml` GET, a PyPI simple-index render) are **never** `publish`/`access` — only `resolution` covers metadata.

## Observability conventions

- Metric names `pantera.<area>.<thing>` (Micrometer dots → Prometheus underscores; counters gain `_total`). Tags must be bounded: repo tags are capped by `RepoNameMeterFilter` (default 50), the `method` tag by `HttpMethodMeterFilter` (a method outside the standard nine becomes `_other`); never add an unbounded tag (paths, versions, artifact names).
- Every recording call is guarded by `MicrometerMetrics.isInitialized()`.
- The Prometheus registry is created and configured (`VertxMain.configureMeters`: `job` common tag, `RepoNameMeterFilter`, `HttpMethodMeterFilter`, SLO ladders) **before** `Vertx.vertx(...)` and handed to Vert.x through `MicrometerMetricsOptions.setMicrometerRegistry`: Vert.x registers pool and JVM meters while it boots, and a tag or filter added afterwards does not reach them. JVM binders are bound once, by Vert.x (`setJvmMetricsEnabled(mctx.jvm())`) — do not bind them again.
- **A metric without a panel is invisible; a panel without a metric is a lie.** If you add a metric, add its Grafana panel in the same PR (`pantera-main/docker-compose/grafana/provisioning/dashboards/`), and verify the exact exposed name + tags against a live scrape of `:8087/metrics/vertx` before writing the query — several past panels charted metric names that never existed.
- Timers publish NO histogram buckets unless `PANTERA_METRICS_PERCENTILES_HISTOGRAM=true`, which applies two curated SLO ladders (defined in `VertxMain`): **transfer** timers (`pantera.http/proxy/upstream.request.duration`, `pantera.storage.operation.duration`, Vert.x server response time — durations include body streaming) get 18 boundaries up to 20 min; **control-plane** timers get 16 boundaries up to 30 s. Panels for bucket-less timers must chart `sum/count` averages, never `histogram_quantile`.
- Recording cost is bucket-count-insensitive; the cost axis is series count at scrape/TSDB time. Bucket shapes are fixed at meter creation — ladder changes need a restart (unlike DB-backed settings).

## CI & release

Exactly two workflows (`.github/workflows/`):

**ci.yml** — triggers: every PR commit (per-ref concurrency cancels superseded runs) and manual dispatch — deliberately NO push-to-master trigger: branch protection requires the three CI checks green (plus a PR) before merge, so a post-merge rebuild would duplicate an already-green tree. Jobs: cheap static gates first (ECS log-schema drift, nginx CVE guard via `scripts/check-nginx-cve-2026-42945.sh`, JVM CVE guard via `scripts/check-jvm-cve-2026-114938.sh`, git-diff change detection) → backend and UI build/test jobs, path-filtered on PRs. The backend step is a single reactor session — `mvn clean install -T8 --fail-at-end -ntp -Dexec.skip=true` — because standalone `mvn pmd:check` cannot resolve the `build-tools` ruleset jar or sibling-module jars on a clean runner. Do not split it.

Security posture: external contributors' workflow runs require maintainer approval **on every push** (`all_external_contributors`); default workflow token is read-only; only GitHub-owned, SHA-pinned actions (org policy blocks third-party actions — this is why the workflows use `gh` CLI and raw `docker buildx` instead of marketplace actions).

**release.yml** — manual dispatch only (write access), from the Actions tab or `gh workflow run release.yml`. Reads the version from the root POM (`bump-version.sh` is the single source of truth), refuses existing tags, BUILDS without re-testing (`mvn clean install -U -T8 -DskipTests=true -Dexec.skip=true` — the released commit already passed the PR gate), then creates the `v<version>` tag and GitHub release with `pantera-<v>.jar`, `pantera-<v>-dist.tar.gz` (jar + `dependency/`), `pantera-ui-<v>.tar.gz`, `SHA256SUMS`, and publishes multi-arch (amd64+arm64) images **`ghcr.io/auto1-oss/pantera`** and **`ghcr.io/auto1-oss/pantera-ui`** (`:latest` withheld on RC/beta/alpha), all with build-provenance attestations.

Dependabot: weekly grouped updates (maven, npm in `pantera-ui/`, github-actions) and the base images in `pantera-main/` (docker). Docker-compose sample projects are deliberately excluded; their alerts are dismissed as `not_used`. GitHub's dependency graph reads a version set through a POM property as unknown, so Dependabot alerts do **not** cover Vert.x, Netty, Jetty, Jackson, Micrometer or anything transitive: no alert is not evidence. Netty and Jackson are imported as BOMs ahead of the Vert.x BOM in the root POM (`netty.version`, `fasterxml.jackson.version`) so a security release of either does not wait for Vert.x.

**JDK of the images.** Every image definition that launches `com.auto1.pantera.VertxMain` takes its JDK from an `eclipse-temurin` tag that names a release (`eclipse-temurin:21.0.13.1_2-jre-alpine`), never a floating one (`21-jre-alpine`): a floating tag resolves through the build cache, and a cached layer of an older release builds a vulnerable image unnoticed. The release must carry the fix for CVE-2026-114938 (Base64 decoder intrinsic of OpenJDK 17.0.6+ on x86-64 with AVX-512 VBMI, reachable through the `Authorization` header before authentication): 17.0.21.1 / 21.0.13.1 / 25.0.5.1 or later. The CI guard (`scripts/check-jvm-cve-2026-114938.sh`) fails an older or unpinned base unless every launch passes `-XX:-UseBASE64Intrinsics`. `Dockerfile-ubuntu` and `Dockerfile-tests` install the same release from the Temurin archive and declare it as `PANTERA_JDK_VERSION`, which the guard reads (the Ubuntu-based `eclipse-temurin` image of a release may lack arm64). Dependabot (`docker` ecosystem, `/pantera-main`) raises the tag of `pantera-main/Dockerfile`; `PANTERA_JDK_VERSION` is raised by hand with it.

## Testing doctrine

- Unit tests (`*Test.java`, surefire) should not require Docker/network/DB — use `InMemoryStorage`. (Some legacy `*Test.java` use TestContainers; do not add new ones.) Service-dependent tests are `*IT.java`/`*ITCase.java` under `-Pitcase` (failsafe), backed by the 10 client images in `test_images/`. No workflow runs the integration suite automatically — run it on demand: `cd test_images && ./build.sh` then `mvn clean verify -Pitcase -T 1C` (build.sh is Linux-only — GNU sed).
- **Never assert absolute wall-clock latency.** Bounds measured on an idle laptop are scheduler noise on shared CI runners — this class of assertion produced seven distinct red builds before being eradicated. Prove semantics instead:
  - *Ordering*: latches — e.g. "foreground `load()` returns while the refresh loader is still parked" proves background refresh; a `@Timeout` converts a blocking regression into a deterministic failure.
  - *Behavior*: invocation counts — "`calls == 1`" proves no upstream call; recording fakes prove propagation.
  - *Regression guards*: if a duration bound is genuinely wanted, set it ≥10× the idle-laptop value (order-of-magnitude guard) and say so in a comment.
  - *Shared resources*: retry briefly on transient contention (the bounded `HandlerExecutor` queue, freshly-started listeners/TLS handshakes, fire-and-forget cleanup) — poll for the eventual state instead of asserting instantly.
- JUnit 6 + Hamcrest with **matcher objects, not static factories**: `new IsEqual<>(y)`, not `Matchers.equalTo(y)`. Single assertion: no reason string; multiple assertions: every one gets a reason string.
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
5. `pantera-ui` `SettingsView.vue`: new card + `SECTION_META` entry + its refs in `TRACKED` and `SECTION_FIELDS` (these drive save-bar dirty state, per-section baseline commit and Discard) + a save function that resolves `false` on failure; label it so it cannot be confused with sibling settings. Update `configuration-reference.md` + `environment-variables.md`.

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
3. Wire all three modes in `RepositorySlices` (hosted: pass `cfg.immutable()` and wrap in `hostedDelete(...)`; proxy: wrap in `proxyEvict(...)`); emit the four audit events with a captured `AuditContext`.
4. Add itcase coverage (client image in `test_images/` if the format has a CLI client), user-guide page, and sample project under docker-compose artifacts.

## Conventions for changes

- Branches off `master`; PRs into `master`. Conventional Commits with the existing scope vocabulary (`fix(breaker):`, `feat(settings):`, `fix(grafana):`, `fix(npm):`, `test(maven):`, `ci:`, `build(deps):`, `docs(changelog):`) — match, don't invent.
- All production code under `com.auto1.pantera.<module>`.
- Configuration is YAML (`pantera.yml`, `${ENV_VAR}` substitution at load time). Runtime tuning env vars use the `PANTERA_` prefix. `ConfigWatchService` hot-reloads repository configs; main settings need a restart unless DB-backed.
- No feature flags for settled changes — ship full replacements; rollback is `git revert`, not a runtime toggle.
- Never commit secrets; throwaway test keys are generated, never checked in.
