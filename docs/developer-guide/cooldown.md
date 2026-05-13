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

## Metadata Filtering: Handler Dispatch Pattern

As of v2.2.0 the metadata-filtering architecture is **handler-dispatched in
each adapter's proxy slice**, not routed through a single central call.
Understand this before adding a new metadata endpoint.

### What the SPI provides

`CooldownAdapterBundle<T>` exposes reusable pieces for **one** metadata format:

```java
public record CooldownAdapterBundle<T>(
    MetadataParser<T> parser,
    MetadataFilter<T> filter,
    MetadataRewriter<T> rewriter,
    MetadataRequestDetector detector,
    CooldownResponseFactory responseFactory
) {}
```

The registry supports exactly **one detector+filter pair per repo type**.

### Why handlers, not one `MetadataFilterService.filterMetadata(...)` call

The earlier design aimed to centralise everything behind
`MetadataFilterService.filterMetadata(repoType, ...)`. That dispatch point is
still in the codebase but is now a **legacy partial abstraction**, because
several adapters have more than one metadata endpoint:

| Adapter          | Endpoints that need separate handlers                        |
|------------------|--------------------------------------------------------------|
| go-proxy         | `/{module}/@v/list`, `/{module}/@latest`                     |
| pypi-proxy       | `/simple/{pkg}/`, `/pypi/{pkg}/json`                         |
| docker-proxy     | `/v2/{name}/tags/list`, `/v2/{name}/manifests/{tag}`         |
| php-proxy        | `/packages/...` + `/p2/...`, plus root `/packages.json` / `/repo.json` |

The SPI cannot express multiple detector+filter pairs per repo type. Each
adapter therefore owns per-endpoint handlers in its proxy slice:

- `GoListHandler` -- canonical reference implementation.
- `GoLatestHandler`
- `PypiSimpleHandler`, `PypiJsonHandler`
- `DockerTagsListHandler`, `DockerManifestTagHandler`
- `ComposerPackageMetadataHandler`, `ComposerRootHandler`

Handlers are invoked from the proxy slice's `response(...)` method **before**
control reaches `BaseCachedProxySlice`'s generic upstream fetch path. The
detector selects the handler; the handler consumes the parser/filter/rewriter
and owns the request lifecycle.

### Adding a new metadata endpoint

1. **Parser + filter + rewriter + detector** -- implement the four bundle
   components for the new endpoint.
2. **Handler** -- create a handler in the adapter's `http` package. Use
   `GoListHandler` as the canonical pattern: parse upstream response, run the
   filter, rewrite, return the response or a format-appropriate 404/403.
3. **Wire into the proxy slice** -- add a branch in the slice's
   `response(...)` method that matches via the detector and dispatches to the
   handler before any generic upstream path.
4. **Register the response factory** -- call `CooldownWiring.register(...)`
   for the primary repo type and every alias the routing layer can produce.
5. **Do not extend `MetadataFilterService` dispatch.** The one-detector-per-
   repo-type limit is intentional; per-endpoint handlers are the supported
   extension point.

### file-proxy is deliberately excluded

`file-proxy` participates in cooldown **only at the artifact-fetch layer**
(via the timestamp-based gate in `RepositoryEvents.java`). It has no
parser/filter/rewriter/detector because there is nothing to filter: raw file
proxies have no version-resolution semantics (no tags, no lists, no
packument). When adding a new raw-style adapter, follow the same pattern --
skip the bundle, apply cooldown at fetch time only.

### Testing pattern

A new metadata endpoint should ship with:

1. **Parser test** -- golden-file parse of a real upstream response.
2. **Filter test** -- curated inputs with known blocked versions; assert output.
3. **Rewriter test** -- round-trip stability + format-specific invariants
   (e.g., PyPI JSON `info.version` matches the highest remaining version).
4. **Handler test** -- happy path, fully-blocked path (expect 403/404), no-op
   path (nothing blocked).
5. **Integration test** -- full proxy slice driven against a fake upstream
   serving canned metadata; assert blocked versions are absent end-to-end.

See `docs/cooldown-metadata-filtering.md` for the full architectural notes
and the v2.2.0 adapter coverage matrix.

---

## Related Pages

- [Cooldown Metadata Filtering](../cooldown-metadata-filtering.md) -- Full architecture notes, coverage matrix, transitive-deps behaviour.
- [Fault Model](fault-model.md) -- Cooldown block is `Fault.Forbidden`, not a bespoke fault.
- [Admin: Cooldown](../admin-guide/cooldown.md) -- Operator configuration + verification.
- [User: Cooldown](../user-guide/cooldown.md) -- End-user view + transitive-deps behaviour.
