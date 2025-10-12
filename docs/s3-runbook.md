S3 Backend Runbook

Where To Configure
- Per-repository storage config (recommended): put keys under `repo.storage` for each repo using S3.
- Or via a shared storage alias: define once in `_storages.yaml` under `storages:<alias>` and reference that alias from your repo.
- Not server-global: do not put these under server-level `meta.storage`.

Example (repo-level)
repo:
  type: maven
  storage:
    type: s3
    bucket: my-bucket
    region: us-east-1
    # Do NOT set `endpoint` for real AWS S3. The SDK uses the regional
    # endpoint automatically based on `region`.
    credentials:
      type: basic
      accessKeyId: ${AWS_ACCESS_KEY_ID}
      secretAccessKey: ${AWS_SECRET_ACCESS_KEY}
      sessionToken: ${AWS_SESSION_TOKEN}   # optional for temporary creds
    http:
      max-concurrency: 2048
    multipart: true
    multipart-min-bytes: 16777216
    part-size-bytes: 33554432
    multipart-concurrency: 64
    checksum: SHA256
    parallel-download: true
    parallel-download-min-bytes: 67108864
    parallel-download-chunk-bytes: 8388608
    parallel-download-concurrency: 16
    cache:
      enabled: true
      path: /var/artipie/cache
      max-bytes: 21474836480
      eviction-policy: LRU
      cleanup-interval-millis: 300000
      high-watermark-percent: 90
      low-watermark-percent: 80
      validate-on-read: true

Example (alias in `_storages.yaml`)
storages:
  s3-fast:
    type: s3
    bucket: my-bucket
    region: us-east-1
    # ... same keys as above ...

Common Errors
- 301 Moved Permanently from s3.amazonaws.com with x-amz-bucket-region header
  - Cause: region mismatch or using the global endpoint.
  - Fix: ensure `region` matches the bucket’s region and remove `endpoint` override.
    For example, if header shows `us-east-2`:
      region: us-east-2
      # no endpoint override for AWS
  - If you must set an endpoint, use the regional one: https://s3.us-east-2.amazonaws.com


Configuration (YAML keys under storage=s3)
- region: AWS region (e.g., us-east-1)
- endpoint: Optional custom endpoint (minio/localstack); `path-style` defaults true
- credentials:
  - type: default            # AWS default provider chain (env, profile, container/IMDS, SSO)
  - type: profile            # use local AWS profile
    profile: myprofile
  - type: basic              # static keys
    accessKeyId: AKIA...
    secretAccessKey: ...
    sessionToken: ...        # optional, for temporary creds
  - type: assume-role        # STS assume role
    roleArn: arn:aws:iam::123456789012:role/MyRole
    sessionName: artipie-session
    externalId: optional-external-id
    source:                  # where to source credentials to call STS from
      type: profile          # or default/basic
      profile: mysource
- http:
  - max-concurrency: default 1024
  - max-pending-acquires: default 2048
  - acquisition-timeout-millis: default 10000
  - read-timeout-millis: default 20000
  - write-timeout-millis: default 20000
  - connection-max-idle-millis: default 60000
- multipart: true|false (default true)
- multipart-min-bytes: default 16777216 (16 MiB)
- part-size-bytes: default 16777216 (16 MiB)
- multipart-concurrency: default 32
- checksum: CRC32C|SHA256|CRC32|SHA1 (default CRC32C)
- sse:
  - type: S3|KMS (default policy-driven)
  - kms-key-id: required when type=KMS
- dualstack: true|false (default false)
- parallel-download: true|false (default false)
- parallel-download-min-bytes: default 67108864 (64 MiB)
- parallel-download-chunk-bytes: default 8388608 (8 MiB)
- parallel-download-concurrency: default 16

Operational Guidance
- Use S3 VPC endpoints; prefer gateway endpoints for cost control if applicable.
- Keep compute and buckets in the same region to minimize latency.
- Ensure TLS 1.2 or newer; SDK v2 Netty client covered.
- Enable bucket versioning.
- Configure lifecycle to abort incomplete multipart uploads after 7 days.
- Consider jumbo frames within VPC if supported end-to-end.
- Avoid NAT egress for S3 traffic; route via VPC endpoint.

Tuning Tips
- Increase `http.max-concurrency` on busy nodes; target ≥1000.
- Size `part-size-bytes` to 16–64 MiB depending on latency/bandwidth; bigger parts reduce overhead, smaller parts increase parallelism.
- Set `multipart-concurrency` roughly 1–4× CPU cores; observe NIC saturation and S3 throttling.
- For hot large reads, enable `parallel-download` and increase chunk/concurrency cautiously.

Integrity
- Uploads: CRC32C per-object (and per-part for multipart) by default.
- Downloads: full-object checksum validation requested when not using range GET.

Benchmarks
- For local dev, use s3mock/minio/localstack for functional checks (no perf parity).
- For throughput/latency measurement, test in-region against real S3 with VPC endpoints.
- Suggested approach: run N parallel workers with 1 MiB and 64 MiB payloads, vary part size and concurrency.
