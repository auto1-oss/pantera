# Caching

> **Guide:** Developer Guide | **Section:** Caching

Pantera's caching strategy is a canonical L1 Caffeine + L2 Valkey two-tier layout with a pub/sub invalidation channel. This page describes the pattern, the existing implementations, and the rules for adding a new cached component.

---

## Canonical Pattern

A cached component in Pantera looks like this:

1. **L1 (Caffeine)** -- In-process, per-JVM. Microsecond reads. Bounded by `maxSize`; `expireAfterWrite` via the configured TTL.
2. **L2 (Valkey)** -- Shared across cluster nodes. Millisecond reads with a strict `timeoutMs` ceiling; on timeout the L2 is treated as a miss, not a failure. Survives JVM restart.
3. **Invalidation** -- Writes that mutate the underlying truth (DB, repo config, etc.) publish an invalidation message on `CacheInvalidationPubSub`. Every node subscribes; each subscriber evicts the keyed L1 entry and deletes the L2 entry.

The three reference implementations to study before building a new one:

| Class | Purpose |
|---|---|
| `com.auto1.pantera.auth.CachedUsers` | Caches the User record by username. Oldest of the three; establishes the pattern. |
| `com.auto1.pantera.auth.CachedLocalEnabledFilter` | Caches the per-user "enabled" flag in front of `LocalEnabledFilter`. Added in v2.2.0 (Group B). |
| `com.auto1.pantera.group.GroupMetadataCache` | Two-tier stale fallback for group repositories. Added in v2.2.0 (Group C). Demonstrates the "aid, not breaker" principle. |

---

## Design Principle: Cache Is an Aid, Never a Breaker

This principle governs every cached-path decision in Pantera. It has three consequences:

1. **Stale fallbacks are last-resort, not required.** If L1 + L2 both miss, the primary path (DB query, upstream fetch) runs and produces a live result. No "stale-only" mode exists where a cold cache forces failure.
2. **Cache failures never become client failures.** An L2 timeout or Valkey unavailability degrades to L1-only operation; the request still completes. Instrumentation records the degradation but does not escalate it.
3. **Bounds are safety nets, not expiry mechanisms.** The `maxSize` on a cache tier exists to prevent pathological memory growth. Under realistic cardinality no eviction fires -- entries expire via TTL or invalidation. If an operator sees persistent eviction, the sizing is wrong, not the workload.

`GroupMetadataCache` is the canonical example: its degradation ladder is `L1 -> L2 -> expired primary-cache entry -> miss`, where the final "miss" falls through to the normal live fanout. No tier being unavailable breaks the contract.

---

## How to Add a New Cached Component

The minimum checklist:

### 1. Write the decorator

A cached component is a decorator that wraps the underlying truth source. Follow `CachedLocalEnabledFilter` for the simplest shape:

```java
public class CachedThing implements Thing {
    private final Thing delegate;
    private final Cache<String, Value> l1;       // Caffeine
    private final ValkeyCache<String, Value> l2; // see GlobalCacheConfig

    @Override
    public CompletableFuture<Value> get(String key) {
        // L1 hit
        Value v = l1.getIfPresent(key);
        if (v != null) return CompletableFuture.completedFuture(v);
        // L2 read with timeoutMs
        return l2.getAsync(key)
            .thenCompose(l2hit -> {
                if (l2hit != null) { l1.put(key, l2hit); return completed(l2hit); }
                return delegate.get(key).thenApply(v2 -> {
                    l1.put(key, v2);
                    l2.putAsync(key, v2);
                    return v2;
                });
            });
    }

    public void invalidate(String key) {
        l1.invalidate(key);
        l2.deleteAsync(key);
    }
}
```

### 2. Wire config into `GlobalCacheConfig`

Add a nested record for your cache's settings alongside `AuthEnabled` and `GroupMetadataStale`. Honor the **3-tier precedence**:

```
environment variable  ->  YAML (meta.caches.<your-cache>)  ->  compile-time default
```

Never inline a literal default -- always route through `ConfigDefaults.getLong / getBoolean / getInt`. This is a hard requirement; the admin guide's "no cache setting is hardcoded" contract is enforced here.

### 3. Subscribe to `CacheInvalidationPubSub`

Use `CacheInvalidationPubSub.subscribe(String namespace, Consumer<String>)`. The namespace is a short stable tag (e.g. `auth.enabled`, `group.metadata`) used as the pub/sub channel suffix. The consumer receives the invalidation key and evicts both tiers.

### 4. Publish on mutation

Every write site that mutates the underlying truth MUST publish. For auth, that's `UserHandler.put/delete/enable/disable/alterPassword`. Missing a publish site means one node's cache goes stale relative to the others -- a silent correctness bug.

### 5. Document in the admin guide

Add a section to `docs/admin-guide/cache-configuration.md` with the full env-var/YAML/default table. This is where operators discover tunables.

### 6. Regression test

At minimum: (a) cache hit returns the value, (b) cache miss falls through to delegate, (c) `invalidate(key)` empties both tiers, (d) L2 timeout degrades gracefully to L1-only, (e) pub/sub receive triggers eviction. `CachedLocalEnabledFilterTest` is the closest template.

---

## Related Pages

- [Admin: Cache Configuration](../admin-guide/cache-configuration.md) -- Operator-facing reference.
- [Admin: Valkey Setup](../admin-guide/valkey-setup.md) -- L2 server-side requirements.
- [Fault Model](fault-model.md) -- How cache failures map to faults (they don't, by construction).
