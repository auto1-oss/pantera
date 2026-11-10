# WS1 load-test results

The `>=1000 req/s reads AND writes` gate (spec §5 acceptance #8), measured by
`S3CacheLoadITCase` driving the real `cache.mode: index` stack
(`CachedBlobStorage` -> `MeteredBlobStore` -> `S3Storage`) against a MinIO
container. Re-run with `docs/slo/load-test/run-load-test.sh`.

## Latest run

```
=== WS1 LOAD RESULT (cache.mode: index → MinIO, 8192 B objects, 64 workers) ===
READ       57,184 ops/s   ops=858106 errors=0   p50=0ms p95=2ms p99=5ms   (15.0s)
WRITE       3,379 ops/s   ops=50730 errors=0   p50=17ms p95=36ms p99=48ms  (15.0s)
```

| Phase | Throughput | Gate (≥1000/s) | p50 | p95 | p99 | errors |
|---|---|---|---|---|---|---|
| **READ** (index + disk hit) | **57,184 ops/s** | **57× over** | 0 ms | 2 ms | 5 ms | 0 |
| **WRITE** (write-back → MinIO) | **3,379 ops/s** | **3.4× over** | 17 ms | 36 ms | 48 ms | 0 |

### Interpretation

- **Reads: gate cleared 57×.** 858,106 reads in 15 s at a sub-millisecond
  median is physically impossible if each were a MinIO round trip — it
  demonstrates the read hot-set is served entirely from the local disk tier via
  the in-memory index, with **zero blob-store contact on a hit**. (The
  per-operation "zero blob-store calls on a hit" property is proven separately
  and deterministically by the invocation-count unit test
  `CachedBlobStorageTest`; this load run shows it holds under sustained
  64-way concurrency.)
- **Writes: gate cleared 3.4×.** Writes are acked from local disk (write-back),
  so throughput is bounded by local disk write + digest, not MinIO PUT latency;
  the S3 upload drains on the background uploader pool. p99 stayed at 48 ms
  under 64-way concurrency with zero saturation rejections.

The write path is lower than the read path (as expected — a write does a disk
write, a digest, an index update, a sidecar write, and an enqueue vs. a read's
index lookup + NIO disk stream), but both clear the release gate comfortably
with zero errors.

> Numbers are from a developer workstation; they are an order-of-magnitude
> floor on the WS1 design, not a hardware benchmark. The gate the ITCase
> asserts is the conservative ≥1000 req/s floor, not these headline figures.
