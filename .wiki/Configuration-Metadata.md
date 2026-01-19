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

Migration note: earlier versions supported SQLite via `sqlite_data_file_path`. This is deprecated in favor of PostgreSQL.
Please migrate your data and update the configuration to use the `postgres_*` settings.

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
