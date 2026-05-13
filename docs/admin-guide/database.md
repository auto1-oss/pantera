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

## Related Pages

- [Environment Variables](environment-variables.md) -- `PANTERA_DB_*` reference.
- [Performance Tuning](performance-tuning.md) -- Pool sizing vs. worker threads.
- [Monitoring](monitoring.md) -- Hikari metric catalogue.
- [Backup and Recovery](backup-and-recovery.md) -- PostgreSQL backup workflow.
