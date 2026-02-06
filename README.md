<a href="http://artipie.com"><img src="https://www.artipie.com/logo.svg" width="64px" height="64px"/></a>

# Artipie - Enterprise Binary Artifact Management (Auto1 Fork)

[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE.txt)
[![Java Version](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.org/)

> **Auto1 Fork**: This is a production-hardened fork of the original [Artipie](https://github.com/artipie/artipie) project, significantly enhanced for enterprise-scale deployments. It includes performance optimizations, security features, and operational improvements developed for high-traffic production workloads.

## What's New in v1.20.14

- **HA Clustering** — PostgreSQL-backed node registry with heartbeat liveness, cross-instance events via Valkey pub/sub
- **Full-Text Search** — tsvector/GIN powered artifact search with relevance ranking
- **Health Checks** — 5-component health endpoint with degraded/unhealthy severity levels
- **Worker Pool Separation** — Independent thread pools for read, write, and list operations
- **Observability** — Pool utilization gauges, event queue depth metrics, structured ECS logging
- **Hardening** — 44 silent catches fixed, zero-copy request path, temp file bulk cleanup

## What is Artipie?

Artipie is a **binary artifact management platform** similar to [JFrog Artifactory](https://jfrog.com/artifactory/), [Sonatype Nexus](https://www.sonatype.com/product-nexus-repository), and [Apache Archiva](https://archiva.apache.org/). It provides a unified solution for hosting, proxying, and managing software packages across multiple ecosystems.

### Key Features (Auto1 Fork)

| Feature | Description |
|---------|-------------|
| **High Performance** | Built on reactive Java with [Vert.x](https://vertx.io/) for non-blocking I/O |
| **Multi-Format Support** | 16+ package manager types in a single deployment |
| **Supply Chain Security** | Cooldown system blocks fresh package versions to prevent attacks |
| **Enterprise Auth** | OAuth/OIDC integration (Keycloak, Okta with MFA), JWT, RBAC |
| **Cloud-Native Storage** | Optimized S3-compatible storage with memory-efficient streaming |
| **Observability** | Prometheus metrics, ECS JSON structured logging, Elastic APM |
| **HA Clustering** | PostgreSQL-backed node coordination with heartbeat liveness detection |
| **Full-Text Search** | Artifact search powered by PostgreSQL tsvector/GIN indexes |
| **Health Checks** | 5-component health probes: storage, database, Valkey, Quartz, HTTP client |
| **Worker Pool Separation** | Named I/O thread pools for read, write, and list operations |
| **Cache Invalidation** | Cross-instance cache invalidation via Valkey pub/sub |
| **Dynamic Configuration** | Create, update, delete repositories at runtime via REST API |
| **Production-Ready** | Docker Compose stack with PostgreSQL, Valkey (Redis), Nginx, monitoring |

### Fork-Specific Enhancements

- **HA Clustering**: PostgreSQL-backed node registry with heartbeat liveness detection and cross-instance event bus
- **Full-Text Artifact Search**: PostgreSQL tsvector/GIN index with relevance-ranked results
- **5-Component Health Checks**: Storage, database, Valkey, Quartz scheduler, and HTTP client probes
- **Named Worker Pools**: Separate thread pools for read (CPU x4), write (CPU x2), and list (CPU) operations
- **Cross-Instance Cache Invalidation**: Valkey pub/sub for distributed cache coherence
- **Cooldown System**: Configurable delay on new package versions (supply chain attack prevention)
- **Okta OIDC Integration**: Full Okta authentication with MFA support (TOTP + push)
- **S3 Performance**: Memory-optimized streaming, retry improvements, connection pooling
- **File Descriptor Optimization**: High ulimit settings for many concurrent connections
- **ECS JSON Logging**: Structured logging compatible with Elasticsearch/Kibana
- **Docker Proxy Improvements**: Streaming optimization, timeout handling, multi-platform support
- **NPM Proxy Deduplication**: Request deduplication for high-concurrency scenarios

## Supported Repository Types

| Type | Local | Proxy | Group | Description |
|------|:-----:|:-----:|:-----:|-------------|
| **Maven** | Yes | Yes | Yes | Java artifacts and dependencies |
| **Gradle** | Yes | Yes | Yes | Gradle artifacts and plugins |
| **Docker** | Yes | Yes | Yes | Container images registry |
| **NPM** | Yes | Yes | Yes | JavaScript packages |
| **PyPI** | Yes | Yes | Yes | Python packages |
| **Go** | Yes | Yes | Yes | Go modules |
| **Composer (PHP)** | Yes | Yes | Yes | PHP packages |
| **Files** | Yes | Yes | Yes | Generic binary files |
| **Gem** | Yes | — | Yes | Ruby gems |
| **NuGet** | Yes | — | — | .NET packages |
| **Helm** | Yes | — | — | Kubernetes charts |
| **RPM** | Yes | — | — | Red Hat/CentOS packages |
| **Debian** | Yes | — | — | Debian/Ubuntu packages |
| **Conda** | Yes | — | — | Data science packages |
| **Conan** | Yes | — | — | C/C++ packages |
| **HexPM** | Yes | — | — | Elixir/Erlang packages |

**Repository Modes:**
- **Local**: Host your own packages (read/write)
- **Proxy**: Cache packages from upstream registries with cooldown protection
- **Group**: Aggregate multiple local and/or proxy repositories

## Quick Start

### Using Docker

```bash
docker run -d \
  --name artipie \
  -p 8080:8080 \
  -p 8086:8086 \
  --ulimit nofile=1048576:1048576 \
  artipie/artipie:latest
```

**Ports:**
- `8080`: Repository endpoints
- `8086`: REST API and Swagger documentation

**Default Credentials:**
- Username: `artipie`
- Password: `artipie`

### Verify Installation

```bash
# Check health
curl http://localhost:8080/.health

# Check version
curl http://localhost:8080/.version

# Open Swagger UI
open http://localhost:8086/api/index.html
```

## Production Deployment (Recommended)

For production, use the Docker Compose stack which includes all required services:

```bash
cd artipie-main/docker-compose

# Copy and configure environment
cp .env.example .env
# Edit .env with your settings

# Start all services
docker-compose up -d
```

> **Multi-Instance HA Deployment**: For high-availability setups with multiple Artipie nodes behind a load balancer, see [`docs/ha-deployment/docker-compose-ha.yml`](docs/ha-deployment/docker-compose-ha.yml). The HA configuration includes PostgreSQL-backed node registry, Valkey pub/sub for cross-instance events, and nginx load balancing.

### Included Services

| Service | Port | Description |
|---------|------|-------------|
| **Artipie** | 8081 (via nginx) | Repository endpoints |
| **Artipie API** | 8086 | REST API, Swagger docs |
| **PostgreSQL** | 5432 | Artifact metadata, cooldown state |
| **Valkey (Redis)** | 6379 | Caching layer |
| **Keycloak** | 8080 | OAuth/OIDC authentication |
| **Nginx** | 8081, 8443 | Reverse proxy with TLS |
| **Prometheus** | 9090 | Metrics collection |
| **Grafana** | 3000 | Dashboards and alerting |

### Production Configuration

The Docker Compose setup includes production-ready defaults:

```yaml
# docker-compose.yaml (artipie service)
cpus: 4
mem_limit: 6gb
ulimits:
  nofile:
    soft: 1048576
    hard: 1048576
  nproc:
    soft: 65536
    hard: 65536
```

### Environment Variables

Key environment variables (see [.env.example](artipie-main/docker-compose/.env.example) for complete list):

```bash
# Artipie
ARTIPIE_VERSION=1.20.12
ARTIPIE_USER_NAME=artipie
ARTIPIE_USER_PASS=changeme

# JVM (optimized for high concurrency)
JVM_ARGS=-Xms3g -Xmx4g -XX:+UseG1GC ...

# AWS/S3 (for S3 storage backend)
AWS_PROFILE=your_profile_name
AWS_REGION=eu-west-1

# Okta OIDC (optional)
OKTA_ISSUER=https://your-org.okta.com
OKTA_CLIENT_ID=your_client_id
OKTA_CLIENT_SECRET=your_client_secret

# Database
POSTGRES_USER=artipie
POSTGRES_PASSWORD=changeme
```

## Configuration

### Main Configuration (`artipie.yml`)

```yaml
meta:
  storage:
    type: fs
    path: /var/artipie/repo

  credentials:
    - type: env
    - type: artipie
    - type: okta  # Auto1 fork feature
      issuer: ${OKTA_ISSUER}
      client-id: ${OKTA_CLIENT_ID}
      client-secret: ${OKTA_CLIENT_SECRET}

  policy:
    type: artipie
    storage:
      type: fs
      path: /var/artipie/security

  # Cooldown system (Auto1 fork feature)
  cooldown:
    enabled: true
    minimum_allowed_age: 7d

  metrics:
    endpoint: /metrics/vertx
    port: 8087
```

### Repository Configuration Examples

**NPM Proxy with Cooldown:**
```yaml
repo:
  type: npm-proxy
  storage:
    type: fs
    path: /var/artipie/data/npm
  remote:
    url: https://registry.npmjs.org
  # Cooldown blocks versions newer than configured age
```

**Docker Registry Proxy:**
```yaml
repo:
  type: docker-proxy
  storage:
    type: fs
    path: /var/artipie/data/docker
  remotes:
    - url: https://registry-1.docker.io
      cache:
        storage:
          type: fs
          path: /var/artipie/cache/docker
```

**Maven Proxy with S3 Storage:**
```yaml
repo:
  type: maven-proxy
  storage:
    type: s3
    bucket: artipie-cache
    region: eu-west-1
  remotes:
    - url: https://repo.maven.apache.org/maven2
```

## Cooldown System (Supply Chain Security)

The cooldown system blocks package versions that are too fresh (recently released) to prevent supply chain attacks:

```yaml
meta:
  cooldown:
    enabled: true
    minimum_allowed_age: 7d  # Block versions newer than 7 days
```

**How it works:**
1. Client requests a package (e.g., `npm install lodash`)
2. Artipie filters metadata to hide versions newer than the cooldown period
3. Fresh versions return `403 Forbidden` if requested directly
4. Old versions are served normally from cache or upstream

**Monitoring:**
```bash
# Check active blocks
docker exec artipie-db psql -U artipie -d artifacts -c \
  "SELECT COUNT(*) FROM artifact_cooldowns WHERE status = 'ACTIVE';"

# View blocked requests in logs
docker logs artipie | grep "event.outcome=blocked"
```

See [Cooldown System Documentation](docs/cooldown-fallback/README.md) for complete details.

## Documentation

| Document | Description |
|----------|-------------|
| [User Guide](docs/USER_GUIDE.md) | Installation, configuration, and usage |
| [Developer Guide](docs/DEVELOPER_GUIDE.md) | Architecture and contributing |
| [Auto1 Fork Changelog](docs/CHANGELOG-AUTO1.md) | Release history for the Auto1 fork |
| [Environment Variables](docs/ENVIRONMENT_VARIABLES.md) | Complete environment variable reference |
| [HA Deployment](docs/ha-deployment/) | High-availability deployment configs (nginx, Docker Compose, Artipie) |
| [API Routing](docs/API_ROUTING.md) | URL patterns and routing |
| [Cooldown System](docs/cooldown-fallback/README.md) | Supply chain attack prevention |
| [S3 Storage](docs/s3-optimizations/README.md) | S3 configuration and tuning |
| [Okta OIDC](docs/OKTA_OIDC_INTEGRATION.md) | Okta authentication with MFA |
| [JVM Optimization](docs/ARTIPIE_JVM_OPTIMIZATION.md) | JVM tuning for production |
| [NPM CLI](docs/NPM_CLI_COMPATIBILITY.md) | NPM command reference |
| [Logging](docs/LOGGING_CONFIGURATION.md) | Log4j2 and ECS JSON setup |

See [docs/README.md](docs/README.md) for the complete documentation index.

## REST API

### Authentication

```bash
# Get auth token
TOKEN=$(curl -s -X POST http://localhost:8086/api/auth/token \
  -H "Content-Type: application/json" \
  -d '{"name":"artipie","pass":"artipie"}' | jq -r '.token')
```

### Repository Management

```bash
# Create Maven repository
curl -X PUT "http://localhost:8086/api/v1/repository/my-maven" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"repo":{"type":"maven","storage":"default"}}'

# List repositories
curl -H "Authorization: Bearer ${TOKEN}" \
  http://localhost:8086/api/v1/repository/list

# Delete repository
curl -X DELETE "http://localhost:8086/api/v1/repository/my-maven" \
  -H "Authorization: Bearer ${TOKEN}"
```

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/token` | Get authentication token |
| PUT | `/api/v1/repository/{name}` | Create or update repository |
| GET | `/api/v1/repository/{name}` | Get repository settings |
| DELETE | `/api/v1/repository/{name}` | Remove repository |
| GET | `/api/v1/repository/list` | List all repositories |

## Building from Source

### Prerequisites

- JDK 21+
- Maven 3.9+
- Docker (for integration tests)

### Build Commands

```bash
# Full build with tests
mvn clean verify

# Fast build (skip tests)
mvn install -DskipTests -Dpmd.skip=true

# Build Docker image
cd artipie-main
mvn package -DskipTests
docker build -t auto1-artipie:local --build-arg JAR_FILE=artipie-main-*.jar .
```

### Running Tests

```bash
# Unit tests only
mvn test

# Integration tests
mvn verify

# Specific module
mvn test -pl npm-adapter
```

## Architecture

```
+-------------------------------------------------------------+
|                     HTTP Layer (Vert.x)                      |
|  +---------+  +---------+  +---------+  +-----------------+ |
|  |MainSlice|--|TimeoutSl|--|AuthSlice|--|RepositorySlices | |
|  +---------+  +---------+  +---------+  +-----------------+ |
+-------------------------------------------------------------+
                              |
+-------------------------------------------------------------+
|                   Repository Adapters                        |
|  +------+ +------+ +-----+ +-----+ +-----+ +-----+          |
|  |Maven | |Docker| | NPM | |PyPI | |Helm | | ... |          |
|  +------+ +------+ +-----+ +-----+ +-----+ +-----+          |
+-------------------------------------------------------------+
                              |
+-------------------------------------------------------------+
|               Cooldown Layer (Auto1 Fork)                    |
|  +----------------+  +----------------+  +---------------+   |
|  |CooldownService |  |MetadataService |  |CooldownInspect|   |
|  +----------------+  +----------------+  +---------------+   |
+-------------------------------------------------------------+
                              |
+-------------------------------------------------------------+
|                  Storage Layer (Asto)                        |
|  +------------+  +--------+  +------+  +-------+            |
|  | FileSystem |  |   S3   |  | etcd |  | Redis |            |
|  +------------+  +--------+  +------+  +-------+            |
+-------------------------------------------------------------+
```

### Key Design Principles

1. **Reactive/Non-blocking**: All I/O operations are asynchronous using `CompletableFuture`
2. **Slice Pattern**: HTTP handlers compose through the `Slice` interface
3. **Storage Abstraction**: Pluggable storage backends via the Asto library
4. **Hot Reload**: Configuration changes apply without restart
5. **Defense in Depth**: Cooldown system adds supply chain security layer

## Monitoring

### Prometheus Metrics

Artipie exposes metrics at `/metrics/vertx` (port 8087):

```bash
curl http://localhost:8087/metrics/vertx
```

Key metrics:
- `artipie_http_requests_total` - Request count by path, method, status
- `artipie_proxy_requests_total` - Proxy requests to upstream
- `artipie_cooldown_blocks_total` - Blocked versions by cooldown
- `artipie_cache_hits_total` - Cache hit/miss ratio

### Grafana Dashboards

Pre-configured dashboards are available in `artipie-main/docker-compose/grafana/dashboards/`:
- Artipie Overview
- Repository Performance
- Cooldown Activity
- JVM Metrics

### Logging

ECS JSON structured logging for Elasticsearch/Kibana:

```json
{
  "@timestamp": "2026-01-19T10:30:00.000Z",
  "log.level": "INFO",
  "message": "Package version blocked by cooldown",
  "event.category": "cooldown",
  "event.action": "block",
  "event.outcome": "blocked",
  "package.name": "lodash",
  "package.version": "4.18.0"
}
```

## Version Information

| Component | Version |
|-----------|---------|
| Artipie | 1.20.14 |
| Java | 21+ |
| Vert.x | 4.5.x |

## Contributing

Contributions are welcome. Please:

1. Fork the repository
2. Create a feature branch
3. Run tests: `mvn clean verify`
4. Submit a pull request

### Code Style

- PMD enforced code style
- Checkstyle validation
- Unit tests required for new features

```bash
# Before submitting a PR
mvn clean verify
```

## License

[MIT License](LICENSE.txt) - Copyright (c) Artipie Contributors

---

<p align="center">
  <i>Auto1 Fork - Production-hardened for enterprise scale</i>
</p>
