# artipie-backfill

Standalone CLI tool for backfilling the PostgreSQL `artifacts` table from disk storage. Scans artifact repositories on disk (in various package manager layouts) and populates a PostgreSQL database with artifact metadata.

## Use Cases

- **Initial indexing** — populate the database when enabling database-backed artifact indexing on existing repositories
- **Re-indexing** — rebuild artifact metadata after storage migrations or data recovery
- **Auditing** — dry-run mode to count and inspect artifacts without writing to the database

## Supported Repository Types

| Type | CLI Value | Description |
|------|-----------|-------------|
| Maven | `maven` | Standard Maven layout (`groupId/artifactId/version/`) |
| Gradle | `gradle` | Same as Maven with a different type identifier |
| Docker | `docker` | Docker v2 registry layout (`repositories/{image}/_manifests/tags/`) |
| NPM | `npm` | Artipie `.versions/` layout or legacy `meta.json` proxy layout |
| PyPI | `pypi` | Flat directory with `.whl`, `.tar.gz`, `.zip`, `.egg` files |
| Go | `go` | Hosted (`@v/list`) or proxy (`@v/*.info`) layouts |
| Helm | `helm` | `index.yaml` with chart tarballs |
| Composer/PHP | `composer`, `php` | `p2/{vendor}/*.json` or root `packages.json` layout |
| Debian | `deb`, `debian` | `dists/{codename}/{component}/binary-{arch}/Packages[.gz]` |
| Ruby Gems | `gem`, `gems` | `gems/` subdirectory or flat layout |
| Generic Files | `file` | Recursive file walk, uses relative path as artifact name |

## Building

```bash
mvn clean package -pl artipie-backfill -am
```

This produces a fat JAR via the Maven Shade plugin at:

```
artipie-backfill/target/artipie-backfill-<version>.jar
```

## Usage

```
backfill-cli -t <TYPE> -p <PATH> -r <NAME> [options]
```

### Required Arguments

| Flag | Long | Description |
|------|------|-------------|
| `-t` | `--type` | Scanner type (`maven`, `docker`, `npm`, `pypi`, `go`, `helm`, `composer`, `file`, `deb`, `gem`) |
| `-p` | `--path` | Root directory path of the repository to scan |
| `-r` | `--repo-name` | Logical repository name (stored in the `repo_name` column) |

### Optional Arguments

| Flag | Long | Default | Description |
|------|------|---------|-------------|
| | `--db-url` | *(required unless `--dry-run`)* | JDBC PostgreSQL URL |
| | `--db-user` | `artipie` | Database user |
| | `--db-password` | `artipie` | Database password |
| `-b` | `--batch-size` | `1000` | Number of records per batch insert |
| | `--owner` | `system` | Default owner written to the `owner` column |
| | `--log-interval` | `10000` | Log progress every N records |
| | `--dry-run` | `false` | Scan and count artifacts without writing to the database |
| `-h` | `--help` | | Print help and exit |

### Examples

**Dry run** — scan a Maven repository and report counts without database writes:

```bash
java -jar artipie-backfill.jar \
  --type maven \
  --path /var/artipie/data/my-maven-repo \
  --repo-name internal-maven \
  --dry-run
```

**Full backfill** — scan and insert into PostgreSQL:

```bash
java -jar artipie-backfill.jar \
  --type maven \
  --path /var/artipie/data/my-maven-repo \
  --repo-name internal-maven \
  --db-url jdbc:postgresql://db.example.com:5432/artipie \
  --db-user artipie \
  --db-password secret123 \
  --batch-size 500
```

**Docker repository backfill:**

```bash
java -jar artipie-backfill.jar \
  --type docker \
  --path /var/artipie/data/my-docker-repo \
  --repo-name docker-registry \
  --db-url jdbc:postgresql://localhost:5432/artipie
```

**NPM repository backfill with custom owner:**

```bash
java -jar artipie-backfill.jar \
  --type npm \
  --path /var/artipie/data/npm-repo \
  --repo-name npm-internal \
  --db-url jdbc:postgresql://localhost:5432/artipie \
  --owner admin
```

## Database Schema

The tool auto-creates the `artifacts` table and indexes if they do not exist:

