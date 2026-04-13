# Configuration

> **Guide:** Admin Guide | **Section:** Configuration

This page describes the structure and key sections of the main Pantera configuration file. For the exhaustive list of every configuration key and its type, default, and description, see the [Configuration Reference](../configuration-reference.md).

---

## pantera.yml Structure

The main configuration file is `/etc/pantera/pantera.yml`. All sections are nested under the top-level `meta:` key.

```yaml
meta:
  storage:            # Where repository YAML configs are stored
  credentials:        # Authentication providers (evaluated in order)
  policy:             # Authorization (RBAC) policy storage
  jwt:                # JWT token settings
  metrics:            # Prometheus metrics
  artifacts_database: # PostgreSQL connection
  http_client:        # Outbound HTTP client for proxies
  http_server:        # Inbound HTTP server settings
  cooldown:           # Supply chain cooldown
  caches:             # Valkey and in-memory cache settings
  global_prefixes:    # URL path prefix stripping
  layout:             # Repository layout (flat or org)
```

---

## Environment Variable Substitution

Any value in `pantera.yml` can reference environment variables using `${VAR}` syntax. Variables are resolved at load time.

```yaml
meta:
  jwt:
    private-key-path: ${JWT_PRIVATE_KEY_PATH}
    public-key-path: ${JWT_PUBLIC_KEY_PATH}
  artifacts_database:
    postgres_password: ${POSTGRES_PASSWORD}
```

This allows secrets to be injected from the environment (Docker secrets, Kubernetes secrets, CI/CD pipelines) without hardcoding them in the configuration file.

---

## meta.storage

Defines where Pantera stores its own configuration files -- repository definitions, user files, role definitions, and storage aliases.

```yaml
meta:
  storage:
    type: fs
    path: /var/pantera/repo
```

Each repository is defined in a separate YAML file under this path (e.g., `my-maven.yaml`). The filename without extension becomes the repository name.

---

## meta.credentials

An ordered array of authentication providers. Pantera evaluates them top-to-bottom; the first provider that recognizes the credentials authenticates the request.

```yaml
meta:
  credentials:
    - type: env                # PANTERA_USER_NAME / PANTERA_USER_PASS
    - type: pantera            # Native users
    - type: keycloak           # Keycloak OIDC
      url: "http://keycloak:8080"
      realm: pantera
      client-id: pantera
      client-password: ${KEYCLOAK_CLIENT_SECRET}
    - type: okta               # Okta OIDC with MFA
      issuer: ${OKTA_ISSUER}
      client-id: ${OKTA_CLIENT_ID}
      client-secret: ${OKTA_CLIENT_SECRET}
      redirect-uri: ${OKTA_REDIRECT_URI}
    - type: jwt-password       # JWT tokens as passwords
```

For detailed configuration of each provider, see [Authentication](authentication.md).

---

## meta.policy

RBAC authorization policy. The `pantera` type uses YAML files for role and permission definitions.

```yaml
meta:
  policy:
    type: pantera
    eviction_millis: 180000    # Policy cache TTL (default: 3 min)
    storage:
      type: fs
      path: /var/pantera/security
```

Role and permission files are stored inside this storage path. See [Authorization](authorization.md) for the full RBAC model.

---

## meta.jwt

JWT token configuration for RS256 asymmetric signing. The private key signs tokens; the public key verifies them.

