High-Performance S3 Backend Design (Java 21, AWS SDK v2)

Summary
- Use `S3AsyncClient` with Netty NIO HTTP client, TLS 1.2+, SigV4.
- Enable adaptive retries; exponential backoff with jitter.
- Regional endpoints with optional dualstack.
- Uploads: multipart for objects > 16 MiB, default part size 16–64 MiB; bounded parallel part uploads; per-part CRC32C checksums; SSE-S3 by bucket policy; optional SSE-KMS via config; resumable within-session via part-number idempotency and abort-on-failure.
- Downloads: optional parallel range GET for large objects; streaming publisher to callers (no full buffering); checksum validation on full GET when available.
- Concurrency and pooling: tuned Netty connection pool; keep-alive; large concurrency defaults.

Key Changes
- S3 client built via Netty: connection pooling, timeouts, keep-alive, retry mode adaptive.
- Upload path
  - Non-multipart: compute CRC32C and send `x-amz-checksum-crc32c` for integrity; stream small bodies from memory.
  - Multipart: parts sized by config (>= 5MiB); compute per-part CRC32C; parallel uploads with bounded concurrency; complete/abort logic preserved and idempotent by S3 semantics.
- Download path
  - Default single GET with checksum validation requested.
  - Optional parallel range GET when size >= threshold; ordered concatenation; still backpressure-friendly.
- Configurability
  - HTTP: `max-concurrency`, `max-pending-acquires`, and timeouts.
  - Multipart: `multipart`, `multipart-min-bytes`, `part-size-bytes`, `multipart-concurrency`.
  - Checksums: `checksum` (CRC32C default).
  - SSE: `sse.type` (S3/KMS), `sse.kms-key-id`.
  - Downloads: `parallel-download`, thresholds, chunk size, concurrency.

Measured Outcomes (targets)
- 5k uploads/s sustained; 20k downloads/s sustained across repos.
- p95 1 MiB upload < 300 ms; p95 1 MiB read < 100 ms (in-region, VPC endpoints).

Implementation Notes
- No change to keys or logical layout; APIs preserved.
- Writes and deletes remain idempotent at API level; multipart abort-on-failure retained to avoid leaks.
- Server-side encryption relies on bucket policy (SSE-S3) by default; SSE-KMS opt-in.

Relevant AWS Docs (references)
- AWS SDK for Java v2 – S3 Async client, Netty HTTP client, retry mode adaptive
  - S3AsyncClient, NettyNioAsyncHttpClient, ClientOverrideConfiguration (RetryMode.ADAPTIVE)
- Amazon S3 – Multipart upload, part size limits, parallelism best practices
- Amazon S3 – Checksum support (`x-amz-checksum-*`), checksum validation and `ChecksumMode`
- Amazon S3 – Transfer acceleration and VPC endpoints guidance
- Amazon S3 – Server-side encryption (SSE-S3, SSE-KMS) request parameters

Notes
- Parallel range GET does not request checksum validation (S3 provides checksum on full-object GET only). Full GET path requests checksum.
- Bucket lifecycle to abort incomplete multipart uploads after 7 days is recommended (runbook).

