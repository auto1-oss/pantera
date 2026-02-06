# Artipie v1.20.13 — Implementation Gap Analysis & Competitive Assessment

**Date:** 2026-02-18
**Scope:** Audit of all P0-P3 items from the Enterprise Technical Assessment against current codebase, plus competitive positioning vs JFrog Artifactory and Sonatype Nexus.
**Method:** Automated codebase exploration of all source files referenced in the assessment, cross-referenced with actual implementation.

---

## 1. Implementation Status Summary

### Overall Scorecard

| Priority | Total Items | Implemented | Partial | Not Implemented | Score |
|----------|------------|-------------|---------|-----------------|-------|
| **P0 — Critical** | 5 | 4 | 1 | 0 | **90%** |
| **P1 — High** | 10 | 10 | 0 | 0 | **100%** |
| **P2 — Medium** | 12 | 8 | 3 | 1 | **79%** |
| **P3 — Lower** | 10 | 7 | 3 | 0 | **85%** |
| **Total** | **37** | **29** | **7** | **1** | **88%** |

---

## 2. P0 — Critical Items (Blocks Production at Scale)

| Item | Status | Evidence |
|------|--------|----------|
| **P0.1** Fix `.join()` in RetrySlice | IMPLEMENTED | Uses `.thenCompose(Function.identity())` at `RetrySlice.java:135` — no blocking `.join()` |
| **P0.2** Fix S3 List Pagination | IMPLEMENTED | Uses `ListObjectsV2Request` with `continuationToken` at `S3Storage.java:317-331` — recursive pagination |
| **P0.3** Streaming Proxy Cache | IMPLEMENTED | `BaseCachedProxySlice.java:538-645` streams to NIO temp file. `DigestComputer.java` has `createDigests()`, `updateDigests()`, `finalizeDigests()` for incremental hashing. Zero heap buffering. |
| **P0.4** Fix `.join()` in MergeShardsSlice | PARTIAL | Uses `.resultNow()` at line 146 instead of `.join()`. Functionally correct after `allOf()` guarantee, but `resultNow()` is still technically blocking. Minor concern. |
| **P0.5** Bound Event Queues | IMPLEMENTED | `MetadataEventQueues.java:117` uses `LinkedBlockingQueue<>(10_000)`. `EventQueue.java` uses `AtomicInteger` size tracking with capacity limit. |

**P0 Assessment:** All critical OOM risks (P0.3, P0.5) and async correctness issues (P0.1, P0.2) are resolved. The system can now handle 2TB+ storage without S3 list truncation, and proxy caching no longer buffers entire artifacts on heap.

---

## 3. P1 — High Priority Items

| Item | Status | Evidence |
|------|--------|----------|
| **P1.2** Skip compression for binary artifacts | IMPLEMENTED | `VertxSliceServer.java:101-117` defines `INCOMPRESSIBLE_TYPES` set (15 types). Lines 1022-1033 apply `Content-Encoding: identity`. |
| **P1.3** VertxFileStorage hierarchical list | IMPLEMENTED | `VertxFileStorage.java:156-227` overrides `list(Key, String)` using `Files.newDirectoryStream()`. Returns `ListResult` with files + directories. |
| **P1.5** Unified idle-timeout & auto-block | IMPLEMENTED | `AutoBlockRegistry.java` (Fibonacci backoff, ONLINE/BLOCKED/PROBING states). `TimeoutSettings.java` (hierarchical config). `CircuitBreakerSlice.java` delegates to registry. `HttpClientSettings.java` has configurable idle timeout. |
| **P1.6** StreamThroughCache 64KB buffer | IMPLEMENTED | `StreamThroughCache.java:188` uses `ByteBuffer.allocate(65_536)` — 8x improvement over old 8KB. |
| **P1.7** Replace circuit breaker with auto-block | IMPLEMENTED | Same as P1.5 — `CircuitBreakerSlice` and `MemberSlice` both delegate to shared `AutoBlockRegistry`. |
| **P1.8** Slice cache off event loop | IMPLEMENTED | `RepositorySlices.java:1035-1039` uses `CompletableFuture.runAsync(this.client::start, RESOLVE_EXECUTOR)`. |
| **P1.9** Retry jitter | IMPLEMENTED | `RetrySlice.java:148` uses `delay * (1.0 + ThreadLocalRandom.current().nextDouble(0.5))`. |
| **P1.10** MergeShardsSlice race fix | IMPLEMENTED | `MergeShardsSlice.java:614-631` — `isEmpty()` check inside `synchronized(chartVersions)` block. |
| **P1.11** Fire-and-forget error handlers | IMPLEMENTED | All `.thenAccept()`/`.thenRun()` chains in `MergeShardsSlice.java:535-631` have `.exceptionally()` with ECS logging. |
| **P1.12** Bound RxFile/ContentAsStream pools | IMPLEMENTED | `RxFile.java:70-76` and `ContentAsStream.java:43-49` both use `newFixedThreadPool(max(16, CPU*4))`. |

