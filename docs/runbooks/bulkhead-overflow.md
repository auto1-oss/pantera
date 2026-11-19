# Runbook — `PanteraBulkheadOverflow`

**Severity**: warn · **Component**: per-repo bulkhead (`RepoBulkhead`) · **Alert source**:
[`alert-rules.yml`](../../pantera-main/src/main/resources/prometheus/alert-rules.yml)

## What it means

A proxy repository refused requests with **503 Service Unavailable**,
`Retry-After: 1` and `X-Pantera-Bulkhead-Overflow: true`.

Each proxy repository runs a bounded number of requests at the same time (its
*permits*: 40 at start, growing to 100, by default). A request beyond them
waits for a free permit and is then served. It is refused only when it waited
20 seconds without getting one, or when 1000 requests of the repository were
already waiting. A burst of downloads alone therefore does not fire this
alert. It fires when the repository cannot work its requests off: the upstream
or the storage is slow, or the permits are too few for the load.

The application log has a `WARN` record per repository, at most one in 10
seconds, with `event.action: bulkhead_overflow`: the number of requests
refused since the previous record, the permits and the waiting requests.

## Confirm

```promql
# Which repositories refuse requests, and how many per second?
sum by (repo) (rate(pantera_bulkhead_overflow_total[5m]))

# Requests waiting for a permit, and the permits they wait for
max by (repo) (pantera_bulkhead_waiting)
max by (repo) (pantera_bulkhead_permits_current)

# Per-phase latency — is one phase dominant?
histogram_quantile(0.99,
  sum by (repo_name, phase, le) (
    rate(pantera_proxy_phase_seconds_bucket[5m])
  )
)
```

Waiting requests with no refusals mean the repository is busy and still
serving everything. The phase-latency query distinguishes "the repository is
just busy" (raise the permits) from "a phase is slow" (fix the phase). See the
**Proxy Phase Latency** Grafana dashboard.

## Mitigate

1. **Confirm the dominant phase**. Look at the Proxy Phase Latency dashboard
   for the affected repo. The p99 of each phase tells you where the time is
   going:
   * `cooldown_and_fetch_miss` — a cache miss: the cooldown check and the
     fetch from the upstream. Cross-check the circuit breaker dashboard (the
     upstream may be flapping), the 429 dashboard (we may be self-throttling)
     and Postgres health (the cooldown lookup).
   * `cache_load` / `metadata_load_on_hit` — storage is slow. Check disk IOPS
     and S3 latencies. Confirm `DispatchedStorage` pool sizes
     (`PANTERA_IO_{READ,WRITE,LIST}_THREADS`) are sane.
   * `cache_first_flow` is the whole of a request and contains the others.
2. **Raise the permits if the repository is just busy**. In the admin UI:
   **Settings → Bulkhead**, raise *Maximum permits*, then *Initial permits*.
   Or with the API, in this order (the range `min <= initial <= max` must hold
   after each call):

   ```bash
   curl -X PATCH http://localhost:8086/api/v1/settings/runtime/http_client.bulkhead.max_permits \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"value": 300}'
   curl -X PATCH http://localhost:8086/api/v1/settings/runtime/http_client.bulkhead.initial_permits \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"value": 300}'
   ```

   The change applies at once to every proxy repository, without a restart:
   requests in flight finish, waiting requests are let through, and each
   repository starts again at the initial permits. Keep the maximum below
   `max_connections_per_destination` + `max_requests_queued_per_destination`
   of `meta.http_client` (64 + 256 by default): beyond them the HTTP client
   itself rejects requests to an upstream host.
3. **Add capacity if multiple repos overflow simultaneously**. The bulkhead
   protects against single-repo saturation; if half your repos are
   overflowing at once, the node is genuinely undersized — add nodes
   horizontally.

## Recovery signal

```promql
sum by (repo) (rate(pantera_bulkhead_overflow_total[5m])) == 0
```

A flat curve at 0 for 5+ minutes means no request is refused any more.
`pantera_bulkhead_waiting` returning to 0 means none waits either.

## After-action

* If it fired because of a slow phase: file the phase-fix as a separate
  perf ticket. Raising the permits lets more requests wait on the same slow
  phase; it does not make them faster.
* If it fired on ordinary load: keep the raised permits. A permit is held
  while a request is fetched from the upstream or read from the cache, so
  more permits mean more concurrent upstream fetches.
