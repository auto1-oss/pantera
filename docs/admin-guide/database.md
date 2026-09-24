# Database

> **Guide:** Admin Guide | **Section:** Database

Pantera uses PostgreSQL for the artifact index, users, repositories, policies, cooldown records, and Quartz scheduler tables. This page covers operator-facing concerns: connection pool sizing, fail-fast timeouts, and leak detection.

---

## Connection Pool (HikariCP)

Pantera uses HikariCP to pool connections. All pool settings are controlled via `PANTERA_DB_*` environment variables -- see [Environment Variables](environment-variables.md#database-hikaricp) for the full list.

### Fail-fast defaults

As of v2.2.0, the default `connectionTimeout` and `leakDetectionThreshold` are tightened. Under a backend outage or a held-connection bug, the previous defaults let requests pile up for minutes; the new defaults surface the problem within seconds.

| Setting | v2.1.x default | v2.2.0 default | What it guards |
|---|---|---|---|
| `PANTERA_DB_CONNECTION_TIMEOUT_MS` | `5000` | `3000` | Thread waits this long for an available connection before `SQLTransientConnectionException`. Shorter = faster fail, clearer signal. |
| `PANTERA_DB_LEAK_DETECTION_MS` | `300000` | `5000` | Hikari WARNs if a connection is held past this threshold. Shorter = connection-leak bugs become visible immediately. |

The other HikariCP defaults (`pool.max=50`, `pool.min=10`, `idleTimeout=600000`, `maxLifetime=1800000`) are unchanged.

### What a Hikari leak WARN means

Seeing `HikariPool-*: Connection ... has been leaked` or `Apparent connection leak detected` in the logs is a **real bug**: a code path acquired a pooled connection and did not return it to the pool within `leakDetectionThreshold`. Pantera's own code paths close connections via try-with-resources, so any WARN is expected to be either:

- A genuine held-connection bug in new code (triage via the stack trace in the WARN).
- A long-running admin query (unlikely under the new defaults, but possible during manual DBA sessions over the same pool -- those should use a separate connection).

Before the v2.2.0 defaults these WARNs were silent -- the threshold was 5 minutes, which is longer than Pantera's internal request deadline. Operators may now see WARN lines that were previously suppressed. **Treat every new WARN as an incident until proven otherwise.**

---

## Canary Ramp Guide

Rolling out the tighter timeouts directly to steady-state prod risks surfacing dormant bugs as client-visible timeouts. Use the following ramp:

### Week 1 -- relaxed overrides on canary

Set these env vars on the canary instance(s) only:

```
PANTERA_DB_CONNECTION_TIMEOUT_MS=10000
PANTERA_DB_LEAK_DETECTION_MS=30000
```

Watch `pantera.hikaricp.connections_pending` and any `Connection leak detected` WARN. A non-zero leak count on the canary in week 1 means there is an outstanding held-connection bug somewhere in the rollout surface -- fix it before proceeding.

### Week 2+ -- drop to defaults

Once week 1 is clean (zero leak WARNs, `connections_pending` p99 well under 3 seconds), remove the env-var overrides. The canary now picks up the v2.2.0 defaults (3s / 5s). Roll progressively to the rest of the fleet.

If a leak WARN surfaces in week 2, **do not raise the threshold again** -- the WARN is catching a bug that would otherwise degrade the whole pool silently. Triage and fix.

---

## PostgreSQL Settings

Pantera relies on standard PostgreSQL tuning. Notes specific to this workload:

- `idle_in_transaction_session_timeout` of a few seconds is safe; Pantera does not hold transactions open across request boundaries.
- `statement_timeout` should be at least as large as `PANTERA_SEARCH_LIKE_TIMEOUT_MS` (default 3000 ms) -- the search fallback relies on server-side cancellation.
- Schema migrations run via Flyway at startup. A schema-lock deadlock will block startup; check `pg_locks` if a rolling upgrade stalls at boot.

---

## Rebuilding the Search Index

The `artifacts` table is the search index. Upload, proxy and delete paths keep
it up to date. It can still drift from storage: for example, it can keep rows
for repositories that were deleted, or rows for files removed outside
Pantera. To rebuild it, start a full rebuild and poll its status:

```bash
curl -X POST http://localhost:8086/api/v1/search/reindex \
  -H "Authorization: Bearer $TOKEN"

curl http://localhost:8086/api/v1/search/reindex \
  -H "Authorization: Bearer $TOKEN"
```

Both calls need `api_search_permissions:write`. The rebuild:

- **Prunes** the rows of repositories that no longer exist, in batches of 500.
  If no repository is configured, it prunes nothing.
- **Rebuilds** each repository whose storage is on the local file system
  (`fs` or `vertx-file`). It scans the repository directory with the same
  scanners as the `pantera-backfill` CLI and upserts what it finds. It then
  deletes the repository's artifact rows whose files are gone. Rows written
  by uploads during the rebuild are kept. Checksum, signature and metadata
  rows are kept. Existing rows keep their owner and creation time.
- **Skips** the following repositories, and logs the reason for each:
  - group repositories;
  - S3 and other non-local storage;
  - Conan, RPM and NuGet repositories (these types have no scanner);
  - repositories whose storage directory does not exist yet.

  Skipped repositories keep their rows as they are.

Only one rebuild runs at a time. A second `POST` on the same node returns
`409`. In a cluster, a PostgreSQL advisory lock allows only one node to
rebuild; a node that cannot take the lock reports this in `last_error`.
Status is kept per node, so poll the node that accepted the `POST`.

The scanners take an artifact's version from its parent directory, while
the upload path takes it from the artifact's file name. When the two
disagree, the rebuild replaces the upload's row with the scanner's row.
This happens, for example, for a version that appears only in the file name,
such as `foo/bar/thing-2.0.jar`.

Every state change is logged by `com.auto1.pantera.index` with
`event.category=database`:

| `event.action` | When it is logged |
|---|---|
| `search_reindex_start` | The rebuild starts |
| `search_reindex_prune` | The rows of one deleted repository have been pruned |
| `search_reindex_repo` | A repository is rebuilt, skipped (with `event.reason`) or fails |
| `search_reindex_finish` | The rebuild ends; `event.outcome` is `failure` if any repository failed or the run could not start |

The trigger itself is recorded in the admin audit trail as `SEARCH_REINDEX`.

---

## Related Pages

- [Environment Variables](environment-variables.md) -- `PANTERA_DB_*` reference.
- [Performance Tuning](performance-tuning.md) -- Pool sizing vs. worker threads.
- [Monitoring](monitoring.md) -- Hikari metric catalogue.
- [Backup and Recovery](backup-and-recovery.md) -- PostgreSQL backup workflow.
- [REST API Reference](../rest-api-reference.md#post-apiv1searchreindex) -- Index rebuild endpoints.
