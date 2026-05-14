# Runbook — `PanteraBulkheadOverflow`

**Severity**: warn · **Component**: per-repo bulkhead (`RepoBulkhead`) · **Alert source**:
[`alert-rules.yml`](../../pantera-main/src/main/resources/prometheus/alert-rules.yml)

## What it means

A repo's bulkhead semaphore has rejected new requests with **503 Service
Unavailable** + `Retry-After: 1`. The bulkhead caps concurrent in-flight +
queued requests per repo to prevent one slow repo from saturating the global
event-loop pool. Sustained overflow means the cap is too low for the actual
demand, OR a phase inside that repo is unexpectedly slow.

## Confirm

```promql
# Which repos are shedding?
sum by (repo) (rate(pantera_bulkhead_overflow_total[5m]))

# Volume of 503s being returned (cross-validation)
sum by (repo) (rate(pantera_http_requests_total{status="503"}[5m]))

# Per-phase latency — is one phase dominant?
histogram_quantile(0.99,
  sum by (repo, phase, le) (
    rate(pantera_proxy_phase_duration_seconds_bucket[5m])
  )
)
```

The phase-latency query is the diagnostic that distinguishes "the repo is
just busy" (raise the cap) from "a phase is slow" (fix the phase). See the
**Proxy Phase Latency** Grafana dashboard.

## Mitigate

1. **Confirm the dominant phase**. Look at the Proxy Phase Latency dashboard
   for the affected repo. The p99 of each phase tells you where the time is
   going:
   * `upstream_fetch` — the upstream is slow. Cross-check the circuit
     breaker dashboard (the upstream may be flapping) and the 429 dashboard
     (we may be self-throttling).
   * `cache_lookup` / `cache_write` — storage is slow. Check disk IOPS and
     S3 latencies. Confirm `DispatchedStorage` pool sizes
     (`PANTERA_IO_{READ,WRITE,LIST}_THREADS`) are sane.
   * `cooldown_evaluate` — cooldown DB lookup is slow. Check Postgres health.
2. **Raise the bulkhead cap if it's just busy**. Default is
   `maxConcurrent=256` / `maxQueueDepth=64` per repo. Override in repo YAML:

   ```yaml
   repo:
     bulkhead:
       max_concurrent: 512
       max_queue_depth: 128
   ```

   Restart the Pantera node for the change to take effect.
3. **Add capacity if multiple repos overflow simultaneously**. The bulkhead
   protects against single-repo saturation; if half your repos are
   overflowing at once, the node is genuinely undersized — add nodes
   horizontally.

## Recovery signal

```promql
sum by (repo) (rate(pantera_bulkhead_overflow_total[5m])) == 0
```

A flat curve at 0 for 5+ minutes means the cap is sufficient for current
load. Confirm by spot-checking `pantera_http_requests_total{repo,status="503"}`
on the same window.

## After-action

* If this fired during a known traffic spike (release day, dependency
  refresh): nothing to do — the bulkhead protected the event loop.
* If it fired because of a slow phase: file the phase-fix as a separate
  perf ticket. Do not raise the cap unless the slow phase is intentional.
* If it fired because we sized too aggressively: lower the cap and document
  the trade-off in the deployment runbook (lower cap = more 503s during
  spikes, but lower memory ceiling).
