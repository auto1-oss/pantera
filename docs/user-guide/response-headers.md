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

Server-side-only marker that a group repository puts on its in-process calls to member repositories. It is **not** emitted in client responses, and Pantera discards it when a client sends it on a request.

If you ever see this header reach a client, that is a bug; please report it.

---

## `Last-Modified`

A standard header, listed here because generic HTTP clients depend on it: CI tool installers (the Jenkins Extra Tool Installers plugin refuses a download without it), `curl -z`, `wget -N`, Ansible `get_url`.

Every successful `GET` and `HEAD` of a repository path carries it, for hosted, proxy and group repositories of every format except Docker (registry clients do not use it).

| Response | Value |
|---|---|
| An artifact stored in the repository (hosted, or already cached by a proxy) | The time the artifact was stored in Pantera. |
| A proxy response that relays the upstream's own `Last-Modified` | The upstream's date. |
| A proxy answering a cache miss, a generated index or listing | The time of the request. |
| A checksum or signature file (`.sha1`, `.md5`, `.sha256`, `.sha512`, `.asc`, `.sig`) | The time of the request. |

The date of a stored artifact changes only when the stored copy is written again (a re-upload, or a proxy fetching the artifact again after its cached copy was evicted). A client that compares it with the date of an earlier download may therefore download a proxied artifact a second time once, right after it was first cached, and not again.

Reading the storage time never delays a download for long: if the storage does not answer within 500 ms the response is dated with the time of the request.

Pantera does not answer `304 Not Modified` to `If-Modified-Since` on artifact downloads: a conditional request gets the full `200` response.

---

## Related Pages

- [Error Reference](error-reference.md) -- What each `X-Pantera-Fault` tag means for your client.
- [Streaming Downloads](streaming-downloads.md) -- How client disconnects propagate.
- [Troubleshooting](troubleshooting.md) -- Common client-side issues.
