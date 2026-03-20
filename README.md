<p align="center">
  <img src="docs/pantera-banner.png" alt="Pantera Artifact Registry" width="800" />
</p>

<h1 align="center">Pantera Artifact Registry</h1>

<p align="center"><strong>Universal multi-format artifact registry built for enterprise teams.</strong></p>

Pantera is based on [Artipie](https://github.com/artipie/artipie), an open-source binary artifact management tool. The core repository patterns, adapter architecture, and storage abstractions originate from Artipie and its contributors. Pantera builds on this foundation with significant enhancements in security, caching, operational tooling, and a complete management UI.

We gratefully acknowledge the Artipie project and community for their foundational work.

---

## Key Capabilities

### Architecture & Infrastructure
- **Comprehensive REST API** — 15+ endpoint handlers providing full programmatic access to all registry operations
- **Enterprise management UI** — Vue.js application with dark theme, repository file browser, artifact search, operational dashboards, and permission-aware views
- **PostgreSQL-backed persistence** — Settings, authorization policies, cooldown state, and artifact metadata stored in a unified relational model replacing file-based storage
- **Valkey/Redis cache invalidation** — Distributed pub/sub layer for cache coherence across nodes
- **Cluster event bus** — Multi-node state coordination and configuration propagation
- **ECS-structured logging** — JSON logging with full request tracing and audit trail
- **HTTP/2 support** — Via Vert.x server
- **Backfill CLI** — Bulk re-indexing tool for existing repository artifacts into PostgreSQL
- **Import CLI** — Migration tool for moving artifacts from external registries

### Cooldown System
- **Artifact freshness enforcement** — Blocks serving of recently-published proxy artifacts until a configurable age threshold is met
- **Per-repository-type configuration** — Configurable durations with global defaults and override hierarchy
- **Metadata-aware filtering** — Rewrites upstream package index responses (Maven `metadata.xml`, npm packument, PyPI simple API, Go module list, Helm `index.yaml`, Composer `packages.json`) to exclude blocked versions
- **Automatic unblock** — All affected artifacts unblocked when cooldown is disabled globally or per repository type
- **Circuit breaker** — Cooldown evaluation failures do not block artifact delivery
- **Hot-reloadable configuration** — Changes via REST API take immediate effect without restart

### Security & Authentication
- **SSO/Okta integration** — OpenID Connect with group-to-role mapping
- **JWT-based API tokens** — Configurable expiry and revocation
- **Granular RBAC** — Separate read/create/update/delete/move permissions per resource type (repositories, users, roles, storage, cooldown, search)
- **Domain-filtered authentication** — Different auth providers for different user domains
- **Per-repository access scoping** — Users only see repositories and search results they have permissions for

### Proxy & Caching
- **Full OCI-compatible Docker proxy** — Proper authentication for ghcr.io, Docker Hub, and private registries with atomic blob caching (compare-and-set to prevent race conditions)
- **Stream-through-cache** — Artifacts streamed directly to client while simultaneously written to cache, replacing save-then-serve for reduced first-request latency
- **Content-encoding normalization** — Gzip/deflate/br stripping after Jetty auto-decode prevents corruption in cached responses
- **Negative caching** — Configurable TTL for upstream failures
- **Content-addressable deduplication** — Blob-level dedup across repositories
- **Request retry** — Exponential backoff with jitter
- **Request tracing** — Blocked-thread diagnostics

### Group Repositories
- **Maven group** — Intelligent `maven-metadata.xml` merging across members
- **npm group** — Cross-member security audit aggregation
- **Composer/PHP group** — Provider URL routing
- **Docker group** — Manifest resolution across members
- **Smart routing** — Group requests resolved using PostgreSQL artifact index rather than probing each member sequentially
- **Configurable timeouts** — Per-member timeout for group resolution

### Search & Discovery
- **Full-text artifact search** — Across all repositories via PostgreSQL indexing
- **Permission-scoped results** — Users only see artifacts from repositories they have access to
- **Faceted filtering** — By repository type and repository name with result counts
- **Artifact location API** — Find which repositories contain a specific artifact path
- **Reindex API** — Trigger full or incremental index rebuilds
- **Index statistics** — Monitoring endpoint for index health and coverage

### Package Format Enhancements
- **npm proxy** — Cooldown-aware packument filtering with security audit passthrough
- **PyPI proxy** — Simple API metadata rewriting for version filtering
- **Go proxy** — Module zip and version list filtering
- **Composer proxy** — Per-package metadata processing with artifact size tracking
- **Docker proxy** — Owner tracking fix with MDC context propagation across async boundaries

---

## Supported Repository Types

| Format | Local | Proxy | Group |
|--------|:-----:|:-----:|:-----:|
| Maven | x | x | x |
| Gradle | x | x | x |
| Docker (OCI) | x | x | x |
| npm | x | x | x |
| PyPI | x | x | x |
| PHP / Composer | x | x | x |
| File (generic) | x | x | x |
| Go | x | x | x |
| RubyGems | x | - | x |
| Helm | x | - | - |
| NuGet | x | - | - |
| Debian | x | - | - |
| RPM | x | - | - |
| Conda | x | - | - |
| Conan | x | - | - |
| Hex | x | - | - |

---

## Quick Start

### Build from source

```bash
git clone https://github.com/auto1-oss/pantera.git
cd pantera
mvn clean install -DskipTests
```

### Run with Docker Compose

```bash
cd pantera-main/docker-compose
docker compose up -d
```

This starts the full stack:

| Service | Port | Description |
|---------|------|-------------|
| Nginx | `8081` (HTTP/HTTPS) | Reverse proxy for artifact access |
| Pantera | `8080` (container) | Artifact repository port |
| API | `8086` | REST API |
| UI | `8090` | Management interface |
| PostgreSQL | `5432` | Metadata & settings database |
| Valkey | `6379` | Distributed cache |
| Keycloak | `8080` | Identity provider (SSO) |
| Prometheus | `9090` | Metrics collection |
| Grafana | `3000` | Monitoring dashboards |

---

## Configuration

Pantera is configured via `pantera.yml`:

```yaml
meta:
  storage:
    type: fs
    path: /var/pantera/data
  credentials:
    - type: artipie
      priority: 1
  database:
    url: jdbc:postgresql://localhost:5432/artifacts
    username: pantera
    password: secret
```

See the [documentation](docs/) for complete configuration reference.

---

## Attribution

Pantera is based on [Artipie](https://github.com/artipie/artipie) by the Artipie contributors, originally licensed under the MIT License. The core repository adapter patterns, storage abstraction layer, and HTTP slice architecture originate from the Artipie project. We thank the Artipie community for building the foundation that Pantera extends.

---

## License

Copyright (c) 2025-2026 Auto1 Group
Maintainers: Auto1 DevOps Team
Lead Maintainer: Ayd Asraf

Licensed under the [GNU General Public License v3.0](LICENSE.txt).
