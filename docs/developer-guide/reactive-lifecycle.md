# Reactive Lifecycle

> **Guide:** Developer Guide | **Section:** Reactive Lifecycle

Pantera streams bodies through RxJava2 `Flowable` chains. Every chain that owns a resource -- file handles, pooled HTTP connections, temp files -- MUST wire all three terminal paths: complete, error, and cancel. Missing the cancel path is the single most common source of resource leaks in the codebase.

---

## Request/Response Lifecycle with Cancel Propagation

At 1000 req/s, clients disconnecting mid-response is routine. Before v2.2.0 those disconnects did not propagate into the slice subscription -- upstream Jetty fetches kept streaming bytes into dead sockets until the next write organically failed, wasting upstream bandwidth and holding file handles.

The v2.2.0 fix (Group A) wires end-to-end cancel:

1. `VertxSliceServer` registers a `closeHandler` on `request.connection()` and `exceptionHandler` on both request and response.
2. The reactive-streams `Subscription` is captured via `doOnSubscribe` and stashed in an `AtomicReference<Runnable>` cancel hook.
3. On any disconnect signal, the cancel hook fires `subscription.cancel()`, which propagates up the `Flowable` chain.
4. Each `Flowable` operator that owns a resource observes the cancel via `doOnCancel` and releases.

The net effect: client disconnect -> Pantera's response path cancels -> upstream `HttpClient.GET` cancels -> upstream socket closes -> Jetty releases buffers. All within a single event-loop tick.

---

## The Three-Terminal-Path Pattern

Every `Flowable`/`Publisher` chain that owns a resource has exactly three terminal paths:

| Path | Trigger | Required action |
|---|---|---|
| **Complete** | Upstream emits `onComplete`. | Normal finalization. Resource is almost always already released by virtue of the `onComplete` observer. |
| **Error** | Upstream emits `onError(Throwable)`. | Explicit cleanup via `doOnError`. The downstream subscriber will also see the error -- do not swallow. |
| **Cancel** | Downstream subscriber cancels. | Explicit cleanup via `doOnCancel`. This is the path that is most commonly missed. |

If your operator only handles complete + error, a cancelling subscriber will leak. Complete + error observers are not invoked on cancel -- cancel is a third, separate terminal.

### Canonical Example: `CachingBlob.content`

`CachingBlob.content` streams a primary body into a temp file while hashing it. It wires all three paths:

```java
return stream
    .doOnComplete(() -> { /* success: temp file promoted to final location */ })
    .doOnError(e   -> { channel.close(); tempFile.deleteIfExists(); })
    .doOnCancel(() -> { channel.close(); tempFile.deleteIfExists(); });
```

The error and cancel cleanup blocks are textually identical -- the channel must close and the temp file must be deleted on any non-success termination. Missing `doOnCancel` here would produce file-descriptor leaks on every client disconnect mid-download.

Other call-sites following the same pattern (updated in v2.2.0):

- `StreamThroughCache` -- `doOnCancel` matches existing `doOnError`.
- `DiskCacheStorage` -- ditto.
- `VertxRxFile.save` -- added safety-net `doOnError` closing the `AsyncFile` on upstream error.
- `ArtifactHandler` (both download paths) -- captures the `Disposable` returned by `Flowable.subscribe` and disposes on response `closeHandler` / `exceptionHandler`.

---

## Requirement for New Reactive Sites

When adding a new `Flowable` / `Publisher` chain that owns any of the following:

- A file handle (`FileChannel`, `AsyncFile`, `InputStream`, `OutputStream`)
- A temp file / temp directory
- A pooled HTTP connection
- A database cursor / `ResultSet`
- Any native resource (ByteBuffer direct memory, off-heap anything)

you MUST wire all three terminal paths. A missing `doOnCancel` is a leak; reviewers should treat it as a required change request.

### Self-check

Before merging a new reactive site, ask:

1. "If a subscriber cancels right now, what leaks?"
2. "Is the cleanup on cancel identical to the cleanup on error? If so, have I written `.doOnError(...).doOnCancel(...)` with the same block?"
3. "Is there an integration test that exercises the cancel path?" -- `VertxSliceServerCancelPropagationTest` and the chaos tests in `pantera-main/src/test/java/com/auto1/pantera/chaos/` are the templates.

---

---

## ContextualExecutor for Async Hops (v2.2.0+)

Every async hop on the request path MUST preserve MDC + APM transaction context. Without it, `trace.id`, `span.id`, `transaction.id`, `user.name`, `client.ip`, and the active APM transaction get dropped on the worker thread — and every log line emitted past the hop is unjoinable in Kibana.

As of v2.2.0 the v1 helper (`MdcPropagation.withMdc*`) is folded into a single `ContextualExecutor` that:

1. **Captures** the current MDC map and APM transaction reference on the calling thread.
2. **Restores** them at the start of the callback on the worker thread.
3. **Cleans up** with `try / finally` so the pooled worker thread leaves no MDC residue for the next task.

### When you MUST use it

Any of these on the request path:

- `CompletableFuture.{supplyAsync, thenApplyAsync, thenComposeAsync, ...}` — any flavour where the continuation runs on a different thread.
- `Flowable.observeOn(...)` / `Maybe.subscribeOn(...)` / similar RxJava boundaries that move to a worker.
- `executor.submit(...)` / `executor.execute(...)` for any bounded executor (DB consumer, drain pool, Quartz).
- Quartz jobs — handled automatically via `TracingJobWrapper` if you use `scheduleJob`; raw `Scheduler.scheduleJob(...)` calls bypass it.
- Pub/sub envelopes — encode trace context in the v2 envelope (see below).

### Pub/sub v2 envelope

`ClusterEventBus` and `CacheInvalidationPubSub` now ship messages in a versioned envelope. The v2 prefix carries `trace.id` + `span.id` alongside the payload; v1 envelopes are still parsed for **rolling-deploy compatibility** so a v2.1.x node can publish to a v2.2.0 subscriber and vice versa during the rolling upgrade window.

When emitting from new code: use the existing `ClusterEventBus.publish(channel, payload)` API; the envelope wrapping is internal. When consuming: the trace.id is restored to MDC for the duration of the subscriber callback — no caller action required.

### Non-Jetty outbound transports

The `JettyClientSlices` decorator chain injects `traceparent` + `X-B3-*` automatically on every outbound proxy fetch. Other outbound transports do **not** get this for free — they must use `TraceHeaders.httpClientHeaders()` and merge the resulting map into their outbound request headers:

- `WebhookDispatcher` (Vert.x WebClient) — already wired.
- `OsvDevClient` (`java.net.http.HttpClient`) — already wired.
- Any new outbound transport you add — must follow the same pattern.

If you add a new outbound HTTP call on the request path and skip this step, the receiving system cannot join its logs to the originating Pantera request in Kibana.

### Self-check

Before merging a new async hop:

1. "Does the callback emit any `EcsLogger` line?" If yes, MDC must be preserved.
2. "Did I use `ContextualExecutor.contextualize(...)` or `MdcPropagation.withMdc*(...)` for the continuation?"
3. "If this is a new outbound transport, am I calling `TraceHeaders.httpClientHeaders()`?"
4. "Is there a test asserting `MDC.get(TRACE_ID)` is non-null inside the callback?"

---

## Related Pages

- [Caching](caching.md) -- Cache reads/writes must also honor cancel.
- [Fault Model](fault-model.md) -- Cancel is NOT a fault; it is a normal terminal path.
- [Admin: Runbooks](../admin-guide/runbooks.md) -- Operator view of the signals.
