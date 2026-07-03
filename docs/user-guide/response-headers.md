# Response Headers

> **Guide:** User Guide | **Section:** Response Headers

Pantera emits a small set of custom response headers to give clients and on-call engineers structured diagnostic signal. This page is the authoritative list.

All Pantera custom headers are namespaced under `X-Pantera-*`.

---

## `X-Pantera-Fault: <tag>`

Present on every 5xx response Pantera generates. The tag identifies the specific fault variant so automated tools (including retry policies) can branch on the cause without parsing bodies.

Current tags:

| Tag | Status | Meaning |
|---|---|---|
| `index-unavailable` | `500` | The artifact index executor is saturated; transient. Retry with backoff. |
| `storage-unavailable` | `500` | Storage backend read/write failed. May be transient; persistent = operator attention needed. |
| `upstream-integrity:<algo>` | `502` | Upstream primary/sidecar digest mismatch under the named algorithm. Nothing was cached. |
| `all-proxies-failed` | 5xx pass-through | Every proxy member failed. Paired with `X-Pantera-Proxies-Tried`. |
| `deadline-exceeded` | `504` | Request-level deadline expired before a response was produced. |
| `overload:<resource>` | `503` | Named bounded queue or semaphore refused admission. |
| `internal` | `500` | Catch-all; classifier fallback. |

See [Error Reference](error-reference.md) for the full client-facing explanation of each tag.

---

## `X-Pantera-Proxies-Tried: <n>`

Present on `all-proxies-failed` responses. Integer count of group/proxy members Pantera attempted before giving up. Useful for distinguishing "single upstream down" (`n=1`) from "fanout-wide outage" (`n=3+`).

---

## `X-Pantera-Stale: true`

Present on responses served from the stale-while-revalidate fallback tier. The body is known-good but not freshly revalidated. No client action is required; this header is purely advisory for caches / monitoring.

Stale-served responses today come from:

- `GroupMetadataCache` stale fallback (group repository metadata during partial upstream outage).
- `FilteredMetadataCache` SWR grace period (cooldown metadata re-evaluation).

---

## `X-Pantera-Internal: true`

Server-side-only marker on log events. This header is **not** emitted in client responses -- it tags internal Pantera-to-Pantera calls in the ECS access log so operators can filter them out of client-facing dashboards.

If you ever see this header reach a client, that is a bug; please report it.

---

## Related Pages

- [Error Reference](error-reference.md) -- What each `X-Pantera-Fault` tag means for your client.
- [Streaming Downloads](streaming-downloads.md) -- How client disconnects propagate.
- [Troubleshooting](troubleshooting.md) -- Common client-side issues.
