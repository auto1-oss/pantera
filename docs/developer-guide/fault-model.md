# Fault Model

> **Guide:** Developer Guide | **Section:** Fault Model

Pantera's request pipeline distinguishes between *faults* (typed, expected, translated to a well-defined HTTP status) and *throwables* (untyped, unexpected, classified via `FaultClassifier` fallback). Every slice returning `Result<Response>` produces one or the other -- the only decision point for HTTP status mapping is `FaultTranslator`.

---

## Sealed Fault Hierarchy

The `Fault` type lives in `pantera-core/src/main/java/com/auto1/pantera/http/fault/`. It is a sealed interface -- every variant is enumerated in one file, so the compiler forces `FaultTranslator`'s switch to be exhaustive.

| Variant | Emitted by | Client-facing status |
|---|---|---|
| `NotFound` | Slices that resolved the repo but not the artifact. | `404` |
| `Forbidden` | Auth/policy slices, cooldown block. | `403` |
| `IndexUnavailable` | `DbArtifactIndex` on executor saturation. | `500` + `X-Pantera-Fault: index-unavailable` |
| `StorageUnavailable` | Storage backend refused a read/write. | `500` + `X-Pantera-Fault: storage-unavailable` |
| `AllProxiesFailed` | Group/proxy fanout with no member success. | Pass-through of the winning proxy's response + `X-Pantera-Fault: all-proxies-failed` + `X-Pantera-Proxies-Tried: <n>` |
| `UpstreamIntegrity` | `ProxyCacheWriter` on primary/sidecar digest mismatch. | `502` + `X-Pantera-Fault: upstream-integrity:<algo>` |
| `Deadline` | Request `Deadline` expired before response produced. | `504` + `X-Pantera-Fault: deadline-exceeded` |
| `Overload` | Bounded queue or semaphore refused admission. | `503` + `X-Pantera-Fault: overload:<resource>` |
| `Internal` | Catch-all; classification fallback only. | `500` + `X-Pantera-Fault: internal` |

The full policy table (worked examples for AllProxiesFailed pass-through, ordering between NotFound and Overload, etc.) is in `docs/analysis/v2.2-target-architecture.md` §9 and locked by the `FaultAllProxiesFailedPassThroughTest` + `FaultTranslatorTest` suites.

---

## New Emitters (v2.2.0)

### `DbArtifactIndex -> Fault.IndexUnavailable`

Added in Group H.1. The index executor switched from `CallerRunsPolicy` to `AbortPolicy`. On queue saturation the `RejectedExecutionException` (whether synchronous from `supplyAsync` or asynchronous via normal completion) is mapped to `Fault.IndexUnavailable` by `GroupResolver`'s existing `.exceptionally(...)` handler. `FaultTranslator` returns a 500 with `X-Pantera-Fault: index-unavailable`.

Behavioral note: a follow-up commit (`abee2ec9`) wrapped `CompletableFuture.supplyAsync` in a try/catch so the synchronous rejection is always observed via the returned future, not propagated up the stack. Callers on the Vert.x event loop see a failed future, never a raw exception.

---

## How to Add a New Fault Variant

1. **Add the record to `Fault.java`.** Keep the field list minimal -- whatever a translator or a classifier needs, nothing more. Example: `UpstreamIntegrity` carries the `algo` string so the header tag can include it.
2. **Extend `FaultTranslator.translate(...)`.** The switch is exhaustive -- the compiler will reject the build until you add a case. Decide: HTTP status, headers, optional body.
3. **Extend `FaultClassifier.classify(...)` if the fault has a fallback path.** Not every fault needs one; only add a classifier case if there is a `Throwable` type that should map to the new fault without an explicit emit site. Most new faults don't need this.
4. **Regression tests.** At minimum, one test that emits the new fault end-to-end through `FaultTranslator` and asserts the response shape, and one exhaustive-switch guard (`FaultTranslatorTest.translatesEveryFaultVariant`).
5. **Document the new header tag in the user guide.** Append to `docs/user-guide/error-reference.md` so external consumers know what the tag means.

Rule of thumb: if you are about to emit `Fault.Internal(e)` from a new slice, stop and consider whether the failure mode deserves its own typed variant. `Internal` is a last resort; every other fault is strictly better diagnostic signal.

---

## Related Pages

- [Reactive Lifecycle](reactive-lifecycle.md) -- How faults propagate through cancel-aware chains.
- [Caching](caching.md) -- Why cache failures do NOT emit faults.
- [Admin: Runbooks](../admin-guide/runbooks.md) -- Operator response to each fault.
- [User: Error Reference](../user-guide/error-reference.md) -- Client-facing tag glossary.
