# S3 Storage Configuration

Artipie provides optimized S3 storage with configurable multipart uploads, parallel downloads, checksums, encryption, and local disk caching.

## Quick Start

```yaml
meta:
  storage:
    type: s3
    bucket: my-artipie-bucket
    region: eu-west-1

    # Multipart upload settings
    multipart-min-size: 32MB
    part-size: 8MB
    multipart-concurrency: 16

    # Parallel download settings
    parallel-download: true
    parallel-download-min-size: 64MB
    parallel-download-chunk-size: 8MB
    parallel-download-concurrency: 8
```

## Configuration Reference

### Basic Settings

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `bucket` | string | required | S3 bucket name |
| `region` | string | - | AWS region (e.g., `eu-west-1`) |
| `endpoint` | string | - | Custom S3 endpoint for S3-compatible services |
| `path-style` | boolean | true | Use path-style access (bucket in path, not subdomain) |
| `dualstack` | boolean | false | Enable IPv4/IPv6 dualstack endpoint |

### Multipart Upload

Multipart upload splits large files into parts uploaded concurrently, improving throughput and reliability.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `multipart` | boolean | true | Enable multipart uploads |
| `multipart-min-size` | size | 32MB | Files larger than this use multipart |
| `part-size` | size | 8MB | Size of each upload part |
| `multipart-concurrency` | int | 16 | Concurrent part uploads |
| `checksum` | string | SHA256 | Checksum algorithm: SHA256, CRC32, SHA1 |

**Size Format**: Supports human-readable sizes: `8MB`, `32MB`, `1GB`, `512KB`

### Parallel Download

Parallel download fetches large files in concurrent chunks for faster retrieval.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `parallel-download` | boolean | false | Enable parallel downloads |
| `parallel-download-min-size` | size | 64MB | Files larger than this use parallel download |
| `parallel-download-chunk-size` | size | 8MB | Size of each download chunk |
| `parallel-download-concurrency` | int | 8 | Concurrent download threads |

### Server-Side Encryption

| Parameter | Type | Description |
|-----------|------|-------------|
| `sse.type` | string | Encryption type: `AES256` or `KMS` |
| `sse.kms-key-id` | string | KMS key ID (required if type=KMS) |

```yaml
storage:
  type: s3
  bucket: secure-artifacts
  sse:
    type: KMS
    kms-key-id: arn:aws:kms:eu-west-1:123456789:key/abc-123
```

### HTTP Client Tuning

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `http.max-concurrency` | int | 1024 | Max concurrent HTTP connections |
| `http.max-pending-acquires` | int | 2048 | Max pending connection acquisitions |
| `http.acquisition-timeout-millis` | int | 30000 | Connection acquisition timeout (ms) |
| `http.read-timeout-millis` | int | 120000 | Read timeout (ms) |
| `http.write-timeout-millis` | int | 120000 | Write timeout (ms) |
| `http.connection-max-idle-millis` | int | 30000 | Max idle time before closing (ms) |

### Credentials

```yaml
storage:
  type: s3
  bucket: my-bucket
  credentials:
    type: default  # Uses AWS SDK default credential chain
```

**Credential Types**:

- `default`: AWS SDK default chain (env vars, instance profile, etc.)
- `basic`: Access key and secret
- `profile`: Named AWS profile
- `assume-role`: STS assume role

```yaml
# Basic credentials
credentials:
  type: basic
  accessKeyId: AKIAIOSFODNN7EXAMPLE
  secretAccessKey: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY

# Profile credentials
credentials:
  type: profile
  profile: production

# Assume role
credentials:
  type: assume-role
  roleArn: arn:aws:iam::123456789:role/ArtipieRole
  sessionName: artipie-session
  externalId: external-id-if-required
```

### Disk Cache

Local disk cache reduces S3 API calls and improves latency for frequently accessed artifacts.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `cache.enabled` | boolean | false | Enable disk caching |
| `cache.path` | string | required | Cache directory path |
| `cache.max-bytes` | size | 10GB | Maximum cache size |
| `cache.high-watermark-percent` | int | 90 | Start cleanup at this % full |
| `cache.low-watermark-percent` | int | 80 | Cleanup until this % full |
| `cache.cleanup-interval-millis` | int | 300000 | Cleanup check interval (ms) |
| `cache.eviction-policy` | string | LRU | Eviction policy: LRU or LFU |
| `cache.validate-on-read` | boolean | true | Validate checksums on cache reads |

```yaml
storage:
  type: s3
  bucket: my-bucket
  cache:
    enabled: true
    path: /var/artipie/cache/s3
    max-bytes: 50GB
    eviction-policy: LRU
```

