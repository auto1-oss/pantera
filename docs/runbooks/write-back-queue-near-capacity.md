# Runbook — `PanteraWriteBackQueueNearCapacity`

**Severity**: warn · **Component**: durable write-back (`CachedBlobStorage`, WS1.2/WS1.6) ·
**Alert source**: [`alert-rules.yml`](../../pantera-main/src/main/resources/prometheus/alert-rules.yml)

## What it means

`CachedBlobStorage` acknowledges a cache write to the caller before the
S3-compatible PUT to the blob-store tier completes, tracking the outstanding
upload in an in-memory write-back queue bounded by
`cache.write-back-queue-capacity`. This alert fires once a cache's queue
depth has sat at or above **80% of capacity for 5 minutes** — a warning
window before the queue actually fills. At 100% capacity, `CachedBlobStorage`
rejects new admissions outright ("Write-back queue saturated; rejecting save
before any disk write", admission-first backpressure) and callers see a
client-visible 503.

Two distinct root causes produce this signal, and they need different
fixes:

1. **Genuine volume** — the blob-store backend (S3 or compatible) is healthy
   but the sustained PUT rate exceeds what write-back can drain in time.
2. **Stuck uploads** — the backend is throttling or unavailable, so queued
   uploads aren't draining at all; depth climbs even under light load.

## Confirm

```promql
# Which cache(s) are near capacity, and how close?
max by (cache) (pantera_storage_cache_writeback_queue_depth)
  /
clamp_min(max by (cache) (pantera_storage_cache_writeback_queue_capacity), 1)

# Is the oldest queued upload old? A high value points at stuck uploads
# (root cause 2), not just high volume (root cause 1).
max by (cache) (pantera_storage_cache_writeback_oldest_pending_age_seconds)

# Is the blob-store backend throttling or erroring?
sum by (backend, operation) (
  rate(pantera_storage_blobstore_requests_total{outcome=~"throttled|error"}[5m])
)

# Backend PUT latency — a latency spike alongside queue growth means the
# backend itself is slow, not just busy.
histogram_quantile(0.95,
  sum by (le, backend) (
    rate(pantera_storage_blobstore_request_duration_seconds_bucket{operation="put"}[5m])
  )
)
```

Open the **Cache & Storage** Grafana dashboard (`pantera-cache-storage.json`,
"Write-Back Queue" and "BlobStore Tier" rows) for the same picture at a
glance.

## Mitigate

1. **Stuck uploads (high oldest-pending age)**: check the blob-store
   backend's own health — S3 API error rate/status page, IAM/credential
   expiry, network path to the endpoint. If backend calls are erroring or
   throttled (`pantera_storage_blobstore_requests_total{outcome!="success"}`
   climbing), this is a backend-side incident — escalate there. Restarting
   the node clears the in-memory queue (queued writes are lost — cache
   misses on those keys self-heal via cold reads, so this is safe but not
   free) only as a last resort if the backend is confirmed unreachable and
   queue depth threatens to hit capacity imminently.
2. **Genuine volume (low oldest-pending age, high depth)**: the backend is
   keeping up but demand is outpacing the queue's absorption capacity.
   Raise `cache.write-back-queue-capacity` for the affected cache (repo
   YAML), or reduce the write burst (e.g. stagger a bulk publish/backfill
   job) if the traffic is a one-off.
3. **Sustained across the fleet**: if multiple nodes/caches show the same
   pattern simultaneously, the blob-store backend is undersized for
   aggregate write throughput — this is a capacity-planning conversation,
   not a per-node tuning knob.

## Recovery signal

```promql
max by (cache) (pantera_storage_cache_writeback_queue_depth)
  /
clamp_min(max by (cache) (pantera_storage_cache_writeback_queue_capacity), 1)
  < 0.8
```

Sustained below 80% for 5+ minutes clears the alert. Confirm
`pantera_storage_cache_writeback_oldest_pending_age_seconds` is also trending
down, not just flat — a flat-but-elevated age with falling depth can mean
older entries are still stuck behind newer ones draining ahead of them
(unlikely given FIFO drain order, but worth a second look if depth recovers
while age does not).

## After-action

- If it fired because of a real backend outage/throttling episode: file
  that as the primary incident; this alert was a correct secondary signal.
- If it fired from a known bulk-publish/backfill job: consider whether that
  job should stagger its write rate, or whether the queue capacity for that
  cache should simply be sized for the job's peak.
- If it fires repeatedly under normal steady-state traffic: the queue
  capacity is undersized for the workload — raise it and note the new value
  in the deployment runbook.