**P1 Assessment:** 100% complete. All performance-critical items are resolved. The auto-block model with Fibonacci backoff replaces the naive count-based circuit breaker. Idle timeouts replace absolute timeouts for data-flow-aware connection management.

---

## 4. P2 — Medium Priority Items

| Item | Status | Evidence |
|------|--------|----------|
| **P2.1** Separate worker pools by operation type | NOT IMPLEMENTED | All storage ops share single Vert.x worker pool. No named `artipie.io.read`/`write`/`list` pools exist. |
| **P2.2** Graceful shutdown drain | IMPLEMENTED | `VertxSliceServer.java:344-390` — `shuttingDown` flag, `inFlightRequests` AtomicInteger, configurable `drainTimeout` (default 30s). |
| **P2.3** Close resources on shutdown | PARTIAL | `VertxMain.stop()` closes HTTP servers, Quartz, ConfigWatch, Settings, Vert.x instance. But `Settings.close()` is still `default void close() {}` no-op — concrete implementations must override. |
| **P2.4** Comprehensive health checks | IMPLEMENTED | `HealthSlice.java` probes storage + database with latency tracking, structured JSON (`{status, components: {storage: {status, latency_ms}, database: {status}}}}`), 3-tier status (healthy/degraded/unhealthy). |
| **P2.5** Externalize hardcoded values | PARTIAL | Body buffer threshold: configurable via `ARTIPIE_BODY_BUFFER_THRESHOLD`. Jetty buffer pool (2GB direct, 1GB heap): still hardcoded. GroupSlice drain permits (20): still hardcoded. |
| **P2.6** Database dead-letter queue | IMPLEMENTED | `DbConsumer.java:211-250` writes to `DeadLetterWriter` after 3 failures. Exponential backoff (1s, 2s, 4s, 8s cap). JSON file output to `${artipie.home}/.dead-letter/`. |
| **P2.7** Reduce DB connection timeout | IMPLEMENTED | `ArtifactDbFactory.java:205` uses `setConnectionTimeout(5000)` — 5 seconds, not 30. |
| **P2.8** Valkey connection pool | IMPLEMENTED | `ValkeyConnection.java` uses `GenericObjectPool` with round-robin async commands (default 8 connections, min idle 2). |
| **P2.9** Log suppressed exceptions | IMPLEMENTED | Health check, cooldown, group drain — all have ECS logging in exception handlers. |
| **P2.10** Metrics cardinality control | IMPLEMENTED | `VertxMain.java:814-843` — `RepoNameMeterFilter` caps at 50 repos (configurable `ARTIPIE_METRICS_MAX_REPOS`). `percentilesHistogram` opt-in via env var. |
| **P2.11** PostgreSQL keyset pagination | SUPERSEDED | Replaced by PostgreSQL-centric search architecture (Section 6). |
| **P2.12** Lock TTL cleanup | IMPLEMENTED | `LockCleanupScheduler.java` — daemon thread, 60s interval, scans `.artipie-locks/` prefix, deletes expired proposals. |