```sql
CREATE TABLE IF NOT EXISTS artifacts (
    id           BIGSERIAL PRIMARY KEY,
    repo_type    VARCHAR NOT NULL,
    repo_name    VARCHAR NOT NULL,
    name         VARCHAR NOT NULL,
    version      VARCHAR NOT NULL,
    size         BIGINT  NOT NULL,
    created_date BIGINT  NOT NULL,
    release_date BIGINT,
    owner        VARCHAR NOT NULL,
    UNIQUE (repo_name, name, version)
);
```

**Indexes:**

| Index | Columns |
|-------|---------|
| `idx_artifacts_repo_lookup` | `repo_name, name, version` |
| `idx_artifacts_repo_type_name` | `repo_type, repo_name, name` |
| `idx_artifacts_created_date` | `created_date` |
| `idx_artifacts_owner` | `owner` |

The insert uses `ON CONFLICT ... DO UPDATE` (upsert), so the tool is **idempotent** — running it multiple times against the same repository safely updates existing records.

## Architecture

```
BackfillCli (entry point)
├── ScannerFactory → creates Scanner by --type
│   ├── MavenScanner        (maven, gradle)
│   ├── DockerScanner       (docker)
│   ├── NpmScanner          (npm)
│   ├── PypiScanner         (pypi)
│   ├── GoScanner           (go)
│   ├── HelmScanner         (helm)
│   ├── ComposerScanner     (composer, php)
│   ├── DebianScanner       (deb, debian)
│   ├── GemScanner          (gem, gems)
│   └── FileScanner         (file)
├── BatchInserter → buffered JDBC batch writer
└── ProgressReporter → throughput logging
```

### Key Components

| Class | Responsibility |
|-------|---------------|
| `BackfillCli` | CLI argument parsing, wiring, and execution lifecycle |
| `Scanner` | Functional interface — `Stream<ArtifactRecord> scan(Path root, String repoName)` |
| `ScannerFactory` | Maps type strings (case-insensitive) to `Scanner` implementations |
| `ArtifactRecord` | Java record representing a row in the `artifacts` table |
| `BatchInserter` | Buffers records and flushes in batches via JDBC; falls back to individual inserts on batch failure |
| `ProgressReporter` | Thread-safe counter with periodic throughput logging |

### Data Flow

```
Disk Storage → Scanner (lazy stream) → BatchInserter (buffered) → PostgreSQL
```

All scanners produce **lazy streams** (`java.util.stream.Stream`) to enable constant-memory processing of arbitrarily large repositories.

## Error Handling

- **Batch insert failure** — automatically falls back to individual record inserts so one bad record does not block the entire batch
- **Malformed metadata** — logged as a warning and skipped; processing continues
- **Missing files** — defaults to file system `mtime` when metadata timestamps are unavailable
- **Connection failure** — records in the failed batch are counted as skipped

### Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Success |
| `1` | Validation error, invalid arguments, or processing failure |

## Connection Pool

Uses HikariCP with the following defaults:

| Setting | Value |
|---------|-------|
| Max pool size | 5 |
| Min idle | 1 |
| Connection timeout | 5000 ms |
| Idle timeout | 30000 ms |

## Testing

### Unit Tests

```bash
mvn test -pl artipie-backfill
```

Covers CLI parsing, batch inserter buffering/flushing, progress reporting, and scanner factory type mapping.

### Integration Tests

Full pipeline integration tests (dry-run mode) run with `mvn test`. PostgreSQL integration tests require the `BACKFILL_IT_DB_URL` environment variable:

```bash
BACKFILL_IT_DB_URL=jdbc:postgresql://localhost:5432/artipie \
BACKFILL_IT_DB_USER=artipie \
BACKFILL_IT_DB_PASSWORD=artipie \
  mvn test -pl artipie-backfill
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Apache Commons CLI | 1.5.0 | CLI argument parsing |
| PostgreSQL JDBC | 42.7.1 | Database driver |
| HikariCP | 5.1.0 | Connection pooling |
| javax.json | — | JSON parsing (NPM, Composer, Go) |
| SnakeYAML | 2.0 | YAML parsing (Helm) |
| SLF4J + Log4j2 | 2.0.17 / 2.24.3 | Logging |

## License

[MIT](../LICENSE.txt)
