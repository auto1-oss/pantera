# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Pantera is a multi-format binary artifact registry (Maven, Docker, npm, PyPI, Go, Composer, Helm, NuGet, Debian, RPM, Conda, Conan, Hex, Gem, generic files), based on Artipie. Java 21 + Maven multi-module backend, Vue 3 + Vite UI in `pantera-ui/`. Tech stack: Vert.x 4.5 (HTTP server), Jetty 12 (HTTP client), PostgreSQL + HikariCP, Valkey (Lettuce), Quartz, Log4j2 with ECS JSON layout, Micrometer/Prometheus, RxJava in adapter internals.

## Common commands

Java:
- `mvn clean verify` -- full build: compile, unit tests, PMD, license headers.
- `mvn test` -- unit tests only (Surefire, `*Test.java`).
- `mvn verify -Pitcase` -- integration tests (Failsafe, `*IT.java` / `*ITCase.java`, requires Docker for TestContainers).
- `mvn install -pl maven-adapter -am -DskipTests` -- build one module + its deps. Replace module name as needed.
- `mvn test -pl pantera-core -Dtest=NegativeCacheTest` -- single test class.
- `VALKEY_HOST=localhost mvn test -pl pantera-core` -- run Valkey-gated tests (annotated `@EnabledIfEnvironmentVariable(named = "VALKEY_HOST", ...)`).
- `mvn install -DskipTests -Dpmd.skip=true` -- fastest build, skips both tests and PMD.
- `mvn license:format` -- add missing GPL-3.0 headers (`LICENSE.header`); required on every Java file.
- `mvn clean install -U -DskipTests -T 1C` -- parallel build.

Local stack (Docker Compose, uses `.env.dev`):
- `make up` / `make down` / `make logs` / `make ps` / `make rebuild` -- the Makefile chains `pantera-main/docker-compose/docker-compose.yaml` with `docker-compose.dev.yaml`.

UI (`cd pantera-ui`):
- `npm install && npm run dev` -- Vite dev server.
- `npm test` (Vitest), `npm run lint` (ESLint), `npm run type-check` (vue-tsc), `npm run build` (type-check then bundle).

Version bumps: `./bump-version.sh <new-version>` updates all modules, Docker Compose tags, `.env*`, and Dockerfile in one shot. Do not edit versions by hand across modules.

## Architecture: what to know before editing

**Slice pattern.** Every HTTP handler -- adapters, middleware, auth, routing -- implements `com.auto1.pantera.http.Slice`: `CompletableFuture<Response> response(RequestLine, Headers, Content)`. Slices compose via decorators (`Slice.Wrap`, `LoggingSlice`, `TimeoutSlice`, `CombinedAuthzSliceWrap`, etc.). Naming: `*Slice` for HTTP handlers, `*Storage` for storage implementations -- enforced by convention, not by PMD.

**Storage abstraction.** `com.auto1.pantera.asto.Storage` is the universal blob interface; all methods return `CompletableFuture`. Implementations: `FileStorage` (Vert.x NIO), `S3Storage`, `InMemoryStorage` (tests only), `SubStorage` (prefix-scoped). `DispatchedStorage` wraps any backing storage and routes completions to dedicated `StorageExecutors` pools (READ / WRITE / LIST) so slow writes don't starve fast reads. For S3 there's `DiskCacheStorage` -- a read-through on-disk LRU/LFU cache with watermark eviction.

**Three repository modes per format.** `local` (Pantera is authoritative), `proxy` (caching reverse proxy with `BaseCachedProxySlice` 7-step pipeline: negative-cache, pre-process, cacheability, cache-first lookup, cooldown, deduplicated upstream fetch, store + digest), `group` (merge of locals + proxies; `GroupSlice` fans out only to proxy members on index miss). See `pantera-main/.../RepositorySlices.java` for the wiring switch.

**Caching pipeline.** `BaseCachedProxySlice` is the template-method base every proxy extends; override `isCacheable`, `buildCooldownRequest`, `digestAlgorithms`, `buildArtifactEvent`, `postProcess`, `generateSidecars`. Supporting pieces: `RequestDeduplicator` (thundering-herd protection via `ConcurrentHashMap<Key, InFlightEntry>`; zombie sweeper at 60s), `NegativeCache` (L1 Caffeine + L2 Valkey with `SCAN+DEL` invalidation).

**Database.** Single PostgreSQL with Flyway migrations under `pantera-main/src/main/resources/db/migration/`. `ArtifactDbFactory` builds the HikariCP pool. `DbConsumer` is an RxJava `PublishSubject` that batches artifact events (2 s windows / 200 events) and sorts by `(repo_name, name, version)` before UPSERTing -- the sort prevents deadlocks under concurrent writes. After 3 failed batches events go to a dead-letter directory with exponential backoff. `DbArtifactIndex` does FTS via `tsvector` + GIN, with an `ILIKE` substring fallback when tsvector returns zero rows. Dashboard counts require the `pg_cron` PostgreSQL extension (materialized views) -- without it the dashboard shows zeros, this is a deployment prerequisite, not a bug.

