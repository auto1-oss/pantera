# Valkey Setup

> **Guide:** Admin Guide | **Section:** Valkey Setup

Pantera uses Valkey (a Redis-compatible store) as the L2 tier for every cross-node cache and as the pub/sub channel for cache invalidation. This page covers the required server-side settings to run Valkey safely for Pantera's workload.

---

## Required Settings

### Eviction policy

```
maxmemory-policy = allkeys-lru
```

Pantera's L2 caches deliberately ship with long or unbounded TTLs (notably `group-metadata-stale.l2.ttlSeconds = 0`). The server MUST evict via LRU under memory pressure, not fail writes -- a failed L2 write is recoverable by the L1 tier, but a blocked Pantera event loop waiting on a rejected write is not.

### maxmemory sizing

Size `maxmemory` to hold the combined working set of:

- `auth-enabled` -- order of 10K user entries, <1 KB each.
- `group-metadata-stale` -- one entry per `(groupRepo, slot)` pair, payload sized to the metadata body (npm `package.json` scans can reach a few hundred KB, Maven `maven-metadata.xml` is typically <10 KB).
- `cooldown-metadata` -- one entry per filtered metadata payload, bounded by `meta.caches.cooldown-metadata.maxSize` (default 5M).
- `group-negative` and `negative` -- small key, empty value, bounded by their configured `maxSize`.

A safe starting point for most deployments is 2-4 GiB. Monitor `used_memory_peak` and `evicted_keys` and adjust. `evicted_keys` growing steadily on `group-metadata-stale` is fine (that's LRU doing its job); the same on `auth-enabled` or `cooldown` indicates `maxSize` is set too high or `maxmemory` too low.

---

## Why `group-metadata-stale.l2.ttlSeconds = 0`

The stale-fallback cache ships with `l2.ttlSeconds = 0` by design. Valkey LRU owns eviction for this tier -- an entry stays until memory pressure evicts it, which can be weeks or months in low-churn clusters. The intended semantic is *"availability over freshness"*: if the primary group fanout cannot reach any member, the stale tier must still have the last body we successfully assembled.

If operators set a non-zero TTL here they are overriding that design and accepting that a long upstream outage can turn into a client-visible 5xx once the TTL passes.

---

## Pub/Sub

Pantera uses Valkey pub/sub channels under the `pantera.cache.invalidation.*` prefix for cross-node cache invalidation (auth user changes, cooldown policy changes, group membership changes). No special configuration is required -- the default pub/sub transport is sufficient. Do not set `notify-keyspace-events` for this purpose; Pantera drives pub/sub explicitly.

---

## Persistence

Pantera treats L2 as a best-effort accelerator. All data is safely reconstructible from PostgreSQL + upstream fetches. You may run Valkey with no AOF / no RDB snapshots if operationally preferred; cold-start latency will be elevated until the L1 tiers warm. Most deployments run with default RDB snapshots enabled.

---

## Related Pages

- [Cache Configuration](cache-configuration.md) -- All `meta.caches.*` settings.
- [High Availability](high-availability.md) -- Multi-node cluster layout.
- [Monitoring](monitoring.md) -- Valkey-exposed metrics Pantera cares about.
