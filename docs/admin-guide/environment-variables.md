# Environment Variables

> **Guide:** Admin Guide | **Section:** Environment Variables

**This is the authoritative reference for all Pantera environment variables.** All variables are optional unless noted otherwise. When omitted, sensible defaults are used. Variables marked "CPU x N" are computed relative to available processors at startup.

All `PANTERA_*` variables can also be set as Java system properties using the lowercase, dot-separated equivalent (e.g., `PANTERA_DB_POOL_MAX` becomes `-Dpantera.db.pool.max=50`).

---

## Database (HikariCP)

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_DB_POOL_MAX` | `50` | Maximum database connection pool size |
| `PANTERA_DB_POOL_MIN` | `10` | Minimum idle connections |
| `PANTERA_DB_CONNECTION_TIMEOUT_MS` | `5000` | Connection acquisition timeout (ms) |
| `PANTERA_DB_IDLE_TIMEOUT_MS` | `600000` | Idle connection timeout (ms) -- 10 minutes |
| `PANTERA_DB_MAX_LIFETIME_MS` | `1800000` | Maximum connection lifetime (ms) -- 30 minutes |
| `PANTERA_DB_LEAK_DETECTION_MS` | `300000` | Connection leak detection threshold (ms) -- 5 minutes |
| `PANTERA_DB_BUFFER_SECONDS` | `2` | Event batch buffer time (seconds) |
| `PANTERA_DB_BATCH_SIZE` | `200` | Maximum events per database batch |

---

## I/O Thread Pools

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_IO_READ_THREADS` | CPU cores x 4 | Thread pool for storage read operations (exists, value, metadata) |
| `PANTERA_IO_WRITE_THREADS` | CPU cores x 2 | Thread pool for storage write operations (save, move, delete) |
| `PANTERA_IO_LIST_THREADS` | CPU cores x 1 | Thread pool for storage list operations |
| `PANTERA_FILESYSTEM_IO_THREADS` | `max(8, CPU cores x 2)` | Dedicated filesystem I/O thread pool (min: 4, max: 256) |

---

## Cache and Deduplication

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_DEDUP_MAX_AGE_MS` | `300000` | Maximum age of in-flight dedup entries (ms) -- 5 minutes. Stale entries are cleaned up by a background thread. |
| `PANTERA_DOCKER_CACHE_EXPIRY_HOURS` | `24` | Docker proxy cache entry lifetime (hours) |
| `PANTERA_NPM_INDEX_TTL_HOURS` | `24` | npm package search index cache TTL (hours) |
| `PANTERA_BODY_BUFFER_THRESHOLD` | `1048576` | Request body size threshold (bytes). Bodies smaller than this are buffered in memory; larger bodies are streamed from disk. |

---

## Metrics

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_METRICS_MAX_REPOS` | `50` | Maximum distinct `repo_name` label values in metrics before cardinality limiting kicks in. Repositories beyond this limit are aggregated under an "other" label. |
| `PANTERA_METRICS_PERCENTILES_HISTOGRAM` | `false` | Enable histogram buckets for all Timer metrics. Increases metric cardinality but provides percentile computation in Prometheus. |

---

## HTTP Client (Jetty)

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_JETTY_BUCKET_SIZE` | `1024` | Jetty buffer pool max bucket size (buffers per size class) |
| `PANTERA_JETTY_DIRECT_MEMORY` | `2147483648` (2 GiB) | Jetty buffer pool max direct memory (bytes) |
| `PANTERA_JETTY_HEAP_MEMORY` | `1073741824` (1 GiB) | Jetty buffer pool max heap memory (bytes) |

---

## Concurrency

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_GROUP_DRAIN_PERMITS` | `20` | Maximum concurrent response body drains in group repositories. Controls how many member repositories are probed in parallel during group resolution. |

---

