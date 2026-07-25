# Storage Backends

> **Guide:** Admin Guide | **Section:** Storage Backends

Pantera supports filesystem and S3-compatible object storage for artifact data. This page covers configuration for each backend, including advanced S3 features and storage aliases. For the complete key-by-key reference, see the [Configuration Reference](../configuration-reference.md#3-storage-configuration).

---

## Filesystem (type: fs)

The simplest storage backend. Artifacts are stored as files on the local filesystem.

```yaml
storage:
  type: fs
  path: /var/pantera/data
```

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `type` | string | Yes | Must be `fs` |
| `path` | string | Yes | Absolute filesystem path to the data directory |

The path must be writable by user `2021:2020` inside the Docker container. For JAR deployments, the path must be writable by the Pantera process user.

**When to use filesystem storage:**

- Development and testing environments
- Single-node deployments with local disk
- Small teams with low artifact volume

**Limitations:**

- Not suitable for HA deployments (storage is node-local)
- No built-in redundancy; rely on OS-level backups

---

## Amazon S3 (type: s3)

S3-compatible object storage with multipart upload, parallel download, disk cache, and server-side encryption.

### Basic Configuration

```yaml
storage:
  type: s3
  bucket: my-artifacts
  region: eu-central-1
  endpoint: https://s3.eu-central-1.amazonaws.com
```

### Full Configuration

```yaml
storage:
  type: s3
  bucket: my-artifacts
  region: eu-central-1
  endpoint: https://s3.eu-central-1.amazonaws.com
  path-style: true
  storage-class: STANDARD_IA   # optional; defaults to the S3 default (STANDARD)

  # Multipart upload
  multipart: true
  multipart-min-size: 32MB
  part-size: 8MB
  multipart-concurrency: 16
  checksum: SHA256

  # Parallel download
  parallel-download: true
  parallel-download-min-size: 64MB
  parallel-download-chunk-size: 8MB
  parallel-download-concurrency: 8

  # Server-side encryption
  sse:
    type: AES256

  # Disk cache
  cache:
    enabled: true
    path: /var/pantera/cache/s3
    max-bytes: 10737418240
    eviction-policy: LRU
    cleanup-interval-millis: 300000
    high-watermark-percent: 90
    low-watermark-percent: 80
    validate-on-read: true

  # HTTP client tuning
  http:
    max-concurrency: 1024
    max-pending-acquires: 2048
    acquisition-timeout-millis: 30000
    read-timeout-millis: 120000
    write-timeout-millis: 120000
    connection-max-idle-millis: 30000

  # Credentials
  credentials:
    type: default
```

### S3 Credential Types

| Type | Description | Required Fields |
|------|-------------|-----------------|
| `default` | AWS SDK default chain (env vars, instance profile, etc.) | None |
| `basic` | Static access key/secret | `accessKeyId`, `secretAccessKey`, optionally `sessionToken` |
| `profile` | AWS profile from `~/.aws/credentials` | `profile` (default: "default") |
| `assume-role` | STS AssumeRole with optional chaining | `roleArn`, optionally `sessionName`, `externalId`, `source` |

**Static credentials:**

```yaml
credentials:
  type: basic
  accessKeyId: AKIAIOSFODNN7EXAMPLE
  secretAccessKey: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
```

**Assume Role with chaining:**

```yaml
credentials:
  type: assume-role
  roleArn: arn:aws:iam::123456789012:role/pantera-storage
  sessionName: pantera-session
  source:
    type: default
```

### Multipart Upload

Multipart upload splits large files into concurrent part uploads for better throughput.

| Key | Default | Description |
|-----|---------|-------------|
| `multipart` | `true` | Enable multipart uploads |
| `multipart-min-size` | `32MB` | Minimum file size for multipart |
| `part-size` | `8MB` | Size of each part |
| `multipart-concurrency` | `16` | Concurrent part uploads |
| `checksum` | `SHA256` | Checksum algorithm: `SHA256`, `CRC32`, `SHA1` |

### Parallel Download

Parallel download uses HTTP range requests to download large artifacts from S3 in parallel.

| Key | Default | Description |
|-----|---------|-------------|
| `parallel-download` | `false` | Enable parallel range-GET downloads |
| `parallel-download-min-size` | `64MB` | Minimum size to trigger parallel download |
| `parallel-download-chunk-size` | `8MB` | Chunk size per range-GET |
| `parallel-download-concurrency` | `8` | Concurrent download threads |

### Server-Side Encryption (SSE)

| Key | Default | Description |
|-----|---------|-------------|
| `sse.type` | `AES256` | `AES256` (SSE-S3) or `KMS` (SSE-KMS) |
| `sse.kms-key-id` | -- | KMS key ARN (required when type is `KMS`) |

**SSE-KMS example:**

```yaml
sse:
  type: KMS
  kms-key-id: arn:aws:kms:eu-west-1:123456789012:key/my-key-id
```

### Disk Cache (Hot Cache Layer)

The disk cache stores recently accessed S3 objects on local disk to reduce S3 API calls and latency. It is a read-through cache: on cache miss, the artifact is fetched from S3 and simultaneously streamed to the client and written to disk.

```yaml
cache:
  enabled: true
  path: /var/pantera/cache/s3
  max-bytes: 10737418240       # 10 GB
  eviction-policy: LRU         # LRU or LFU
  cleanup-interval-millis: 300000
  high-watermark-percent: 90   # Eviction starts at 90% full
  low-watermark-percent: 80    # Eviction stops at 80% full
  validate-on-read: true       # Validate against S3 metadata on read
```

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | -- | Must be `true` to activate |
| `path` | -- | Local filesystem path for cache files |
| `max-bytes` | `10737418240` (10 GiB) | Maximum cache size in bytes |
| `eviction-policy` | `LRU` | `LRU` (least recently used) or `LFU` (least frequently used) |
| `cleanup-interval-millis` | `300000` (5 min) | Eviction check interval |
| `high-watermark-percent` | `90` | Trigger eviction at this capacity |
| `low-watermark-percent` | `80` | Stop eviction at this capacity |
| `validate-on-read` | `true` | Validate cache integrity against S3 |

**Sizing recommendation:** Set `max-bytes` to fit your hot working set (the artifacts accessed most frequently in a typical build cycle). For most teams, 10-50 GB is sufficient.

### Index Cache Mode (`cache.mode: index`)

`cache.mode: index` opts a storage into the WS1.1 index-accelerated cache
(`CachedBlobStorage`) instead of the disk cache above. The difference is the
hit path: the disk cache (`cache.mode: disk`, the default) still issues 1-2
synchronous S3 HEADs on every cache hit to check existence and validate the
cached copy against S3; index mode never does. An in-memory `StorageIndex`,
hydrated once at boot by scanning the cache directory's `.meta` sidecars,
answers `exists`/`metadata`/`list` from memory with zero S3 round trips, and a
disk-served read is a pure local file read with no inline S3 call at all. A
disk copy is trusted for `freshness-ttl-millis` before it would need
re-validation; a confirmed-absent key is remembered for `negative-ttl-millis`
so a repeated lookup for a key that doesn't exist doesn't re-hit S3 either.
Concurrent requests for the same not-yet-cached key are coalesced into a
single S3 fetch.

```yaml
cache:
  enabled: true
  mode: index
  path: /var/pantera/cache/s3
  freshness-ttl-millis: 300000     # 5 min -- how long a disk copy is trusted
  negative-ttl-millis: 30000       # 30 sec -- how long a confirmed miss is remembered
```

| Key | Default | Description |
|-----|---------|-------------|
| `mode` | `disk` | `disk` (existing `DiskCacheStorage`) or `index` (`CachedBlobStorage`) |
| `freshness-ttl-millis` | `300000` (5 min) | How long a disk-cached entry is trusted without S3 re-validation |
| `negative-ttl-millis` | `30000` (30 sec) | How long a confirmed S3 miss is cached to avoid repeat lookups |

**What's different from the disk cache:**

- **Writes are asynchronous durable write-back by default** (WS1.2) -- see
  [Write-Back (Async Durable Writes)](#write-back-async-durable-writes-cachewrite-through)
  below. `cache.write-through: true` opts a repository back into the
  pre-WS1.2 synchronous behaviour.
- **Size-bounded with index-driven LRU/LFU eviction** (WS1.4) -- see
  [Eviction & Admission Control](#eviction--admission-control-cachemax-disk-bytes)
  below. The disk directory under `cache.path` never exceeds
  `cache.max-disk-bytes`: a write that would cross it evicts the coldest
  entries first, synchronously, before the write lands. A key with an
  unconfirmed write-back upload (`PENDING_WRITE`) is NEVER evicted -- it is
  the only durable copy of those bytes until the upload confirms.
- **Cache files are sharded on disk** (WS1.4) -- a 2-level hex fan-out keyed
  off a hash of the artifact key, not the artifact key's own path structure,
  so the cache directory never accumulates one huge flat directory. This is
  purely an internal disk layout detail; it has no config key and no
  observable effect other than the directory structure under `cache.path`.
- **`list()` is scoped to what the index has observed** -- the boot-time disk
  scan plus every key written or read since. A repository switched to
  `cache.mode: index` with pre-existing S3 objects that Pantera has never
  locally touched will not show those objects in a listing until they are
  individually accessed (or a future backfill populates the index). This does
  not affect artifact *retrieval* (a `value()`/`exists()` for an unindexed key
  still correctly falls through to S3), only listing/browsing completeness.
- **Cross-node staleness is event-driven** (WS1.5) -- see
  [Cross-Node Coherence](#cross-node-coherence-ws15) below.
  `freshness-ttl-millis` remains only the backstop for the window before an
  invalidation message arrives, or if one is lost.

The `validate-on-read` disk-cache option does not apply in index mode --
there is nothing to validate against on the hot path by design.

### Write-Back (Async Durable Writes) (`cache.write-through`)

By default, `cache.mode: index` acknowledges a `save()` from **local disk
durability**, not from a confirmed S3 write: bytes land on disk, a digest is
computed once from the just-written file, the index records the key as
`PENDING_WRITE`, and a bounded pool of background uploader threads drains the
upload to S3 with retry/backoff. This is what makes writes scale past S3's
per-request latency -- the caller is acknowledged in one local disk write
instead of one disk write plus one round trip to S3.

```yaml
cache:
  enabled: true
  mode: index
  path: /var/pantera/cache/s3
  write-through: false                     # default: async write-back
  write-back-queue-capacity: 1024          # high-water mark for in-flight uploads
  write-back-uploader-threads: 4           # dedicated daemon uploader pool size
  write-back-max-retries: 5                # retries before dead-lettering an upload
  write-back-backoff-millis: 500           # backoff before the first retry
  write-back-max-backoff-millis: 30000     # backoff ceiling
  write-back-retry-after-seconds: 5        # Retry-After hint on a saturated queue
```

| Key | Default | Description |
|-----|---------|-------------|
| `write-through` | `false` | `true` restores the pre-WS1.2 synchronous behaviour: `save()` does not acknowledge until S3 confirms the write. Set this for repositories that cannot tolerate the durability window below (e.g. compliance). |
| `write-back-queue-capacity` | `1024` | High-water mark for concurrently in-flight (queued + retrying) uploads. A `save()` past this mark is rejected immediately, before any disk write. |
| `write-back-uploader-threads` | `4` | Size of the dedicated background thread pool draining the queue to S3. |
| `write-back-max-retries` | `5` | Retry attempts after the first failed S3 `PUT` before an upload is dead-lettered. |
| `write-back-backoff-millis` | `500` | Backoff before the first retry; doubles per attempt up to the ceiling below. |
| `write-back-max-backoff-millis` | `30000` | Backoff ceiling. |
| `write-back-retry-after-seconds` | `5` | `Retry-After` hint surfaced to a caller whose `save()` was rejected because the queue is saturated. |

**Durability window.** Between a `save()` returning and the background
uploader confirming the S3 `PUT`, the only durable copy of those bytes is the
local disk file. If the process crashes in that window, the write survives:
the `PENDING_WRITE` state is persisted in the on-disk `.meta` sidecar next to
the file (not a second in-memory-only queue), and on restart `CachedBlobStorage`
re-scans the cache directory and re-enqueues every still-pending upload for
retry. What does **not** survive is the local disk itself being lost (disk
failure, volume deletion) before the upload confirms -- for a proxy-cached
artifact this is self-healing (the next request re-fetches from upstream),
but for a hosted-mode upload it is a genuine, if narrow, durability gap.
Repositories that cannot accept this window at all should set
`cache.write-through: true`.

**Backpressure.** When the write-back queue is at `write-back-queue-capacity`,
a further `save()` is rejected immediately -- before any byte reaches disk, so
the local disk cache cannot grow unbounded under sustained backpressure. For a
proxy cache fill this is invisible to the client (the client already received
its bytes; the cache write for that key is skipped and logged, and the next
request for that key simply re-fetches from upstream). For a hosted-mode
upload (where the `save()` *is* the client's request), this surfaces as
`503 Service Unavailable` with a `Retry-After` header at the routes that map
it (see the REST API reference for exactly which upload routes do today).

### Eviction & Admission Control (`cache.max-disk-bytes`)

Only meaningful under `cache.mode: index` (WS1.4). Keeps the local disk cache
bounded without ever `Files.walk`-ing the directory to find out how full it
is: an in-memory running byte counter, updated incrementally on every
write/evict/remove, answers "how many bytes are cached right now" instantly.

```yaml
cache:
  enabled: true
  mode: index
  path: /var/pantera/cache/s3
  max-disk-bytes: 10737418240            # 10 GiB
  eviction-high-watermark-percent: 90     # proactive eviction starts at 90% full
  eviction-low-watermark-percent: 80      # proactive eviction stops at 80% full
  eviction-policy: LRU                    # LRU or LFU
```

| Key | Default | Description |
|-----|---------|-------------|
| `max-disk-bytes` | `10737418240` (10 GiB) | Hard bound on total disk-cache bytes. A `save()` that would cross it evicts synchronously first; if it still can't fit (e.g. the content itself exceeds the bound, or everything else is pinned `PENDING_WRITE`) the write is rejected. `<= 0` disables eviction/admission entirely (unbounded). |
| `eviction-high-watermark-percent` | `90` | Percentage of `max-disk-bytes` at which a write proactively triggers eviction (ahead of the hard bound). |
| `eviction-low-watermark-percent` | `80` | Percentage of `max-disk-bytes` eviction works down toward once triggered -- evicting further than the immediate write needs, so the cache isn't evicting on almost every subsequent write. |
| `eviction-policy` | `LRU` | `LRU` (least recently used) or `LFU` (least frequently used) -- same vocabulary and semantics as `cache.mode: disk`'s `eviction-policy`. |

**Hard bound, not just a watermark.** Even if eviction fails to free enough
space (every other entry happens to be `PENDING_WRITE`), the hard
`max-disk-bytes` check runs on every single write and rejects rather than
silently exceeding the bound -- the disk directory never grows past
`max-disk-bytes`, checked at every write, not just at a periodic sweep.

**`PENDING_WRITE` is never evicted.** An entry with an unconfirmed write-back
upload is the ONLY durable copy of those bytes (see
[Write-Back](#write-back-async-durable-writes-cachewrite-through) above) --
eviction always skips it, regardless of how cold it is or how full the cache
gets. A repository under sustained write pressure with a very small
`max-disk-bytes` and a large in-flight write-back queue can therefore see
disk usage stay above the low watermark indefinitely (bounded from above only
by `max-disk-bytes` itself, via the hard-bound rejection) until uploads drain.

**Coldness signal (`hits`/`lastAccess`) survives a restart, approximately.**
Every index entry tracks how many times it has been read and when it was last
read, driving the LRU/LFU comparison. These are persisted in the same
per-file `.meta` sidecar as everything else, but only refreshed at the points
a sidecar is already written for another reason (a confirmed write, or a
write-back upload confirming) -- NOT on every read. A restart therefore
recovers each entry's coldness signal "as of its last write", not "as of its
last read"; a background per-read sidecar rewrite was deliberately avoided
because it can race a concurrent reader of the same sidecar file (most
notably another node's or another restart's boot-time disk scan). In
practice this means freshly-restarted eviction ordering is a reasonable
approximation, converging back to precise ordering as the process runs.

### Sharded Cache Directories

`cache.mode: index` shards its disk cache directory: each cached file lives
under a 2-level hex fan-out directory (e.g. `a1/b2/<encoded-key>`) derived
from a hash of the artifact key, not the artifact key's own path segments.
This keeps any single directory from growing unbounded regardless of how flat
a format's own key naming is (many generic/npm uploads, for instance, can
share a shallow prefix). This is purely an on-disk layout detail: no config
key controls it, and it has no effect on any API behaviour -- only on what you
see if you browse `cache.path` directly. The mapping is deterministic and
reconstructed on every boot from the encoded file name alone (the hash-derived
directory levels are write-only fan-out, never consulted on read).

**Known limitation:** a percent-encoded key can, in principle, exceed a
filesystem's typical single-filename length limit (~255 bytes) for an
unusually long logical key. This mirrors a pre-existing limitation of
`cache.mode: disk` for deeply nested keys and is not specially handled.

### Cross-Node Coherence (WS1.5)

`cache.mode: index` trusts a disk-cached entry for `freshness-ttl-millis`
without re-validating against S3 (see
[Index Cache Mode](#index-cache-mode-cachemode-index) above) -- WS1.5 is what
makes that safe across a multi-node cluster instead of only within one
process: on a write-through or write-back commit, and on a delete, the owning
node publishes an invalidation (`key` + content digest + commit time) over a
new `storage` pub/sub channel, reusing the SAME cross-instance bus (Valkey
pub/sub) that already carries auth/filters/policy-settings invalidation. A
peer node that has that key cached locally drops its disk+index entry on
receipt, so its NEXT access re-resolves the key with a fresh S3 fetch instead
of serving what it now knows is stale. `freshness-ttl-millis` remains the
backstop for the window before a message arrives, or if a message never
arrives at all (e.g. a Valkey outage) -- it does not disappear, its role
narrows to "worst case", not "only case".

**No configuration required.** There is no `cache.*` key for this -- it is
wired in automatically wherever `cache.mode: index` is active AND clustering
(Valkey) is configured, the same condition that already enables the existing
auth/filters/policy cross-instance invalidation. A single-instance deployment
(no Valkey) gets a no-op bus: behaviour is identical to a pre-WS1.5 index
cache, because there are no peers to notify or be notified by.

**What is published, and when:**

| Trigger | Published? | Notes |
|---|---|---|
| Write-through `save()` confirms an S3 `PUT` | Yes | Content digest computed on the write is carried as the version marker. |
| Write-back upload confirms an S3 `PUT` (async) | Yes | Same as above, published once the background uploader confirms `PRESENT`, not when `save()` itself returns. |
| `delete()` that actually removed a durably-confirmed (`PRESENT`) key | Yes | A tombstone (no digest). |
| `delete()` of a key that was still `PENDING_WRITE` locally | No | Never confirmed anywhere else, so there is nothing to invalidate on a peer. |
| Local WS1.4 eviction (disk-cache housekeeping) | No | The object is unchanged in S3; only THIS node's local disk cache shrank. Not a coherence event. |

**Two races handled explicitly, so an operator never sees a spurious
eviction or a corrupted in-flight upload:**

- **A node's own in-flight write-back upload is never touched by a peer
  message.** While a key is `PENDING_WRITE` (upload not yet confirmed), an
  invalidation for that same key from ANY peer is ignored unconditionally --
  the local disk file is the only durable copy of those bytes until the
  upload confirms, and the background uploader thread is reading that exact
  file. Dropping it out from under an in-flight upload would corrupt it.
- **A delayed/reordered message can never evict a newer local write.** Every
  invalidation carries the commit time the publishing node recorded for it;
  a receiver ignores any message whose commit time is not strictly after its
  OWN local entry's last write. This is what stops a network-delayed message
  from a node's own earlier (now-superseded) write from wrongly evicting a
  node's own more recent one.

Multiple repositories with `cache.mode: index` in the same process share ONE
`storage` channel; each repository's messages carry its own cache-directory
path as a namespace tag, so a repository never reacts to another
repository's traffic even though they are multiplexed together.

---

## S3 Express One Zone (type: s3-express)

S3 Express One Zone provides single-digit millisecond read latency for frequently accessed data. Uses the same configuration keys as `s3` -- `s3-express` is implemented as a thin extension of the `s3` factory that only changes two defaults: `path-style` defaults to `false` (S3 Express One Zone requires virtual-hosted-style access) and `storage-class` defaults to `EXPRESS_ONEZONE`. Both remain overridable if you ever need to.

```yaml
storage:
  type: s3-express
  bucket: my-express-bucket--euw1-az1--x-s3
  region: eu-west-1
```

S3 Express buckets use directory bucket naming (suffix `--<az>--x-s3`). All standard S3 features (multipart, parallel download, encryption) are supported.

---

## S3-API-Compatible Object Stores

The `s3` storage type talks to any service that speaks the S3 API, not just AWS S3 -- set a custom `endpoint`, the matching `path-style` setting, and backend-appropriate `credentials`. No separate storage type or code path is needed; internally this is the same `BlobStore` reference implementation (`S3Storage`) that backs the `type: s3` factory.

**MinIO** (path-style required):

```yaml
storage:
  type: s3
  bucket: artifacts
  region: us-east-1
  endpoint: http://minio:9000
  path-style: true
  credentials:
    type: basic
    accessKeyId: minioadmin
    secretAccessKey: minioadmin
```

**Cloudflare R2** (virtual-hosted style, account-scoped endpoint):

```yaml
storage:
  type: s3
  bucket: artifacts
  region: auto
  endpoint: https://<account-id>.r2.cloudflarestorage.com
  path-style: false
  credentials:
    type: basic
    accessKeyId: <r2-access-key-id>
    secretAccessKey: <r2-secret-access-key>
```

**Backblaze B2** (S3-compatible endpoint, path-style required):

```yaml
storage:
  type: s3
  bucket: artifacts
  region: us-west-002
  endpoint: https://s3.us-west-002.backblazeb2.com
  path-style: true
  credentials:
    type: basic
    accessKeyId: <b2-key-id>
    secretAccessKey: <b2-application-key>
```

**Wasabi** (path-style required):

```yaml
storage:
  type: s3
  bucket: artifacts
  region: eu-central-1
  endpoint: https://s3.eu-central-1.wasabisys.com
  path-style: true
  credentials:
    type: basic
    accessKeyId: <wasabi-access-key>
    secretAccessKey: <wasabi-secret-key>
```

**Ceph / RADOS Gateway** (path-style required):

```yaml
storage:
  type: s3
  bucket: artifacts
  region: default
  endpoint: https://rgw.internal.example.com
  path-style: true
  credentials:
    type: basic
    accessKeyId: <radosgw-access-key>
    secretAccessKey: <radosgw-secret-key>
```

**Google Cloud Storage** (via its S3 interoperability endpoint):

```yaml
storage:
  type: s3
  bucket: artifacts
  region: auto
  endpoint: https://storage.googleapis.com
  path-style: false
  credentials:
    type: basic
    accessKeyId: <gcs-hmac-access-key>
    secretAccessKey: <gcs-hmac-secret>
```

`path-style: true` is required for MinIO, Backblaze B2, Wasabi, and Ceph/RADOS Gateway (services that do not support virtual-hosted-style URLs). Cloudflare R2 and GCS's S3-interop endpoint support virtual-hosted style. Consult each provider's docs to confirm; when in doubt, `path-style: true` is the safer default and works everywhere.

Native (non-S3-API) backends -- Google Cloud Storage's own API and Azure Blob Storage -- are a separate, later addition and are not yet available; the S3-interoperability path above is the supported way to use GCS today.

---

## Storage Aliases

Storage aliases let you define named storage configurations once and reference them by name in repository files. This avoids repeating full S3 configurations in every repository.

### Defining Aliases

Aliases are stored in `_storages.yaml` under the meta storage path:

```yaml
# /var/pantera/repo/_storages.yaml
storages:
  default:
    type: fs
    path: /var/pantera/data

  s3-prod:
    type: s3
    bucket: pantera-artifacts
    region: eu-west-1
    credentials:
      type: assume-role
      roleArn: arn:aws:iam::123456789012:role/PanteraRole

  s3-express:
    type: s3-express
    bucket: my-express-bucket--euw1-az1--x-s3
    region: eu-west-1
```

### Using Aliases in Repository Files

Reference an alias by name instead of inlining the full storage configuration:

```yaml
# my-maven.yaml
repo:
  type: maven
  storage: default
```

```yaml
# maven-central.yaml
repo:
  type: maven-proxy
  storage: s3-prod
  remotes:
    - url: https://repo1.maven.org/maven2
```

### Managing Aliases via REST API

Aliases can also be managed via the REST API:

```bash
# List aliases
curl http://pantera-host:8086/api/v1/storages \
  -H "Authorization: Bearer $TOKEN"

# Create an alias
curl -X PUT http://pantera-host:8086/api/v1/storages/s3-prod \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"s3","bucket":"pantera-artifacts","region":"eu-west-1"}'

# Delete an alias (fails if repositories reference it)
curl -X DELETE http://pantera-host:8086/api/v1/storages/old-alias \
  -H "Authorization: Bearer $TOKEN"
```

See the [REST API Reference](../rest-api-reference.md#7-storage-alias-management) for the full storage alias API.

---

## Choosing a Storage Backend

| Factor | Filesystem | S3 |
|--------|-----------|-----|
| Setup complexity | Low | Medium |
| HA support | No (node-local) | Yes (shared bucket) |
| Cost | Disk only | S3 API + storage costs |
| Latency | Sub-millisecond | 10-50ms (with disk cache: sub-ms for hot data) |
| Durability | Depends on disk/RAID | 99.999999999% (11 nines) |
| Scalability | Limited by disk | Virtually unlimited |

**Recommendation:** Use S3 for production deployments, especially in HA configurations. Use filesystem for development, testing, and single-node deployments.

---

## Related Pages

- [Configuration Reference](../configuration-reference.md#3-storage-configuration) -- Complete storage key reference
- [Configuration Reference](../configuration-reference.md#4-storage-aliases-_storagesyaml) -- Storage alias format
- [High Availability](high-availability.md) -- S3 shared storage in HA deployments
- [Performance Tuning](performance-tuning.md) -- S3 tuning recommendations
- [REST API Reference](../rest-api-reference.md#7-storage-alias-management) -- Storage alias API
