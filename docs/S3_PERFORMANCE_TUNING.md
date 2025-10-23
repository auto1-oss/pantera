# S3 Storage Performance Tuning Guide

## Critical Issues Fixed

### 1. Blocking in Parallel Downloads
**Fixed in S3Storage.java line 526** - Removed `.join()` that blocked threads during range requests.

### 2. Connection Pool Exhaustion

**Problem:** After ~1000 operations, connections are exhausted because:
- S3AsyncClient is never explicitly closed
- Default connection limits may be too low for high concurrency
- No connection idle timeout cleanup

**Solution:** Configure HTTP client properly in storage YAML:

```yaml
type: s3
bucket: my-bucket
region: us-east-1
credentials:
  type: basic
  accessKeyId: XXX
  secretAccessKey: YYY

# Critical: HTTP client configuration to prevent pool exhaustion
http:
  # Maximum concurrent requests (default: 2048)
  # Increase if you see "no pool" errors
  max-concurrency: 1024
  
  # Maximum pending connection requests (default: 4096)
  # Queue for connections waiting to be acquired
  max-pending-acquires: 2048
  
  # Connection acquisition timeout (default: 30000ms)
  # Fail fast if connections unavailable
  acquisition-timeout-millis: 10000
  
  # Read timeout for S3 responses (default: 60000ms)
  read-timeout-millis: 30000
  
  # Write timeout for S3 uploads (default: 60000ms)
  write-timeout-millis: 120000
  
  # Close idle connections after this time (default: 60000ms)
  # CRITICAL: Prevents connection pool exhaustion
  connection-max-idle-millis: 30000

# Multipart upload settings (for large files)
multipart: true
multipart-min-size: "32MB"
part-size: "8MB"
multipart-concurrency: 8

# Parallel download settings (for large files)
parallel-download: false  # Set to true only for very large files
parallel-download-min-size: "64MB"
parallel-download-chunk-size: "8MB"
parallel-download-concurrency: 4

# Optional: Local disk cache to reduce S3 calls
cache:
  enabled: true
  path: /tmp/artipie-s3-cache
  max-bytes: 10GB
  high-watermark-percent: 90
  low-watermark-percent: 80
  cleanup-interval-millis: 300000  # 5 minutes
  validate-on-read: true
  eviction-policy: LRU
```

## Performance Tuning Guidelines

### For High-Concurrency Workloads (1000+ req/s)
```yaml
http:
  max-concurrency: 2048
  max-pending-acquires: 4096
  acquisition-timeout-millis: 15000
  connection-max-idle-millis: 20000
```

### For Memory-Constrained Environments
```yaml
http:
  max-concurrency: 512
  max-pending-acquires: 1024
  acquisition-timeout-millis: 5000
  connection-max-idle-millis: 15000

multipart-concurrency: 4
parallel-download: false
```

### For Slow/Unreliable Networks
```yaml
http:
  read-timeout-millis: 120000
  write-timeout-millis: 300000
  acquisition-timeout-millis: 30000
```

## Monitoring

### Symptoms of Connection Pool Exhaustion
- Logs: "Failed to acquire connection" or "no pool"
- Server stops responding after N operations
- Increasing response times
- High memory usage

### Symptoms of Blocking Issues  
- Thread pool exhaustion
- Requests timing out
- CPU usage stays low but throughput is poor

### Recommended Monitoring
```bash
# Watch for S3 connection errors
docker logs artipie 2>&1 | grep -i "pool\|connection\|timeout"

# Monitor memory usage
docker stats artipie

# Check thread count
docker exec artipie jstack 1 | grep -c "Thread"
```

## Migration from Default Configuration

If you're experiencing issues with existing setup:

1. **Add `connection-max-idle-millis: 30000`** (forces connection cleanup)
2. **Reduce `max-concurrency` if memory is limited**
3. **Enable disk cache** to reduce S3 API calls
4. **Monitor logs** for "pool exhausted" errors
5. **Restart Artipie** after configuration changes

## Advanced: JVM Tuning

For very high-load scenarios, tune JVM:
```bash
# Increase heap for caching
JAVA_OPTS="-Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Increase direct memory for Netty
-XX:MaxDirectMemorySize=1g
```

## Known Limitations

1. **No graceful shutdown**: S3AsyncClient connections are not explicitly closed on shutdown
2. **Memory growth**: Without `connection-max-idle-millis`, connections accumulate
3. **Parallel downloads blocking** (FIXED): Was blocking threads in range requests

## Testing Recommendations

```bash
# Test with load
for i in {1..2000}; do
  curl -u user:pass -X GET http://localhost:8080/repo/package.tar.gz &
done
wait

# Monitor for errors
docker logs artipie 2>&1 | grep -E "ERROR|pool|connection"
```
