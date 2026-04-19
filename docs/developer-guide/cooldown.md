# Cooldown (Developer View)

> **Guide:** Developer Guide | **Section:** Cooldown

This page is the contributor-facing notes on the cooldown response-factory registry. For the end-user view, see [User Guide: Cooldown](../user-guide/cooldown.md). For operator configuration, see [Admin Guide: Cooldown](../admin-guide/cooldown.md).

---

## `CooldownResponseRegistry` -- use `getOrThrow`

Every adapter that needs to emit a 403 "blocked by cooldown" response does so through `CooldownResponseRegistry`. There are two lookup methods:

| Method | When to use |
|---|---|
| `getOrThrow(repoType)` | **Default.** Every production call site. Missing registration is a startup-time bug and should fail loudly. |
| `get(repoType)` | Only when the caller genuinely handles the missing-factory case (tests, diagnostic tools). |

As of v2.2.0 (Group G), all 11 adapter sites across files / npm / pypi / composer / go / docker are migrated to `getOrThrow`. `BaseCachedProxySlice`'s former silent fallback (`.orElseGet(() -> CooldownResponses.forbidden(block))`) is replaced by `getOrThrow` as well -- the old fallback is deleted.

### Why

The `.get(repoType)` path previously NPE'd on missing registration, losing the descriptive `repoType` context. `getOrThrow` produces a clear `IllegalStateException("No CooldownResponseFactory registered for repoType: <type>")` instead, making the startup wiring omission immediately triage-able.

---

## Adapter Responsibility: Register at Startup

Every adapter that participates in cooldown MUST register its response factory through `CooldownWiring` during startup. The canonical shape:

```java
CooldownWiring.register(registry, "<primary-type>", myFactory);
// plus any aliases the routing layer might resolve to
CooldownWiring.register(registry, "<family-alias-1>", myFactory);
CooldownWiring.register(registry, "<family-alias-2>", myFactory);
```

Aliases matter. The v2.2.0 wiring currently ships aliases for `npm-proxy`, `pypi-proxy`, `docker-proxy`, `go-proxy`, `php`, `php-proxy` so every `repoType` string that can reach the registry at runtime resolves to a factory.

**Missing registration now fails fast.** With `getOrThrow` on every site, the first request that routes to the unregistered type will throw `IllegalStateException` instead of serving a degenerate response or NPE'ing. This is intentional: a silent silent-fallback hides the bug forever; a loud startup error surfaces it in the first canary smoke test.

### Checklist when adding a new adapter

1. Implement your `CooldownResponseFactory` (format-appropriate 403 body).
2. Call `CooldownWiring.register(...)` for the primary `repoType` in your adapter's bootstrap.
3. Register every alias the routing layer can produce for your family. Check `ApiRoutingSlice` normalization rules if unsure.
4. Adapter test coverage: at least one test that goes through the full wiring and hits your factory via `getOrThrow`.

---

## Related Pages

- [Fault Model](fault-model.md) -- Cooldown block is `Fault.Forbidden`, not a bespoke fault.
- [Admin: Cooldown](../admin-guide/cooldown.md) -- Operator configuration.
- [User: Cooldown](../user-guide/cooldown.md) -- End-user view.
