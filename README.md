<p align="center">
  <img src="docs/pantera-banner.png" alt="Pantera Artifact Registry" width="800" />
</p>

<h1 align="center">Pantera Artifact Registry</h1>

<p align="center"><strong>Universal multi-format artifact registry built for enterprise teams.</strong></p>

<p align="center">
  <a href="docs/user-guide/index.md">User Guide</a> |
  <a href="docs/developer-guide.md">Developer Guide</a> |
  <a href="docs/configuration-reference.md">Configuration</a> |
  <a href="docs/rest-api-reference.md">REST API</a> |
  <a href="CONTRIBUTING.md">Contributing</a>
</p>

---

Pantera is based on [Artipie](https://github.com/artipie/artipie), an open-source binary artifact management tool. The core repository patterns, adapter architecture, and storage abstractions originate from Artipie and its contributors. Pantera builds on this foundation with significant enhancements in security, caching, operational tooling, and a complete management UI.

## Key Features

- **15 package formats** in a single deployment with local, proxy, and group repository modes
- **Enterprise management UI** with Vue.js dark-theme dashboard, file browser, and artifact search
- **PostgreSQL-backed persistence** for settings, RBAC policies, artifact metadata, and full-text search
- **Supply chain security** via configurable cooldown system that blocks freshly-published artifacts
- **SSO integration** with Okta OIDC (MFA support) and Keycloak, plus JWT-as-Password for high-performance auth
- **HA clustering** with Valkey pub/sub cache invalidation, PostgreSQL node registry, and shared S3 storage
- **Stream-through caching** with request deduplication, negative cache (L1 Caffeine + L2 Valkey), and disk cache with LRU/LFU eviction
- **Prometheus metrics** and ECS-structured JSON logging with Grafana dashboards
- **REST API** with 15+ endpoint handlers for full programmatic access

## Supported Repository Types

| Format | Local | Proxy | Group |
|--------|:-----:|:-----:|:-----:|
| Maven | x | x | x |
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

## Quick Start

### Prerequisites

- JDK 21+ and Maven 3.4+ (for building from source)
- Docker and Docker Compose (for running)
- **pg_cron** PostgreSQL extension — required for dashboard statistics (artifact count, storage usage). Without it the dashboard shows zeros. See [Admin Guide — Database Setup](docs/admin-guide/installation.md#database-setup-dashboard-materialized-views) for setup.

### Run the published Docker images

Every release publishes multi-arch (amd64 + arm64) images to GitHub Container Registry:

```bash
docker pull ghcr.io/auto1-oss/pantera:latest      # backend (8080 repository, 8086 REST API)
docker pull ghcr.io/auto1-oss/pantera-ui:latest   # management UI (nginx, port 80)
```

Generate the RS256 JWT key pair (required at boot):

```bash
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/jwt-private.pem
openssl rsa -in keys/jwt-private.pem -pubout -out keys/jwt-public.pem
```

Minimal `docker compose` wiring (PostgreSQL + backend + UI):

```yaml
services:
  db:
    image: postgres:17
    environment:
      POSTGRES_USER: pantera
      POSTGRES_PASSWORD: changeme
      POSTGRES_DB: pantera

  pantera:
    image: ghcr.io/auto1-oss/pantera:latest
    depends_on: [db]
    environment:
      JWT_PRIVATE_KEY_PATH: /etc/pantera/keys/jwt-private.pem
      JWT_PUBLIC_KEY_PATH: /etc/pantera/keys/jwt-public.pem
      POSTGRES_USER: pantera
      POSTGRES_PASSWORD: changeme
    volumes:
      - ./pantera.yml:/etc/pantera/pantera.yml   # see Configuration below
      - ./keys:/etc/pantera/keys:ro
      - pantera-data:/var/pantera                # must be writable by uid 2021
    ports:
      - "8080:8080"   # repository traffic
      - "8086:8086"   # REST API

  ui:
    image: ghcr.io/auto1-oss/pantera-ui:latest
    depends_on: [pantera]
    environment:
      API_UPSTREAM: pantera:8086   # UI nginx proxies /api/v1 to the backend
    ports:
      - "8090:80"

volumes:
  pantera-data:
```

In `pantera.yml`, point `artifacts_database.postgres_host` at the `db` service and reference the mounted JWT keys (see [Configuration](#configuration) below). For production setup — pg_cron, Valkey, SSO, monitoring — see the [Admin Guide — Installation](docs/admin-guide/installation.md).

### Run from release artifacts

Each [GitHub release](https://github.com/auto1-oss/pantera/releases) also attaches standalone artifacts: `pantera-<version>.jar`, `pantera-<version>-dist.tar.gz` (the JAR plus a `dependency/` directory with all runtime jars), `pantera-ui-<version>.tar.gz` (compiled UI static files), and `SHA256SUMS`. To run the backend without Docker (JDK 21+, PostgreSQL 15+; Valkey only for HA):

```bash
tar xzf pantera-<version>-dist.tar.gz
java -cp 'pantera-main-<version>.jar:dependency/*' \
  com.auto1.pantera.VertxMain \
  --config-file=pantera.yml --port=8080 --api-port=8086
```

The UI tarball can be served by any static web server — see [UI Deployment](docs/admin-guide/ui-deployment.md).

### Build from source

```bash
git clone https://github.com/auto1-oss/pantera.git
cd pantera
mvn clean install -DskipTests
```

### Run with Docker Compose

```bash
cd pantera-main/docker-compose
cp .env.example .env   # Edit with your settings
docker compose up -d
```

This starts the full stack:

| Service | Port | Description |
|---------|------|-------------|
| Nginx | `8081` / `8443` | Reverse proxy (HTTP/HTTPS) |
| Pantera | `8088` (mapped from 8080) | Artifact repository |
| API | `8086` | REST API |
| UI | `8090` | Management interface |
| PostgreSQL | `5432` | Metadata & settings database |
| Valkey | `6379` | Distributed cache & pub/sub |
| Keycloak | `8080` | Identity provider (SSO) |
| Prometheus | `9090` | Metrics collection |
| Grafana | `3000` | Monitoring dashboards |

### Verify

```bash
curl http://localhost:8088/.health    # Health check
curl http://localhost:8088/.version   # Version info
```

## Configuration

Pantera is configured via `pantera.yml`:

```yaml
meta:
  storage:
    type: fs
    path: /var/pantera/data
  credentials:
    - type: local
      storage:
        type: fs
        path: /var/pantera/security
  policy:
    type: pantera
    storage:
      type: fs
      path: /var/pantera/security
  artifacts_database:
    postgres_host: localhost
    postgres_port: 5432
    postgres_database: artifacts
    postgres_user: pantera
    postgres_password: ${POSTGRES_PASSWORD}
  jwt:
    private-key-path: ${JWT_PRIVATE_KEY_PATH}
    public-key-path: ${JWT_PUBLIC_KEY_PATH}
    access-token-expiry-seconds: 3600
    refresh-token-expiry-seconds: 604800
```

See the [Configuration Reference](docs/configuration-reference.md) for all options.

## Documentation

| Document | Description |
|----------|-------------|
| [User Guide](docs/user-guide/index.md) | Installation, configuration, repository setup, auth, monitoring, troubleshooting |
| [Developer Guide](docs/developer-guide.md) | Architecture, codebase map, adding features, testing, debugging |
| [Configuration Reference](docs/configuration-reference.md) | Complete reference for all YAML config, environment variables, and CLI options |
| [REST API Reference](docs/rest-api-reference.md) | All API endpoints with examples |
| [Contributing](CONTRIBUTING.md) | How to contribute, build, test, and submit PRs |
| [Code Standards](CODE_STANDARDS.md) | Coding conventions, style rules, testing patterns |
| [Changelog](docs/CHANGELOG.md) | Release history |

### Additional References

| Document | Description |
|----------|-------------|
| [Okta OIDC Integration](docs/admin-guide/authentication.md) | Okta SSO setup with MFA support |
| [NPM CLI Compatibility](docs/user-guide/repositories/npm.md) | NPM command support matrix across repository types |
| [S3 Storage Tuning](docs/admin-guide/storage-backends.md) | S3 multipart, parallel download, disk cache configuration |
| [Cooldown System](docs/admin-guide/cooldown.md) | Supply chain security cooldown architecture |
| [Import API](docs/user-guide/import-and-migration.md) | Bulk artifact import endpoint |
| [API Routing](docs/rest-api-reference.md) | URL pattern support per repository type |
| [Logging Configuration](docs/admin-guide/logging.md) | Log4j2 external configuration and hot-reload |

## Attribution

Pantera is based on [Artipie](https://github.com/artipie/artipie) by the Artipie contributors, originally licensed under the MIT License. The core repository adapter patterns, storage abstraction layer, and HTTP slice architecture originate from the Artipie project. We thank the Artipie community for building the foundation that Pantera extends.

## License

Copyright (c) 2025-2026 Auto1 Group
Maintainers: Auto1 DevOps Team
Lead Maintainer: Ayd Asraf

Licensed under the [GNU General Public License v3.0](LICENSE.txt).