**Cluster / HA.** `DbNodeRegistry` (PostgreSQL heartbeats), `ClusterEventBus` (Valkey pub/sub, self-message filtering by instance UUID), `CacheInvalidationPubSub` (cross-instance Caffeine invalidation), Quartz JDBC mode (uses `QRTZ_*` tables, scheduler name `PanteraScheduler` shared across nodes). Pure single-instance mode uses RAM Quartz and skips Valkey.

**JWT auth.** RS256 with Auth0 `java-jwt`. Three token types (`access`, `refresh`, `api`) sharing one key pair -- the `type` claim is mandatory and missing-type tokens are rejected even with a valid signature. Access tokens are stateless; refresh/api tokens live in `user_tokens` with revocation flags. Access-token revocation is broadcast over Valkey pub/sub (`pantera:revoke:user:{username}`) into an in-memory blocklist; without Valkey nodes poll the table every 30 s. When adding a protected endpoint in `AsyncApiVerticle`, **reuse the shared `jwtAuthHandler` instance** -- creating a new `JWTAuthHandler` skips the blocklist + JTI ownership checks.

**Thread model -- the hard rule.** The Vert.x event loop (`vert.x-eventloop-thread-*`) must never block. Never call `.join()` / `.get()` on a `CompletableFuture` in code that may run on the event loop. JDBC, sync file I/O, anything blocking goes through `DispatchedStorage` or `StorageExecutors`. `BlockedThreadDiagnostics` warns at >5 s event-loop block / >120 s worker block. The named pools: `pantera-io-read-%d` (CPU x 4), `pantera-io-write-%d` (CPU x 2), `pantera-io-list-%d` (CPU x 1) -- override via `PANTERA_IO_{READ,WRITE,LIST}_THREADS`.

**Reactive body handling.** `Content` is a `Publisher<ByteBuffer>`. Bodies must always be consumed, even on error paths -- ignoring the publisher leaks ByteBuffers (`resp.body().asBytesFuture().thenAccept(b -> {})` if you really want to discard).

**Module map (when you need to find something).** `pantera-core` (Slice, Storage interface, cache, security framework, cluster bus), `pantera-storage/{core,vertx-file,s3}` (storage impls + verification harness), `pantera-main` (entry point `VertxMain`, REST API `AsyncApiVerticle`, DB layer, Flyway, Quartz, `RepositorySlices` wiring), `vertx-server` (Vert.x HTTP adapter), `http-client` (`JettyClientSlices` for proxy fetches), `pantera-backfill` / `pantera-import-cli` (standalone CLIs), `*-adapter` (one per format), `pantera-ui` (Vue 3 + PrimeVue + Pinia).

## PMD and style -- non-obvious rules that fail the build

PMD ruleset: `build-tools/src/main/resources/pmd-ruleset.xml`. `printFailingErrors=true`, so any violation breaks `mvn verify`.

- **No public static methods** except `main(String...)`. This is enforced -- design helpers as instance methods or fields.
- **Only one constructor initializes fields**; secondary constructors must `this(...)` delegate.
- **Cyclomatic complexity** capped at 15 per method / 80 per class; cognitive complexity at 17. Long switches and proxy pipelines bump up against this -- prefer extracting into instance methods or strategy objects.
- **In tests, do not use `Files.createFile`** -- use `@TempDir` or `TemporaryFolder`.
- All Java files need the GPL-3.0 header from `LICENSE.header`; the license-maven-plugin fails the build otherwise. `mvn license:format` adds them.

Test conventions:
- JUnit 5 + Hamcrest, with **matcher objects, not static factories**: `assertThat(x, new IsEquals<>(y))`, not `Matchers.equalTo(y)`.
- Single assertion: no reason string. Multiple assertions: every `assertThat` gets a reason string as the first arg.
- Unit tests (`*Test.java`) **must not** touch Docker/network/DB -- use `InMemoryStorage`. Anything needing a real service is an integration test (`*IT.java` / `*ITCase.java`) under the `itcase` profile, with TestContainers.

## Conventions for changes

- Branches off `master`; Conventional Commits (`feat(maven): ...`, `fix(ui): ...`, `test(npm): ...`). Recent history uses scopes like `fix(pmd):`, `fix(ui):`, `fix(integrity):` -- match the existing scope vocabulary rather than inventing new ones.
- All production code lives under `com.auto1.pantera.<module>`.
- Configuration is YAML (`pantera.yml`); supports `${ENV_VAR}` substitution at load time. Runtime tuning env vars use the `PANTERA_` prefix.
- `ConfigWatchService` hot-reloads repository configs from the config directory -- changes don't require restart for most fields.
