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

---

## S3 Express One Zone (type: s3-express)

S3 Express One Zone provides single-digit millisecond read latency for frequently accessed data. Uses the same configuration keys as `s3`.

```yaml
storage:
  type: s3-express
  bucket: my-express-bucket--euw1-az1--x-s3
  region: eu-west-1
```

S3 Express buckets use directory bucket naming (suffix `--<az>--x-s3`). All standard S3 features (multipart, parallel download, encryption) are supported.

---

## MinIO / S3-Compatible Storage

Use the S3 storage type with `endpoint` and `path-style: true` for S3-compatible services like MinIO, Ceph, or LocalStack.

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

The `path-style: true` setting is required for MinIO and most S3-compatible services that do not support virtual-hosted-style URLs.

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
