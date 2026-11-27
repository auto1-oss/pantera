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
   - [meta.crontab](#113-metacrontab)
2. [Repository Configuration](#2-repository-configuration)
   - [Supported Repository Types](#21-supported-repository-types)
   - [Local Repository](#22-local-repository)
   - [Proxy Repository](#23-proxy-repository)
   - [Group Repository](#24-group-repository)
   - [Type-Specific Settings](#25-type-specific-settings)
   - [HTTP/3 Protocol Support](#26-http3-protocol-support-experimental)
   - [Repository Filters](#27-repository-filters)
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

No additional keys. Validates JWT tokens (access tokens or API tokens) submitted as the password in HTTP Basic Authentication. Tokens are verified using the RS256 public key configured in `meta.jwt.public-key-path`. Access tokens are verified purely from the signature (no DB query). API tokens are additionally checked against the `user_tokens` table. A credential that has the form of a JWT is checked by this provider only, wherever it stands in the list: a rejected token is answered with `401` and is never passed to another provider. Without this provider such a credential is rejected.

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

JSON Web Token settings for RS256 asymmetric signing and token lifecycle configuration.

> **Breaking change (v2.1.0+):** The `secret` field (HS256) is no longer supported and will cause a startup failure if present. Migrate to `private-key-path` / `public-key-path`. See [Authentication](admin-guide/authentication.md#jwt-token-configuration) for key generation instructions.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `private-key-path` | string | Yes | -- | Filesystem path to the RSA private key PEM file. Supports `${ENV_VAR}`. |
| `public-key-path` | string | Yes | -- | Filesystem path to the RSA public key PEM file. Supports `${ENV_VAR}`. |
| `access-token-expiry-seconds` | int | No | `3600` | Access token lifetime in seconds (1 hour default). Configurable at runtime via auth settings API. |
| `refresh-token-expiry-seconds` | int | No | `604800` | Refresh token lifetime in seconds (7 days default). Configurable at runtime via auth settings API. |

```yaml
meta:
  jwt:
    private-key-path: ${JWT_PRIVATE_KEY_PATH}
    public-key-path: ${JWT_PUBLIC_KEY_PATH}
    access-token-expiry-seconds: 3600
    refresh-token-expiry-seconds: 604800
```

#### auth_settings table

Token policy is also stored in the database (`auth_settings` table) and can be updated at runtime without restart via `PUT /api/v1/admin/auth-settings`. Database values take precedence over YAML values when present.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `access_token_expiry_seconds` | int | `3600` | Access token TTL |
| `refresh_token_expiry_seconds` | int | `604800` | Refresh token TTL |
| `api_token_max_expiry_days` | int | `90` | Maximum expiry days for user-generated API tokens |
| `allow_permanent_tokens` | boolean | `false` | Whether users may generate non-expiring API tokens |

#### Client-facing base URL settings (auth_settings table)

Governs how [`ClientBaseUrl`](#22-local-repository) derives the absolute base URL Pantera emits (e.g. npm `dist.tarball`) for a repository with no explicit `url:`. DB-backed and hot-reloadable via `GET`/`PUT /api/v1/admin/client-base-url-settings` -- no restart, and a change broadcasts to every node in a cluster. Falls back to the env var, then the hardcoded default, when no DB row is present -- see [7.8](#78-miscellaneous) for the env-var fallback names.

Precedence for the base used to build a repository's emitted URLs:

1. **The repository's own `url:`** -- always wins when explicitly set, regardless of any setting below.
2. **`client_base_url`** (canonical, below) -- used for every repository with no `url:`. When set, it is ENFORCED: `Host` and `X-Forwarded-*` are not consulted at all for those repositories, so Host-spoofing is structurally impossible rather than merely filtered.
3. **Request-derived** (`Host` / `X-Forwarded-*`, subject to `client_base_host_allowlist`) -- today's behaviour, only reached when `client_base_url` is unset.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `client_base_url` | string (absolute URL) | `` (empty) | Canonical origin (+ optional path prefix) used for every repository with no explicit `url:`, e.g. `http://localhost:9999` or `https://reg.example.com/artifactory`. Must parse as an absolute `http`/`https` URL with a host; a trailing slash is stripped so it never doubles when composed with the derived repository path. The repository-relative path portion is always still derived from the request (preserving any global path prefix and the `/api/<type>/<name>` route style) -- only the origin (and optional prefix) comes from this setting. **Empty (the default) is unset** -- falls through to tier 3 above, making `Host`/`trust_forwarded_headers`/`client_base_host_allowlist` unnecessary once set. |
| `trust_forwarded_headers` | boolean | `false` | Honour `X-Forwarded-Proto`/`-Host`/`-Prefix`. Enable only when a fronting reverse proxy overwrites these on every inbound request -- they are otherwise client-suppliable. Ignored entirely while `client_base_url` is set. |
| `client_base_host_allowlist` | string (comma-separated) | `` (empty) | `Host` header values permitted for derivation, matched case-insensitively including port. **Empty is PERMISSIVE**: any `Host` is honoured -- this is the default so upgrading an existing deployment never breaks it, but it means a client can steer emitted URLs at any host it supplies. A non-matching `Host` is treated exactly as an absent one (falls through to the repository's configured `url:` or the request's own further fallback), never emitted verbatim. Pantera logs a startup WARN (`event.action=client_base_host_allowlist_permissive`) while this stays empty. Ignored entirely while `client_base_url` is set. |
| `client_base_scheme` | `auto` \| `http` \| `https` | `auto` | Scheme used in the absolute links Pantera emits. `auto` derives it from the request: `X-Forwarded-Proto` when `trust_forwarded_headers` is on, otherwise `http`. Set to `https` when Pantera sits behind a TLS-terminating **layer-4** load balancer (an AWS NLB with a TLS listener forwarding to a TCP target group, say): such a deployment gives Pantera neither a TLS connection of its own nor an `X-Forwarded-Proto` header, so `auto` has no signal and every emitted link says `http`. The host is still derived per request, so several client-facing DNS names each keep their own URLs -- which is what distinguishes this from `client_base_url`, which pins host and scheme together. Ignored entirely while `client_base_url` is set. |

#### Security policy settings (auth_settings table)

Since 2.2.9 the security limits introduced in that release are DB-backed admin settings, editable in the admin UI (Settings → *Request & Storage Limits*, *Outbound Egress Policy*, *Failed logins*) or through `GET`/`PUT /api/v1/admin/request-limits-settings`, `/egress-settings` and `/failed-logins-settings` (see the [REST API reference](rest-api-reference.md)). The *Login Throttling* card of 2.2.9 (`login_throttle_*`) was removed in 2.2.15: the failed-logins keys below replace it. Each key resolves **DB row → `PANTERA_<KEY>` environment variable → default**; the environment variable is a fallback consulted only while no row has been saved (rows are deliberately not seeded, so an existing env-driven deployment keeps working unchanged). A write reloads the node that served it and broadcasts the reload to peer nodes over Valkey (the shared cache-invalidation channel), and every consumer reads the setting per decision, so changes apply on the next request cluster-wide without a restart. Keys are validated independently: a value that fails validation (a body cap below the 1 MiB floor, a relative root, a malformed host list, a failed-logins value outside its range) is rejected with `400` by the admin endpoints and UI; if one nevertheless reaches the store or environment, only that key falls back to its default -- logged as `event.action=<section>_settings_load` with `event.reason=<key>` -- and its sibling keys keep their configured values.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `max_request_body_bytes` | integer (bytes, ≥ 1048576) | `10737418240` (10 GiB) | Hard cap on a single request body, applied to every request regardless of per-repository `content-length-max`. A declared `Content-Length` above it is rejected with `413` before any body byte is read; a chunked body is metered as it streams. Set it above your largest artifact. Env fallback `PANTERA_MAX_REQUEST_BODY_BYTES`. |
| `fs_storage_roots` | string (path-separator delimited absolute directories) | `/var/pantera/data` | Directories under which a local-filesystem storage `path` (`fs` or `vertx-file`) submitted through the REST API or admin UI may live -- an inline repository storage block or a storage alias (`PUT /api/v1/storages/<name>`, `PUT /api/v1/repositories/<name>/storages/<alias>`). The check follows symlinks: a path is accepted only when its real location is under an approved root. An update that keeps a repository's (or alias's) saved path is not re-validated, so repositories and aliases created before the roots existed stay editable; changing the path is validated. Env fallback `PANTERA_FS_STORAGE_ROOTS`. |
| `egress_block_private` | boolean | `false` | Outbound egress strictness for every connection Pantera makes on its own behalf (proxy remotes, index links, bearer realms, storage-alias endpoints). Link-local, any-local, multicast and the cloud metadata service (`169.254.169.254`, `fd00:ec2::254`, `fd20:ce::254`, `100.100.100.200`, `metadata.google.internal`, matched with or without a trailing dot) are always refused; `true` also refuses loopback and private ranges (RFC1918, `fc00::/7` unique-local, `100.64.0.0/10` shared address space). Enforced after DNS resolution on every connect, redirect hops included. Behind an outbound HTTP proxy (`meta.http_client.proxies` or the JVM `http.proxyHost`/`https.proxyHost` properties), each request target and redirect hop is checked by name and against Pantera's own DNS answer before the connection to the proxy opens; the proxy resolves the name itself, so a target Pantera cannot resolve is left to the proxy -- enforce the same deny list on the proxy. Env fallback `PANTERA_EGRESS_BLOCK_PRIVATE`. |
| `egress_allow_hosts` | string (comma-separated host names) | `` (empty) | Hosts exempt from the strict-mode loopback/private-range refusal, e.g. an internal mirror that must stay reachable in strict mode. The allowlist never exempts the cloud metadata service, link-local, any-local or multicast destinations, even when an allowlisted name resolves to one. Env fallback `PANTERA_EGRESS_ALLOW_HOSTS`. |
| `upstream_credential_allow_hosts` | string (comma-separated host names) | `` (empty) | Additional hosts that may receive an upstream's configured credentials: the host of a bearer-token realm, or of an index/mirror link. Only the upstream host itself qualifies by default: there is no parent-domain rule, because on shared suffixes a sibling subdomain is a different tenant. **Docker Hub is the common case:** its registry is `registry-1.docker.io` and its token realm is `auth.docker.io`, a different host, so a Docker Hub proxy with credentials pulls as anonymous (and hits the anonymous rate limit, and cannot see private images) until `auth.docker.io` is listed here. Pantera logs it once per upstream and host, then at most hourly (`event.action: bearer_realm_untrusted` or `mirror_credentials_withheld`). Env fallback `PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS`. |
| `failed_logins_delay_from_failure` | integer (0-100) | `2` | Failed login number from which logins of that user are delayed; `0` switches the delay off. See [Failed Logins](admin-guide/failed-logins.md) for this and the nine keys below (2.2.15). |
| `failed_logins_first_delay_seconds` | integer (1-60) | `1` | Unit of the delay: a delay lasts this many seconds times the square of the failure count. |
| `failed_logins_max_delay_seconds` | integer (1-3600) | `60` | Longest delay; not below the first delay. |
| `failed_logins_lock_after` | integer (0-100) | `3` | Failed logins after which the user is locked; `0` switches the lock off and unlocks every locked user. |
| `failed_logins_unlock_after_minutes` | integer (0-1440) | `0` | Minutes after which a lock ends by itself; `0` = only an admin unlocks. |
| `failed_logins_forget_after_minutes` | integer (0-43200) | `0` | Minutes without a new failure after which the count starts again; `0` = failures are never forgotten by time. Ends no lock. |
| `failed_logins_count_rejected_keys` | boolean | `false` | Whether a rejected API key counts as a failed login. |
| `failed_logins_lock_all_credentials` | boolean | `true` | Whether a lock blocks every credential of the user (password, API keys, sessions, an identity provider's button) or password sign-in only. |
| `failed_logins_second_factor_limit` | integer (0-100) | `5` | Sign-ins whose password was right but whose second factor was not confirmed (a push denied or not answered, a wrong code) after which password sign-in of the user is paused; `0` = no pause. Such a sign-in is never a failed login: it neither delays nor locks. |
| `failed_logins_second_factor_pause_minutes` | integer (1-1440) | `15` | How long the pause lasts. Unconfirmed second factors count while each comes within this time of the previous one. |
| `password_sign_in_okta`, `password_sign_in_keycloak`, `password_sign_in_github` | `all`, `form` or `off` | `all` | Where the provider of that type checks a user name and password: the sign-in form and repository requests, the sign-in form and `POST /api/v1/auth/token` only, or nowhere. See [Password sign-in](admin-guide/authentication.md#password-sign-in-where-a-provider-takes-passwords) (v2.2.15+). |
| `sign_in_report_enabled` | boolean | `true` | Whether sign-in failures (rejected API keys, failed password logins, unconfirmed second factors) are noted and stored. See [Sign-in Failures Report](admin-guide/sign-in-failures.md) (v2.2.15+). |
| `sign_in_report_retention_days` | integer (1-3650) | `90` | Days after its last event that a line of the report is kept. |

A `PUT` with an unknown key, or a value the setting refuses (a body cap below 1 MiB, a relative storage root, a malformed host, a zero threshold), answers `400` and writes nothing.

**Deliberately env-only (boot-time) settings.** A few 2.2.9 knobs stay environment variables because they are needed before the database is reachable, or because storing them in the settings table would defeat their purpose: `PANTERA_BOOTSTRAP_ADMIN_PASSWORD` (consumed once, on the first database-backed start, before any admin exists), `PANTERA_DOWNLOAD_TOKEN_SECRET` (a signing secret; the table holds only the generated key when the variable is unset), `PANTERA_METRICS_BIND` (a listener bind address, fixed when the socket is opened at boot), and the dev-compose-only `KEYCLOAK_CLIENT_SECRET` / `PANTERA_DEV_SSO_USER_PASSWORD` (secrets injected into third-party containers, never Pantera settings).

#### Docker proxy tag refresh (auth_settings table)

How long a `docker-proxy` repository serves a cached image **tag** (e.g. `latest`, `20`) before it checks the tag against the upstream again. Editable in the admin UI (System Settings → *Docker Proxy Tags*) or through `GET`/`PUT /api/v1/admin/docker-settings` (see the [REST API reference](rest-api-reference.md#get-apiv1admindocker-settings)). Resolves **DB row → `PANTERA_DOCKER_TAG_REFRESH_SECONDS` → `3600`**; migration V152 seeds the row with `3600`, so on a deployment with a database the environment variable applies only while that row is absent (a database-less boot reads it directly). A `PUT` applies to the next request of the node that served it, without a restart; every other node of a cluster re-reads the row every 30 seconds. A repository overrides the value with its own [`tag_refresh_seconds`](#23-proxy-repository).

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `docker_tag_refresh_seconds` | integer (0-31536000) | `3600` | Seconds a cached tag is served from the cache, counted per node from the moment that node last fetched or checked the tag. When a cached tag is older, Pantera asks the upstream for the tag's digest with a `HEAD` request (Docker Hub does not count a `HEAD` against its pull limit): the same digest serves the cached manifest; a new digest fetches and stores the new manifest -- when cooldown allows it; while cooldown blocks the new image, clients keep getting the cached one; an unreachable upstream serves the cached manifest. `0` checks the tag on every request. A digest reference (`@sha256:...`) is never checked again: it cannot change. The age is held in memory, so after a restart each tag is checked once on its first request. A `PUT` with any other key, or a value that is not a JSON integer in range, answers `400` and writes nothing. |

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
| `proxy_timeout` | int | No | `60` | Upstream request timeout in seconds |
| `connection_timeout` | int | No | `15000` | TCP connection timeout in milliseconds |
| `max_connections_per_destination` | int | No | `64` | Maximum pooled connections per upstream host |
| `max_requests_queued_per_destination` | int | No | `256` | Maximum requests waiting for a connection per upstream host; a request beyond it is rejected (`event.reason: request_rejected`) |
| `idle_timeout` | int | No | `30000` | Connection idle timeout in milliseconds |
| `follow_redirects` | boolean | No | `true` | Follow HTTP 3xx redirects |
| `connection_acquire_timeout` | int | No | `30000` | Milliseconds to wait for a pooled connection before failing. Bounds only connection acquisition, never the response-body transfer (large downloads stream for as long as they make progress; stalls are caught by `idle_timeout`). |

```yaml
meta:
  http_client:
    proxy_timeout: 120
    max_connections_per_destination: 512
    max_requests_queued_per_destination: 2048
    idle_timeout: 30000
    connection_timeout: 15000
    follow_redirects: true
    connection_acquire_timeout: 30000
```

#### Runtime-tunable HTTP client keys (DB-backed, hot-reloaded)

The following keys live in the `settings` table and are editable from the admin UI or via `PATCH /api/v1/settings/runtime/<key>`. They are NOT read from `pantera.yml`. Changes apply without a restart; admin operations are written to the immutable `audit_log` with old + new values.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `http_client.bulkhead.adaptive` | boolean | `true` | When `true` the permit count of a proxy repository grows from `initial_permits` to `max_permits`; when `false` it stays at `initial_permits`. It never falls (v2.2.12+). |
| `http_client.bulkhead.min_permits` | int | `5` | Lower bound of `initial_permits`. No other effect since v2.2.12: the permit count no longer falls. |
| `http_client.bulkhead.max_permits` | int | `100` | Upper bound of the permit count: the most requests of one proxy repository that run at the same time. Further requests wait for a permit. |
| `http_client.bulkhead.initial_permits` | int | `40` | Permit count when a bulkhead is created (at start and after a change of these keys); also the fixed value when `adaptive=false`. |
| `http_client.bulkhead.target_p99_ms` | int | `500` | No effect since v2.2.12: a slow request no longer lowers the permit count. The key is still accepted. |
| `http_client.bulkhead.window_seconds` | int | `5` | Interval at which the permit count is raised. |
| `http_client.bulkhead.ramp_up_step` | int | `4` | Permits added after a window in which the repository served requests and none ended in an exception. |
| `http_client.bulkhead.ramp_down_factor` | float | `0.5` | No effect since v2.2.12: the permit count is no longer lowered. The key is still accepted. |

The three permit keys must satisfy `min_permits <= initial_permits <= max_permits`. A `PATCH` (or a `DELETE` back to the default) that would break this is refused with `400` and nothing is written; to move the range, change the keys in an order that keeps it valid (raise `max_permits` before `min_permits`, lower `min_permits` before `max_permits`). The admin UI saves them in that order.

---

### 1.8 meta.http_server

Settings for the inbound HTTP server.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `request_timeout` | string | No | `PT2M` | Maximum request duration. ISO-8601 duration or milliseconds. `0` disables. |
| `proxy_protocol` | string | No | `false` | Enable Proxy Protocol v2 on the **main + per-repo** listeners. When `"true"`, Pantera parses the PROXYv2 header prepended by an upstream load balancer (typically AWS NLB with `proxy_protocol_v2.enabled` on the target group) to extract real client IPs. Only enable when the LB actually sends PROXYv2 — enabling without a PP-capable LB breaks every connection because plain `GET /` bytes are misparsed as a malformed PROXY header. |
| `api_proxy_protocol` | string | No | value of `proxy_protocol` | Per-listener PROXYv2 toggle for the **API port** (default `8086`). Use this when the main port is behind an NLB but the API port is behind an ALB — ALB does not emit PROXYv2 and would fail every health check if PROXYv2 were enabled on the API listener. Set to `"false"` to keep PROXYv2 on for the main port and off for the API port. Defaults to `proxy_protocol`'s value for backward compatibility. |

```yaml
meta:
  http_server:
    request_timeout: PT2M
    # NLB → main port (real client IPs preserved via PROXYv2)
    proxy_protocol: "true"
    # ALB → API port (ALB doesn't emit PROXYv2; use X-Forwarded-For instead)
    api_proxy_protocol: "false"
```

> **Topology note (mixed NLB + ALB):** an ALB terminates the L7 HTTP connection and adds `X-Forwarded-For` rather than emitting a PROXYv2 preamble. If the API listener has PROXYv2 enabled but is fronted by an ALB, ALB health checks (and every real request) will hit Pantera's PROXY decoder instead of the HTTP parser, the decoder will throw `HAProxyProtocolException`, the connection will close, and the target group will mark Pantera unhealthy. Set `api_proxy_protocol: "false"` in this topology.

---

### 1.9 meta.cooldown

Cooldown is a supply-chain release-age gate for proxy repositories: an
upstream version published less than `minimum_allowed_age` ago cannot be
downloaded through a proxy (403 with `Retry-After`), and it is left out of
the metadata listings clients resolve versions from (npm packuments, Maven
`maven-metadata.xml`, PyPI simple index, ...). Once the version is older than
the threshold, it is served normally. See the
[admin guide](admin-guide/cooldown.md) and the
[user guide](user-guide/cooldown.md).

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `enabled` | boolean | No | `true` | Global default enable/disable |
| `minimum_allowed_age` | string | No | `72h` | Minimum upstream publish age a version must reach before it can be downloaded. Supports `m` (minutes), `h` (hours), `d` (days). |
| `repo_types` | map | No | -- | Per-repository-type overrides |

Each entry under `repo_types` is a map with:

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `enabled` | boolean | No | inherits global | Enable cooldown for this repo type |
| `minimum_allowed_age` | string | No | inherits global | Minimum publish age for this repo type |

Cooldown settings saved through the admin UI or `PUT /api/v1/cooldown/config`
are stored in the database and take precedence over these YAML values.

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

### DB-backed cooldown settings

These settings are persisted in the `settings` table (key `"cooldown"`)
and edited via `PUT /api/v1/cooldown/config` or the admin UI
"Cooldown settings" dialog. They are NOT configured via YAML.

#### history_retention_days

- Type: integer
- Bounds: `(0, 3650]`
- Default: 90
- Description: Days to retain entries in `artifact_cooldowns_history`
  before automatic purge. Read at job-run time by the pg_cron
  `purge-cooldown-history` job (via `_cooldown_retention_days()` Postgres
  function) and by the Vertx fallback's purge routine. Admin changes take
  effect on the next purge tick without restart.

#### cleanup_batch_limit

- Type: integer
- Bounds: `[1, 100000]`
- Default: 10000
- Description: Maximum rows moved from `artifact_cooldowns` to history
  in a single cleanup tick (applies to both pg_cron and Vertx fallback
  paths). Reduce on very large tables to minimize lock contention at the
  cost of more cleanup iterations per run. Admin changes take effect on
  the next cleanup tick without restart (via `_cooldown_batch_limit()` or
  direct CooldownSettings read).

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

| Cache Name | Purpose | Reader |
|-----------|---------|---|
| `auth-enabled` | Per-user enabled-flag cache for `LocalEnabledFilter` (L1+L2) | `GlobalCacheConfig.authEnabled()` |
| `group-metadata-stale` | Last-known-good metadata for group repos during upstream outage | `GlobalCacheConfig.groupMetadataStale()` |
| `repo-negative` | Shared 404 negative cache (legacy alias: `group-negative`, deprecated with WARN) | `RepositorySlices.loadNegativeCacheConfig()` |
| `cooldown-metadata` | Filtered-metadata envelope cache for cooldown re-evaluation | `FilteredMetadataCacheConfig.fromYaml()` |
| `artifact-index-positive` | Search-index positive tier (`repos that have this artifact`) | `ArtifactIndexCacheConfig.fromYaml(caches, POSITIVE_SUBKEY)` |
| `artifact-index-negative` | Search-index 404 sentinels | `ArtifactIndexCacheConfig.fromYaml(caches, NEGATIVE_SUBKEY)` |
| `auth` | User credential cache | `CachedUsers` via generic `CacheConfig.from()` |
| `policy-perms` / `policy-users` / `policy-roles` | Authorization policy YAML caches | `CachedYamlPolicy` via generic `CacheConfig.from()` |
| `filters` | Parsed per-repo filter configurations | `GuavaFiltersCache` via generic `CacheConfig.from()` |
| `profiles.<name>` | Shared L1/L2 profile referenced by other blocks via `profile:` field | `CacheConfig.fromProfile()` |

```yaml
meta:
  caches:
    valkey:
      enabled: true
      host: valkey
      port: 6379
      timeout: 100ms

    # Global shared NegativeCache used by GroupResolver, BaseCachedProxySlice,
    # and per-adapter proxy slices via NegativeCacheRegistry. One instance
    # per Pantera node. Covers hosted/proxy/group repo types after the
    # WI-06 consolidation. The earlier key name `group-negative` is still
    # accepted at boot with a deprecation WARN and will be removed in
    # a future release — admins should migrate to `repo-negative`.
    #
    # For per-repository overrides (e.g. different TTL on a specific
    # Maven proxy), use the per-repo `cache.negative.{enabled,ttl,maxSize}`
    # block in `repo/<name>.yaml`, NOT this global key.
    repo-negative:
      ttl: 5m
      maxSize: 10000
      valkey:
        enabled: true
        l1MaxSize: 10000
        l1Ttl: 5m
        l2MaxSize: 1000000
        l2Ttl: 5m

    # User credential cache. Read by CachedUsers via the generic CacheConfig
    # factory; see pantera-main/settings/cache/CachedUsers.java:158.
    auth:
      ttl: 5m
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 1000
        l1Ttl: 5m
        l2MaxSize: 100000
        l2Ttl: 5m

    # Cooldown-filtered metadata envelope cache. Read by
    # FilteredMetadataCacheConfig.fromYaml; see
    # pantera-core/.../FilteredMetadataCacheConfig.java:268.
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

> **Note — keys NOT to use.** Earlier docs listed `meta.caches.cooldown.*`,
> `meta.caches.negative.*`, and `meta.caches.maven-metadata.*` as configurable. None of these are read by the current
> code path. The cooldown decision cache (`CooldownCache`) is constructed
> via its no-arg constructor with hardcoded defaults; the generic
> `meta.caches.negative` block is parsed into a singleton that has zero
> production consumers; `maven-metadata` only appears as a metric tag,
> not a YAML key. See [admin-guide/cache-configuration.md](admin-guide/cache-configuration.md)
> for the authoritative cache key inventory.

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

### 1.13 meta.crontab

Scheduled server-side scripts run by the JVM scripting engine (Quartz).

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `path` | string | Yes | -- | Path to the script file (relative to `meta.storage.path` or absolute) |
| `cronexp` | string | Yes | -- | [Quartz cron expression](http://www.quartz-scheduler.org/documentation/quartz-2.3.0/tutorials/crontrigger.html) |

```yaml
meta:
  crontab:
    - path: path/to/script.groovy
      cronexp: "*/3 * * * * ?"
```

**Supported languages:** Groovy (`.groovy`), Python 2 (`.py`).

**Script context variables** (injected automatically):

| Variable | Type | Description |
|----------|------|-------------|
| `_settings` | `com.auto1.pantera.settings.Settings` | Application settings |
| `_repositories` | `com.auto1.pantera.settings.repo.Repositories` | Repository configurations |

Example (Groovy):

```groovy
File file = new File('/my-repo/info/cfg.log')
cfg = _repositories.config('my-repo').toCompletableFuture().join()
file.write cfg.toString()
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
    connection_acquire_timeout: 30000

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
| `url` | string | No | -- | Client-facing base URL for this repository (see note below); still required for some types -- [2.5](#25-type-specific-settings) |
| `port` | int | No | -- | Dedicated port (conan only) |
| `immutable` | boolean | No | `true` | Whether a stored artifact can be overwritten -- see [Immutable artifacts](#immutable-artifacts) below. Not used by `docker` |
| `settings` | map | No | -- | Type-specific settings |

`url` is optional for most local repository types. When set, it is used
verbatim as the client-facing base URL for absolute URLs this repository
emits (e.g. npm `dist.tarball`). When unset, the base comes from the
DB-backed `client_base_url` admin setting if one is configured (canonical
origin, enforced -- `Host`/`X-Forwarded-*` are not consulted at all); only
when that setting is also unset does the base fall back to deriving from
the inbound request -- scheme `http` and the `Host` header by default
(subject to the `client_base_host_allowlist` admin setting), or
`X-Forwarded-Proto`/`-Host`/`-Prefix` when the `trust_forwarded_headers`
admin setting is enabled (see [7.8](#78-miscellaneous)). All three settings
are DB-backed and hot-reloadable via the admin API/UI -- see [Client-facing
base URL settings](#client-facing-base-url-settings-auth_settings-table)
below. `url:` remains hard-required for exactly three repository types, whose adapters
construct absolute URLs without this derivation
([2.5](#25-type-specific-settings)): `helm`, `nuget`, `conda`. Every
other type works with no `url:` at all. (`conan` is not among them despite
emitting absolute `download_urls`: it builds them from the request `Host`
directly -- note that this bypasses `client_base_url` and
`client_base_host_allowlist`, unlike every adapter that goes through
`ClientBaseUrl`.)

The UI's Set Me Up snippets also use a repository's `url` (or `settings.url`)
verbatim when set; otherwise they use the UI Registry URL plus the first
global prefix (see [UI Deployment](admin-guide/ui-deployment.md#registry-url-for-set-me-up)).

Since 2.2.6 hosted **`npm`** is no longer one of them. All three of its
base-URL consumers -- the full packument, the single-version manifest, and the
`.npmrc` the `/.auth` endpoint emits -- resolve the base per request: the
client-base header a group stamps for the repository actually addressed
([2.4](#24-group-repository)) first, then this repository's `url:` if it has
one, then the request's own origin. So a hosted npm repository served over
more than one hostname should leave `url:` unset, and each client gets
`dist.tarball` links under the hostname it used; setting `url:` pins every
client to that one host, whatever hostname they asked for. `npm-proxy` and
`npm-group` behaved this way already.

Since 2.2.10 hosted **`php`** and **`php-proxy`** are no longer among them either. A
proxy's `packages.json` (`metadata-url`) and every proxied
`p2/<vendor>/<package>.json` (`dist.url`, with the upstream URL kept under
`original_url`) point back at the proxy under the base resolved per request,
whatever base its cached metadata was rewritten under, so a proxy reached
through a group sends Composer back to the group. A hosted repository's served
metadata -- `packages.json` (`metadata-url`, `available-packages-url`) and
every `p2/<vendor>/<package>.json` -- is re-rooted per request with the same
three tiers, the last one being the request origin plus the repository name.
The `dist.url` frozen into stored metadata is not served as-is: a `dist.url`
that points into this repository is served as `<resolved base>/<path inside
the repository>`, whatever host it was stored with (an older `url:`, or a URL
imported from another registry; a `direct-dists/` alias segment is dropped),
and a dist hosted elsewhere is left untouched. A stored `dist.url` counts as
"this repository's" when it is repository-relative or an absolute archive URL
(`.zip`, `.tar.gz`, `.tgz`) whose path reaches the archive through a segment
named like the repository, under any host and any path prefix. Through a group
the links are therefore under the group. Without `url:` new uploads store a
repository-relative `dist.url`; with it they keep storing an absolute one, and
`url:` still pins every client to that host. A `php` repository configured the
pre-2.2.10 way, with the URL under `settings.url` instead of `url:`, is pinned
the same way. The re-rooting is stream-through: the stored document is neither
buffered nor parsed, only the link strings are substituted as the bytes flow,
and the response carries the same `Vary` as every other per-request base.

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
  # Optional since 2.2.6. Set it only to PIN every client's dist.tarball links
  # to this exact base; omit it to have them follow the hostname each client
  # actually used.
  url: http://localhost:8081/test_prefix/api/npm/npm
  storage:
    type: fs
    path: /var/pantera/data
```

#### Immutable artifacts

`immutable` is a flat boolean under `repo:` (next to `type`). It applies to
every local repository type except `docker`; proxies and groups ignore it.
A missing key means `true`. Only an explicit `false` turns it off: any other
value, including a misspelling, keeps the repository immutable. Through the
REST API the value must be a JSON boolean (anything else answers `400`), and
because `PUT /api/v1/repositories/:name` replaces the whole configuration, a
body that omits the key makes the repository immutable. Changes through the
API or UI apply on every node without a restart.

| | `immutable: true` (default) | `immutable: false` |
|---|---|---|
| Re-upload of a stored artifact | Refused; the stored artifact is kept | Overwrites it, for any user with `write` on the repository; the format metadata (indexes, checksums, digests) and the search index follow the new content |
| RPM `?override=true`, Hex `?replace=true` | Refused like any re-upload | Required to overwrite, as the protocol defines |
| Delete | Allowed with `delete` | Allowed with `delete` |

Refusal per format: `maven`/`gradle`, `php`, `go`, `nuget`, `rpm`, `helm`,
`gem`, `conda`, `deb`, `file`, `npm` answer `409 Conflict`; `pypi` answers
`400` with the reason `File already exists`; `hexpm` answers `422`; `conan`
answers `404` to the `upload_urls` request (naming the stored file) and `409`
to an upload to a signed URL of a stored file. A byte-identical re-upload is
accepted without change for `maven`/`gradle`, `php`, `go`, `pypi` and
`file`; the other formats refuse it too (npm and RubyGems never republish a
version). For `deb` and `rpm` the check is by storage path (file name).

Files that change by design stay writable whatever the setting: Maven
`-SNAPSHOT` versions, `maven-metadata.xml` and checksums, Composer dev
branches (`dev-*`, `*-dev`), a Go version's `.info` until its `.zip` is
stored, and index files Pantera regenerates. Docker tag moves are governed by
the `overwrite` action in `docker_repository_permissions` instead. The
import endpoint (`PUT /.import/...`) always refuses to replace a file with
different content, whatever the setting.

On upgrade to 2.2.10, migration `V146` adds `immutable: false` to existing
database-stored repositories of type `file`, `npm`, `gem`, `conda`, `deb`,
`helm`, `rpm`, `hexpm` and `conan` that have no `immutable` key, because those
types overwrote before. Repositories read from YAML files are not migrated;
see [Upgrade Procedures](admin-guide/upgrade-procedures.md#upgrading-to-2210).

```yaml
# File: bin.yaml
repo:
  type: file
  immutable: false   # allow overwrites by users with write
  storage:
    type: fs
    path: /var/pantera/data
```

#### HTTP DELETE on repository paths

Every local repository except `docker`, `pypi`, `rpm` and `deb` accepts
`DELETE /<repo>/<path>` (Basic or token authentication, `delete` permission on
the repository). A file path deletes the file; a directory path deletes the
subtree. The answer is `204`, `404` when nothing is stored or indexed at the
path, and `400` for the repository root or an invalid path. The search index,
the tree view, the format's own metadata and the `artifact_delete` audit
record are updated exactly as by `DELETE /api/v1/repositories/:name/artifacts`
([REST API Reference](rest-api-reference.md#delete-apiv1repositoriesnamepackages)).
Native protocol deletes stay with the adapter: npm unpublish and dist-tag
removal, the Helm chart API (`/charts/<name>[/<version>]`), conda token
revocation, NuGet `dotnet nuget delete` (`/package/<id>/<version>`), the Hex
release revert (`/packages/<name>/releases/<version>`), and the whole
`DELETE` handling of `pypi`, `rpm` and `deb`. These deletes also remove the
artifact from search. Docker deletes through the registry API
(`/v2/.../manifests|blobs/...`).

On `file-proxy`, `maven-proxy`, `gradle-proxy`, `npm-proxy`, `pypi-proxy`,
`go-proxy` and `php-proxy`, the same request evicts the cached copy (file or
subtree, checksum and metadata sidecars, search index rows, and the
repository's negative-cache and metadata-cache entries for the path) without
contacting the upstream. `docker-proxy` and group repositories answer `405`.

---

### 2.3 Proxy Repository

A proxy repository caches artifacts from one or more remote upstream servers.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | Yes | -- | Must end in `-proxy` (e.g., `maven-proxy`) |
| `storage` | map | Yes | -- | Local cache storage backend |
| `remotes` | list | Yes | -- | Ordered list of upstream servers |
| `url` | string | No | -- | Client-facing base URL for this repository -- same optionality and derivation as [2.2](#22-local-repository); proxy adapters that support the derivation (including `npm-proxy`) never hard-require it |
| `path` | string | No | -- | URL path segment override |
| `tag_refresh_seconds` | int | No | global `docker_tag_refresh_seconds` (`3600`) | `docker-proxy` only. Seconds this repository serves a cached image tag before it asks the upstream for the tag's digest again; `0` checks on every request. An integer from `0` to `31536000`; the repository API and UI refuse any other value with `400`, and a value in a YAML file that is not one is ignored with a `WARN` (`event.action=repository_config_invalid`), so the global setting applies. Leave it out to use the global setting -- see [Docker proxy tag refresh](#docker-proxy-tag-refresh-auth_settings-table) for what the check does. |

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
  # Optional: check a cached tag against the upstream after 10 minutes
  # instead of the global docker_tag_refresh_seconds (default 3600).
  tag_refresh_seconds: 600
```

```yaml
# File: npm_proxy.yaml
repo:
  type: npm-proxy
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
| `url` | string | No | -- | Client-facing base URL for the group itself -- same optionality and derivation as [2.2](#22-local-repository) |
| `routing` | list | No | -- | Rules that limit a member to the paths it can have, see [Routing rules](#routing-rules) |

A group's members never contribute their own `url` (set or derived) to URLs
emitted through the group. The client-facing base is stamped for the
repository the client actually addressed -- the group -- before member
resolution runs, so a group always emits URLs under its own `url` (or its
own derived base) regardless of which member served the response.

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

#### What a group answers when a member cannot be reached

A group answers `404` when no member has the file. A proxy member that
cannot answer -- its upstream failed, timed out, or its circuit breaker is
open -- does not change that (v2.2.14+): if at least one other member said
"not found", the group answers `404`, as Artifactory and Nexus do, so a
build tool moves on to its next repository instead of failing. Pantera does
not remember such a `404`, because the file may exist on the upstream that
did not answer, and its access record names the cause in `event.reason`
(`circuit_open`, `idle_timeout`, `upstream_status_503`, ...) instead of
`not_found`.

The group keeps a `5xx` in three cases:

| Case | Answer |
|------|--------|
| A hosted member failed -- it may hold the file | `500` / `502` |
| No member answered at all (every member failed or was skipped) | `502`, or `503` with `Retry-After` |
| A proxy member had no free bulkhead permit -- that clears in a moment | `503` with `Retry-After` |

A file the search index knows to be stored in a member that then fails to
serve it is also a `5xx`: it exists.

The client side of the trade-off: Maven remembers a `404` for a release
artifact until the repository's `updatePolicy` interval ends (a day by
default). A build that asked during an upstream outage needs `mvn -U`
(Gradle: `--refresh-dependencies`) once the upstream is back.

A request sent to a proxy repository directly, not through a group, is not
affected: it answers `502` / `503` when its upstream fails.

#### Routing rules

`routing` limits a member to the paths it can have. A member named by at
least one rule is asked only for the paths its rules match; for any other
path it counts as "not found" without being asked, so the request reaches
neither its storage nor its upstream. A member without rules is asked for
everything.

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `member` | string | Yes | Name of a repository that is a direct member of the group. Not a nested group and not a repository of one: see the notes |
| `prefix` | string | One of `prefix` / `pattern` | The path starts with this text, e.g. `org/jboss/` |
| `pattern` | string | One of `prefix` / `pattern` | Java regular expression that must match the whole path |

The path is the one inside the repository, without a leading slash: the
part of the URL after the group's name (`com/acme/app/1.0/app-1.0.jar`).
Several rules for one member are alternatives: the member is asked when any
of them matches.

```yaml
# File: libs-release.yaml
repo:
  type: maven-group
  members:
    - libs-release-local
    - maven-central
    - jboss
  routing:
    # Internal coordinates are never sent to a public upstream.
    - member: maven-central
      pattern: '(?!com/acme/|internal/).*'
    # A slow or narrow upstream is asked only for what it hosts.
    - member: jboss
      prefix: org/jboss/
    - member: jboss
      prefix: org/wildfly/
```

Use routing rules for two things:

- **Keep internal names away from public upstreams.** Without a rule, a
  request for an internal artifact that no hosted member has is forwarded
  to every proxy member, which tells the upstream the name and lets a
  package published there under that name be served to your builds.
- **Contain a slow upstream.** A proxy that is asked only for its own
  group ids cannot delay or fail the lookups of everything else.

Notes:

- A rule that names a repository which is not a member has no effect. A
  rule without `member`, or with neither `prefix` nor `pattern`, or with an
  invalid regular expression, keeps the group from loading; the error is
  logged.
- **Nested groups.** A group asks the repositories of a nested group
  itself, in the place of that group, and the nested group's rules come
  along: they limit its repositories in every group that contains it. Keep
  the rules for a nested group's repositories in that nested group. A rule
  of the outer group that names the nested group, or one of its
  repositories, holds for only a part of the requests (files or metadata,
  not both) and is refused on save.
- Saved through the UI or the REST API, rules are checked first (v2.2.15+):
  a rule that cannot be read, names a repository that is not a direct
  member, names a group, has both `prefix` and `pattern`, or repeats an
  earlier rule is refused with `400` and the number of the rule. Take a
  member's rules out when you take the member out of the group. A YAML file
  is not checked this way.
- Rules follow their member (v2.2.15+): renaming a repository renames it in
  the rules of its groups, deleting it takes its rules out of them, and
  restoring it puts them back under the name it is restored with. On
  2.2.14 a rename left the old name in the rules, which then limited
  nobody: check the rules of a group whose member was renamed on 2.2.14.
- A member that was skipped by its rules is named in the Troubleshoot trace
  with the reason `skipped_by_routing_rule`. The access record of a `404`
  carries that reason only when no member was asked at all.

To see which members a group would ask for a path, use the **Routing rules**
card of the group's page (**Test a path**), or
[`POST /api/v1/repositories/:name/routing/test`](rest-api-reference.md#post-apiv1repositoriesnameroutingtest).
It works on rules that are not saved yet and sends no request to a member.
- Rules apply to every request through the group, `maven-metadata.xml`
  included, and to every group type.
- A nested group keeps its own rules. To limit a repository inside a nested
  group, put the rule in that group.
- A request sent to the member repository directly is not limited.
- Do not use the per-repository [`filters`](#27-repository-filters) for
  this: a filtered path answers `403`, which a group relays as final.

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
| `url` | string | No | -- | Client-facing URL of the repository on the main port. The download and upload URLs Pantera returns to Conan are built from it; without it they follow the address the client used, including any path prefix. Not used on a dedicated port |
| `port` | int | No | -- | Dedicated Conan server port. Without it the repository is served on the main port under its name |

```yaml
repo:
  type: conan
  port: 9300
  storage:
    type: fs
    path: /var/pantera/data
```

#### PHP Composer (`php`)

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `url` | string | No | -- | Client-facing base URL. Unset: served links are resolved per request ([2.2](#22-local-repository)). Set: pins every client to this host. The pre-2.2.10 `settings.url` is still honoured the same way |

```yaml
repo:
  type: php
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
| `url` | string | Yes | -- | Client-facing URL of the repository; anaconda-client uploads are sent to upload URLs built from it |

```yaml
repo:
  type: conda
  url: http://pantera:8080/my-conda
  storage:
    type: fs
    path: /var/pantera/data
```

---

### 2.6 HTTP/3 Protocol Support (Experimental)

HTTP/3 (via Jetty HTTP/3) can be enabled per repository. Experimental — protocol support and client tooling maturity vary by package ecosystem.

**Server-side (per-repository):**

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `http3` | boolean | No | `false` | Enable HTTP/3 listener for this repo's port |
| `http3_ssl.jks.path` | string | If `http3: true` | -- | Path to JKS keystore (HTTP/3 requires TLS) |
| `http3_ssl.jks.password` | string | If `http3: true` | -- | Keystore password |

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

Multiple repositories can share the same HTTP/3 port.

**Client-side (outbound proxy requests):**

| Environment variable | Default | Description |
|---------------------|---------|-------------|
| `http3.client` | `false` | Use HTTP/3 for all outbound proxy requests when upstream supports it |

---

### 2.7 Repository Filters

Per-repository allow/deny list that matches request paths against glob or regexp patterns. Defined under the repository's `filters` block.

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

**Evaluation rules:**

- A resource is **allowed** if it matches at least one `include` pattern and no `exclude` pattern.
- A resource is **blocked** if it matches an `exclude` pattern, or if both `include` and `exclude` are empty.
- Filters are evaluated in definition order, tie-broken by `priority` (higher first, default `0`).

**Glob filter** — [Java glob syntax](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String)), matched against the request path.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `filter` | string | Yes | -- | Glob expression |
| `priority` | int | No | `0` | Evaluation priority within its include/exclude bucket |

**Regexp filter** — Java regular expression.

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `filter` | string | Yes | -- | Regular expression |
| `priority` | int | No | `0` | Evaluation priority |
| `full_uri` | boolean | No | `false` | Match full URI (scheme + host + path + query) instead of path only |
| `case_insensitive` | boolean | No | `false` | Case-insensitive matching |

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
| `docker_repository_permissions` | Per-registry, per-repo | `pull`, `push`, `overwrite`, `delete`, `*` |
| `docker_registry_permissions` | Per-registry | `base`, `catalog`, `*` |
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
| `docker_repository_permissions` | `<registry>` -> `<repo>` -> list | `pull`, `push`, `overwrite`, `delete`, `*` (`*` includes `delete`) |
| `docker_registry_permissions` | `<registry>` -> list | `base`, `catalog`, `*` |
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
| `PANTERA_DB_WRITE_POOL_MAX` | `10` | Maximum connections in the dedicated `DbConsumer` write pool |
| `PANTERA_DB_WRITE_POOL_MIN` | `2` | Minimum idle connections in the `DbConsumer` write pool |
| `PANTERA_REPO_DELETE_RETENTION_HOURS` | `24` | Restore window of a deleted repository: hours between `DELETE /api/v1/repositories/:name` and the start of the purge of its data, `0`-`8760`. `0` purges at once. A delete can override it with `retention_hours`. See [Repository rename and delete](admin-guide/repository-lifecycle.md). |

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
| `PANTERA_DOCKER_TAG_REFRESH_SECONDS` | `3600` | **Fallback tier only** -- the DB-backed `docker_tag_refresh_seconds` admin setting (see [Docker proxy tag refresh](#docker-proxy-tag-refresh-auth_settings-table)) is primary, and migration V152 seeds its row, so with a database this variable applies only while the row is absent. Seconds a `docker-proxy` serves a cached image tag before it checks the tag against the upstream, `0`-`31536000`; another value is ignored. |
| `PANTERA_BODY_BUFFER_THRESHOLD` | `1048576` | Request body size threshold (bytes). Below this: buffered in memory. Above: streamed from disk. |
| `PANTERA_MAX_REQUEST_BODY_BYTES` | `10737418240` (10 GiB) | **Fallback tier only** -- the DB-backed `max_request_body_bytes` admin setting (see [Security policy settings](#security-policy-settings-auth_settings-table)) is primary; this variable is consulted only while no row has been saved. Hard cap on a single request body (bytes), metered on actual bytes for declared and chunked framing alike; exceeding it answers `413`. Applies regardless of per-repository `content-length-max`. |

### 7.4 Metrics

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_METRICS_MAX_REPOS` | `50` | Maximum distinct `repo_name` label values before cardinality limiting |
| `PANTERA_METRICS_PERCENTILES_HISTOGRAM` | `false` | Publish histogram buckets for latency timers using curated SLO ladders (16 buckets for control-plane timers up to 30 s; 18 for transfer timers up to 20 min). Required for the Grafana p95/p99 panels. Bucket shape is fixed at meter creation - changing it needs a restart |

### 7.5 HTTP Client (Jetty)

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_JETTY_BUCKET_SIZE` | `1024` | Jetty buffer pool max bucket size (buffers per size class) |
| `PANTERA_JETTY_DIRECT_MEMORY` | `2147483648` (2 GiB) | Jetty buffer pool max direct memory (bytes) |
| `PANTERA_JETTY_HEAP_MEMORY` | `1073741824` (1 GiB) | Jetty buffer pool max heap memory (bytes) |

### 7.6 Concurrency

Group response-body drain concurrency is sized automatically per repository by the adaptive `RepoBulkhead`; there is no fixed permit environment variable. Storage I/O concurrency is set by the `PANTERA_IO_*_THREADS` pools (§7.2).

### 7.7 Search and API

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_SEARCH_LIKE_TIMEOUT_MS` | `3000` | SQL statement timeout for LIKE fallback queries (ms) |
| `PANTERA_SEARCH_MAX_SIZE` | `100` | Maximum results per search page. Deep pagination is capped at a fixed offset of 10,000 (`page × size`); beyond it a search is refused with `400`. |
| `PANTERA_DOWNLOAD_TOKEN_SECRET` | persisted random / ephemeral random | HMAC key for direct-download tokens; ≥32 random bytes. Unset → generated once and stored in `auth_settings` (DB-backed, shared by HA nodes) or ephemeral per process (DB-less). Never derived from process metadata (2.2.9). |
| `PANTERA_FS_STORAGE_ROOTS` | `/var/pantera/data` | **Fallback tier only** -- the DB-backed `fs_storage_roots` admin setting (see [Security policy settings](#security-policy-settings-auth_settings-table)) is primary; this variable is consulted only while no row has been saved. Approved base directories for local-filesystem (`fs`, `vertx-file`) storage paths submitted via the REST API/UI, inline or as a storage alias; paths outside are rejected with `400` (2.2.9). |
| `PANTERA_METRICS_BIND` | `0.0.0.0` | Interface the unauthenticated Prometheus metrics listener binds to; restrict to a private address on hosts with a public interface (2.2.9). |
| `PANTERA_EGRESS_BLOCK_PRIVATE` | `false` | **Fallback tier only** -- the DB-backed `egress_block_private` admin setting (see [Security policy settings](#security-policy-settings-auth_settings-table)) is primary; this variable is consulted only while no row has been saved. Egress policy strictness: link-local/metadata/any-local/multicast destinations are always denied; `true` also denies loopback and private ranges (RFC1918, `fc00::/7` unique-local, `100.64.0.0/10` shared address space) (2.2.9). |
| `PANTERA_EGRESS_ALLOW_HOSTS` | *(empty)* | **Fallback tier only** -- the DB-backed `egress_allow_hosts` admin setting (see [Security policy settings](#security-policy-settings-auth_settings-table)) is primary; this variable is consulted only while no row has been saved. Hostnames exempt from the strict-mode loopback/private-range refusal; never from the always-denied metadata, link-local, any-local and multicast destinations (2.2.9). |
| `PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS` | *(empty)* | **Fallback tier only** -- the DB-backed `upstream_credential_allow_hosts` admin setting (see [Security policy settings](#security-policy-settings-auth_settings-table)) is primary; this variable is consulted only while no row has been saved. Extra hosts allowed to receive an upstream's credentials on Bearer realms / mirror links; the default is the upstream host only (2.2.9) -- Docker Hub needs `auth.docker.io` listed for authenticated pulls. |
| `PANTERA_FAILED_LOGINS_DELAY_FROM_FAILURE`, `PANTERA_FAILED_LOGINS_FIRST_DELAY_SECONDS`, `PANTERA_FAILED_LOGINS_MAX_DELAY_SECONDS`, `PANTERA_FAILED_LOGINS_LOCK_AFTER`, `PANTERA_FAILED_LOGINS_UNLOCK_AFTER_MINUTES`, `PANTERA_FAILED_LOGINS_FORGET_AFTER_MINUTES`, `PANTERA_FAILED_LOGINS_COUNT_REJECTED_KEYS`, `PANTERA_FAILED_LOGINS_LOCK_ALL_CREDENTIALS`, `PANTERA_FAILED_LOGINS_SECOND_FACTOR_LIMIT`, `PANTERA_FAILED_LOGINS_SECOND_FACTOR_PAUSE_MINUTES` | `2`, `1`, `60`, `3`, `0`, `0`, `false`, `true`, `5`, `15` | **Fallback tier only** -- the DB-backed `failed_logins_*` admin settings are primary (see [Failed Logins](admin-guide/failed-logins.md)). |
| `PANTERA_PASSWORD_SIGN_IN_OKTA`, `PANTERA_PASSWORD_SIGN_IN_KEYCLOAK`, `PANTERA_PASSWORD_SIGN_IN_GITHUB` | `all` | **Fallback tier only** -- the DB-backed `password_sign_in_<type>` admin settings are primary. Where that provider checks passwords: `all`, `form` or `off` (2.2.15). |
| `PANTERA_SIGN_IN_REPORT_ENABLED`, `PANTERA_SIGN_IN_REPORT_RETENTION_DAYS` | `true`, `90` | **Fallback tier only** -- the DB-backed `sign_in_report_*` admin settings are primary. Whether sign-in failures are recorded, and for how many days a line is kept (2.2.15). |

### 7.8 Miscellaneous

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_DIAGNOSTICS_DISABLED` | `false` | Set to `true` to disable blocked-thread diagnostics |
| `PANTERA_INIT` | `false` | Set to `true` to initialize default example configs on first start |
| `PANTERA_BUF_ACCUMULATOR_MAX_BYTES` | `104857600` (100 MB) | Maximum buffer size for HTTP header/multipart boundary parsing. Safety limit to prevent OOM from malformed requests. Not used for artifact streaming. |
| `PANTERA_CLIENT_BASE_URL` | `` (empty) | **Fallback tier only**, mirrors the DB-backed `client_base_url` admin setting -- see [Client-facing base URL settings](#client-facing-base-url-settings-auth_settings-table). Canonical origin (+ optional path prefix, e.g. `https://reg.example.com/artifactory`) used for every repository with no explicit `url:`. Once set, it is ENFORCED: `Host` and `X-Forwarded-*` are not consulted at all for those repositories -- making `PANTERA_TRUST_FORWARDED_HEADERS`/`PANTERA_CLIENT_BASE_HOST_ALLOWLIST` below unnecessary. Must be an absolute `http`/`https` URL; an invalid value is rejected with `400` at the admin endpoint (this env var itself is not validated until the loader reads it, at which point an invalid value falls back to the hardcoded default rather than crashing the boot). |
| `PANTERA_TRUST_FORWARDED_HEADERS` | `false` | **Fallback tier only** -- since 2.3.0 this is the DB-backed `trust_forwarded_headers` admin setting (see [Client-facing base URL settings](#client-facing-base-url-settings-auth_settings-table)); this env var is consulted only when no `auth_settings` row is present, and the key name is unchanged so an existing deployment's setting keeps working unmodified. Set to `true` only when a fronting reverse proxy overwrites `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-Prefix` on every inbound request. Controls whether `ClientBaseUrl` (used to build absolute URLs Pantera emits, e.g. npm `dist.tarball`) honours those client-suppliable headers. When `false`, the base URL is derived from `Host` alone (scheme `http`, subject to `client_base_host_allowlist` below) and `X-Forwarded-Prefix` is ignored entirely. Ignored entirely while `PANTERA_CLIENT_BASE_URL`/`client_base_url` is set. **Interaction with `url:`:** setting an explicit `url:` on the addressed repository (see [2.2](#22-local-repository), [2.3](#23-proxy-repository)) overrides the derived base entirely, so a fixed, correctly-configured `url:` is the other -- and often simpler -- way to get correct absolute URLs behind a reverse proxy, without trusting any forwarded headers at all. |
| `PANTERA_CLIENT_BASE_HOST_ALLOWLIST` | `` (empty) | **Fallback tier only**, mirrors the DB-backed `client_base_host_allowlist` admin setting -- see [Client-facing base URL settings](#client-facing-base-url-settings-auth_settings-table). Comma-separated `Host` values permitted for base-URL derivation. Empty is PERMISSIVE (any `Host` honoured); Pantera logs a startup WARN while it stays empty. Ignored entirely while `PANTERA_CLIENT_BASE_URL`/`client_base_url` is set. |

---

### 7.9 Deployment / Docker Compose

These variables are consumed by the Docker Compose stack and Dockerfile, not by the
Java application directly (unless noted).

#### Pantera Application

| Variable | Description |
|----------|-------------|
| `PANTERA_BOOTSTRAP_ADMIN_PASSWORD` | Initial password for the auto-created `admin` user, consumed once on the first database-backed start. Unset → a random password is written to `<pantera.home>/bootstrap-admin-password` (default `/var/pantera/bootstrap-admin-password`, mode 0600); the log records only that path. |
| `PANTERA_USER_NAME` | Username for the `env` credential provider (`type: env`) |
| `PANTERA_USER_PASS` | Password for the `env` credential provider. Not the bootstrap admin -- see `PANTERA_BOOTSTRAP_ADMIN_PASSWORD`. |
| `PANTERA_CONFIG` | Path to pantera.yml inside the container (default: `/etc/pantera/pantera.yml`) |
| `PANTERA_VERSION` | Docker image tag / application version |

#### Secrets (used in pantera.yml via `${VAR}`)

| Variable | Description |
|----------|-------------|
| `JWT_PRIVATE_KEY_PATH` | Path to the RSA private key PEM file (replaces `JWT_SECRET`) |
| `JWT_PUBLIC_KEY_PATH` | Path to the RSA public key PEM file |
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
| `PANTERA_USER_NAME` | `pantera` | Username for the `env` auth provider (dev stack); not the bootstrap admin |
| `PANTERA_USER_PASS` | (set one) | Password for the `env` auth provider (dev stack) |
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
| `KEYCLOAK_CLIENT_SECRET` | `your_secret` | Pantera Keycloak client secret (also seeds the bundled dev realm on import) |
| `PANTERA_DEV_SSO_USER_PASSWORD` | `changeme` | Temporary password of the dev realm's sample user, seeded on import (dev stack only) |
| **Grafana** | | |
| `GF_SECURITY_ADMIN_USER` | `admin` | Grafana admin username |
| `GF_SECURITY_ADMIN_PASSWORD` | `changeme` | Grafana admin password |
| `GF_USERS_ALLOW_SIGN_UP` | `false` | Allow self-registration |
| `GF_SERVER_ROOT_URL` | `http://localhost:3000` | Grafana root URL |
| `GF_INSTALL_PLUGINS` | `grafana-piechart-panel` | Grafana plugins to install |
| **Application Secrets** | | |
| `JWT_PRIVATE_KEY_PATH` | `/etc/pantera/jwt-private.pem` | Path to RSA private key PEM file (required) |
| `JWT_PUBLIC_KEY_PATH` | `/etc/pantera/jwt-public.pem` | Path to RSA public key PEM file (required) |

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

Before starting Java, the command adds the start time and host name to a
`-XX:HeapDumpPath` in `JVM_ARGS` that ends in `.hprof`
(`heapdump.hprof` becomes `heapdump-20261028-090100-<host>.hprof`), because the
JVM never overwrites an existing dump.

The image runs a JDK that carries the fix for CVE-2026-114938 (the Base64
decoder of OpenJDK 17.0.6 and later on x86-64 with AVX-512 VBMI), so the
command no longer passes `-XX:-UseBASE64Intrinsics`.

`-Dorg.bouncycastle.native.cpu_variant=java` selects BouncyCastle's Java
implementation: its native libraries are built for glibc and cannot be loaded
on the Alpine image. Leave it out on a glibc system to use the native code.

```
java \
  -javaagent:/opt/apm/elastic-apm-agent.jar \
  $JVM_ARGS \
  -Dorg.bouncycastle.native.cpu_variant=java \
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
