# Runbooks

> **Guide:** Admin Guide | **Section:** Runbooks

This page collects on-call runbooks for Pantera signals. Each entry describes what the signal means, how to confirm the diagnosis, and the immediate operator action.

---

## X-Pantera-Fault headers on 5xx responses

Every 5xx response that Pantera generates internally carries an `X-Pantera-Fault: <tag>` header identifying the specific fault variant. Use the tag to route the alert to the correct runbook.

### `X-Pantera-Fault: index-unavailable` (500)

**Meaning:** The `DbArtifactIndex` executor queue is saturated. New lookups are being rejected by `AbortPolicy` rather than silently running on the Vert.x event loop.

**Before v2.2.0:** The executor used `CallerRunsPolicy`, which meant a saturated pool caused inline JDBC on the event-loop thread. That was worse than the 500 -- it pushed latency into every concurrent request on the same event loop.

**Diagnosis:**

- Check `pantera.index.executor.queue_size` -- near or at capacity.
- Check PostgreSQL: slow queries, lock waits, or replication lag holding reads.
- Check Hikari: `pantera.hikaricp.connections_pending` climbing -- pool exhaustion upstream of the executor.

**Action:** This is transient-by-design. The client sees a typed 500 and retries with backoff. Operator intervention is only needed if the 500 rate stays elevated for minutes -- treat as a DB-layer incident.

### `X-Pantera-Fault: storage-unavailable` (500)

**Meaning:** The `Storage` backend (S3, filesystem, or swift) refused or timed out on a read/write.

**Diagnosis:** Check `pantera.storage.*` metrics and the backend's own health (S3 API errors, disk I/O saturation, swift 5xx).

**Action:** Persistent = backend outage, escalate. Transient = no action; upstream or client retries will succeed.

### `X-Pantera-Fault: deadline-exceeded` (504)

**Meaning:** The request's end-to-end `Deadline` expired before a response was produced. The deadline is set at request entry (default 30 s) and propagated via `RequestContext`.

**Diagnosis:** Either the upstream is slow (check `pantera.http.client.duration` by host) or an internal cache/DB hop is slow. The access-log line for the expired request carries `url.path` and `http.response.status_code=504`.

**Action:** Identify which hop burned the budget. If upstream, that's a remote-registry issue. If internal, profile the slow hop.

### `X-Pantera-Fault: overload:<resource>` (503)

**Meaning:** A bounded queue or semaphore refused admission. The `<resource>` suffix names the specific limiter (`group-drain`, `io-read`, etc.).

**Action:** Temporary spike -> no action. Sustained -> the limit is too tight for current traffic; scale horizontally or raise the bound.

### `X-Pantera-Fault: upstream-integrity:<algo>` (502)

**Meaning:** `ProxyCacheWriter` fetched a primary artifact and its sidecar digest (MD5, SHA-1, SHA-256, SHA-512), recomputed the digest over the streamed bytes, and the sidecar's declared value disagreed with the bytes. `<algo>` names the specific algorithm that failed.

**Nothing was written to the cache.** The bad pair is rejected atomically -- Pantera cannot cache a drifted primary/sidecar pair by construction.

**Diagnosis:**

- Check `pantera.proxy.cache.integrity_failure{repo, algo}` counter.
- Common root causes: upstream sidecar serving stale byte-for-byte while upstream primary was republished; upstream intermediary (CDN, corporate proxy) serving cached bytes from different epochs on different requests.

**Action:** Usually resolves within a refetch cycle. If sustained, verify the upstream registry is not itself serving drift; the corresponding cache entry is naturally absent so the next client request re-fetches from scratch.

---

## AllProxiesFailed pass-through (behavior change in v2.2.0)

When a proxy or group repository has exhausted all members and none produced a success, Pantera used to synthesize a 502 with a generic body.

**As of v2.2.0:** Pantera passes the winning proxy's actual status and body through to the client. The 5xx response carries both:

- `X-Pantera-Fault: all-proxies-failed`
- `X-Pantera-Proxies-Tried: <n>` -- the number of members attempted.

This means clients that previously saw a synthesized `502` may now see `503`, `504`, or even a `500` body from upstream, depending on what the upstream returned. The change is intentional -- the pass-through preserves diagnostic detail that the synthesized 502 threw away.

**Client impact:** Any client-side retry policy keyed on "502 from Pantera = retry" should also cover 5xx more broadly. Most ecosystem clients already do this.

---

## Related Pages

- [Troubleshooting](troubleshooting.md) -- Diagnostic tool catalogue.
- [Monitoring](monitoring.md) -- Metric reference.
- [../user-guide/error-reference.md](../user-guide/error-reference.md) -- Client-facing error reference.
