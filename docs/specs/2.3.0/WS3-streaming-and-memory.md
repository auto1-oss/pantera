# WS3 — Streaming & Memory (cross-cutting scale)

- **Status:** 📝 DRAFT
- **Depends on:** partial overlap with WS1.3 (shared `ProxyCacheWriter` write path) — coordinate, do not double-implement
- **Blocks:** the 1000 req/s read/write gate (this is the OOM-avoidance half; WS1 is the round-trip half)
- **Decision-gated:** no
- **Size:** L

## 1. Problem & goal

Multiple adapters materialize a **whole artifact or whole metadata document in heap** on the hot path. Under a CI storm against a few large, hot objects this is a GC-storm / OOM waiting to happen — the exact condition 2.3.0's "1000 req/s" targets. The article the release supports literally claims Pantera holds a thousand requests a second "by refusing to do redundant work"; whole-body buffering is the biggest violation of that claim.

**Goal:** no request path materializes a full artifact or full metadata body in heap for the purpose of serving, storing, transforming, or hashing it. Bodies stream; transforms are streaming or bounded; single-flight followers **share the leader's already-computed result** instead of each re-buffering.

## 2. Current state (evidence)

1. **npm packument fully buffered per request, per serve, with per-follower re-buffering.** `serveFull`/`serveAbbreviated` do `Concatenation.withSize(...).single() → byte[]` (`DownloadPackageSlice.java:305-311,552-557`) before filtering; full packuments are 30–40 MB, abbreviated 3–5 MB. `CachedNpmProxySlice`'s `SingleFlight` dedups the *upstream* fetch but each follower re-traverses origin and **re-buffers** (`CachedNpmProxySlice.java:307`). N concurrent readers of one hot large package = N full-packument heap allocations. **Structural** — the URL-transform + cooldown-filter currently assume a materialized `byte[]`.
2. **`ProxyCacheWriter` reads the whole artifact into heap before store.** `commitStreamed`/`commitVerified` do `Files.readAllBytes(tempFile)` then `cache.save(Content.From(bytes))` (`ProxyCacheWriter.java:681,1020`). Download streams to a temp file (good) but the *commit* materializes the entire artifact. Affects Maven, Go, any `BaseCachedProxySlice` format. (**Shared with WS1.3.**)
3. **Composer dist downloads fully buffered.** Rewritten dist URLs route to `ProxyDownloadSlice`, which does `asBytesFuture()` over the whole archive (`ProxyDownloadSlice.java:371`) with no streaming and no single-flight; the well-engineered stream-through path (`CachedProxySlice.verifyAndServePrimary`) is dead in the Composer wiring.
4. **Size-unknown uploads spool the whole body to a temp file and re-read it** just to choose multipart-vs-single-PUT (`EstimatedContentCompliment.java:84-115`). (**Shared with WS1.3.**)
5. **PyPI simple-index rewrite buffers the whole index** into a `String` for regex rewrite (`ProxySlice.java:699,1134`), bounded at `MAX_INDEX_SIZE=10MB` — a full heap copy per cache-miss for large projects (numpy/boto3-class). Wheels/sdists themselves stream correctly.
6. **npm cold fetch parses tens of MB multiple times** — `asStringFuture()` whole-body String, regex rewrite, then a full javax.json DOM `readObject()` + two SHA-256 passes (`HttpNpmRemote.java:62`, `RxNpmProxyStorage.java:146`).

## 3. Target design

### WS3.1 — Streaming cache commit (shared with WS1.3)
Save the download temp file to storage via a **file-backed / streaming `Content`** (a `Publisher<ByteBuffer>` that reads the temp file in bounded chunks), never `Files.readAllBytes`. Compute the digest on the streaming pass (reuse `DigestedFlowable`) instead of a separate full read. Remove the `EstimatedContentCompliment` whole-body spool: decide multipart-vs-single by buffering only up to the multipart threshold, then stream the rest. **One implementation, shared by WS1.3** — whichever workstream lands first owns it; the other cross-references.

