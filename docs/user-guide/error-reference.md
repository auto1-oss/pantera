# Error Reference

> **Guide:** User Guide | **Section:** Error Reference

This page explains the 5xx responses Pantera can return and how clients should react to each. Every 5xx carries an `X-Pantera-Fault: <tag>` header; match the tag against the sections below.

For auth-related 4xx, see [Troubleshooting](troubleshooting.md).

---

## `500` with `X-Pantera-Fault: index-unavailable`

**What it means:** Pantera's artifact-index executor is saturated. The request arrived at a moment when the DB-backed index queue was full.

**Client action:** Retry with exponential backoff. This condition is transient by design -- the whole point of returning a typed 500 here is that the backend has shed load rather than letting the queue block. A simple retry with 100 ms -> 200 ms -> 400 ms backoff will almost always succeed.

**Persistent failure:** If the 500 rate stays elevated across multiple retry windows, it's an operational incident on the Pantera side -- your administrator should see the signal.

---

## `500` with `X-Pantera-Fault: storage-unavailable`

**What it means:** Pantera's storage backend (S3, filesystem, or OpenStack Swift) refused a read or write.

**Client action:** Retry with backoff. Most occurrences are transient (a momentary S3 5xx, a brief I/O spike). If the failure persists across several minutes of retries, it is a storage-layer outage and client-side retry will not recover it.

---

## `502` with `X-Pantera-Fault: upstream-integrity:<algo>`

**What it means:** The upstream registry advertised a digest (`<algo>` is one of `md5`, `sha1`, `sha256`, `sha512`) for the artifact you requested, but the bytes upstream returned did not match that digest. Pantera refuses to cache a drifted primary/sidecar pair -- nothing was written to our cache.

**Client action:** Retry. The next fetch will re-pull from upstream and may succeed if the upstream served transient drift (common with CDN-fronted registries). If the failure is sticky, the upstream registry itself has a drift problem; report to that registry's operator.

**Why this matters to you:** A cached drifted artifact would later produce a `ChecksumFailureException` in your Maven / Gradle / pip client. The 502 here is strictly better than that downstream failure -- you are never served bytes that failed digest verification.

---

## `5xx` with `X-Pantera-Fault: all-proxies-failed` (behavior change in v2.2.0)

**What it means:** Every member of a group or proxy repository was attempted and none produced a success.

**v2.2.0 change:** Pantera now **passes through the winning proxy's actual response**. Previously this was always synthesized as `502`; now you may see the real `503` / `504` / `500` that the upstream returned. The response includes:

- `X-Pantera-Fault: all-proxies-failed`
- `X-Pantera-Proxies-Tried: <n>` (integer: how many members were attempted)

**Client action:** Retry per your client's normal 5xx policy. The pass-through gives your client more signal than the synthesized 502 did -- a `504` from an upstream, for instance, now reaches you as a `504` rather than being flattened to `502`.

---

## `503` with `X-Pantera-Fault: overload:<resource>`

**What it means:** A named internal resource (thread pool, semaphore, queue) refused to admit your request because it was at capacity. The `<resource>` suffix identifies which one.

**Client action:** Retry with backoff. Standard Retry-After-style semantics apply.

---

## `504` with `X-Pantera-Fault: deadline-exceeded`

**What it means:** The request's end-to-end deadline expired before Pantera could produce a response. The deadline is set at request entry (default 30 s) and threaded through every hop.

**Client action:** Retry. If your workload legitimately needs longer than 30 s, coordinate with your administrator -- the deadline is an operator-configurable limit.

---

## `500` with `X-Pantera-Fault: internal`

**What it means:** Catch-all for anything the fault classifier could not type more specifically. Every `internal` fault is logged on the server side with a full stack trace.

**Client action:** Retry once; if it recurs, escalate to your administrator with the request's trace ID (carried in the `X-Request-Id` / `trace.id` field).

---

## Related Pages

- [Response Headers](response-headers.md) -- All custom Pantera response headers.
- [Streaming Downloads](streaming-downloads.md) -- Cancel behavior.
- [Troubleshooting](troubleshooting.md) -- Auth and "not found" issues.
