# Streaming Downloads

> **Guide:** User Guide | **Section:** Streaming Downloads

This page describes how Pantera handles mid-download client disconnects. As of v2.2.0, the behavior is transparent and requires no client-side configuration -- it is documented here for operators and advanced users who want to understand what happens.

---

## Client Disconnect Propagation

When your HTTP client closes the connection mid-response (Ctrl+C, timeout, network hiccup), Pantera propagates the cancel signal end-to-end:

1. Pantera's HTTP server observes the close on the client socket.
2. The in-flight response pipeline is cancelled.
3. If the response was being streamed from an upstream registry (Maven Central, npm registry, etc.), Pantera also cancels the upstream fetch.
4. Any temporary files or open streams created for the request are released.

**No client-side action is needed.** Standard clients (curl, Maven, npm, pip, etc.) all signal the disconnect through normal TCP close; Pantera picks it up from there.

---

## Why This Matters

Before v2.2.0, a mid-download disconnect caused Pantera to keep pulling bytes from the upstream into a dead socket until the upstream organically errored or completed. That behavior wasted upstream bandwidth, held file descriptors on the Pantera side, and -- for very large artifacts -- could hold significant RAM.

Cancelling clients used to cost Pantera real resources; now they cost nothing.

---

## Edge Cases

- **Partial-download caches.** If a cache-miss download is cancelled mid-write, the temp file is deleted and the cache slot remains empty. The next client request will trigger a fresh upstream fetch. No partial artifact is ever committed to the cache.
- **Shared in-flight fetches.** When multiple clients request the same artifact at the same time, Pantera coalesces them into a single upstream fetch (see `SingleFlight`). A single client cancelling does NOT cancel the coalesced fetch -- the remaining clients still receive the response. Only when every subscribed client cancels does the upstream fetch get cancelled.
- **HTTP/3 large uploads.** Pantera enforces a per-stream buffer cap on HTTP/3 (default 16 MiB). Uploads exceeding that cap are rejected; see [Admin: Environment Variables](../../admin-guide/environment-variables.md#http-3) for the tunable.

---

## Related Pages

- [Response Headers](response-headers.md) -- Pantera's custom response headers.
- [Error Reference](error-reference.md) -- 5xx fault tags.
- [Troubleshooting](troubleshooting.md) -- Client-side issue catalogue.
