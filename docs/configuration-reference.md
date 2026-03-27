# Pantera Artifact Registry -- Configuration Reference

**Version 2.0.0** | Auto1 Group

This document is the authoritative reference for every configuration option in Pantera.
It covers the main server configuration file (`pantera.yml`), per-repository YAML files,
storage aliases, user and role definitions, environment variables, CLI options, and URL
routing patterns.

---

## Table of Contents

1. [Main Configuration File (pantera.yml)](#1-main-configuration-file-panterayml)
   - [meta.storage](#11-metastorage)
   - [meta.credentials](#12-metacredentials)
   - [meta.policy](#13-metapolicy)
   - [meta.jwt](#14-metajwt)
   - [meta.metrics](#15-metametrics)
   - [meta.artifacts_database](#16-metaartifacts_database)
   - [meta.http_client](#17-metahttp_client)
   - [meta.http_server](#18-metahttp_server)
   - [meta.cooldown](#19-metacooldown)
   - [meta.caches](#110-metacaches)
   - [meta.global_prefixes](#111-metaglobal_prefixes)
   - [meta.layout](#112-metalayout)
2. [Repository Configuration](#2-repository-configuration)
   - [Supported Repository Types](#21-supported-repository-types)
   - [Local Repository](#22-local-repository)
   - [Proxy Repository](#23-proxy-repository)
   - [Group Repository](#24-group-repository)
   - [Type-Specific Settings](#25-type-specific-settings)
3. [Storage Configuration](#3-storage-configuration)
   - [Filesystem (fs)](#31-filesystem-fs)
   - [Amazon S3 (s3)](#32-amazon-s3-s3)
   - [S3 Express One Zone (s3-express)](#33-s3-express-one-zone-s3-express)
   - [Disk Hot Cache for S3](#34-disk-hot-cache-for-s3)
4. [Storage Aliases (_storages.yaml)](#4-storage-aliases-_storagesyaml)
5. [User Files](#5-user-files)
6. [Role / Permission Files](#6-role--permission-files)
7. [Environment Variables Reference](#7-environment-variables-reference)
   - [Database (HikariCP)](#71-database-hikaricp)
   - [I/O Thread Pools](#72-io-thread-pools)
   - [Cache and Deduplication](#73-cache-and-deduplication)
   - [Metrics](#74-metrics)
   - [HTTP Client (Jetty)](#75-http-client-jetty)
   - [Concurrency](#76-concurrency)
   - [Search and API](#77-search-and-api)
   - [Miscellaneous](#78-miscellaneous)
   - [Deployment / Docker Compose](#79-deployment--docker-compose)
8. [Docker Compose Environment (.env)](#8-docker-compose-environment-env)
9. [CLI Options](#9-cli-options)
10. [URL Routing Patterns](#10-url-routing-patterns)

---

## 1. Main Configuration File (pantera.yml)

The main configuration file is typically mounted at `/etc/pantera/pantera.yml`.
All settings live under the top-level `meta:` key.

### 1.1 meta.storage

Defines where Pantera stores its own configuration files (repository definitions,
user files, role definitions, storage aliases).

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | Yes | -- | Storage backend: `fs`, `s3`, or `s3-express` |
| `path` | string | Yes (fs) | -- | Filesystem path for `fs` type |

See [Section 3 -- Storage Configuration](#3-storage-configuration) for the full
set of keys available for each storage type.

```yaml
meta:
  storage:
    type: fs
    path: /var/pantera/repo
```

---

### 1.2 meta.credentials

An ordered array of authentication providers. Pantera evaluates them top-to-bottom;
the first provider that recognizes the credentials authenticates the request.

| Index | `type` value | Description |
|-------|-------------|-------------|
| -- | `env` | Reads username/password from `PANTERA_USER_NAME` / `PANTERA_USER_PASS` environment variables |
| -- | `pantera` | Native file-based users stored in `_credentials.yaml` or individual user YAML files under the policy storage |
| -- | `keycloak` | OpenID Connect via Keycloak |
| -- | `okta` | OpenID Connect via Okta |
| -- | `jwt-password` | Accepts a signed JWT token as the password field in HTTP Basic auth |

#### Provider: `env`

No additional keys. Authentication credentials are taken from environment variables.

```yaml
credentials:
  - type: env
```

| Environment Variable | Description |
|---------------------|-------------|
| `PANTERA_USER_NAME` | Username for env-based auth |
| `PANTERA_USER_PASS` | Password for env-based auth |

#### Provider: `pantera`

Uses user YAML files stored in the policy storage path (see [Section 5](#5-user-files)).

```yaml
credentials:
  - type: pantera
```

#### Provider: `keycloak`

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `url` | string | Yes | -- | Keycloak server base URL |
| `realm` | string | Yes | -- | Keycloak realm name |
| `client-id` | string | Yes | -- | OIDC client identifier |
| `client-password` | string | Yes | -- | OIDC client secret (supports `${ENV_VAR}` syntax) |
| `user-domains` | list | No | -- | Accepted email domain suffixes for user matching |

```yaml
credentials:
  - type: keycloak
    url: "http://keycloak:8080"
    realm: pantera
    client-id: pantera
    client-password: ${KEYCLOAK_CLIENT_SECRET}
    user-domains:
      - "local"
```

#### Provider: `okta`

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `issuer` | string | Yes | -- | Okta issuer URL (e.g., `https://your-org.okta.com`) |
| `client-id` | string | Yes | -- | OIDC client identifier |
| `client-secret` | string | Yes | -- | OIDC client secret |
| `redirect-uri` | string | Yes | -- | OAuth2 redirect URI |
| `scope` | string | No | `openid email profile groups` | Space-separated OIDC scopes |
| `groups-claim` | string | No | `groups` | JWT claim containing group membership |
| `group-roles` | list of maps | No | -- | Maps Okta groups to Pantera roles |
| `user-domains` | list | No | -- | Accepted email domain suffixes |
| `authn-url` | string | No | auto | Override authentication endpoint URL |
| `authorize-url` | string | No | auto | Override authorization endpoint URL |
| `token-url` | string | No | auto | Override token endpoint URL |

```yaml
credentials:
  - type: okta
    issuer: ${OKTA_ISSUER}
    client-id: ${OKTA_CLIENT_ID}
    client-secret: ${OKTA_CLIENT_SECRET}
    redirect-uri: ${OKTA_REDIRECT_URI}
    scope: "openid email profile groups"
    groups-claim: "groups"
    group-roles:
      - pantera_readers: "reader"
      - pantera_admins: "admin"
    user-domains:
      - "@auto1.local"
```

#### Provider: `jwt-password`

No additional keys. Validates JWT tokens submitted as the password in HTTP Basic
Authentication. The JWT must be signed with the secret configured in `meta.jwt.secret`.

```yaml
credentials:
  - type: jwt-password
```

#### Complete Example

```yaml
meta:
  credentials:
    - type: keycloak
      url: "http://keycloak:8080"
      realm: pantera
      client-id: pantera
      client-password: ${KEYCLOAK_CLIENT_SECRET}
      user-domains:
        - "local"
    - type: jwt-password
    - type: okta
      issuer: ${OKTA_ISSUER}
      client-id: ${OKTA_CLIENT_ID}
      client-secret: ${OKTA_CLIENT_SECRET}
      redirect-uri: ${OKTA_REDIRECT_URI}
      scope: "openid email profile groups"
      groups-claim: "groups"
      group-roles:
        - pantera_readers: "reader"
        - pantera_admins: "admin"
    - type: env
    - type: pantera
```

---

### 1.3 meta.policy

Defines the authorization policy engine that maps users to permissions.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | Yes | -- | Policy engine type: `pantera` |
| `eviction_millis` | int | No | `180000` (3 min) | Cache eviction interval for permission data (ms) |
| `storage` | map | Yes | -- | Storage backend for role/permission YAML files |

```yaml
meta:
  policy:
    type: pantera
    eviction_millis: 180000
    storage:
      type: fs
      path: /var/pantera/security
```

Role and permission files are stored inside this storage path.
See [Section 6 -- Role / Permission Files](#6-role--permission-files) for the format.

---

### 1.4 meta.jwt

JSON Web Token settings for API token generation and validation.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `secret` | string | Yes | -- | HMAC signing key. Supports `${ENV_VAR}` syntax. |
| `expires` | boolean | No | `true` | Whether tokens expire |
| `expiry-seconds` | int | No | `86400` | Token lifetime in seconds (24 hours default) |

```yaml
meta:
  jwt:
    secret: ${JWT_SECRET}
    expires: true
    expiry-seconds: 86400
```

---

### 1.5 meta.metrics

Prometheus-compatible metrics endpoint configuration.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `endpoint` | string | Yes | -- | URL path for the metrics endpoint (must start with `/`) |
| `port` | int | Yes | -- | TCP port to serve metrics on |
| `types` | list | No | -- | Metric categories to enable: `jvm`, `storage`, `http` |

```yaml
meta:
  metrics:
    endpoint: /metrics/vertx
    port: 8087
    types:
      - jvm
      - storage
      - http
```

---

### 1.6 meta.artifacts_database

PostgreSQL database for artifact metadata tracking and search.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `postgres_host` | string | Yes | `localhost` | PostgreSQL server hostname |
| `postgres_port` | int | No | `5432` | PostgreSQL server port |
| `postgres_database` | string | Yes | `artifacts` | Database name |
| `postgres_user` | string | Yes | `pantera` | Database username. Supports `${ENV_VAR}`. |
| `postgres_password` | string | Yes | `pantera` | Database password. Supports `${ENV_VAR}`. |
| `pool_max_size` | int | No | `50` | HikariCP maximum pool size |
| `pool_min_idle` | int | No | `10` | HikariCP minimum idle connections |
| `buffer_time_seconds` | int | No | `2` | Buffering interval before flushing events to DB |
| `buffer_size` | int | No | `50` | Maximum events per batch flush |
| `threads_count` | int | No | `1` | Number of parallel threads processing the events queue |
| `interval_seconds` | int | No | `1` | Interval (seconds) to check and flush events queue |

```yaml
meta:
  artifacts_database:
    postgres_host: "pantera-db"
    postgres_port: 5432
    postgres_database: pantera
    postgres_user: ${POSTGRES_USER}
    postgres_password: ${POSTGRES_PASSWORD}
    pool_max_size: 50
    pool_min_idle: 10
```

---

### 1.7 meta.http_client

Global settings for the outbound HTTP client used by all proxy repositories.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `proxy_timeout` | int | No | `120` | Upstream request timeout in seconds |
| `connection_timeout` | int | No | `15000` | TCP connection timeout in milliseconds |
| `max_connections_per_destination` | int | No | `512` | Maximum pooled connections per upstream host |
| `max_requests_queued_per_destination` | int | No | `2048` | Maximum queued requests per upstream host |
| `idle_timeout` | int | No | `30000` | Connection idle timeout in milliseconds |
| `follow_redirects` | boolean | No | `true` | Follow HTTP 3xx redirects |
| `connection_acquire_timeout` | int | No | `120000` | Milliseconds to wait for a pooled connection |

```yaml
meta:
  http_client:
    proxy_timeout: 120
    max_connections_per_destination: 512
    max_requests_queued_per_destination: 2048
    idle_timeout: 30000
    connection_timeout: 15000
    follow_redirects: true
    connection_acquire_timeout: 120000
```

---

### 1.8 meta.http_server

Settings for the inbound HTTP server.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `request_timeout` | string | No | `PT2M` | Maximum request duration. ISO-8601 duration or milliseconds. `0` disables. |

```yaml
meta:
  http_server:
    request_timeout: PT2M
```

---

### 1.9 meta.cooldown

Cooldown prevents Pantera from retrying failed upstream fetches too frequently.
When an artifact is not found upstream, it is "cooled down" for a configured duration.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `enabled` | boolean | No | `false` | Global default enable/disable |
| `minimum_allowed_age` | string | No | -- | Duration before retry. Supports `m` (minutes), `h` (hours), `d` (days). |
| `repo_types` | map | No | -- | Per-repository-type overrides |

Each entry under `repo_types` is a map with:

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `enabled` | boolean | No | inherits global | Enable cooldown for this repo type |

```yaml
meta:
  cooldown:
    enabled: false
    minimum_allowed_age: 7d
    repo_types:
      npm-proxy:
        enabled: true
```

---

### 1.10 meta.caches

Multi-tier caching configuration. Pantera supports an optional Valkey (Redis-compatible)
layer for shared L2 caching across cluster nodes.

#### Top-level Valkey connection

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `valkey.enabled` | boolean | No | `false` | Enable Valkey integration |
| `valkey.host` | string | Yes (if enabled) | -- | Valkey server hostname |
| `valkey.port` | int | No | `6379` | Valkey server port |
| `valkey.timeout` | string | No | `100ms` | Connection timeout (duration string) |

#### Per-cache configuration

Each named cache section supports:

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `ttl` | string | No | -- | Local (L1) cache time-to-live (e.g., `5m`, `24h`, `30d`) |
| `maxSize` | int | No | -- | L1 maximum entry count |
| `valkey.enabled` | boolean | No | `false` | Enable Valkey L2 for this cache |
| `valkey.l1MaxSize` | int | No | -- | L1 max entries when Valkey is enabled |
| `valkey.l1Ttl` | string | No | -- | L1 TTL when Valkey is enabled |
| `valkey.l2MaxSize` | int | No | -- | L2 (Valkey) max entries |
| `valkey.l2Ttl` | string | No | -- | L2 (Valkey) TTL |

**Named cache sections:**

| Cache Name | Purpose |
|-----------|---------|
| `cooldown` | Cooldown result cache (upstream failure tracking) |
| `negative` | Negative lookup cache (artifact-not-found results) |
| `auth` | Authentication/authorization decision cache |
| `maven-metadata` | Maven metadata XML cache |
| `npm-search` | NPM package search index cache |
| `cooldown-metadata` | Long-lived cooldown metadata cache |

```yaml
meta:
  caches:
    valkey:
      enabled: true
      host: valkey
      port: 6379
      timeout: 100ms

    cooldown:
      ttl: 24h
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 1000
        l1Ttl: 24h
        l2MaxSize: 5000000
        l2Ttl: 7d

    negative:
      ttl: 24h
      maxSize: 5000
      valkey:
        enabled: true
        l1MaxSize: 5000
        l1Ttl: 24h
        l2MaxSize: 5000000
        l2Ttl: 7d

    auth:
      ttl: 5m
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 1000
        l1Ttl: 5m
        l2MaxSize: 100000
        l2Ttl: 5m

    maven-metadata:
      ttl: 24h
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 0
        l1Ttl: 24h
        l2MaxSize: 1000000
        l2Ttl: 72h

    npm-search:
      ttl: 24h
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 1000
        l1Ttl: 24h
        l2MaxSize: 1000000
        l2Ttl: 72h

    cooldown-metadata:
      ttl: 30d
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 0
        l1Ttl: 30d
        l2MaxSize: 500000
        l2Ttl: 30d
```

---

### 1.11 meta.global_prefixes

A list of URL path prefixes that Pantera strips before routing. This is useful when
Pantera sits behind a reverse proxy that adds a path prefix.

```yaml
meta:
  global_prefixes:
    - test_prefix
```

With this configuration, a request to `/test_prefix/my-maven/com/example/...` is
routed identically to `/my-maven/com/example/...`.

---

### 1.12 meta.layout

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `layout` | string | No | `flat` | Repository layout: `flat` (single tenant) or `org` (multi-tenant) |

```yaml
meta:
  layout: flat
```

---

### Complete pantera.yml Example

```yaml
meta:
  layout: flat

  storage:
    type: fs
    path: /var/pantera/repo

  jwt:
    secret: ${JWT_SECRET}
    expires: true
    expiry-seconds: 86400

  credentials:
    - type: keycloak
      url: "http://keycloak:8080"
      realm: pantera
      client-id: pantera
      client-password: ${KEYCLOAK_CLIENT_SECRET}
    - type: jwt-password
    - type: okta
      issuer: ${OKTA_ISSUER}
      client-id: ${OKTA_CLIENT_ID}
      client-secret: ${OKTA_CLIENT_SECRET}
      redirect-uri: ${OKTA_REDIRECT_URI}
      scope: "openid email profile groups"
      groups-claim: "groups"
      group-roles:
        - pantera_readers: "reader"
        - pantera_admins: "admin"
    - type: env
    - type: pantera

  policy:
    type: pantera
    eviction_millis: 180000
    storage:
      type: fs
      path: /var/pantera/security

  cooldown:
    enabled: false
    minimum_allowed_age: 7d
    repo_types:
      npm-proxy:
        enabled: true

  artifacts_database:
    postgres_host: "pantera-db"
    postgres_port: 5432
    postgres_database: pantera
    postgres_user: ${POSTGRES_USER}
    postgres_password: ${POSTGRES_PASSWORD}
    pool_max_size: 50
    pool_min_idle: 10

  http_client:
    proxy_timeout: 120
    max_connections_per_destination: 512
    max_requests_queued_per_destination: 2048
    idle_timeout: 30000
    connection_timeout: 15000
    follow_redirects: true
    connection_acquire_timeout: 120000

  http_server:
    request_timeout: PT2M

  metrics:
    endpoint: /metrics/vertx
    port: 8087
    types:
      - jvm
      - storage
      - http

  caches:
    valkey:
      enabled: true
      host: valkey
      port: 6379
      timeout: 100ms
    auth:
      ttl: 5m
      maxSize: 1000

  global_prefixes:
    - my_prefix
```

---

## 2. Repository Configuration

Each repository is defined in an individual YAML file stored in the meta storage path.
The file name (without extension) becomes the repository name.

All repository files live under the `repo:` top-level key.

### 2.1 Supported Repository Types

| Type Keyword | Category | Description |
|-------------|----------|-------------|
| `maven` | Local | Maven 2 / Gradle repository |
| `maven-proxy` | Proxy | Proxies a remote Maven repository |
| `maven-group` | Group | Virtual group of Maven repositories |
| `gradle-proxy` | Proxy | Proxies remote Gradle plugin portals / Maven Central |
| `gradle-group` | Group | Virtual group of Gradle repositories |
| `docker` | Local | Docker (OCI) image registry |
| `docker-proxy` | Proxy | Proxies remote Docker registries |
| `docker-group` | Group | Virtual group of Docker registries |
| `npm` | Local | NPM package registry |
| `npm-proxy` | Proxy | Proxies remote NPM registries |
| `npm-group` | Group | Virtual group of NPM registries |
| `pypi` | Local | Python Package Index |
| `pypi-proxy` | Proxy | Proxies remote PyPI servers |
| `pypi-group` | Group | Virtual group of PyPI servers |
| `go` | Local | Go module proxy |
| `go-proxy` | Proxy | Proxies remote Go module proxies |
| `go-group` | Group | Virtual group of Go module proxies |
| `file` | Local | Generic binary / file storage |
| `file-proxy` | Proxy | Proxies remote file servers |
| `file-group` | Group | Virtual group of file repositories |
| `php` | Local | PHP Composer (Packagist) repository |
| `php-proxy` | Proxy | Proxies remote Composer repositories |
| `php-group` | Group | Virtual group of Composer repositories |
| `helm` | Local | Helm chart repository |
| `gem` | Local | RubyGems repository |
| `gem-group` | Group | Virtual group of Gem repositories |
| `nuget` | Local | NuGet (.NET) package repository |
| `deb` | Local | Debian APT repository |
| `rpm` | Local | RPM (Yum/DNF) repository |
| `conda` | Local | Conda package repository |
| `conan` | Local | Conan C/C++ package repository |
| `hexpm` | Local | Hex.pm (Elixir/Erlang) package repository |

---

### 2.2 Local Repository

A local repository stores artifacts directly in the configured storage backend.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | Yes | -- | Repository type (see table above) |
| `storage` | map | Yes | -- | Storage backend configuration |
| `url` | string | No | -- | Public-facing URL (required by some types: npm, php, helm, nuget, conan, conda) |
| `port` | int | No | -- | Dedicated port (conan only) |
| `settings` | map | No | -- | Type-specific settings |

```yaml
# File: maven.yaml
repo:
  type: maven
  storage:
    type: fs
    path: /var/pantera/data
```

```yaml
# File: npm.yaml
repo:
  type: npm
  url: "http://pantera:8080/npm"
  storage:
    type: fs
    path: /var/pantera/data
```

---

### 2.3 Proxy Repository

A proxy repository caches artifacts from one or more remote upstream servers.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | Yes | -- | Must end in `-proxy` (e.g., `maven-proxy`) |
| `storage` | map | Yes | -- | Local cache storage backend |
| `remotes` | list | Yes | -- | Ordered list of upstream servers |
| `url` | string | No | -- | Public-facing URL |
| `path` | string | No | -- | URL path segment override |

Each entry in `remotes`:

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `url` | string | Yes | -- | Upstream server URL |
| `username` | string | No | -- | Basic auth username for upstream |
| `password` | string | No | -- | Basic auth password for upstream |
| `cache.enabled` | boolean | No | `true` | Whether to cache artifacts from this remote |

```yaml
# File: maven_proxy.yaml
repo:
  type: maven-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://repo1.maven.org/maven2
```

```yaml
# File: docker_proxy.yaml
repo:
  type: docker-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://registry-1.docker.io
    - url: https://docker.elastic.co
    - url: https://gcr.io
    - url: https://k8s.gcr.io
```

```yaml
# File: npm_proxy.yaml
repo:
  type: npm-proxy
  url: http://localhost:8081/npm_proxy
  path: npm_proxy
  remotes:
    - url: "https://registry.npmjs.org"
  storage:
    type: fs
    path: /var/pantera/data
```

```yaml
# File: gradle_proxy.yaml
repo:
  type: gradle-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://repo1.maven.org/maven2
    - url: https://plugins.gradle.org/m2
```

---

### 2.4 Group Repository

A group repository is a virtual aggregation of other repositories (local, proxy, or
other groups). Requests are resolved against members in order; the first match wins.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | Yes | -- | Must end in `-group` (e.g., `maven-group`) |
| `members` | list | Yes | -- | Ordered list of member repository names |
| `url` | string | No | -- | Public-facing URL (required by some types) |

```yaml
# File: maven_group.yaml
repo:
  type: maven-group
  members:
    - maven_proxy    # checked first
    - maven          # checked second
```

```yaml
# File: docker_group.yaml
repo:
  type: docker-group
  members:
    - docker_proxy
    - docker_local
```

```yaml
# File: npm_group.yaml
repo:
  type: npm-group
  members:
    - npm
    - npm_proxy
```

---

### 2.5 Type-Specific Settings

#### Debian (`deb`)

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `settings.Components` | string | No | `main` | Repository component name |
| `settings.Architectures` | string | No | -- | Space-separated CPU architectures (e.g., `amd64`) |

```yaml
repo:
  type: deb
  storage:
    type: fs
    path: /var/pantera/data
  settings:
    Components: main
    Architectures: amd64
```

#### Conan (`conan`)

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `url` | string | Yes | -- | Public URL |
| `port` | int | No | -- | Dedicated Conan server port |

```yaml
repo:
  type: conan
  url: http://pantera:9300/my-conan
  port: 9300
  storage:
    type: fs
    path: /var/pantera/data
```

#### PHP Composer (`php`)

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `url` | string | Yes | -- | Public URL for packages.json resolution |

```yaml
repo:
  type: php
  url: http://pantera:8080/my-php
  storage:
    type: fs
    path: /var/pantera/data
```

#### Helm (`helm`)

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `url` | string | Yes | -- | Public URL for index.yaml resolution |

```yaml
repo:
  type: helm
  url: "http://localhost:8080/my-helm/"
  storage:
    type: fs
    path: /var/pantera/data
```

#### NuGet (`nuget`)

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `url` | string | Yes | -- | NuGet V3 service index URL |

```yaml
repo:
  type: nuget
  url: http://pantera:8080/my-nuget
  storage:
    type: fs
    path: /var/pantera/data
```

#### Conda (`conda`)

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `url` | string | Yes | -- | Public URL |

```yaml
repo:
  type: conda
  url: http://pantera:8080/my-conda
  storage:
    type: fs
    path: /var/pantera/data
```

---

## 3. Storage Configuration

Storage configurations appear in `meta.storage`, per-repository `repo.storage`, and
storage alias definitions. All share the same key structure based on the `type` value.

### 3.1 Filesystem (`fs`)

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | Yes | -- | Must be `fs` |
| `path` | string | Yes | -- | Absolute filesystem path to the data directory |

```yaml
storage:
  type: fs
  path: /var/pantera/data
```

---

### 3.2 Amazon S3 (`s3`)

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | Yes | -- | Must be `s3` |
| `bucket` | string | Yes | -- | S3 bucket name |
| `region` | string | No | SDK default | AWS region (e.g., `eu-west-1`) |
| `endpoint` | string | No | SDK default | Custom S3-compatible endpoint URL |
| `path-style` | boolean | No | `true` | Use path-style access (required for MinIO, LocalStack) |
| `dualstack` | boolean | No | `false` | Enable IPv4+IPv6 dualstack endpoints |

#### S3 Credentials

Nested under `credentials`:

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | No | SDK chain | `default`, `basic`, `profile`, or `assume-role` |

**type: default** -- Uses the standard AWS SDK credential chain (env vars, instance profile, etc.).

**type: basic**

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `accessKeyId` | string | Yes | AWS access key ID |
| `secretAccessKey` | string | Yes | AWS secret access key |
| `sessionToken` | string | No | Temporary session token |

**type: profile**

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `profile` | string | No | `default` | AWS profile name from `~/.aws/credentials` |

**type: assume-role**

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `roleArn` | string | Yes | -- | IAM role ARN to assume |
| `sessionName` | string | No | `pantera-session` | STS session name |
| `externalId` | string | No | -- | External ID for cross-account access |
| `source` | map | No | default chain | Nested credentials block for the source identity |

#### S3 HTTP Client

Nested under `http`:

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `max-concurrency` | int | No | `1024` | Maximum concurrent connections to S3 |
| `max-pending-acquires` | int | No | `2048` | Maximum queued connection requests |
| `acquisition-timeout-millis` | long | No | `30000` | Connection acquisition timeout (ms) |
| `read-timeout-millis` | long | No | `120000` | Socket read timeout (ms) |
| `write-timeout-millis` | long | No | `120000` | Socket write timeout (ms) |
| `connection-max-idle-millis` | long | No | `30000` | Max idle time before connection is closed (ms) |

#### S3 Multipart Upload

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `multipart` | boolean | No | `true` | Enable multipart uploads |
| `multipart-min-size` | string | No | `32MB` | Minimum object size for multipart (supports `KB`, `MB`, `GB`) |
| `part-size` | string | No | `8MB` | Size of each part (supports `KB`, `MB`, `GB`) |
| `multipart-concurrency` | int | No | `16` | Number of concurrent part uploads |

#### S3 Checksum and Encryption

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `checksum` | string | No | `SHA256` | Checksum algorithm: `SHA256`, `CRC32`, `SHA1` |

Nested under `sse` (Server-Side Encryption):

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | No | `AES256` | Encryption type: `AES256` or `KMS` |
| `kms-key-id` | string | No | -- | KMS key ID (required when type is `KMS`) |

#### S3 Parallel Download

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `parallel-download` | boolean | No | `false` | Enable parallel range-based downloads |
| `parallel-download-min-size` | string | No | `64MB` | Minimum object size for parallel download |
| `parallel-download-chunk-size` | string | No | `8MB` | Size of each download range |
| `parallel-download-concurrency` | int | No | `8` | Number of concurrent download ranges |

#### Complete S3 Example

```yaml
storage:
  type: s3
  bucket: my-artifacts
  region: eu-west-1
  endpoint: https://s3.eu-west-1.amazonaws.com
  path-style: false
  dualstack: false
  credentials:
    type: assume-role
    roleArn: arn:aws:iam::123456789012:role/PanteraRole
    sessionName: pantera-prod
    source:
      type: profile
      profile: production
  http:
    max-concurrency: 1024
    max-pending-acquires: 2048
    acquisition-timeout-millis: 30000
    read-timeout-millis: 120000
    write-timeout-millis: 120000
    connection-max-idle-millis: 30000
  multipart: true
  multipart-min-size: 32MB
  part-size: 8MB
  multipart-concurrency: 16
  checksum: SHA256
  sse:
    type: KMS
    kms-key-id: arn:aws:kms:eu-west-1:123456789012:key/my-key-id
  parallel-download: true
  parallel-download-min-size: 64MB
  parallel-download-chunk-size: 8MB
  parallel-download-concurrency: 8
```

---

### 3.3 S3 Express One Zone (`s3-express`)

Uses the same keys as `s3` but targets S3 Express One Zone directory buckets for
ultra-low-latency access. Set `type: s3-express`.

```yaml
storage:
  type: s3-express
  bucket: my-express-bucket--euw1-az1--x-s3
  region: eu-west-1
```

---

### 3.4 Disk Hot Cache for S3

Any S3 storage can be wrapped with a local disk cache to avoid repeated S3 fetches
for hot artifacts. Configure the `cache` section within the S3 storage block.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `enabled` | boolean | Yes | -- | Must be `true` to activate |
| `path` | string | Yes | -- | Local filesystem path for cache files |
| `max-bytes` | long | No | `10737418240` (10 GiB) | Maximum cache size in bytes |
| `high-watermark-percent` | int | No | `90` | Cache eviction starts at this percentage |
| `low-watermark-percent` | int | No | `80` | Eviction stops when cache drops to this percentage |
| `cleanup-interval-millis` | long | No | `300000` (5 min) | How often to run eviction |
| `eviction-policy` | string | No | `LRU` | Eviction policy: `LRU` or `LFU` |
| `validate-on-read` | boolean | No | `true` | Validate cache integrity on every read |

```yaml
storage:
  type: s3
  bucket: my-artifacts
  region: eu-west-1
  cache:
    enabled: true
    path: /var/pantera/cache/s3
    max-bytes: 10737418240
    high-watermark-percent: 90
    low-watermark-percent: 80
    cleanup-interval-millis: 300000
    eviction-policy: LRU
    validate-on-read: true
```

---

## 4. Storage Aliases (_storages.yaml)

Storage aliases let you define named storage configurations once and reference them
by name in repository files. The file is named `_storages.yaml` and lives in the
meta storage path.

```yaml
# File: _storages.yaml
storages:
  default:
    type: fs
    path: /var/pantera/repo/data

  s3:
    type: s3
    bucket: "my-s3-bucket"
    region: eu-west-1

  fast-cache:
    type: s3-express
    bucket: my-express-bucket--euw1-az1--x-s3
    region: eu-west-1
```

Then reference by name in a repository file:

```yaml
repo:
  type: maven
  storage: default
```

---

## 5. User Files

User files are YAML files stored under the policy storage path, typically inside a
`users/` subdirectory. The filename (minus extension) is the username.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | Yes | -- | Password encoding: `plain` |
| `pass` | string | Yes | -- | User password |
| `email` | string | No | -- | User email address |
| `roles` | list | No | `[]` | List of role names assigned to this user |
| `enabled` | boolean | No | `true` | Whether the user account is active |
| `permissions` | map | No | -- | Inline permissions (alternative to roles) |

### Inline Permission Types

| Permission Key | Scope | Values |
|---------------|-------|--------|
| `adapter_basic_permissions` | Per-repository | `read`, `write`, `delete`, `*` (all) |
| `docker_repository_permissions` | Per-registry, per-repo | `pull`, `push`, `*` |
| `docker_registry_permissions` | Per-registry | `base` |
| `all_permission` | Global | `{}` (grants everything) |

### Example User Files

```yaml
# File: users/alice.yaml
type: plain
pass: s3cret
permissions:
  adapter_basic_permissions:
    my-maven:
      - "*"
    my-npm:
      - read
      - write
```

```yaml
# File: users/bob.yaml
type: plain
pass: qwerty
roles:
  - readers
```

```yaml
# File: users/john.yaml
type: plain
pass: xyz
roles:
  - admin
```

---

## 6. Role / Permission Files

Role files are YAML files stored under the policy storage path, typically inside a
`roles/` subdirectory. The filename (minus extension) is the role name.

Each file contains a `permissions` map. The same permission types from user files
apply here.

### Admin Role (all permissions)

```yaml
# File: roles/admin.yaml
permissions:
  all_permission: {}
```

### Read-Only Role

```yaml
# File: roles/readers.yaml
permissions:
  adapter_basic_permissions:
    "*":
      - read
```

### Custom Role

```yaml
# File: roles/deployer.yaml
permissions:
  adapter_basic_permissions:
    maven:
      - read
      - write
    npm:
      - read
      - write
    docker_local:
      - read
      - write
  docker_repository_permissions:
    "*":
      "*":
        - pull
        - push
```

### Permission Reference

| Permission Type | Key Pattern | Allowed Values |
|----------------|-------------|----------------|
| `adapter_basic_permissions` | `<repo_name>` -> list | `read`, `write`, `delete`, `*` |
| `docker_repository_permissions` | `<registry>` -> `<repo>` -> list | `pull`, `push`, `*` |
| `docker_registry_permissions` | `<registry>` -> list | `base` |
| `all_permission` | `{}` | Grants unrestricted access to all repositories |

Use `"*"` as a wildcard to match all repositories or registries.

---

## 7. Environment Variables Reference

Pantera reads environment variables at startup to configure internal subsystems.
These override compiled defaults and allow tuning without changing YAML files.

All variables follow the convention `PANTERA_*`. Each variable can also be set as
a Java system property using the lowercase, dot-separated equivalent (e.g.,
`PANTERA_DB_POOL_MAX` becomes `-Dpantera.db.pool.max=50`).

### 7.1 Database (HikariCP)

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_DB_POOL_MAX` | `50` | Maximum database connection pool size |
| `PANTERA_DB_POOL_MIN` | `10` | Minimum idle connections |
| `PANTERA_DB_CONNECTION_TIMEOUT_MS` | `5000` | Connection acquisition timeout (ms) |
| `PANTERA_DB_IDLE_TIMEOUT_MS` | `600000` | Idle connection timeout (ms) -- 10 minutes |
| `PANTERA_DB_MAX_LIFETIME_MS` | `1800000` | Maximum connection lifetime (ms) -- 30 minutes |
| `PANTERA_DB_LEAK_DETECTION_MS` | `300000` | Leak detection threshold (ms) -- 5 minutes |
| `PANTERA_DB_BUFFER_SECONDS` | `2` | Event buffer flush interval (seconds) |
| `PANTERA_DB_BATCH_SIZE` | `200` | Maximum events per database batch |

### 7.2 I/O Thread Pools

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_IO_READ_THREADS` | CPU cores x 4 | Thread pool for storage read operations |
| `PANTERA_IO_WRITE_THREADS` | CPU cores x 2 | Thread pool for storage write operations |
| `PANTERA_IO_LIST_THREADS` | CPU cores x 1 | Thread pool for storage list operations |
| `PANTERA_FILESYSTEM_IO_THREADS` | `max(8, CPU cores x 2)` | Dedicated filesystem I/O thread pool (min: 4, max: 256) |

### 7.3 Cache and Deduplication

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_DEDUP_MAX_AGE_MS` | `300000` | Maximum age of in-flight dedup entries (ms) -- 5 minutes |
| `PANTERA_DOCKER_CACHE_EXPIRY_HOURS` | `24` | Docker proxy cache entry lifetime (hours) |
| `PANTERA_NPM_INDEX_TTL_HOURS` | `24` | NPM search index TTL (hours) |
| `PANTERA_BODY_BUFFER_THRESHOLD` | `1048576` | Request body size threshold (bytes). Below this: buffered in memory. Above: streamed from disk. |

### 7.4 Metrics

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_METRICS_MAX_REPOS` | `50` | Maximum distinct `repo_name` label values before cardinality limiting |
| `PANTERA_METRICS_PERCENTILES_HISTOGRAM` | `false` | Enable histogram buckets for all Timer metrics |

### 7.5 HTTP Client (Jetty)

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_JETTY_BUCKET_SIZE` | `1024` | Jetty buffer pool max bucket size (buffers per size class) |
| `PANTERA_JETTY_DIRECT_MEMORY` | `2147483648` (2 GiB) | Jetty buffer pool max direct memory (bytes) |
| `PANTERA_JETTY_HEAP_MEMORY` | `1073741824` (1 GiB) | Jetty buffer pool max heap memory (bytes) |

### 7.6 Concurrency

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_GROUP_DRAIN_PERMITS` | `20` | Maximum concurrent response body drains in group repositories |

### 7.7 Search and API

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_SEARCH_LIKE_TIMEOUT_MS` | `3000` | SQL statement timeout for LIKE fallback queries (ms) |
| `PANTERA_SEARCH_MAX_PAGE` | `500` | Maximum page number for search pagination |
| `PANTERA_SEARCH_MAX_SIZE` | `100` | Maximum results per search page |
| `PANTERA_SEARCH_OVERFETCH` | `10` | Over-fetch multiplier for permission-filtered search results. The DB fetches `page_size * N` rows so that after dropping rows the user has no access to, the page can still be filled. Increase for deployments with many repos where users only access a few. |
| `PANTERA_DOWNLOAD_TOKEN_SECRET` | auto-generated | HMAC secret for download token signing |

### 7.8 Miscellaneous

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_DIAGNOSTICS_DISABLED` | `false` | Set to `true` to disable blocked-thread diagnostics |
| `PANTERA_INIT` | `false` | Set to `true` to initialize default example configs on first start |
| `PANTERA_BUF_ACCUMULATOR_MAX_BYTES` | `104857600` (100 MB) | Maximum buffer size for HTTP header/multipart boundary parsing. Safety limit to prevent OOM from malformed requests. Not used for artifact streaming. |

---

### 7.9 Deployment / Docker Compose

These variables are consumed by the Docker Compose stack and Dockerfile, not by the
Java application directly (unless noted).

#### Pantera Application

| Variable | Description |
|----------|-------------|
| `PANTERA_USER_NAME` | Default admin username (consumed by `type: env` credential provider) |
| `PANTERA_USER_PASS` | Default admin password (consumed by `type: env` credential provider) |
| `PANTERA_CONFIG` | Path to pantera.yml inside the container (default: `/etc/pantera/pantera.yml`) |
| `PANTERA_VERSION` | Docker image tag / application version |

#### Secrets (used in pantera.yml via `${VAR}`)

| Variable | Description |
|----------|-------------|
| `JWT_SECRET` | HMAC key for JWT token signing |
| `KEYCLOAK_CLIENT_SECRET` | Keycloak OIDC client secret |
| `POSTGRES_USER` | PostgreSQL username |
| `POSTGRES_PASSWORD` | PostgreSQL password |

#### Okta OIDC

| Variable | Description |
|----------|-------------|
| `OKTA_ISSUER` | Okta issuer URL (e.g., `https://your-org.okta.com`) |
| `OKTA_CLIENT_ID` | Okta OIDC client identifier |
| `OKTA_CLIENT_SECRET` | Okta OIDC client secret |
| `OKTA_REDIRECT_URI` | OAuth2 callback URL |

#### AWS

| Variable | Description |
|----------|-------------|
| `AWS_CONFIG_FILE` | Path to AWS config file inside the container |
| `AWS_SHARED_CREDENTIALS_FILE` | Path to AWS credentials file inside the container |
| `AWS_SDK_LOAD_CONFIG` | Set to `1` to load AWS config |
| `AWS_PROFILE` | AWS named profile |
| `AWS_REGION` | AWS region |

#### JVM

| Variable | Description |
|----------|-------------|
| `JVM_ARGS` | JVM arguments passed to the `java` command |

#### Logging

| Variable | Description |
|----------|-------------|
| `LOG4J_CONFIGURATION_FILE` | Path to Log4j2 configuration file |

#### Elastic APM

| Variable | Default | Description |
|----------|---------|-------------|
| `ELASTIC_APM_ENABLED` | `false` | Enable Elastic APM agent |
| `ELASTIC_APM_ENVIRONMENT` | `development` | APM environment label |
| `ELASTIC_APM_SERVER_URL` | -- | APM server URL |
| `ELASTIC_APM_SERVICE_NAME` | `pantera` | Service name in APM |
| `ELASTIC_APM_SERVICE_VERSION` | -- | Application version in APM |
| `ELASTIC_APM_LOG_LEVEL` | `INFO` | APM agent log level |
| `ELASTIC_APM_LOG_FORMAT_SOUT` | `JSON` | APM log output format |
| `ELASTIC_APM_TRANSACTION_MAX_SPANS` | `1000` | Max spans per transaction |
| `ELASTIC_APM_ENABLE_EXPERIMENTAL_INSTRUMENTATIONS` | `true` | Enable experimental instrumentations |
| `ELASTIC_APM_CAPTURE_BODY` | `errors` | When to capture request body |
| `ELASTIC_APM_USE_PATH_AS_TRANSACTION_NAME` | `false` | Use URL path as transaction name |
| `ELASTIC_APM_SPAN_COMPRESSION_ENABLED` | `true` | Enable span compression |
| `ELASTIC_APM_CAPTURE_JMX_METRICS` | -- | JMX metric capture pattern |

---

## 8. Docker Compose Environment (.env)

Copy `.env.example` to `.env` and set values for your deployment. Below is the
complete variable reference for the Docker Compose stack.

| Variable | Example Value | Description |
|----------|--------------|-------------|
| **Pantera** | | |
| `PANTERA_VERSION` | `2.0.0` | Docker image version tag |
| `PANTERA_USER_NAME` | `pantera` | Initial admin username |
| `PANTERA_USER_PASS` | `changeme` | Initial admin password |
| `PANTERA_CONFIG` | `/etc/pantera/pantera.yml` | Config file path in container |
| **AWS** | | |
| `AWS_CONFIG_FILE` | `/home/.aws/config` | AWS config path in container |
| `AWS_SHARED_CREDENTIALS_FILE` | `/home/.aws/credentials` | AWS credentials path |
| `AWS_SDK_LOAD_CONFIG` | `1` | Enable AWS config loading |
| `AWS_PROFILE` | `your_profile_name` | Named AWS profile |
| `AWS_REGION` | `eu-west-1` | AWS region |
| **JVM** | | |
| `JVM_ARGS` | `-Xms3g -Xmx4g ...` | Full JVM argument string |
| **Elastic APM** | | |
| `ELASTIC_APM_ENABLED` | `false` | Enable APM |
| `ELASTIC_APM_ENVIRONMENT` | `development` | Environment label |
| `ELASTIC_APM_SERVER_URL` | `http://apm:8200` | APM server endpoint |
| `ELASTIC_APM_SERVICE_NAME` | `pantera` | APM service name |
| `ELASTIC_APM_SERVICE_VERSION` | `2.0.0` | APM service version |
| `ELASTIC_APM_LOG_LEVEL` | `INFO` | Agent log verbosity |
| `ELASTIC_APM_LOG_FORMAT_SOUT` | `JSON` | Agent log format |
| **Okta** | | |
| `OKTA_ISSUER` | `https://your-org.okta.com` | Okta issuer URL |
| `OKTA_CLIENT_ID` | `your_client_id` | OIDC client ID |
| `OKTA_CLIENT_SECRET` | `your_client_secret` | OIDC client secret |
| `OKTA_REDIRECT_URI` | `http://localhost:8081/okta/callback` | OAuth2 callback |
| **PostgreSQL** | | |
| `POSTGRES_USER` | `pantera` | Database username |
| `POSTGRES_PASSWORD` | `changeme` | Database password |
| **Keycloak** | | |
| `KC_DB` | `postgres` | Keycloak database type |
| `KC_DB_URL` | `jdbc:postgresql://pantera-db:5432/keycloak` | Keycloak DB JDBC URL |
| `KC_DB_USERNAME` | `pantera` | Keycloak DB username |
| `KC_DB_PASSWORD` | `changeme` | Keycloak DB password |
| `KEYCLOAK_ADMIN` | `admin` | Keycloak admin username |
| `KEYCLOAK_ADMIN_PASSWORD` | `changeme` | Keycloak admin password |
| `KC_HOSTNAME_STRICT` | `false` | Keycloak hostname strict mode |
| `KC_HOSTNAME_STRICT_HTTPS` | `false` | Keycloak HTTPS strict mode |
| `KC_HTTP_ENABLED` | `true` | Enable Keycloak HTTP |
| `KEYCLOAK_CLIENT_SECRET` | `your_secret` | Pantera Keycloak client secret |
| **Grafana** | | |
| `GF_SECURITY_ADMIN_USER` | `admin` | Grafana admin username |
| `GF_SECURITY_ADMIN_PASSWORD` | `changeme` | Grafana admin password |
| `GF_USERS_ALLOW_SIGN_UP` | `false` | Allow self-registration |
| `GF_SERVER_ROOT_URL` | `http://localhost:3000` | Grafana root URL |
| `GF_INSTALL_PLUGINS` | `grafana-piechart-panel` | Grafana plugins to install |
| **Application Secrets** | | |
| `JWT_SECRET` | -- | JWT signing key (required) |

---

## 9. CLI Options

Pantera is started via `com.auto1.pantera.VertxMain`. The following CLI options are
available:

| Option | Long Form | Required | Default | Description |
|--------|-----------|----------|---------|-------------|
| `-f` | `--config-file` | Yes | -- | Path to pantera.yml configuration file |
| `-p` | `--port` | No | `80` | Repository server port (artifact traffic) |
| `-ap` | `--api-port` | No | `8086` | REST API port (management API) |

### Dockerfile Default Command

```
java \
  -javaagent:/opt/apm/elastic-apm-agent.jar \
  $JVM_ARGS \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.security=ALL-UNNAMED \
  -cp /usr/lib/pantera/pantera.jar:/usr/lib/pantera/lib/* \
  com.auto1.pantera.VertxMain \
  --config-file=/etc/pantera/pantera.yml \
  --port=8080 \
  --api-port=8086
```

### Exposed Ports

| Port | Purpose |
|------|---------|
| `8080` | Repository traffic (artifact uploads/downloads, Docker registry API) |
| `8086` | REST management API |
| `8087` | Prometheus metrics endpoint (configured via `meta.metrics.port`) |

---

## 10. URL Routing Patterns

Pantera supports multiple URL patterns for accessing repositories. The routing
engine resolves the repository name from the URL and dispatches the request.

### Supported Access Patterns

| Pattern | Example | Description |
|---------|---------|-------------|
| `/<repo_name>/<path>` | `/maven/com/example/lib/1.0/lib-1.0.jar` | Direct access by repository name |
| `/<prefix>/<repo_name>/<path>` | `/test_prefix/maven/com/example/...` | Prefixed access (requires `global_prefixes`) |
| `/api/<repo_name>/<path>` | `/api/maven/com/example/...` | API-routed access |
| `/<prefix>/api/<repo_name>/<path>` | `/test_prefix/api/maven/...` | Prefixed API access |
| `/api/<repo_type>/<repo_name>/<path>` | `/api/npm/my-npm-repo/lodash` | Type-qualified API access |
| `/<prefix>/api/<repo_type>/<repo_name>/<path>` | `/test_prefix/api/npm/my-npm-repo/...` | Prefixed type-qualified API access |

### Repository Type URL Aliases

When using the `/api/<repo_type>/...` pattern, the following type names are recognized:

| URL Type | Maps to `repo.type` |
|----------|-------------------|
| `conan` | `conan` |
| `conda` | `conda` |
| `debian` | `deb` |
| `docker` | `docker` |
| `storage` | `file` |
| `gems` | `gem` |
| `go` | `go` |
| `helm` | `helm` |
| `hex` | `hexpm` |
| `npm` | `npm` |
| `nuget` | `nuget` |
| `composer` | `php` |
| `pypi` | `pypi` |

### Limited Support Types

The following repository types do **not** support the `/api/<repo_type>/<repo_name>`
URL pattern. They must be accessed directly by repository name:

- `gradle`
- `rpm`
- `maven`

For these types, use `/<repo_name>/<path>` or `/api/<repo_name>/<path>` instead.

### Disambiguation Rules

When the first segment after `/api/` matches both a known repository type and a
repository name, Pantera checks the second segment against the repository registry.
If the second segment is a known repository name, the type-qualified interpretation
is used. Otherwise, the first segment is treated as the repository name.

### Special Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/.health` | GET | Health check (returns 200 OK) |
| `/.version` | GET | Returns Pantera version information |
| `/.import/<path>` | PUT/POST | Bulk artifact import API |
| `/.merge/<path>` | POST | Shard merge API |

---

## Appendix: Default JVM Arguments (Dockerfile)

The Pantera Docker image ships with the following default JVM arguments. Override
them by setting the `JVM_ARGS` environment variable.

```
-XX:+UseG1GC
-XX:MaxGCPauseMillis=300
-XX:G1HeapRegionSize=16m
-XX:+UseStringDeduplication
-XX:+ParallelRefProcEnabled
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-XX:+ExitOnOutOfMemoryError
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/pantera/logs/dumps/heapdump.hprof
-Xlog:gc*:file=/var/pantera/logs/gc.log:time,uptime:filecount=5,filesize=100m
-Djava.io.tmpdir=/var/pantera/cache/tmp
-Dvertx.cacheDirBase=/var/pantera/cache/tmp
-Dio.netty.allocator.maxOrder=11
-Dio.netty.leakDetection.level=simple
```

---

## Appendix: Docker Compose Service Architecture

The reference Docker Compose stack includes the following services:

| Service | Image | Port(s) | Purpose |
|---------|-------|---------|---------|
| `pantera` | `pantera:${PANTERA_VERSION}` | 8080, 8086, 8087 | Artifact registry server |
| `nginx` | `nginx:latest` | 80, 443 | TLS termination and reverse proxy |
| `keycloak` | `quay.io/keycloak/keycloak:26.0.0` | 8080 | Identity provider (OIDC) |
| `pantera-db` | `postgres:17.8-alpine` | 5432 | PostgreSQL database |
| `valkey` | `valkey/valkey:8.1.4` | 6379 | Distributed cache (Redis-compatible) |
| `prometheus` | `prom/prometheus:latest` | 9090 | Metrics collection |
| `grafana` | `grafana/grafana:latest` | 3000 | Metrics visualization |
| `pantera-ui` | custom build | 8090 | Web management UI |

### Resource Recommendations

| Resource | Default | Description |
|----------|---------|-------------|
| CPUs | 4 | Minimum for parallel request handling |
| Memory | 6 GB | Reservation and limit for the Pantera container |
| File descriptors | 1,048,576 | Required for concurrent proxy connections |
| Process limit | 65,536 | Maximum threads/processes |

---

*Copyright 2025-2026 Auto1 Group. All rights reserved.*
