# Artifacts metadata (PostgreSQL)

Artipie gathers uploaded artifacts metadata and writes them to a PostgreSQL database.
To enable this, add the following section to the main configuration file (`meta` section):

```yaml
meta:
  artifacts_database:
    postgres_host: localhost           # required: PostgreSQL host
    postgres_port: 5432                # optional: default 5432
    postgres_database: artifacts       # required: DB name
    postgres_user: artipie             # required: DB user
    postgres_password: artipie         # required: DB password
    threads_count: 2                   # optional: default 1
    interval_seconds: 3                # optional: default 1
```

The metadata writer runs as a Quartz job and periodically flushes queued events to the database.
Quartz can be configured separately; by default it uses `org.quartz.simpl.SimpleThreadPool` with 10 threads.
If `threads_count` exceeds the pool size, it is limited by the pool.

The database has a single table `artifacts` with the following structure:

| Name         | Type     | Description                              |
|--------------|----------|------------------------------------------|
| id           | int      | Unique identification, primary key       |
| repo_type    | char(10) | Repository type (maven, docker, npm etc) |
| repo_name    | char(20) | Repository name                          |
| name         | varchar  | Artifact full name                       |
| version      | varchar  | Artifact version                         |
| size         | bigint   | Artifact size in bytes                   |
| created_date | datetime | Date uploaded                            |
| owner        | varchar  | Artifact uploader login                  |

All fields are NOT NULL; a UNIQUE constraint is created on `(repo_name, name, version)`.

### Full-text search (tsvector)

The `artifacts` table includes a `search_tokens` column of type `tsvector`. This column is
auto-populated by a PostgreSQL trigger on every INSERT and UPDATE. The trigger concatenates
`repo_name`, `name`, and `version` into a single text-search vector so no application-side
indexing is required.

Full-text queries use `ts_rank()` to order results by relevance. When the query string
contains wildcard characters (`*` or `?`), the search engine automatically falls back to
`LIKE`-based matching so that glob-style patterns still work.

### Connection pool environment variables

The following environment variables tune the HikariCP connection pool used for PostgreSQL.
They can be set as system environment variables or passed via `-D` JVM flags:

| Variable                           | Default   | Description                                      |
|------------------------------------|-----------|--------------------------------------------------|
| `ARTIPIE_DB_CONNECTION_TIMEOUT_MS` | 5000      | Maximum time (ms) to wait for a connection       |
| `ARTIPIE_DB_IDLE_TIMEOUT_MS`      | 600000    | Maximum time (ms) a connection may sit idle       |
| `ARTIPIE_DB_MAX_LIFETIME_MS`      | 1800000   | Maximum lifetime (ms) of a connection in the pool |

These defaults are suitable for most single-instance deployments. In HA setups with many
concurrent writers you may need to lower `ARTIPIE_DB_IDLE_TIMEOUT_MS` or raise the pool
size to avoid connection starvation.

Migration note: earlier versions supported SQLite via `sqlite_data_file_path`. This is deprecated in favor of PostgreSQL.
Please migrate your data and update the configuration to use the `postgres_*` settings.

## Artifact Index (Lucene)

Artipie supports a Lucene-based artifact index for fast O(1) group repository lookups.
To enable this, add the following section to the main configuration file (`meta` section):

```yaml
meta:
  artifact_index:
    enabled: true                     # Enable Lucene artifact index (default: false)
    directory: /var/artipie/index     # Path for Lucene index files (required if enabled)
    warmup_on_startup: true           # Scan repos on startup to populate index (default: true)
```

| Field              | Required | Default | Description                                              |
|--------------------|----------|---------|----------------------------------------------------------|
| enabled            | no       | false   | Enable or disable the Lucene artifact index              |
| directory          | yes*     | -       | Filesystem path for Lucene index files (*required if enabled) |
| warmup_on_startup  | no       | true    | Scan all repository storage on startup to populate index |

When the index is enabled:
- On startup, `IndexWarmupService` scans all repository storage to build the initial index (unless `warmup_on_startup` is `false`)
- During warmup, group repositories fall back to querying all members (fan-out)
- Once warmup completes, group lookups return immediately from the index
- Artifact uploads and deletes automatically update the index via the event pipeline
- The REST API exposes search and stats endpoints under `/api/v1/search/`

## Maven, NPM and PyPI proxy adapters

[Maven-proxy](maven-proxy), [npm-proxy](npm-proxy) and [python-proxy](pypi-proxy) have some extra mechanism to process
uploaded artifacts from origin repositories. Generally, the mechanism is a quartz job which verifies uploaded and 
saved to cache storage artifacts and adds metadata common mechanism and database. Proxy adapters metadata gathering is
enabled when artifacts database is enabled and proxy repository storage is configured. 
It's possible to configure `threads_count` and `interval_seconds` for [Maven-proxy](maven-proxy), [npm-proxy](npm-proxy) 
and [python-proxy](pypi-proxy) repositories individually.
Just add these fields into the repository setting file, for example:
```yaml
repo:
  type: maven-proxy
  storage:
    type: fs
    path: /tmp/artipie/maven-central-cache
  threads_count: 3 # optional, default 1
  interval_seconds: 5 # optional, default 1
```
