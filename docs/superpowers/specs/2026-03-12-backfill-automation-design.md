# Backfill Automation Design

**Date:** 2026-03-12
**Module:** `artipie-backfill`
**Status:** Approved

## Overview

Add a fully automated bulk-backfill mode to the `artipie-backfill` CLI. Instead of specifying one repo at a time (`--type`, `--path`, `--repo-name`), the operator provides a config directory (containing Artipie `*.yaml` repo configs) and a storage root. The CLI reads each config, derives the repo name and scanner type, and runs all scanners in sequence using a single shared database connection pool.

## Background

The existing `BackfillCli` takes `--type`, `--path`, and `--repo-name` and scans a single repository. Running a full environment requires invoking it once per repo, which is error-prone and slow to set up. The new mode automates this.

## New CLI Options

Two new options added to `BackfillCli`:

| Option | Short | Description |
|--------|-------|-------------|
| `--config-dir` | `-C` | Directory containing `*.yaml` Artipie repo config files |
| `--storage-root` | `-R` | Root directory; each repo's data lives at `<storage-root>/<repo-name>/` |

**Constraints:**
- `--config-dir` and `--storage-root` must be supplied together.
- They are mutually exclusive with `--type`, `--path`, and `--repo-name`.
- Supplying one without the other, or mixing them with single-repo options, is a parse error.
- All existing shared options (`--db-url`, `--db-user`, `--db-password`, `--batch-size`, `--owner`, `--log-interval`, `--dry-run`) apply to the bulk run unchanged.

## Pre-existing Classes (unchanged)

The following classes already exist in `com.artipie.backfill` and are used as-is:

- **`Scanner`** — interface: `Stream<ArtifactRecord> scan(Path root, String repoName) throws IOException`
- **`ScannerFactory`** — maps type strings (case-insensitive) to `Scanner` instances. Method signature: `static Scanner create(String type)`. Always throws `IllegalArgumentException` for unknown types (never returns null); callers catch `IllegalArgumentException` to treat as SKIPPED.
- **`ArtifactRecord`** — Java record: `(String repoType, String repoName, String name, String version, long size, long createdDate, Long releaseDate, String owner)`. `BulkBackfillRunner` overrides the owner in a stream `map` using the full constructor: `new ArtifactRecord(r.repoType(), r.repoName(), r.name(), r.version(), r.size(), r.createdDate(), r.releaseDate(), owner)`. There is no `withOwner()` helper — always use the constructor form.
- **`BatchInserter`** — implements `AutoCloseable`; constructor: `BatchInserter(DataSource, int batchSize, boolean dryRun, ProgressReporter)`. Key methods: `accept(ArtifactRecord)`, `getInsertedCount()`, `getSkippedCount()`, `close()` (flushes remaining batch). **`insertedCount`** = records successfully written to DB (or counted in dry-run); **`skippedCount`** = records that failed to insert due to errors (e.g. batch-level JDBC exceptions after individual-insert fallback).
- **`ProgressReporter`** — constructor: `ProgressReporter(String repoName, int logInterval)`. Tracks and logs progress; `getInsertedCount()` and `getSkippedCount()` expose totals for the summary table.

## New Components

### `RepoEntry`

A package-level Java record in `com.artipie.backfill`: `RepoEntry(String repoName, String rawType)`. Used internally between `RepoConfigYaml` and `BulkBackfillRunner`.

### `RepoConfigYaml`

Parses one Artipie YAML repo config file.

- **Input:** `Path` to a single `.yaml` file (must have `.yaml` extension)
- **Output:** `RepoEntry(String repoName, String rawType)` record
  - `repoName` derived from the filename stem without the `.yaml` extension (e.g. `go.yaml` → `go`)
  - `rawType` read from `repo.type` in the YAML
- **Errors:** Throws `IOException` if `repo.type` is missing, the YAML is malformed, or the file is unreadable
- **Display name on error:** When `BulkBackfillRunner` catches the exception, it uses the filename stem (stripping `.yaml` if present, otherwise the full raw filename) as the display name in the summary table

### `RepoTypeNormalizer`

Maps raw YAML repo types to scanner type strings.

- **Method:** `static String normalize(String rawType)`
- **Logic:** Strips `-proxy` suffix (e.g. `docker-proxy` → `docker`, `npm-proxy` → `npm`). Types without a suffix pass through unchanged.
- **No dependencies;** purely a string transformation.

### `BulkBackfillRunner`

Orchestrates the full bulk run.

