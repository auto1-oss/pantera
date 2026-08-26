# Cache Configuration

> **Guide:** Admin Guide | **Section:** Cache Configuration

This page documents the cache settings an operator can tune. Every key listed here is read by production code (verified 2026-05-17) and is safe to set in `pantera.yml` or via the matching environment variable.

Pantera has internal caches with hardcoded defaults that this page intentionally does not cover -- those are described in the [Developer Guide: Caching](../developer-guide/caching.md) page. If you need to change the behaviour of an internal cache that has no YAML key here, that is a development task, not a configuration change.

There are two scopes:

1. **Global caches** -- configured under `meta.caches.*` in `pantera.yml`. One instance per Pantera node, shared by every repository.
2. **Per-repository caches** -- configured under `cache.*` inside the repo's own YAML (`repo/<name>.yaml`). One instance per repository.

---

## Override Precedence

```
environment variable  ->  YAML (pantera.yml)  ->  compile-time default
```

The compile-time default applies only when both the environment variable and the YAML key are absent. A value of `0` in an env var is treated as "explicitly set to zero" (it is not a reset to the default).

---

## Global cache keys (under `meta.caches`)

### auth-enabled -- per-user enabled-flag cache

Caches the per-user "enabled" flag in front of `LocalEnabledFilter` so a 1000 req/s workload does not exhaust the Hikari pool with a per-request JDBC hit. Cross-node eviction runs over `CacheInvalidationPubSub` -- admin changes (put/delete/enable/disable/alter-password) invalidate every node.

| Setting | Env var | Default | Unit |
|---|---|---|---|
| `meta.caches.auth-enabled.l1.maxSize` | `PANTERA_AUTH_ENABLED_L1_MAX_SIZE` | `10000` | entries |
| `meta.caches.auth-enabled.l1.ttlSeconds` | `PANTERA_AUTH_ENABLED_L1_TTL_SECONDS` | `300` | seconds |
| `meta.caches.auth-enabled.l2.enabled` | `PANTERA_AUTH_ENABLED_L2_ENABLED` | `true` | boolean |
| `meta.caches.auth-enabled.l2.ttlSeconds` | `PANTERA_AUTH_ENABLED_L2_TTL_SECONDS` | `3600` | seconds |
| `meta.caches.auth-enabled.l2.timeoutMs` | `PANTERA_AUTH_ENABLED_L2_TIMEOUT_MS` | `100` | milliseconds |

A healthy cluster should see `auth-enabled.hit_rate` above 95% under steady-state traffic.

### group-metadata-stale -- group repo last-known-good fallback

Holds the last-known-good metadata payload for group repositories so a partial upstream outage still serves consumers from the stale tier.

| Setting | Env var | Default | Unit |
|---|---|---|---|
| `meta.caches.group-metadata-stale.l1.maxSize` | `PANTERA_GROUP_METADATA_STALE_L1_MAX_SIZE` | `100000` | entries |
| `meta.caches.group-metadata-stale.l1.ttlSeconds` | `PANTERA_GROUP_METADATA_STALE_L1_TTL_SECONDS` | `2592000` (30 d) | seconds |
| `meta.caches.group-metadata-stale.l2.enabled` | `PANTERA_GROUP_METADATA_STALE_L2_ENABLED` | `true` | boolean |
| `meta.caches.group-metadata-stale.l2.ttlSeconds` | `PANTERA_GROUP_METADATA_STALE_L2_TTL_SECONDS` | `0` (no TTL) | seconds |
| `meta.caches.group-metadata-stale.l2.timeoutMs` | `PANTERA_GROUP_METADATA_STALE_L2_TIMEOUT_MS` | `100` | milliseconds |

`l2.ttlSeconds = 0` is intentional -- Valkey LRU owns eviction for this tier. Pair with `maxmemory-policy = allkeys-lru` on the Valkey side (see [Valkey Setup](valkey-setup.md)).

### repo-negative -- shared 404 negative cache

Short-circuits known-missing artifacts so repeated requests for a 404 do not fan out repeatedly.

The legacy key `meta.caches.group-negative.*` is accepted with a deprecation WARN at boot and will be removed in a future release. Rename to `repo-negative` to silence the warning.

```yaml
meta:
  caches:
    repo-negative:
      ttl: 5m
      maxSize: 10000
      valkey:
        enabled: true
        l1MaxSize: 10000
        l1Ttl: 5m
        l2MaxSize: 1000000
        l2Ttl: 5m
```

### cooldown-metadata -- cooldown-filtered metadata envelopes

Long-lived L1 (+ optional L2) cache of cooldown-filtered metadata payloads. TTL is dynamic per entry, derived from the earliest `blockedUntil` timestamp in the filtered metadata.