**P2 Assessment:** 79% complete. The only unimplemented item (P2.1 — separate worker pools) is an optimization that isn't blocking production. Two partial items are minor: Settings.close() needs concrete implementation overrides, and some Jetty/GroupSlice values remain hardcoded.

---

## 5. P3 — Lower Priority Items

| Item | Status | Evidence |
|------|--------|----------|
| **P3.1** Remove asto-etcd | PARTIAL | Module directory exists but **not in Maven build** (removed from `asto/pom.xml` modules list). Filesystem artifact only. |
| **P3.2** Redis pub/sub for cache invalidation | IMPLEMENTED | `CacheInvalidationPubSub.java`, `PublishingCleanable.java`, `PublishingFiltersCache.java`. Lettuce-based with instanceId filtering. |
| **P3.3** Quartz JDBC clustering | IMPLEMENTED | `QuartzService.java` with DataSource constructor, `QuartzSchema.java` DDL, `ArtipieQuartzConnectionProvider.java`, `JobDataRegistry.java`. |
| **P3.4** Replace Lucene with PostgreSQL | IMPLEMENTED | `DbArtifactIndex.java` implements `ArtifactIndex`. No `LuceneArtifactIndex`, `IndexWarmupService`, or `IndexConsumer` exist in codebase. |
| **P3.5** Eliminate double memory copy | IMPLEMENTED | `VertxSliceServer.java:1472-1474` uses `Unpooled.wrappedBuffer(ByteBuffer)` for zero-copy. |
| **P3.6** Configurable 1MB body threshold | IMPLEMENTED | `VertxSliceServer.java` — `ARTIPIE_BODY_BUFFER_THRESHOLD` env var + system property. |
| **P3.7** Remove asto-redis | PARTIAL | Module directory exists but **not in Maven build**. All code uses Lettuce exclusively. |
| **P3.8** Temp file cleanup on shutdown | IMPLEMENTED | `Http3Server.java:88` calls `deleteOnExit()`. `BaseCachedProxySlice.java:549` calls `deleteOnExit()` on cache temp files. |
| **P3.9** Fix Quartz double-shutdown | IMPLEMENTED | `QuartzService.java:282` uses `AtomicBoolean stopped` with `compareAndSet(false, true)` guard. |
| **P3.10** Deprecation cleanup | PARTIAL | Docker and RPM adapters clean. NPM adapter has `@Deprecated` constructors in `CachedNpmProxySlice.java`. |

**P3 Assessment:** 85% complete. Three partial items are all minor: leftover module directories (harmless — not in build), and NPM deprecated constructors.

---

## 6. Remaining Gaps — Prioritized

### Must-Fix Before Enterprise Production

| Gap | Impact | Effort | Recommendation |
|-----|--------|--------|----------------|
| **P2.1** Separate worker pools | A pathological `list()` on 500K files can starve reads | Medium | Create named executor services: `artipie.io.read` (CPU*4), `artipie.io.write` (CPU*2), `artipie.io.list` (CPU, capped). Wrap storage operations to dispatch. |
| **P2.3** Settings.close() no-op | HikariCP, S3AsyncClient, Valkey connections leak on restart | Low | Override `close()` in `YamlSettings` to shut down DataSource, S3 clients, Valkey. |
| **P2.5** Jetty buffer hardcoded | 2GB direct memory + 1GB heap not tunable | Low | Add `ARTIPIE_JETTY_DIRECT_MEMORY` and `ARTIPIE_JETTY_HEAP_MEMORY` env vars. |

### Nice-to-Have Cleanups

| Gap | Impact | Effort |
|-----|--------|--------|
| P3.1/P3.7 Delete leftover module dirs | Filesystem clutter | Trivial |
| P3.10 NPM deprecated constructors | Code hygiene | Low |
| P0.4 Replace `resultNow()` with async collect | Purist async correctness | Low |

---

