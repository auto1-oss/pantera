# Artipie User Guide

**Version:** 1.20.11
**Last Updated:** January 2026

---

## Table of Contents

1. [Introduction](#introduction)
2. [Quick Start](#quick-start)
3. [Installation](#installation)
4. [Configuration](#configuration)
5. [Repository Types](#repository-types)
6. [Storage Backends](#storage-backends)
7. [Authentication & Authorization](#authentication--authorization)
8. [REST API](#rest-api)
9. [Metrics & Monitoring](#metrics--monitoring)
10. [Logging](#logging)
11. [Performance Tuning](#performance-tuning)
12. [Troubleshooting](#troubleshooting)

---

## Introduction

### What is Artipie?

Artipie is an **enterprise-grade binary artifact management platform** similar to [JFrog Artifactory](https://jfrog.com/artifactory/), [Sonatype Nexus](https://www.sonatype.com/product-nexus-repository), and [Apache Archiva](https://archiva.apache.org/). It provides a unified solution for hosting, proxying, and managing software packages across multiple ecosystems.

### Key Features

| Feature | Description |
|---------|-------------|
| **Multi-Format Support** | 16 package manager types in a single deployment |
| **High Performance** | Built on reactive Java with Vert.x for non-blocking I/O |
| **Dynamic Configuration** | Create, update, and delete repositories at runtime via REST API |
| **Cloud-Native Storage** | First-class support for S3-compatible storage |
| **Enterprise Security** | OAuth/OIDC (Keycloak, Okta), JWT, and granular RBAC |
| **Observability** | Prometheus metrics, ECS JSON logging, and JFR events |

### Supported Repository Types

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
| **Gem** | Yes | - | Yes | Ruby gems |
| **NuGet** | Yes | - | - | .NET packages |
| **Helm** | Yes | - | - | Kubernetes charts |
| **RPM** | Yes | - | - | Red Hat/CentOS packages |
| **Debian** | Yes | - | - | Debian/Ubuntu packages |
| **Conda** | Yes | - | - | Data science packages |
| **Conan** | Yes | - | - | C/C++ packages |
| **HexPM** | Yes | - | - | Elixir/Erlang packages |

### Repository Modes

- **Local**: Host your own packages (read/write)
- **Proxy**: Cache packages from upstream registries (read-only with caching)
- **Group**: Aggregate multiple local and/or proxy repositories

---

## Quick Start

### Using Docker (Recommended)

```bash
docker run -it -p 8080:8080 -p 8086:8086 artipie/artipie:latest
```

This starts Artipie with:
- **Port 8080**: Repository endpoints
- **Port 8086**: REST API and Swagger documentation

### Default Credentials

- **Username**: `artipie`
- **Password**: `artipie`

### Verify Installation

1. Open Swagger UI: http://localhost:8086/api/index.html
2. Check health: `curl http://localhost:8080/.health`
3. Check version: `curl http://localhost:8080/.version`

### Upload Your First Artifact

```bash
# Upload a file to the binary repository
curl -X PUT -d 'Hello Artipie!' http://localhost:8080/my-bin/hello.txt

# Download it back
curl http://localhost:8080/my-bin/hello.txt
```

---

## Installation

### Option 1: Docker (Recommended)

```bash
# Pull the latest image
docker pull artipie/artipie:latest

# Run with volume mounts for persistence
docker run -d \
  --name artipie \
  -p 8080:8080 \
  -p 8086:8086 \
  -v $(pwd)/artipie-data:/var/artipie \
  -v $(pwd)/artipie-config:/etc/artipie \
  artipie/artipie:latest
```

**Important**: Set correct ownership for mounted directories:
```bash
sudo chown -R 2021:2020 ./artipie-data ./artipie-config
```

### Option 2: Docker Compose (Production)

For production deployments with PostgreSQL, Redis, Keycloak, and monitoring:

```bash
cd artipie-main/docker-compose
docker-compose up -d
```

Services available:
- **Artipie Repositories**: http://localhost:8081
- **REST API**: http://localhost:8086
- **Swagger Docs**: http://localhost:8086/api/index.html
- **Keycloak Admin**: http://localhost:8080
- **Grafana**: http://localhost:3000

### Option 3: JAR File

**Prerequisites:**
- JDK 21+

```bash
# Download from releases
wget https://github.com/artipie/artipie/releases/download/v1.20.11/artipie.jar

# Run
java -jar artipie.jar \
  --config-file=/etc/artipie/artipie.yml \
  --port=8080 \
  --api-port=8086
```

### Command Line Options

| Option | Short | Description | Default |
|--------|-------|-------------|---------|
| `--config-file` | `-f` | Path to configuration file | `artipie.yml` |
| `--port` | `-p` | Repository server port | `8080` |
| `--api-port` | `-ap` | REST API port | `8086` |

---

## Configuration

### Main Configuration File (`artipie.yml`)

```yaml
meta:
  # Primary storage for artifacts
  storage:
    type: fs
    path: /var/artipie/repo

  # Authentication providers (tried in order)
  credentials:
    - type: env
    - type: artipie
      storage:
        type: fs
        path: /var/artipie/security

  # Authorization policy
  policy:
    type: artipie
    storage:
      type: fs
      path: /var/artipie/security

  # Metrics endpoint
  metrics:
    endpoint: /metrics/vertx
    port: 8087

  # JWT settings
  jwt:
    secret: ${JWT_SECRET}
    expires: true
    expiry-seconds: 86400  # 24 hours
```

### Repository Configuration

Each repository has its own YAML file in the configuration directory:

#### Local Maven Repository (`my-maven.yaml`)

```yaml
repo:
  type: maven
  storage:
    type: fs
    path: /var/artipie/data/maven
```

#### Maven Proxy Repository (`maven-proxy.yaml`)

```yaml
repo:
  type: maven-proxy
  storage:
    type: s3
    bucket: artipie-cache
    region: us-east-1
  remotes:
    - url: https://repo.maven.apache.org/maven2
      cache:
        enabled: true
```

#### Group Repository (`maven-group.yaml`)

```yaml
repo:
  type: maven-group
  members:
    - my-maven       # Try local first
    - maven-proxy    # Then proxy
```

### Environment Variable Substitution

Configuration supports environment variable substitution:

```yaml
meta:
  storage:
    type: s3
    bucket: ${S3_BUCKET}
    region: ${AWS_REGION}
    credentials:
      type: basic
      accessKeyId: ${AWS_ACCESS_KEY_ID}
      secretAccessKey: ${AWS_SECRET_ACCESS_KEY}
```

### Storage Aliases (`_storages.yaml`)

Define reusable storage configurations:

```yaml
storages:
  default:
    type: fs
    path: /var/artipie/data

  s3-main:
    type: s3
    bucket: my-bucket
    region: us-east-1
    credentials:
      type: basic
      accessKeyId: ${AWS_ACCESS_KEY_ID}
      secretAccessKey: ${AWS_SECRET_ACCESS_KEY}
```

Then reference by name:

```yaml
repo:
  type: maven
  storage: s3-main  # References alias
```

---

## Repository Types

### Maven Repository

**Local Repository:**
```yaml
repo:
  type: maven
  storage:
    type: fs
    path: /var/artipie/data/maven
```

**Client Configuration (`~/.m2/settings.xml`):**
```xml
<settings>
  <servers>
    <server>
      <id>artipie</id>
      <username>admin</username>
      <password>password</password>
    </server>
  </servers>
  <mirrors>
    <mirror>
      <id>artipie</id>
      <url>http://localhost:8080/maven</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>
</settings>
```

**Deploy:**
```bash
mvn deploy -DaltDeploymentRepository=artipie::default::http://localhost:8080/maven
```

### NPM Repository

**Local Repository:**
```yaml
repo:
  type: npm
  storage:
    type: fs
    path: /var/artipie/data/npm
```

**Proxy Repository:**
```yaml
repo:
  type: npm-proxy
  storage:
    type: fs
    path: /var/artipie/data/npm-proxy
  remotes:
    - url: https://registry.npmjs.org
```

**Group Repository:**
```yaml
repo:
  type: npm-group
  members:
    - npm        # Local packages
    - npm-proxy  # Public packages
```

**Client Configuration (`~/.npmrc`):**
```ini
registry=http://localhost:8080/npm_group
//localhost:8080/npm_group/:_authToken=<your-token>
```

**Publish:**
```bash
npm publish --registry=http://localhost:8080/npm
```

**Install:**
```bash
npm install express --registry=http://localhost:8080/npm_group
```

### Docker Registry

**Repository:**
```yaml
repo:
  type: docker
  storage:
    type: fs
    path: /var/artipie/data/docker
```

**Client Usage:**
```bash
# Login
docker login localhost:8080 -u admin -p password

# Tag image
docker tag myimage:latest localhost:8080/docker/myimage:latest

# Push
docker push localhost:8080/docker/myimage:latest

# Pull
docker pull localhost:8080/docker/myimage:latest
```

### PyPI Repository

**Repository:**
```yaml
repo:
  type: pypi
  storage:
    type: fs
    path: /var/artipie/data/pypi
```

**Client Configuration (`~/.pip/pip.conf`):**
```ini
[global]
index-url = http://admin:password@localhost:8080/pypi/simple/
trusted-host = localhost
```

**Upload:**
```bash
twine upload --repository-url http://localhost:8080/pypi/ dist/*
```

### Helm Repository

**Repository:**
```yaml
repo:
  type: helm
  storage:
    type: fs
    path: /var/artipie/data/helm
```

**Client Usage:**
```bash
# Add repository
helm repo add artipie http://localhost:8080/helm

# Update
helm repo update

# Install chart
helm install myrelease artipie/mychart
```

### Go Module Proxy

**Repository:**
```yaml
repo:
  type: go-proxy
  storage:
    type: fs
    path: /var/artipie/data/go
  remotes:
    - url: https://proxy.golang.org
```

**Client Configuration:**
```bash
export GOPROXY=http://localhost:8080/go,https://proxy.golang.org,direct
go mod download
```

### Files (Generic Binary)

**Repository:**
```yaml
repo:
  type: file
  storage:
    type: fs
    path: /var/artipie/data/files
```

**Usage:**
```bash
# Upload
curl -X PUT -T myfile.bin http://localhost:8080/files/myfile.bin

# Download
curl -O http://localhost:8080/files/myfile.bin

# List
curl http://localhost:8080/files/
```

---

## Storage Backends

### Filesystem Storage

```yaml
storage:
  type: fs
  path: /var/artipie/data
```

### S3 Storage

```yaml
storage:
  type: s3
  bucket: my-bucket
  region: us-east-1

  credentials:
    type: basic
    accessKeyId: ${AWS_ACCESS_KEY_ID}
    secretAccessKey: ${AWS_SECRET_ACCESS_KEY}

  # Performance tuning
  http:
    max-concurrency: 1024
    max-pending-acquires: 2048
    acquisition-timeout-millis: 10000
    connection-max-idle-millis: 30000

  # Large file handling
  multipart: true
  multipart-min-size: 32MB
  part-size: 8MB
  multipart-concurrency: 8

  # Local disk cache
  cache:
    enabled: true
    path: /tmp/artipie-s3-cache
    max-bytes: 10GB
    high-watermark-percent: 90
    low-watermark-percent: 80
    cleanup-interval-millis: 300000
    eviction-policy: LRU
```

### MinIO/S3-Compatible Storage

```yaml
storage:
  type: s3
  bucket: artipie
  region: us-east-1
  endpoint: http://minio:9000

  credentials:
    type: basic
    accessKeyId: minioadmin
    secretAccessKey: minioadmin
```

### etcd Storage

```yaml
storage:
  type: etcd
  endpoints:
    - http://etcd1:2379
    - http://etcd2:2379
    - http://etcd3:2379
```

### Redis Storage

```yaml
storage:
  type: redis
  endpoint: redis://localhost:6379
```

---

## Authentication & Authorization

### Authentication Methods

#### 1. Artipie Users (File-Based)

**Configuration:**
```yaml
meta:
  credentials:
    - type: artipie
      storage:
        type: fs
        path: /var/artipie/security
```

**User File (`/var/artipie/security/users/admin.yml`):**
```yaml
type: plain
pass: password123
email: admin@example.com
roles:
  - admin
```

#### 2. Environment Variables

```yaml
meta:
  credentials:
    - type: env
```

Set user via environment:
```bash
export ARTIPIE_USER_admin=password123
```

#### 3. OAuth/OIDC (Keycloak)

```yaml
meta:
  credentials:
    - type: keycloak
      url: ${KEYCLOAK_URL}
      realm: ${KEYCLOAK_REALM}
      client-id: ${KEYCLOAK_CLIENT_ID}
      client-password: ${KEYCLOAK_CLIENT_SECRET}
```

#### 4. OAuth/OIDC (Okta)

```yaml
meta:
  credentials:
    - type: okta
      issuer: ${OKTA_ISSUER}
      client-id: ${OKTA_CLIENT_ID}
      client-secret: ${OKTA_CLIENT_SECRET}
      redirect-uri: ${OKTA_REDIRECT_URI}
      scope: "openid profile groups"
      groups-claim: "groups"
      group-roles:
        "okta-artipie-admins": "admin"
        "okta-artipie-readers": "readers"
```

#### 5. JWT-as-Password (High Performance)

For MFA-enabled OAuth without latency on every request:

```yaml
meta:
  jwt:
    secret: ${JWT_SECRET}
    expires: true
    expiry-seconds: 86400

  credentials:
    - type: jwt-password  # First: fast local JWT validation
    - type: okta          # Last: for token generation only
```

**Workflow:**
1. Generate token (triggers MFA): `POST /api/auth/token`
2. Use JWT as password in package manager configs
3. Subsequent requests validated locally (~1ms)

### Authorization

**Policy Configuration:**
```yaml
meta:
  policy:
    type: artipie
    storage:
      type: fs
      path: /var/artipie/security
```

**Permission File (`/var/artipie/security/roles/admin.yml`):**
```yaml
permissions:
  # All repositories
  "*":
    - download
    - upload
    - delete

  # Specific repository
  "my-maven":
    - download
    - upload
```

**Actions:**
- `download` - Read artifacts
- `upload` - Write/publish artifacts
- `delete` - Remove artifacts
- `metadata` - Access repository metadata

---

## REST API

### Base URL

```
http://localhost:8086/api/v1
```

### Authentication

All API endpoints require JWT authentication:

```bash
# Get token
TOKEN=$(curl -X POST http://localhost:8086/api/auth/token \
  -H "Content-Type: application/json" \
  -d '{"name":"admin","pass":"password"}' | jq -r .token)

# Use token
curl -H "Authorization: Bearer $TOKEN" http://localhost:8086/api/v1/repository/list
```

### Repository Management

**List Repositories:**
```bash
GET /api/v1/repository/list
```

**Create Repository:**
```bash
PUT /api/v1/repository/{name}
Content-Type: application/json

{
  "repo": {
    "type": "maven",
    "storage": "default"
  }
}
```

**Get Repository Settings:**
```bash
GET /api/v1/repository/{name}
```

**Delete Repository:**
```bash
DELETE /api/v1/repository/{name}
```

### User Management

**List Users:**
```bash
GET /api/v1/users
```

**Create User:**
```bash
PUT /api/v1/users/{username}
Content-Type: application/json

{
  "type": "plain",
  "pass": "password123",
  "email": "user@example.com"
}
```

### Swagger Documentation

Interactive API documentation available at:
```
http://localhost:8086/api/index.html
```

---

## Metrics & Monitoring

### Prometheus Metrics

**Enable metrics:**
```yaml
meta:
  metrics:
    endpoint: /metrics/vertx
    port: 8087
```

**Access metrics:**
```bash
curl http://localhost:8087/metrics/vertx
```

### Available Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `vertx_http_server_requests_total` | Counter | Total HTTP requests |
| `vertx_http_server_request_duration_seconds` | Histogram | Request duration |
| `vertx_http_server_active_requests` | Gauge | Current active requests |
| `artipie_storage_operations_total` | Counter | Storage operations |
| `artipie_storage_operation_duration_seconds` | Histogram | Storage latency |
| `artipie_repository_requests_total` | Counter | Per-repository requests |

### Grafana Dashboard

When using Docker Compose, Grafana is available at http://localhost:3000 with pre-configured Artipie dashboards.

### Health Checks

**Health Endpoint:**
```bash
curl http://localhost:8080/.health
# Returns: OK
```

**Version Endpoint:**
```bash
curl http://localhost:8080/.version
# Returns: 1.20.11
```

---

## Logging

### ECS JSON Format

Artipie uses Elastic Common Schema (ECS) JSON format for structured logging:

```json
{
  "@timestamp": "2025-01-03T10:15:30.123Z",
  "log.level": "INFO",
  "log.logger": "com.artipie.http.MainSlice",
  "message": "Request completed",
  "trace.id": "abc123",
  "user.name": "admin",
  "client.ip": "192.168.1.100",
  "http.request.method": "GET",
  "url.path": "/maven/artifact.jar",
  "http.response.status_code": 200,
  "event.duration": 45000000
}
```

### Log Configuration

**External Configuration (`log4j2.xml`):**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN" monitorInterval="30">
    <Appenders>
        <Console name="Console" target="SYSTEM_OUT">
            <EcsLayout serviceName="artipie"/>
        </Console>
    </Appenders>
    <Loggers>
        <Logger name="com.artipie" level="INFO"/>
        <Logger name="security" level="DEBUG"/>
        <Root level="WARN">
            <AppenderRef ref="Console"/>
        </Root>
    </Loggers>
</Configuration>
```

### Log Levels

| Level | Use Case |
|-------|----------|
| `TRACE` | Deep debugging (high volume) |
| `DEBUG` | Development troubleshooting |
| `INFO` | Production (recommended) |
| `WARN` | Warnings only |
| `ERROR` | Errors only |

### Adapter-Specific Logging

Enable debug logging for specific adapters:

```xml
<!-- Maven adapter -->
<Logger name="com.artipie.maven" level="DEBUG"/>

<!-- NPM adapter -->
<Logger name="com.artipie.npm" level="DEBUG"/>

<!-- S3 storage -->
<Logger name="com.artipie.asto.s3" level="DEBUG"/>
```

### Viewing Logs

```bash
# Docker logs (JSON format)
docker logs -f artipie 2>&1 | jq .

# Filter by level
docker logs artipie 2>&1 | jq 'select(.log.level == "ERROR")'

# Filter by user
docker logs artipie 2>&1 | jq 'select(.user.name == "admin")'
```

---

## Performance Tuning

### JVM Optimization

**Recommended JVM Settings:**
```bash
export JVM_ARGS="\
  -Xms4g \
  -Xmx8g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication \
  -XX:+ParallelRefProcEnabled \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/artipie/logs/heapdump.hprof"
```

**Docker Compose:**
```yaml
services:
  artipie:
    environment:
      JVM_ARGS: >-
        -Xms8g
        -Xmx16g
        -XX:+UseG1GC
        -XX:MaxGCPauseMillis=200
    deploy:
      resources:
        limits:
          memory: 16G
          cpus: '8'
```

### S3 Performance

**Optimized S3 Configuration:**
```yaml
storage:
  type: s3
  bucket: my-bucket

  http:
    max-concurrency: 2048
    max-pending-acquires: 4096
    acquisition-timeout-millis: 15000
    connection-max-idle-millis: 20000

  multipart: true
  multipart-min-size: 32MB
  part-size: 8MB
  multipart-concurrency: 16

  cache:
    enabled: true
    path: /tmp/artipie-cache
    max-bytes: 50GB
    validate-on-read: false
```

### Disk Cache Tuning

For high-traffic deployments:

```yaml
cache:
  enabled: true
  path: /var/artipie/cache
  max-bytes: 50GB
  cleanup-interval-millis: 60000    # 1 minute
  high-watermark-percent: 85
  low-watermark-percent: 75
  eviction-policy: LRU
  validate-on-read: false           # Critical for performance
```

### Connection Pooling

Vert.x thread pools are automatically sized:
- **Event loop threads**: 2x CPU cores
- **Worker threads**: max(20, 4x CPU cores)

---

## Troubleshooting

### Common Issues

#### 1. Connection Refused

**Symptom:** Cannot connect to Artipie

**Check:**
```bash
# Verify container is running
docker ps | grep artipie

# Check logs
docker logs artipie

# Test connectivity
curl -v http://localhost:8080/.health
```

#### 2. Authentication Failed

**Symptom:** 401 Unauthorized

**Check:**
```bash
# Verify credentials
curl -v -u admin:password http://localhost:8080/repo/file.txt

# Generate token
curl -X POST http://localhost:8086/api/auth/token \
  -H "Content-Type: application/json" \
  -d '{"name":"admin","pass":"password"}'
```

#### 3. Storage Errors

**Symptom:** 500 Internal Server Error on upload

**Check:**
```bash
# Verify storage permissions
ls -la /var/artipie/data

# Check S3 credentials
aws s3 ls s3://my-bucket

# Enable debug logging
docker exec artipie cat /etc/artipie/log4j2.xml
```

#### 4. Memory Issues

**Symptom:** OutOfMemoryError

**Fix:**
```bash
# Increase heap
export JVM_ARGS="-Xms4g -Xmx8g"

# Monitor memory
docker stats artipie

# Check GC logs
tail -f /var/artipie/logs/gc.log
```

#### 5. Slow Performance

**Symptom:** High latency, slow downloads

**Check:**
1. Enable disk cache for S3
2. Increase connection pool size
3. Check network connectivity to upstream
4. Review GC logs for long pauses

### Debug Mode

Enable full debug logging:

```xml
<Logger name="com.artipie" level="DEBUG"/>
<Logger name="io.vertx" level="DEBUG"/>
<Logger name="software.amazon.awssdk" level="DEBUG"/>
```

### Support

- **GitHub Issues**: https://github.com/artipie/artipie/issues
- **Discussions**: https://github.com/artipie/artipie/discussions
- **Telegram**: [@artipie](https://t.me/artipie)

---

## Appendix A: URL Patterns

### Standard URL Format

```
http://artipie:8080/<repo_name>/<path>
```

### API URL Formats

| Pattern | Example |
|---------|---------|
| `/<repo_name>` | `/my-maven/artifact.jar` |
| `/api/<repo_name>` | `/api/my-maven/artifact.jar` |
| `/api/<repo_type>/<repo_name>` | `/api/maven/my-maven/artifact.jar` |
| `/<prefix>/api/<repo_name>` | `/v1/api/my-maven/artifact.jar` |

---

## Appendix B: Cooldown System

The cooldown system blocks package versions that are too recently released to prevent supply chain attacks.

### Configuration

```yaml
meta:
  cooldown:
    enabled: true
    minimum_allowed_age: 7d  # Block versions newer than 7 days
```

### Per-Repository Type

```yaml
meta:
  cooldown:
    repo_types:
      npm:
        enabled: true
        minimum_allowed_age: 7d
      maven:
        enabled: false
```

### Monitoring

```bash
# Check active blocks
docker exec artipie-db psql -U artipie -d artifacts -c \
  "SELECT COUNT(*) FROM artifact_cooldowns WHERE status = 'ACTIVE';"

# View blocked versions in logs
docker logs artipie | grep "event.outcome=blocked"
```

---

## Appendix C: Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `JVM_ARGS` | - | JVM arguments |
| `LOG4J_CONFIGURATION_FILE` | - | External log4j2.xml path |
| `ARTIPIE_ENV` | production | Environment name |
| `JWT_SECRET` | - | JWT signing secret |
| `AWS_ACCESS_KEY_ID` | - | S3 credentials |
| `AWS_SECRET_ACCESS_KEY` | - | S3 credentials |
| `KEYCLOAK_URL` | - | Keycloak server URL |
| `KEYCLOAK_REALM` | - | Keycloak realm |
| `KEYCLOAK_CLIENT_ID` | - | Keycloak client ID |
| `KEYCLOAK_CLIENT_SECRET` | - | Keycloak client secret |
| `OKTA_ISSUER` | - | Okta OIDC issuer |
| `OKTA_CLIENT_ID` | - | Okta client ID |
| `OKTA_CLIENT_SECRET` | - | Okta client secret |
| `OKTA_REDIRECT_URI` | - | Okta redirect URI |

---

*This guide covers Artipie version 1.20.11. For the latest updates, see the [GitHub repository](https://github.com/artipie/artipie).*
