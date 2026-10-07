# WS1 storage load test — the ≥1000 req/s R+W release gate

This is the repeatable harness for the **2.3.0 headline claim**: the
index-accelerated S3 cache (`cache.mode: index`) sustains **≥1000 req/s reads
AND ≥1000 req/s writes** against an S3-compatible object store, with reads
served from the local disk tier (zero blob-store round trips on a hit) and
writes acked from local disk with the S3 upload draining asynchronously
(WS1 spec `docs/specs/2.3.0/WS1-storage-for-scale.md` §5 acceptance #8).

It drives the **real production stack** — `CachedBlobStorage` →
`MeteredBlobStore` → `S3Storage`, exactly as `S3StorageFactory` builds it for a
`cache.mode: index` repository — against a **MinIO** container (a real
S3-compatible server, not a mock).

## What it is

- **`S3CacheLoadITCase`** (`pantera-storage/pantera-storage-s3/src/test/java/com/auto1/pantera/asto/s3/`)
  — a failsafe integration test (`*ITCase`, `-Pitcase`). It seeds a hot read
  set, then runs a read phase and a write phase, each with 64 virtual-thread
  workers for 15 s, and asserts both phases clear the ≥1000 ops/s floor with
  zero errors and sub-second read p99.
- **`run-load-test.sh`** — starts nothing you have to manage: Testcontainers
  brings up (and tears down) MinIO automatically. The script just runs the
  ITCase, prints the numbers, and appends them to `RESULTS.md`.
- **`RESULTS.md`** — the measured result from the last run committed to the
  tree.

Measuring wall-clock throughput is normally forbidden by the project's testing
doctrine (CLAUDE.md) — this is the one sanctioned exception: the load gate is
the only place duration is measured, and only as an order-of-magnitude floor
on the WS1 design, never as a micro-benchmark.

## Requirements

- Docker (for the MinIO container, pulled automatically by Testcontainers).
- A built tree (`mvn -q -pl pantera-storage/pantera-storage-s3 -am install -DskipTests` once, or a full `mvn install`).

## Run it

```bash
docs/slo/load-test/run-load-test.sh
```

or directly:

```bash
mvn verify -pl pantera-storage/pantera-storage-s3 -Pitcase \
  -Dit.test=S3CacheLoadITCase -Dexec.skip=true
```

Tunables are system properties on the ITCase (defaults in parentheses):
`pantera.minio.image` (`minio/minio:latest`).

## What "pass" proves

- **Reads** sustain many multiples of 1000 req/s at sub-millisecond median
  latency — physically impossible against per-request MinIO round trips, so it
  demonstrates the read hot-set is served from the local disk tier via the
  in-memory index with **zero blob-store contact** (the per-operation proof is
  the invocation-count unit test `CachedBlobStorageTest`; this shows it holds
  under sustained concurrent load).
- **Writes** sustain ≥1000 req/s acked from local disk, with the S3 upload
  draining on the background write-back pool — the write path is not bounded by
  MinIO PUT latency.