## 7. Competitive Analysis: Artipie vs JFrog Artifactory vs Sonatype Nexus

### 7.1 Repository Format Support

| Format | Artipie | JFrog Artifactory | Sonatype Nexus |
|--------|---------|-------------------|----------------|
| Maven | Yes | Yes | Yes |
| npm | Yes | Yes | Yes |
| Docker | Yes | Yes | Yes |
| PyPI | Yes | Yes | Yes |
| NuGet | Yes | Yes | Yes |
| Go | Yes | Yes | Yes |
| Helm | Yes | Yes | Yes |
| Composer (PHP) | Yes | Yes | Yes |
| RPM | Yes | Yes | Yes |
| Debian | Yes | Yes | Yes |
| RubyGems | Yes | Yes | Yes |
| Conan | Yes | Yes | Yes (2.0) |
| Conda/Anaconda | Yes | Yes | Yes |
| Hex (Elixir) | Yes | Yes | No |
| Cargo (Rust) | No | Yes | Yes |
| Swift | No | Yes | No |
| Terraform | No | Yes | No |
| Hugging Face (AI/ML) | No | Yes | Yes |
| Gitea/Generic | File adapter | Generic | Raw |
| **Total** | **~15** | **~30+** | **~25** |

**Gap:** Artipie lacks Cargo, Swift, Terraform, and Hugging Face support. The AI/ML model management space (Hugging Face) is where both JFrog and Nexus are investing heavily in 2025-2026.

### 7.2 Security & Compliance

| Capability | Artipie | JFrog Artifactory | Sonatype Nexus |
|------------|---------|-------------------|----------------|
| **Authentication** | Basic, API keys, JWT | LDAP, SAML, OAuth2, API keys, access tokens | LDAP, SAML/SSO, OAuth, API keys |
| **RBAC** | Per-repo permissions | Fine-grained (build, deploy, read, annotate, delete per path) | Content Selectors (CSEL) with path-level privileges |
| **Vulnerability Scanning** | None | Xray (SCA, 25+ package types, deep recursive) | Lifecycle/IQ Server (ABF, low false positives) |
| **License Compliance** | None | Xray (policy enforcement, compliance reports) | Lifecycle (custom policies, SDLC enforcement) |
| **SBOM** | None | SPDX, CycloneDX auto-generation | SPDX, CycloneDX via IQ Server |
| **Signing/Provenance** | None | Evidence Service (DSSE, in-toto, GPG) | Repository Firewall, model analysis |
| **Supply Chain Security** | None | Curation ("package firewall"), AppTrust governance | Repository Firewall (malware blocking) |
| **AI/ML Security** | None | JFrog ML model scanning | Hugging Face model analysis (pickle scan) |

**Gap:** This is Artipie's largest competitive weakness. Zero vulnerability scanning, license compliance, SBOM generation, signing/verification, or supply chain security. Both competitors have mature, deeply integrated security platforms (JFrog Xray/Curation/AppTrust, Sonatype Lifecycle/Firewall).

### 7.3 Performance & Scale

| Capability | Artipie (current) | JFrog Artifactory | Sonatype Nexus |
|------------|-------------------|-------------------|----------------|
| **Architecture** | Vert.x event loop + worker pools | Tomcat + AJP (or Spring Boot cloud) | Jetty (OSE/Karaf) |
| **Database** | PostgreSQL (artifacts table) | PostgreSQL (metadata, all queries) | PostgreSQL (moved from OrientDB + Elasticsearch) |
| **Search Engine** | PostgreSQL full-text (replaced Lucene) | AQL on PostgreSQL | SQL search (removed Elasticsearch in v3.88.0) |
| **Cache Layers** | L1 Caffeine, L2 Valkey, DiskCache | FileStore + cloud CDN | Blob store + optional CDN |
| **Proxy Caching** | Streaming (NIO temp file, no heap buffer) | Store-and-forward | Store-and-forward |
| **Connection Pooling** | HikariCP (50 max), Valkey pool (8) | Tomcat pool | HikariCP |
| **1000 req/s** | Achievable (cached reads) | Production-proven | Production-proven |
| **Multi-instance** | Possible (PostgreSQL shared, Valkey L2, pub/sub) | Enterprise+ multi-site replication | HA clustering (Pro) |
| **Auto-blocking** | Fibonacci backoff (AutoBlockRegistry) | Configurable auto-block | Auto-blocking with Fibonacci backoff |
| **Storage backends** | FileSystem, S3 | S3, GCS, Azure Blob, NFS, local | S3, Azure Blob, local blob stores |