```yaml
meta:
  caches:
    cooldown-metadata:
      ttl: 30d
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 0
        l1Ttl: 30d
        l2MaxSize: 500000
        l2Ttl: 30d
```

### artifact-index-positive / artifact-index-negative -- search-index caches

Two-tier search index. Positive tier caches "this artifact lives in these repos" lookups; negative tier caches 404 sentinels. Configured independently with the same shape.

```yaml
meta:
  caches:
    artifact-index-positive:
      ttl: 10m
      maxSize: 50000
      valkey:
        enabled: true
        l1MaxSize: 50000
        l1Ttl: 10m
        l2MaxSize: 500000
        l2Ttl: 1h

    artifact-index-negative:
      ttl: 30s
      maxSize: 50000
      valkey:
        enabled: true
        l1MaxSize: 50000
        l1Ttl: 30s
        l2MaxSize: 500000
        l2Ttl: 5m
```

### auth -- user credential cache

Caches user records by username so login + token-verification paths avoid per-request DB reads.

```yaml
meta:
  caches:
    auth:
      ttl: 5m
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 1000
        l1Ttl: 5m
        l2MaxSize: 100000
        l2Ttl: 5m
```

### policy-perms / policy-users / policy-roles -- authorization YAML caches

Cache parsed YAML for the file-backed RBAC policy (permissions, users, roles).

```yaml
meta:
  caches:
    policy-perms:
      ttl: 5m
      maxSize: 1000
    policy-users:
      ttl: 5m
      maxSize: 5000
    policy-roles:
      ttl: 5m
      maxSize: 500
```

### filters -- per-repo filter configuration cache

Caches parsed filter configurations from repository YAML so the HTTP proxy does not re-parse on every request.

```yaml
meta:
  caches:
    filters:
      ttl: 5m
      maxSize: 1000
```

### valkey -- shared L2 client configuration

Host, port, timeout, TLS, password. Read by the L2 tier of every two-tier cache above. See [Valkey Setup](valkey-setup.md) for the full reference.

```yaml
meta:
  caches:
    valkey:
      enabled: true
      host: valkey
      port: 6379
      timeout: 100ms
```

### profiles -- shared cache profiles

A named profile that other cache blocks can reference via a `profile: <name>` field instead of repeating the full L1/L2 shape. Optional; useful when many caches share the same Valkey settings.

---

## Per-repository cache keys (under `cache.*` in `repo/<name>.yaml`)

These knobs override the per-repo proxy slice's local cache behaviour. They live in the individual repository's YAML, not in `meta.caches`.

| YAML key | Default | Purpose |
|---|---|---|
| `cache.negative.enabled` | `true` | Enable per-repo negative caching for this proxy. |
| `cache.negative.ttl` | `PT24H` | Per-repo override of negative-cache TTL. |
| `cache.negative.maxSize` | `50000` | Per-repo override of negative-cache max entries. |
| `cache.metadata.enabled` | `false` | Enable metadata caching for this proxy. |
| `cache.metadata.ttl` | `P7D` | Metadata cache TTL. |
| `cache.metadata.soft_ttl` | `PT30S` | SWR soft TTL -- serve cached without checking upstream. |
| `cache.metadata.hard_ttl` | `PT2H` | SWR hard TTL -- block on upstream. |
| `cache.cooldown.enabled` | `false` | Enable cooldown enforcement for this repo. |
| `cache.conditional_requests` | `true` | Enable `If-None-Match`/`If-Modified-Since` to upstream. |
| `cache.stale_while_revalidate.enabled` | `true` | Serve stale cached bytes on upstream failure (`X-Pantera-Stale: true`). |
| `cache.stale_while_revalidate.max_age` | `PT1H` | Max age of stale bytes acceptable. |
| `cache.retry.max_retries` | `0` | Max retries on upstream errors. |
| `cache.retry.initial_delay` | `PT0.1S` | Initial retry backoff. |
| `cache.retry.backoff_multiplier` | `2.0` | Exponential backoff multiplier. |
| `cache.metrics` | `true` | Emit per-repo cache metrics. |

---

## Related Pages

- [Valkey Setup](valkey-setup.md) -- Required Valkey settings for the L2 tiers.
- [Environment Variables](environment-variables.md) -- Full env-var index.
- [Configuration Reference](../configuration-reference.md) -- Schema reference.
- [Monitoring](monitoring.md) -- Cache hit-rate and stale-served metrics.
- [Developer Guide: Caching](../developer-guide/caching.md) -- Internal cache architecture, hardcoded caches, design rules for adding a new one.
