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

## Related Pages

- [Caching](caching.md) -- Cache reads/writes must also honor cancel.
- [Fault Model](fault-model.md) -- Cancel is NOT a fault; it is a normal terminal path.
- [Admin: Runbooks](../admin-guide/runbooks.md) -- Operator view of the signals.