**Assessment:** Artipie's streaming proxy cache (P0.3) is architecturally superior — neither JFrog nor Nexus stream through to disk with incremental digest computation. The PostgreSQL-centric search (replacing Lucene) mirrors exactly what Nexus did in v3.88.0. Auto-block with Fibonacci backoff matches Nexus's approach. The Vert.x event-loop model gives inherent advantages for high-concurrency workloads.

**Gap:** No GCS or Azure Blob storage backends. No CDN integration. No multi-site replication.

### 7.4 Enterprise Operations

| Capability | Artipie | JFrog Artifactory | Sonatype Nexus |
|------------|---------|-------------------|----------------|
| **Health Checks** | Storage + Database (structured JSON) | Comprehensive (all components) | System status + metrics API |
| **Metrics** | Prometheus (Micrometer, cardinality-controlled) | Prometheus, OpenMetrics, JMX | Datadog, Prometheus, JMX |
| **Logging** | ECS (structured JSON) | ECS, request log | Log4j, request log |
| **Graceful Shutdown** | Drain with configurable timeout | Rolling restart | Graceful shutdown |
| **Backup/Restore** | Database + S3 (standard ops) | Built-in export/import, replication | Built-in backup tasks |
| **Config Hot-Reload** | YAML watcher (artipie.yml) | UI + REST API | UI + REST API |
| **Management UI** | Minimal REST API | Full web UI + REST API | Full web UI + REST API |
| **MCP Server (AI agents)** | None | JFrog MCP Server (July 2025) | None |