- **Inputs:** config dir path, storage root path, shared scan settings (datasource, `String owner`, batch size, dry-run, log interval). `owner` is never null — `BackfillCli` supplies its default `"system"` if `--owner` is not specified.
- **Config dir iteration:** Non-recursive — only `*.yaml` files directly in the config dir (not subdirectories). Files with `.yml` extension or no extension emit a DEBUG-level log and are skipped. Subdirectories are silently skipped. Files are processed in sorted (alphabetical) order for deterministic runs. A `Set<String>` of seen repo names is maintained; if a new filename produces a stem already in the set, the file is logged as WARN and marked `SKIPPED (duplicate)` before any parsing occurs.
- **Process:** For each `.yaml` file:
  1. Parse via `RepoConfigYaml` → `RepoEntry`. If throws → mark `PARSE_ERROR`, log WARN, continue.
  2. Normalize type via `RepoTypeNormalizer`
  3. Create scanner via `ScannerFactory`; catch `IllegalArgumentException` → mark `SKIPPED`, log WARN, continue
  4. Compute storage path: `storageRoot/<repoName>/`; if not exists → mark `SKIPPED`, log WARN, continue
  5. Create `ProgressReporter(repoName, logInterval)`
  6. Declare `ProgressReporter reporter = new ProgressReporter(repoName, logInterval)` **before** the try-with-resources so it is accessible in the catch block; open scanner stream and inserter together in nested try-with-resources:
     ```
     try (BatchInserter inserter = new BatchInserter(datasource, batchSize, dryRun, reporter);
          Stream<ArtifactRecord> stream = scanner.scan(storagePath, repoName)) {
         stream.map(r -> new ArtifactRecord(r.repoType(), r.repoName(), r.name(),
                         r.version(), r.size(), r.createdDate(), r.releaseDate(), owner))
               .forEach(inserter::accept);
     }
     ```
     `BatchInserter.close()` flushes the remaining batch. `Stream.close()` releases the file walk.
  9. After the try-with-resources exits (either normally or via exception), `BatchInserter.close()` has been called and the final flush has run
  10. Outer `catch (Exception e)` around the try-with-resources: read counts from `reporter` (which are complete up to the point of failure), log ERROR, mark `FAILED`, continue. If both scan and `close()` throw, Java suppresses the `close()` exception — the outer catch still marks `FAILED`.
- **Datasource ownership:** `BulkBackfillRunner` receives the datasource but does **not** close it. The caller (`BackfillCli`) closes it after `run()` returns.
- **Output:** Prints summary table to stderr; returns exit code `0` if all repos are OK/SKIPPED/PARSE_ERROR, `1` if any repo is `FAILED`

## Data Flow

```
BackfillCli.main()
  │
  ├─ [existing] --type + --path + --repo-name → single-repo scan (unchanged)
  │
  └─ [new] --config-dir + --storage-root
       │
       ├─ Build shared HikariCP datasource (once)
       │
       └─ BulkBackfillRunner.run()
            │
            ├─ for each *.yaml in config-dir (non-recursive, sorted):
            │    ├─ check stem against seenNames set
            │    │    └─ if duplicate → SKIPPED(duplicate), log WARN, continue
            │    ├─ RepoConfigYaml.parse(file) → RepoEntry(repoName, rawType)
            │    │    └─ on IOException → PARSE_ERROR, log WARN, continue
            │    ├─ RepoTypeNormalizer.normalize(rawType) → scannerType
            │    ├─ ScannerFactory.create(scannerType)
            │    │    └─ on IllegalArgumentException → SKIPPED, log WARN, continue
            │    ├─ storagePath = storageRoot / repoName
            │    │    └─ if not exists → SKIPPED, log WARN, continue
            │    ├─ ProgressReporter(repoName, logInterval)
            │    ├─ try-with-resources BatchInserter(datasource, batchSize, dryRun, reporter)
            │    │    └─ scanner.scan(storagePath, repoName)
            │    │         └─ stream.map(r → new ArtifactRecord(r.repoType(),r.repoName(),r.name(),r.version(),r.size(),r.createdDate(),r.releaseDate(),owner)).forEach(inserter::accept)
            │    │    └─ [BatchInserter.close() always called on exit, flushes remaining]
            │    ├─ read counts from reporter → record OK
            │    └─ catch Exception → FAILED, log ERROR, continue
            │
            ├─ print summary table (stderr)
            └─ return 0 (all OK/SKIPPED) or 1 (any FAILED)
```

One shared datasource, owned and closed by `BackfillCli` after `run()` returns. One `BatchInserter` and one `ProgressReporter` created per repo, `BatchInserter` always closed via `try-with-resources`.

## Error Handling

All per-repo failures are non-fatal to the overall run:

| Scenario | Status | Action |
|----------|--------|--------|
| File has `.yml` extension or no extension | *(ignored)* | Log DEBUG, skip file — not counted in processed total |
| File is a subdirectory | *(ignored)* | Silently skipped — not counted |
| YAML unreadable or missing `repo.type` | `PARSE_ERROR` | Log WARN, continue |
| Two YAML filenames produce the same repo name stem | `SKIPPED (duplicate repo name)` | Log WARN on the second occurrence before parsing, continue |
| Normalized type unknown to `ScannerFactory` | `SKIPPED (unknown type: <rawType>)` | Log WARN, continue |
| Storage path does not exist | `SKIPPED (storage path missing)` | Log WARN, continue |
| Scanner or inserter throws during scan | `FAILED` | Log ERROR with exception, continue |

The "N repos processed" total counts all repos that were attempted (i.e. had their YAML file opened or at minimum their filename stem evaluated), including PARSE_ERROR, SKIPPED, and FAILED. It excludes only silently ignored entries: `.yml` files, files with no extension, and subdirectories. A `SKIPPED (duplicate repo name)` entry is counted because it was evaluated as a candidate.

**Type normalization scope:** Only the `-proxy` suffix is stripped. Other suffixes like `-hosted`, `-group` are out of scope and will produce `SKIPPED (unknown type)` with a WARN, which is intentional.

## Summary Output

Printed to stderr after all repos are processed:

```
Bulk backfill complete — 8 repos processed
  go          [go]       inserted=1234  skipped=0    OK
  maven       [maven]    inserted=5678  skipped=12   OK
  docker      [docker]   inserted=910   skipped=0    OK
  docker_old  [docker]   inserted=0     skipped=0    FAILED (scanner threw: ...)
  npm_proxy   [npm]      inserted=340   skipped=2    OK
  helm_proxy  [helm]     inserted=99    skipped=0    OK
  myrepo      [UNKNOWN]  -              -            SKIPPED (unknown type: myrepo-type)
  badrepo     -          -              -            PARSE_ERROR (missing repo.type)

Exit code: 1  (1 repo failed)
```

Exit code `0` if all repos succeeded or were skipped/parse-errored; `1` if any repo has status `FAILED`.

**Display rules for summary rows:**
- `OK`/`FAILED`: show normalized type in brackets; show actual inserted/skipped counts (read from `reporter` after `BatchInserter.close()`)
- `SKIPPED (unknown type: <rawType>)`: show `[UNKNOWN]` for type; show `-` for counts
- `SKIPPED (storage path missing)`: show normalized type; show `-` for counts
- `SKIPPED (duplicate repo name)`: detected before parsing — show `-` for type and `-` for counts
- `PARSE_ERROR`: show `-` for type; show `-` for counts
- `FAILED` counts reflect all records accepted before the exception (the final flush from `close()` is included since `try-with-resources` closes before the outer catch reads counts)

## Testing

### `RepoConfigYamlTest`
- Happy path: correctly parses `repo.type` and derives name from filename
- Missing `repo.type` key → throws `IOException`
- Malformed YAML → throws `IOException`

### `RepoTypeNormalizerTest`
- `docker-proxy` → `docker`
- `npm-proxy` → `npm`
- `maven` → `maven` (passthrough)
- `file` → `file`

### `BulkBackfillRunnerTest`
- All repos succeed → exit code 0, correct inserted counts
- One repo has unknown type → skipped, rest continue, exit code 0
- One repo storage path missing → skipped, rest continue, exit code 0
- One repo YAML is malformed / missing `repo.type` → marked PARSE_ERROR, rest continue, exit code 0
- One repo scanner throws → marked FAILED, rest continue, exit code 1
- One repo PARSE_ERROR only → exit code 0 (PARSE_ERROR is not a failure)
- One repo PARSE_ERROR + one repo FAILED in same run → exit code 1 (any FAILED → non-zero)
- Empty config dir → runs cleanly, summary shows 0 repos
- Subdirectories in config dir are ignored (non-recursive iteration)
- `.yml` file in config dir → DEBUG log emitted, file not processed
- Duplicate repo name (two files producing same stem) → second marked SKIPPED before parsing, type shown as `-`

### `BackfillCliTest` (extensions)
- `--config-dir` without `--storage-root` → parse error, non-zero exit
- `--storage-root` without `--config-dir` → parse error, non-zero exit
- `--config-dir` + `--type` together → parse error (mutually exclusive)
- Valid `--config-dir` + `--storage-root` → delegates to `BulkBackfillRunner`

## Out of Scope

- Parallel repo scanning (sequential only for simplicity; parallelism can be added later)
- Reading storage path from the YAML's `storage.path` field (always `<storage-root>/<repo-name>/`)
- Cross-repo database transactions