## Search

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_SEARCH_MAX_PAGE` | `500` | Maximum page number for search pagination |
| `PANTERA_SEARCH_MAX_SIZE` | `100` | Maximum results per search page |
| `PANTERA_SEARCH_LIKE_TIMEOUT_MS` | `3000` | SQL statement timeout for LIKE fallback queries (ms). If the tsvector search returns zero results, a LIKE fallback query runs with this timeout. |
| `PANTERA_SEARCH_OVERFETCH` | `10` | Over-fetch multiplier for permission-filtered search results. The database fetches `page_size * N` rows so that after dropping rows the user has no access to, the page can still be filled. Increase for deployments with many repos where users only access a few. |

---

## Diagnostics

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_DIAGNOSTICS_DISABLED` | `false` | Set to `true` to disable blocked-thread diagnostics (Vert.x blocked thread checker) |
| `PANTERA_BUF_ACCUMULATOR_MAX_BYTES` | `104857600` (100 MB) | Maximum buffer for HTTP header/multipart boundary parsing (bytes). Safety limit to prevent OOM from malformed requests. Not used for artifact streaming. |

---

## Application

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_USER_NAME` | (none) | Bootstrap admin username. Used by the `env` auth provider. |
| `PANTERA_USER_PASS` | (none) | Bootstrap admin password. Used by the `env` auth provider. |
| `PANTERA_INIT` | `false` | Set to `true` to auto-initialize default example configurations on first start |
| `PANTERA_VERSION` | `2.0.0` | Version identifier. Set automatically in the Docker image. |
| `PANTERA_DOWNLOAD_TOKEN_SECRET` | (auto-generated) | HMAC secret for direct download token signing. If not set, a random secret is generated at startup (not shared across HA nodes). Set explicitly in HA deployments. |

---

## JVM and Runtime

| Variable | Default | Description |
|----------|---------|-------------|
| `JVM_ARGS` | (see Dockerfile defaults) | Complete JVM argument string. When set, replaces all default JVM flags. |
| `ULIMIT_NOFILE` | `1048576` | Target file descriptor soft limit. The container entrypoint raises the soft limit to this value or the hard limit, whichever is lower. |
| `ULIMIT_NPROC` | `65536` | Target process/thread soft limit. |

---

## Logging

| Variable | Default | Description |
|----------|---------|-------------|
| `LOG4J_CONFIGURATION_FILE` | (none) | Path to an external Log4j2 configuration file. When set, overrides the built-in logging configuration. |

---

## Secrets (Used in pantera.yml via ${VAR})

These variables are not read directly by Pantera code but are referenced in `pantera.yml` via `${VAR}` substitution:

| Variable | Description |
|----------|-------------|
| `JWT_SECRET` | HMAC key for JWT token signing |
| `POSTGRES_USER` | PostgreSQL username |
| `POSTGRES_PASSWORD` | PostgreSQL password |
| `KEYCLOAK_CLIENT_SECRET` | Keycloak OIDC client secret |
| `OKTA_ISSUER` | Okta issuer URL |
| `OKTA_CLIENT_ID` | Okta OIDC client identifier |
| `OKTA_CLIENT_SECRET` | Okta OIDC client secret |
| `OKTA_REDIRECT_URI` | OAuth2 callback URL |

---

## AWS

These variables are consumed by the AWS SDK inside the container:

| Variable | Description |
|----------|-------------|
| `AWS_CONFIG_FILE` | Path to AWS config file inside the container |
| `AWS_SHARED_CREDENTIALS_FILE` | Path to AWS credentials file inside the container |
| `AWS_SDK_LOAD_CONFIG` | Set to `1` to load AWS config |
| `AWS_PROFILE` | AWS named profile |
| `AWS_REGION` | AWS region |

---

## Elastic APM

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

## Related Pages

- [Configuration](configuration.md) -- Main pantera.yml configuration
- [Configuration Reference](../configuration-reference.md#7-environment-variables-reference) -- Environment variables in the configuration reference
- [Performance Tuning](performance-tuning.md) -- How to size thread pools and connection pools
- [Installation](installation.md) -- Setting environment variables in Docker