> **Breaking change (v2.1.0+):** `meta.jwt.secret` (HS256) has been removed. Replace it with `private-key-path` and `public-key-path`. See [Authentication](authentication.md#jwt-token-configuration) for key generation instructions.

```yaml
meta:
  jwt:
    private-key-path: ${JWT_PRIVATE_KEY_PATH}   # Path to RSA private key PEM file
    public-key-path: ${JWT_PUBLIC_KEY_PATH}      # Path to RSA public key PEM file
    access-token-expiry-seconds: 3600            # Access token TTL (default: 1 hour)
    refresh-token-expiry-seconds: 604800         # Refresh token TTL (default: 7 days)
```

In HA deployments, all nodes must reference the same key pair (mount the PEM files from shared storage or a secrets manager). The public key alone is sufficient for verification -- nodes that only verify tokens (no token issuance) need only `public-key-path`.

---

## meta.metrics

Prometheus metrics endpoint configuration.

```yaml
meta:
  metrics:
    endpoint: /metrics/vertx   # Path for metrics scraping
    port: 8087                 # Dedicated metrics port
    types:
      - jvm                    # JVM heap, GC, threads
      - storage                # Storage operation counts and latency
      - http                   # HTTP request/response metrics
```

See [Monitoring](monitoring.md) for the full metrics reference and Grafana setup.

---

## meta.artifacts_database

PostgreSQL connection for metadata, search, settings, and RBAC persistence.

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

The database is required for search, cooldown, API token management, and HA clustering. Flyway migrations are applied automatically at startup.

For the full list of database keys (buffer sizes, thread counts, intervals), see the [Configuration Reference](../configuration-reference.md#16-metaartifacts_database).

---

## meta.http_client

Global settings for the outbound HTTP client (Jetty) used by all proxy repositories.

```yaml
meta:
  http_client:
    proxy_timeout: 120                        # Seconds before upstream timeout
    max_connections_per_destination: 512       # Max connections per upstream host
    max_requests_queued_per_destination: 2048  # Max queued requests per host
    idle_timeout: 30000                        # Idle connection timeout (ms)
    connection_timeout: 15000                  # Initial connect timeout (ms)
    follow_redirects: true                     # Follow HTTP 3xx redirects
    connection_acquire_timeout: 120000         # Wait for pooled connection (ms)
```

These settings apply to all proxy repository upstream requests. See [Performance Tuning](performance-tuning.md) for sizing recommendations.

---

## meta.http_server

Inbound HTTP server settings.

```yaml
meta:
  http_server:
    request_timeout: PT2M     # ISO-8601 duration or milliseconds
```

Set to `0` to disable the request timeout. The default is 2 minutes.

---

## meta.cooldown

Supply chain security cooldown configuration.

```yaml
meta:
  cooldown:
    enabled: false
    minimum_allowed_age: 7d
    repo_types:
      npm-proxy:
        enabled: true
      maven-proxy:
        enabled: true
        minimum_allowed_age: 3d
```

See [Cooldown](cooldown.md) for the full operational guide.

---

## meta.caches

Multi-tier caching configuration with Valkey (L2) and Caffeine (L1).

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
    auth:
      ttl: 5m
      maxSize: 1000
    maven-metadata:
      ttl: 24h
      maxSize: 1000
    npm-search:
      ttl: 24h
      maxSize: 1000
    cooldown-metadata:
      ttl: 30d
      maxSize: 1000
```

Each named cache section supports `ttl`, `maxSize`, and an optional nested `valkey` block for L2 configuration. For the complete key list, see the [Configuration Reference](../configuration-reference.md#110-metacaches).

---

## Repository YAML Files

Each repository is defined in a separate YAML file stored under the `meta.storage` path. The filename (without extension) becomes the repository name.

**Local repository:**

```yaml
# my-maven.yaml
repo:
  type: maven
  storage:
    type: fs
    path: /var/pantera/data
```

**Proxy repository:**

```yaml
# maven-central.yaml
repo:
  type: maven-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://repo1.maven.org/maven2
```

**Group repository:**

```yaml
# maven-group.yaml
repo:
  type: maven-group
  members:
    - my-maven        # local repo (resolved first)
    - maven-central   # proxy repo (fallback)
```

Repositories can also be managed via the REST API. See the [REST API Reference](../rest-api-reference.md#4-repository-management) for the repository CRUD endpoints.

For the full set of repository type keywords, type-specific settings, and proxy/group configuration keys, see the [Configuration Reference](../configuration-reference.md#2-repository-configuration).

### Dedicated Port per Repository

A repository can be bound to its own port by specifying the `port` field:

```yaml
repo:
  type: docker
  storage: default
  port: 54321
```

This is especially useful for Docker repositories. Repositories created or updated via the REST API take effect immediately, including new port listeners.

---

## Complete Example

```yaml
meta:
  layout: flat

  storage:
    type: fs
    path: /var/pantera/repo

  jwt:
    private-key-path: ${JWT_PRIVATE_KEY_PATH}
    public-key-path: ${JWT_PUBLIC_KEY_PATH}
    access-token-expiry-seconds: 3600
    refresh-token-expiry-seconds: 604800

  credentials:
    - type: keycloak
      url: "http://keycloak:8080"
      realm: pantera
      client-id: pantera
      client-password: ${KEYCLOAK_CLIENT_SECRET}
    - type: jwt-password
    - type: env
    - type: pantera

  policy:
    type: pantera
    eviction_millis: 180000
    storage:
      type: fs
      path: /var/pantera/security

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
    connection_timeout: 15000

  http_server:
    request_timeout: PT2M

  metrics:
    endpoint: /metrics/vertx
    port: 8087
    types:
      - jvm
      - storage
      - http

  cooldown:
    enabled: false
    minimum_allowed_age: 7d

  caches:
    valkey:
      enabled: true
      host: valkey
      port: 6379
      timeout: 100ms
    auth:
      ttl: 5m
      maxSize: 1000
```

---

## Scheduled Scripts (Crontab)

Pantera supports running custom server-side scripts on a schedule using the JVM scripting engine. Scripts are configured in pantera.yml under `meta.crontab`.

### Configuration

```yaml
meta:
  crontab:
    - path: path/to/script.groovy
      cronexp: "*/3 * * * * ?"
```

The `cronexp` value uses [Quartz cron format](http://www.quartz-scheduler.org/documentation/quartz-2.3.0/tutorials/crontrigger.html). The example above runs every 3 minutes.

### Supported Languages

| Language | File Extension |
|----------|---------------|
| Groovy | `.groovy` |
| Python 2 | `.py` |

### Accessing Pantera Objects

Scripts can access server objects via underscore-prefixed variables:

| Variable | Type | Description |
|----------|------|-------------|
| `_settings` | `com.auto1.pantera.settings.Settings` | Application settings |
| `_repositories` | `com.auto1.pantera.settings.repo.Repositories` | Repository configurations |

**Example (Groovy):**

```groovy
File file = new File('/my-repo/info/cfg.log')
cfg = _repositories.config('my-repo').toCompletableFuture().join()
file.write cfg.toString()
```

---

## HTTP/3 Protocol Support (Experimental)

Pantera supports HTTP/3 via the Jetty HTTP/3 implementation. This feature is experimental.

### Server-Side (Per-Repository)

Enable HTTP/3 for a specific repository by adding `http3` settings to the repository YAML:

```yaml
repo:
  type: maven
  storage: default
  port: 5647
  http3: true
  http3_ssl:
    jks:
      path: keystore.jks
      password: secret
```

HTTP/3 requires TLS, so SSL settings are mandatory. Multiple repositories can share the same HTTP/3 port.

### Client-Side (Proxy Adapters)

To use HTTP/3 for all outbound proxy requests, set the environment variable:

```bash
http3.client=true
```

---

## Repository Filters

Pantera can filter repository resources by URL pattern. Filters are defined in the repository YAML under the `filters` section.

### Configuration

```yaml
repo:
  type: maven
  storage: default
  filters:
    include:
      glob:
        - filter: '**/org/springframework/**/*.jar'
        - filter: '**/org/apache/logging/**/*.jar'
          priority: 10
      regexp:
        - filter: '.*/com/auto1/.*\.jar'
    exclude:
      glob:
        - filter: '**/org/apache/logging/log4j/log4j-core/2.17.0/*.jar'
```

### Filter Rules

- A resource is **allowed** if it matches at least one `include` pattern and does not match any `exclude` pattern.
- A resource is **blocked** if it matches an `exclude` pattern, or if both `include` and `exclude` are empty.
- Filters are ordered by definition order and optional `priority` field (default: 0).

### Filter Types

**Glob filters** match against the request path using [Java glob syntax](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String)):

| Field | Required | Description |
|-------|----------|-------------|
| `filter` | Yes | Glob expression |
| `priority` | No | Numeric priority (default: 0) |

**Regexp filters** match against the request path or full URI:

| Field | Required | Description |
|-------|----------|-------------|
| `filter` | Yes | Regular expression |
| `priority` | No | Numeric priority (default: 0) |
| `full_uri` | No | Match full URI instead of path only (default: false) |
| `case_insensitive` | No | Case-insensitive matching (default: false) |

---

## Related Pages

- [Configuration Reference](../configuration-reference.md) -- Exhaustive key-by-key reference
- [Authentication](authentication.md) -- Detailed auth provider configuration
- [Authorization](authorization.md) -- RBAC policy setup
- [Storage Backends](storage-backends.md) -- Filesystem and S3 storage options
- [Environment Variables](environment-variables.md) -- All tunable environment variables
