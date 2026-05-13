# Cache Configuration

> **Guide:** Admin Guide | **Section:** Cache Configuration

This page is the consolidated reference for every `meta.caches.*` setting Pantera consumes. It covers the L1 (Caffeine in-process) + L2 (Valkey) two-tier caches used by the auth-enabled filter, group-metadata stale fallback, the group-negative cache, and cooldown-metadata.

---

## Override Precedence

Every cache setting resolves via a strict 3-tier precedence chain. **No cache setting is hardcoded** -- the compile-time default only applies when both the environment variable and the YAML key are absent.

```
environment variable  ->  YAML (pantera.yml)  ->  compile-time default
```

This means operators can override any setting without editing `pantera.yml`, and CI/CD can pin values via env without shipping a new config bundle. A value of `0` in an env var is treated as "explicitly set to zero" (it is not a reset to the default).

---

## auth-enabled (CachedLocalEnabledFilter)

Caches the per-user "enabled" flag in front of `LocalEnabledFilter` so a 1000 req/s workload does not exhaust the Hikari pool with a per-request JDBC hit. Cross-node eviction runs over `CacheInvalidationPubSub` -- admin changes (put/delete/enable/disable/alter-password) invalidate every node.

| Setting | Env var | Default | Unit |
|---|---|---|---|
| `meta.caches.auth-enabled.l1.maxSize` | `PANTERA_AUTH_ENABLED_L1_MAX_SIZE` | `10000` | entries |
| `meta.caches.auth-enabled.l1.ttlSeconds` | `PANTERA_AUTH_ENABLED_L1_TTL_SECONDS` | `300` | seconds |
| `meta.caches.auth-enabled.l2.enabled` | `PANTERA_AUTH_ENABLED_L2_ENABLED` | `true` | boolean |
| `meta.caches.auth-enabled.l2.ttlSeconds` | `PANTERA_AUTH_ENABLED_L2_TTL_SECONDS` | `3600` | seconds |
| `meta.caches.auth-enabled.l2.timeoutMs` | `PANTERA_AUTH_ENABLED_L2_TIMEOUT_MS` | `100` | milliseconds |

A healthy cluster should see `auth-enabled.hit_rate` above 95% under steady-state traffic. If the hit rate is materially lower, check for pub/sub invalidation storms or a misconfigured TTL.

---

## group-metadata-stale (GroupMetadataCache stale fallback)

Holds the last-known-good metadata payload for group repositories so a partial upstream outage still serves consumers from the stale tier. **Design principle: the cache is an aid, never a breaker.** Under realistic cardinality no eviction fires; the `maxSize` entries exist only as a JVM-memory safety net against pathological growth.

Degradation path on read is `L1 -> L2 -> expired primary-cache entry -> miss`. L2 now survives JVM restart (the previous `ConcurrentHashMap` did not), strictly improving availability.

| Setting | Env var | Default | Unit |
|---|---|---|---|
| `meta.caches.group-metadata-stale.l1.maxSize` | `PANTERA_GROUP_METADATA_STALE_L1_MAX_SIZE` | `100000` | entries |
| `meta.caches.group-metadata-stale.l1.ttlSeconds` | `PANTERA_GROUP_METADATA_STALE_L1_TTL_SECONDS` | `2592000` (30 d) | seconds |
| `meta.caches.group-metadata-stale.l2.enabled` | `PANTERA_GROUP_METADATA_STALE_L2_ENABLED` | `true` | boolean |
| `meta.caches.group-metadata-stale.l2.ttlSeconds` | `PANTERA_GROUP_METADATA_STALE_L2_TTL_SECONDS` | `0` (no TTL) | seconds |
| `meta.caches.group-metadata-stale.l2.timeoutMs` | `PANTERA_GROUP_METADATA_STALE_L2_TIMEOUT_MS` | `100` | milliseconds |

`l2.ttlSeconds = 0` is intentional -- Valkey LRU owns eviction for this tier. Pair with `maxmemory-policy = allkeys-lru` on the Valkey side (see [Valkey Setup](valkey-setup.md)).

---

## Related cache sections

These sections predate Groups B/C and are documented in the [Configuration Reference](../configuration-reference.md#meta-caches):

- `meta.caches.group-negative.*` -- group-miss negative cache (per-slot 404s avoid repeated fanout).
- `meta.caches.cooldown-metadata.*` -- long-lived cached metadata payloads for cooldown re-evaluation.
- `meta.caches.cooldown.*` -- per-version cooldown block decisions (L1 + L2).
- `meta.caches.negative.*` -- generic negative cache (non-group).
- `meta.caches.valkey.*` -- shared Valkey client configuration.

All of these honor the same env/YAML/default precedence.

---

## Related Pages

- [Valkey Setup](valkey-setup.md) -- Required Valkey settings for the L2 tiers.
- [Environment Variables](environment-variables.md) -- Full env-var index.
- [Configuration Reference](../configuration-reference.md) -- Complete `meta.caches` schema.
- [Monitoring](monitoring.md) -- Cache hit-rate and stale-served metrics.
