# Group member ordering

Pantera v2.2.0 moves to **sequential-only** group fanout, matching Nexus
and JFrog Artifactory semantics. The order of repositories in a group's
`members:` list is now operationally significant — pantera tries them in
declared order, returns the first `2xx` (or `403`, which is
authoritative), and only proceeds to the next on a `404`.

Wrong order means slow cold-cache builds (extra round-trips per artifact)
and possibly wrong-source results (e.g. consuming a stale snapshot from
a proxy when a release exists in the local hosted repo).

## What changed

Pre-2.2.0, group repos accepted a `members_strategy: parallel|sequential`
key. The default was `sequential`. Parallel raced every member at once
and returned the first `2xx`, which on a cold cache amplified upstream
traffic by `len(members)` for every miss without delivering meaningful
latency improvement (a sequential walk usually hits the first member
anyway, because group ordering by 2.1.x convention had hosted/primary
first). Parallel is removed in v2.2.0.

The `members_strategy` YAML key is preserved for forward-compat
tolerance: if it's still present and non-blank, pantera logs a one-time
WARN per group at boot and ignores the value. Remove the key or update
your config-management to stop emitting it.

## Heuristics

### Mixed local + proxy groups (most common)

Place **local (hosted) members first**, **proxy members after**.

```yaml
# good
repo:
  type: maven-group
  members:
    - libs-release-local       # hosted: instant 200 or instant 404
    - libs-snapshot-local      # hosted: instant 200 or instant 404
    - maven_central_proxy      # proxy: only consulted on 404 above
```

Reasoning:

- Hosted repos answer immediately (no upstream RTT).
- A `404` from a hosted repo is instant; a proxy `404` may have already
  done a round-trip to the upstream registry.
- Locally published artifacts (dev builds, internal libraries) take
  precedence over upstream-named-collisions.

### Multi-proxy groups (redundant mirrors)

**Don't, generally.** If two proxy members proxy the same upstream
registry (e.g. two Maven Central mirrors), pick one and remove the
other. Keeping both adds nothing functional but means a `404` on the
first triggers an unnecessary second upstream call.

If you genuinely need fallback (primary mirror is flaky, you want
geographic redundancy):

```yaml
repo:
  type: maven-group
  members:
    - maven_proxy_primary      # try first
    - maven_proxy_fallback     # tried only on 404 from primary
```

Be aware that the fallback path adds latency on every miss; it's not
free.

### Different ecosystems / upstreams

Order by **expected hit rate**:

- Most artifacts come from Maven Central → put it first.
- Niche or vendor-specific repos → later.

```yaml
repo:
  type: maven-group
  members:
    - maven_central_proxy      # ~95% of hits
    - confluent_proxy          # niche, ~3%
    - vendor_proxy             # rare
```

### Mixed snapshot + release

Snapshot and release repos should usually live in **separate groups**.
If you must combine them, place the namespace you query more often
first.

## Common anti-patterns

- **Proxy first, local second.** Every dev-built artifact triggers an
  upstream `404` round-trip before the local hit. Slow, noisy, and the
  upstream may rate-limit you.
- **Many proxies for the same upstream.** Doubles or triples upstream
  load with no functional benefit.
- **Reordering after deploy.** Group order is not a runtime tunable —
  changing it requires a config push + reload (or a pantera restart in
  classic deployments). Plan ahead; rolling it out mid-build can lead to
  inconsistent resolution between concurrent clients.

## Verifying your order

After changing group config, run a cold-cache build through the group
and check the per-member hit metric:

```bash
curl -sS http://localhost:8087/metrics/vertx \
  | grep pantera_group_member_hit_total
```

The first member should account for >90% of hits in a healthy mixed
group. If a later member is doing most of the work, your order is
wrong.

## Migration checklist

For each group repo (`*-group` type) in `pantera/repo/` or
`pantera/prod_repo/`:

1. **Reorder** `members:` so the highest-hit-rate / hosted member is
   first.
2. **Remove** any `members_strategy:` key under `settings:`. Pantera
   will WARN at boot if it's still there.
3. **Reload** the configuration (`docker compose restart pantera`, or
   the in-process config-watcher in classic deployments).
4. **Verify** with the `pantera_group_member_hit_total` metric above.
