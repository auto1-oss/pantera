# Artipie Import CLI

The `artipie-import` command streams exported artifacts from a file-system dump into the
Artipie global upload API. It preserves the on-disk layout and forwards canonical metadata
for Maven, Gradle, npm, PyPI, NuGet, Docker/OCI, Go, Debian, Composer, Helm, RPM and
generic repositories.

## Building

```
mvn -pl artipie-import-cli -am package
```

## Usage

```
java -jar target/artipie-import-cli-*-jar-with-dependencies.jar --help
```

Key options:

| Option | Description |
| ------ | ----------- |
| `--url` | Base URL to the Artipie server (port 8080 by default). |
| `--export-dir` | Root directory of the Artifactory export. |
| `--username` / `--password` | Basic authentication credentials. |
| `--token` | Bearer token alternative to basic auth. |
| `--checksum-mode` | `compute` (default), `metadata`, `skip`. |
| `--concurrency` | Bounded worker pool size (default 4). |
| `--resume` | Continue from an existing progress log. |
| `--progress-log` | Location of the resumable progress log. |
| `--failures-dir` | Directory containing per-repository failure lists. |
| `--report` | JSON summary report path. |

The importer is idempotent. Each artifact upload uses a deterministic idempotency key
based on the repository and relative path. Use `--resume` to skip previously completed
uploads after an interruption.

## Repository Layout Mapping

The CLI derives repository type and name from the first two path segments below
`export-dir`. The remainder of the path is preserved when uploading. Example mappings:

| Export prefix | Artipie type |
| ------------- | ------------ |
| `Maven/` | `maven` |
| `Gradle/` | `gradle` |
| `npm/` | `npm` |
| `PyPI/` | `pypi` |
| `NuGet/` | `nuget` |
| `Docker/` or `OCI/` | `docker` |
| `Composer/` | `php` |
| `Files/`, `Generic/` | `file` |
| `Go/` | `go` |
| `Debian/` | `deb` |
| `Helm/` | `helm` |
| `RPM/` | `rpm` |

Artifact metadata is derived using repository-specific heuristics:

- **Maven / Gradle**: `<group>/<artifact>/<version>/<artifact>-<version>.*`
- **npm**: file name parsed as `<name>-<version>.tgz` with optional `@scope`.
- **PyPI**: `<package>/<version>/<filename>`
- **NuGet**: `<package>/<version>/<package>.<version>.nupkg`
- **Go**: `<module>/<version>`
- **Generic / others**: fallback to file name.

For checksum mode `metadata`, sibling files with suffix `.sha1`, `.sha256`, `.md5`
are consumed. If they are missing the importer automatically falls back to computing
checksums locally.

## Integrity and Reliability

- Retry with exponential backoff and jitter for transient HTTP failures.
- Progress log ensures at-least-once behaviour without duplicates.
- Failures are quarantined server-side and recorded under `--failures-dir`.
- JSON summary report contains per-repository success, already-present, failure and
  quarantine counts.
- Idempotency keys prevent duplicate writes when the importer is restarted.

## Tests

```
mvn -pl artipie-import-cli test
```

The test suite covers repository layout detection, checksum handling, resumable progress
logging and summary reporting.