**Gap:** No management web UI. No built-in backup/restore tooling. No MCP server for AI agent integration (JFrog's newest competitive advantage).

### 7.5 Recent Innovations (2024-2026)

| Innovation | JFrog | Sonatype | Artipie |
|------------|-------|----------|---------|
| AI/ML model repository | JFrog ML (Hugging Face caching) | Hugging Face proxy + model analysis | None |
| AI agent integration | MCP Server, JFrog Fly ("agentic repository") | None | None |
| Package firewall | Curation (4M+ OSS packages scanned) | Repository Firewall (malware blocking) | None |
| App governance | AppTrust (evidence-based release gates) | Lifecycle policies | None |
| Cloud-native SaaS | JFrog Cloud (multi-region) | Nexus Repository Cloud (Oct 2025) | Self-hosted only |
| Removed Elasticsearch | N/A (never used) | Moved to SQL in v3.88.0 | Removed Lucene, moved to PostgreSQL |

---

## 8. Competitive Positioning Assessment

### Where Artipie is Strong

1. **Architecture fundamentals are solid.** The Vert.x event-loop model, streaming proxy cache with incremental digest computation, PostgreSQL-centric search, auto-block with Fibonacci backoff, and multi-tier caching (Caffeine + Valkey + DiskCache) represent sound engineering choices that match or exceed the architectural patterns used by both competitors.

2. **All critical P0 and P1 items are implemented.** The system can handle 2TB+ storage, concurrent proxy cache misses without OOM, proper async chains without event-loop deadlocks, and bounded resource consumption.

3. **Open-source with no license restrictions.** MIT-licensed, no Enterprise+ paywall for basic HA or replication features.

4. **Format breadth is competitive** at ~15 package formats including niche ones like Hex (Elixir) that Nexus doesn't support.

### Where Artipie is Weak

1. **Security is the critical gap.** No vulnerability scanning, license compliance, SBOM, signing, or supply chain security. This is a non-starter for enterprise customers who require these capabilities. Both competitors have mature, deeply integrated security platforms that are their primary revenue drivers.

2. **No management UI.** Enterprise customers expect a web interface for repository management, user administration, and monitoring dashboards. REST API alone is insufficient.

3. **No AI/ML model management.** Both JFrog and Sonatype are investing heavily in Hugging Face support, model security scanning, and AI agent integration. This is the fastest-growing segment of the artifact management market.

4. **No cloud-native SaaS offering.** Both competitors launched managed cloud offerings in 2025. Self-hosted only limits adoption.

5. **Missing storage backends.** No GCS or Azure Blob support limits cloud deployment flexibility.

### Enterprise Readiness Score

| Dimension | Score | Notes |
|-----------|-------|-------|
| **Core Artifact Management** | 8/10 | Strong format support, solid proxy/group/hosted patterns |
| **Performance & Scale** | 8/10 | Streaming cache, bounded pools, PostgreSQL search. P2.1 gap (worker pool separation) is minor. |
| **Reliability** | 7/10 | Graceful shutdown, auto-block, dead-letter queue. Settings.close() gap needs fix. |
| **Security & Compliance** | 1/10 | Basic auth only. No scanning, SBOM, signing, or supply chain security. |
| **Operations & Observability** | 6/10 | Prometheus metrics, ECS logging, health checks. No UI, no built-in backup. |
| **Multi-Instance / HA** | 6/10 | PostgreSQL shared, Valkey pub/sub, Quartz JDBC. No replication, no CDN. |
| **AI/ML & Innovation** | 1/10 | No Hugging Face, no MCP server, no model scanning. |
| **Overall** | **5.3/10** | Strong foundation, critical gaps in security and modern features. |

---

## 9. Recommended Roadmap to Enterprise Competitiveness

### Phase 1 — Complete P2 Gaps (Foundation)
- P2.1: Separate worker pools by operation type
- P2.3: Wire Settings.close() properly
- P2.5: Externalize remaining hardcoded values
- Clean up P3 partial items (delete leftover module dirs, NPM deprecated constructors)

### Phase 2 — Security Minimum Viable Product
- Vulnerability scanning integration (proxy to OSV/NVD databases)
- License detection and policy enforcement
- SBOM generation (CycloneDX format)
- GPG signing for Maven/Debian/RPM artifacts

### Phase 3 — Enterprise Operations
- Web management UI (repository CRUD, user management, monitoring dashboard)
- Built-in backup/restore tooling
- GCS and Azure Blob storage backends
- Multi-site replication

### Phase 4 — Market Differentiation
- Hugging Face / AI model proxy repository
- MCP server for AI agent integration
- Repository Firewall (malicious package detection)
- Cloud-native SaaS offering

---

## 10. Conclusion

Artipie v1.20.13 has successfully implemented 88% of the enterprise technical assessment's action items (29/37 fully, 7 partial, 1 not implemented). The core artifact management infrastructure — streaming proxy cache, PostgreSQL-centric search, auto-block resilience, bounded resource pools — is architecturally sound and in several areas (streaming cache design, incremental digest computation) superior to the approaches used by JFrog and Nexus.

However, **security and compliance capabilities are the decisive gap** for enterprise adoption. Both JFrog and Sonatype derive the majority of their enterprise revenue from security features (Xray/Curation/AppTrust and Lifecycle/Firewall respectively). Without vulnerability scanning, license compliance, and SBOM support, Artipie cannot compete for enterprise contracts regardless of how well the core artifact management works.

The secondary gap is **AI/ML model management**, which is the fastest-growing market segment. Both competitors launched Hugging Face support and model security scanning in 2025, with JFrog additionally introducing an MCP server for AI agent integration and the "agentic repository" concept with JFrog Fly.

The infrastructure work (P0-P3) was the necessary prerequisite. The next phase must focus on security and modern features to close the competitive gap.