### WS3.2 — Bounded/streaming npm packument serving (the structural one)
The npm hot path must stop allocating a full `byte[]` per request. Approach:
- Serve **pre-transformed, pre-filtered cached bytes** where possible: cache the URL-transformed + cooldown-filtered packument keyed by `(package, tarballPrefix, filterGeneration)` and stream it from storage on a hit, so the transform/filter cost is paid once per (content, prefix, cutoff), not per request. The npm ETag already reflects filtered bytes (2.2.5 fix), so the same key basis applies.
- Make the URL transform (`ByteLevelUrlTransformer`) and the metadata filter operate **streaming** (they are already byte-level / SAX-amenable) so the one-time compute doesn't need a full materialization either.
- `SingleFlight` followers **share the leader's produced (cached) result** — return the same streamed `Content` from storage rather than each re-running `origin.response`.
This is the highest-effort item in WS3 and the single biggest OOM lever; treat it as its own sub-project with a load test.

### WS3.3 — Composer dist streaming + single-flight (shared with WS4-composer)
Route Composer `.zip/.tar/.phar` dist downloads through a stream-through, single-flighted path — either fix the wiring so `CachedProxySlice.verifyAndServePrimary` is actually reached for dist paths, or give `ProxyDownloadSlice` the same stream-through-tee + `SingleFlight` the other adapters use. Cross-referenced from WS4-composer; owned here for the streaming mechanism.

### WS3.4 — PyPI streaming index rewrite
Replace the whole-`String` regex rewrite with a streaming/tokenized rewrite (or cache the rewritten index so the buffer cost is once-per-change, not per-request). Coordinate with WS5 (cooldown re-filter caching) — the filtered+rewritten index should be cached together.

### WS3.5 — npm cold-fetch single-pass
On cold ingest, avoid the String+regex+DOM+double-SHA passes: parse once (streaming JSON), transform + digest in the same pass. Lower priority than WS3.2 (cold path, not the hot serve path) but removes multi-pass CPU on large packuments.

## 4. Implementation plan (ordered)

1. **WS3.1** streaming commit — coordinate with WS1.3; land once.
2. **WS3.3** Composer dist streaming — unblocks a CRITICAL Composer memory risk, small once WS3.1 primitives exist.
3. **WS3.2** npm packument streaming/cached-transform — the big structural item; load-test gated.
4. **WS3.4** PyPI index streaming/caching.
5. **WS3.5** npm cold-fetch single-pass.

## 5. Acceptance criteria

1. **No full-artifact heap allocation on store.** A store of an N-hundred-MB artifact holds bounded memory (heap-allocation assertion / `-Xmx`-constrained test that would OOM under `readAllBytes` and passes streaming).
2. **npm hot-path bounded under concurrency.** 200 concurrent requests for one 30 MB packument complete within a constrained heap that the current code OOMs (regression test under a low `-Xmx`); followers issue **one** transform+filter, not N (invocation-count assertion).
3. **Composer dist streamed + single-flight.** N concurrent cold-dist requests → one upstream fetch, bounded heap, bytes streamed (invocation-count + heap assertions).
4. **No behavioral regression.** Served bytes, ETags, digests, and cooldown filtering are byte-identical to pre-WS3 for the same inputs (golden-file tests per format).
5. **Load test:** the WS1 release-gate load test runs under a **fixed, modest heap** and does not GC-thrash or OOM on a hot-set of large artifacts/packuments.

## 6. Test requirements

- Heap-bounded regression tests: run the hot path under a low `-Xmx` (e.g. via a forked surefire JVM arg) so a whole-body buffer deterministically OOMs and the streaming impl passes. Pair with invocation-count assertions for single-flight sharing (CLAUDE.md doctrine — counts, not wall-clock).
- Golden-file byte-equality tests to prove streaming transforms match the old materialized transforms.
- Reuse the WS1 load harness under a constrained heap for the integration proof.

## 7. Out of scope

- The S3-round-trip elimination (that's WS1 — this workstream is purely the heap/streaming half).
- HTTP/2 or chunked-transfer tuning at the server layer (`vertx-server` is unchanged).

## 8. Risks & rollback

- **WS3.2 is structural** — the npm transform/filter assume `byte[]`. Land it behind the cached-transform approach (safer than a full streaming rewrite of the filter) and keep the current path selectable until the load test + golden-file tests pass.
- Streaming digest must remain exactly equivalent to the current digest (integrity depends on it) — golden-file guard.

## 9. Docs & observability

- Metrics (WS7): peak heap / allocation-rate on the hot paths before/after (for the release-note perf claim); single-flight follower-share counter.
- CHANGELOG under `### ⚡ Performance`.
- No user-facing config; note the streaming behavior in `docs/developer-guide/` (cache-writer + npm serving internals).