## S3 Express One Zone

For ultra-low latency workloads, use S3 Express One Zone storage class:

```yaml
storage:
  type: s3-express
  bucket: my-express-bucket--use1-az1--x-s3
  region: us-east-1
```

S3 Express provides ~10x lower latency than S3 Standard but is limited to a single availability zone.

## Configuration Examples

### High-Throughput Maven Proxy

```yaml
repo:
  type: maven-proxy
  storage:
    type: s3
    bucket: maven-cache
    region: eu-west-1

    # Large artifacts benefit from multipart
    multipart-min-size: 16MB
    part-size: 16MB
    multipart-concurrency: 32

    # Enable parallel downloads
    parallel-download: true
    parallel-download-min-size: 32MB
    parallel-download-chunk-size: 16MB
    parallel-download-concurrency: 16

    # Tune HTTP client for high concurrency
    http:
      max-concurrency: 2048
      max-pending-acquires: 4096
      read-timeout-millis: 180000
```

### Cost-Optimized with Disk Cache

```yaml
repo:
  type: npm-proxy
  storage:
    type: s3
    bucket: npm-artifacts
    region: eu-west-1

    # Local cache reduces S3 requests
    cache:
      enabled: true
      path: /var/artipie/cache/npm
      max-bytes: 100GB
      high-watermark-percent: 85
      low-watermark-percent: 70
```

### Encrypted Storage

```yaml
repo:
  type: docker
  storage:
    type: s3
    bucket: docker-images
    region: eu-west-1

    # SSE-KMS encryption
    sse:
      type: KMS
      kms-key-id: alias/artipie-key

    # Integrity validation
    checksum: SHA256
```

### S3-Compatible Storage (MinIO)

```yaml
storage:
  type: s3
  bucket: artipie
  endpoint: http://minio:9000
  path-style: true
  credentials:
    type: basic
    accessKeyId: minioadmin
    secretAccessKey: minioadmin
```

## Architecture

### Upload Flow

```
Content arrives
    |
    v
Size estimation
    |
    +-- Size < multipart-min-size --> Single PUT request
    |
    +-- Size >= multipart-min-size
            |
            v
        CreateMultipartUpload
            |
            v
        Split into parts (part-size each)
            |
            v
        Upload parts concurrently (multipart-concurrency limit)
            |
            +-- All parts succeed --> CompleteMultipartUpload
            |
            +-- Any part fails --> AbortMultipartUpload
```

### Download Flow

```
GetObject request
    |
    v
Check disk cache (if enabled)
    |
    +-- Cache hit --> Return cached content
    |
    +-- Cache miss
            |
            v
        Check file size via HEAD
            |
            +-- Size < parallel-download-min-size --> Single GET
            |
            +-- Size >= parallel-download-min-size
                    |
                    v
                Range requests in parallel (parallel-download-concurrency)
                    |
                    v
                Merge chunks
                    |
                    v
                Populate cache (if enabled)
```

## Performance Tuning

### Memory Optimization

The S3 storage uses streaming to minimize memory usage:

- **No buffering**: Content streams directly to S3 without full buffering
- **Chunk-based processing**: Large files processed in configurable chunks
- **Concurrent limits**: Prevent memory exhaustion from too many parallel operations

### Recommended Settings by Workload

| Workload | multipart-min-size | part-size | concurrency |
|----------|-------------------|-----------|-------------|
| Small files (npm, PyPI) | 32MB | 8MB | 16 |
| Medium files (Maven) | 16MB | 16MB | 32 |
| Large files (Docker) | 64MB | 32MB | 16 |
| Very large files | 128MB | 64MB | 8 |

### Network Considerations

- **High latency**: Increase concurrency, increase part size
- **Low bandwidth**: Decrease concurrency, decrease part size
- **High bandwidth**: Increase concurrency, use parallel downloads

## Code References

| File | Purpose |
|------|---------|
| [S3Storage.java](../../asto/asto-s3/src/main/java/com/artipie/asto/s3/S3Storage.java) | Main storage implementation |
| [S3StorageFactory.java](../../asto/asto-s3/src/main/java/com/artipie/asto/s3/S3StorageFactory.java) | Configuration and instantiation |
| [MultipartUpload.java](../../asto/asto-s3/src/main/java/com/artipie/asto/s3/MultipartUpload.java) | Multipart upload orchestration |
| [S3ExpressStorageFactory.java](../../asto/asto-s3/src/main/java/com/artipie/asto/s3/S3ExpressStorageFactory.java) | S3 Express One Zone support |
