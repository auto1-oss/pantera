# Troubleshooting

> **Guide:** Admin Guide | **Section:** Troubleshooting

This page covers common problems encountered when operating Pantera, along with diagnostic steps and solutions.

---

## Diagnostic Tools

Before troubleshooting specific issues, familiarize yourself with these diagnostic tools.

### Log Inspection

```bash
# View recent logs
docker logs --tail 200 pantera

# Follow logs in real time
docker logs -f pantera

# Filter for errors
docker logs pantera 2>&1 | jq 'select(.["log.level"] == "ERROR")'

# Filter by event category
docker logs pantera 2>&1 | jq 'select(.["event.category"] == "authentication")'
```

### Health Checks

```bash
# Repository port
curl http://localhost:8080/.health

# API port
curl http://localhost:8086/api/v1/health

# Version
curl http://localhost:8080/.version
```

### Thread Dumps

```bash
# Using jattach (installed in the Docker image)
docker exec pantera jattach 1 threaddump

# Using jstack (if JDK is available)
docker exec pantera jstack 1 > /tmp/threaddump.txt
```

### Heap Dumps

```bash
# Generate heap dump on demand
docker exec pantera jattach 1 dumpheap /var/pantera/logs/dumps/heap.hprof

# Copy heap dump from container
docker cp pantera:/var/pantera/logs/dumps/heap.hprof ./heap.hprof
```

### Java Flight Recorder

```bash
# Start a 60-second recording
docker exec pantera jattach 1 jcmd \
  "JFR.start name=pantera duration=60s filename=/var/pantera/logs/pantera.jfr"

# Dump the recording
docker exec pantera jattach 1 jcmd \
  "JFR.dump name=pantera filename=/var/pantera/logs/pantera.jfr"
```

### Database Inspection

```bash
# Connect to the database
docker exec -it pantera-db psql -U pantera -d pantera

# Check repository count
SELECT count(*) FROM repositories;

# Check artifact count
SELECT count(*) FROM artifacts;

# Check Flyway migration status
SELECT version, description, installed_on, success
FROM flyway_schema_history ORDER BY installed_rank;

# Check active connections
SELECT count(*) FROM pg_stat_activity WHERE datname = 'pantera';
```

### Prometheus Metrics

```bash
# Check specific metrics
curl -s http://localhost:8087/metrics/vertx | grep pantera.pool
curl -s http://localhost:8087/metrics/vertx | grep hikaricp
curl -s http://localhost:8087/metrics/vertx | grep jvm_memory
```

---

## Common Issues

### "Connection refused" on Port 8080

**Symptoms:** `curl: (7) Failed to connect to localhost port 8080: Connection refused`

**Diagnosis:**

1. Verify the container is running:
   ```bash
   docker ps | grep pantera
   ```
2. Check container logs for startup errors:
   ```bash
   docker logs pantera
   ```
3. Verify port mapping:
   ```bash
   docker port pantera
   ```

**Common causes:**

| Cause | Solution |
|-------|----------|
| Container is not running | `docker start pantera` or check why it exited: `docker logs pantera` |
| pantera.yml is missing or invalid | Verify the file is mounted correctly at `/etc/pantera/pantera.yml` |
| Port conflict | Another process is using port 8080. Check with `lsof -i :8080` |
| Volume permission error | All volumes must be owned by UID 2021, GID 2020: `chown -R 2021:2020 /path/to/data` |
| Startup failure (migration error) | Check logs for Flyway or database errors |

---

### "UNAUTHORIZED" When Accessing Repositories

**Symptoms:** HTTP 401 responses when pushing or pulling artifacts.

**Diagnosis:**

```bash
# Check if the token is valid
curl http://localhost:8086/api/v1/auth/me \
  -H "Authorization: Bearer $TOKEN"

# Generate a fresh token
curl -X POST http://localhost:8086/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"name":"admin","pass":"changeme"}'
```

**Common causes:**

| Cause | Solution |
|-------|----------|
| JWT token expired | Generate a new token via `POST /api/v1/auth/token` |
| Username does not match token subject | For jwt-password auth, the username must match the `sub` claim |
| User lacks read permissions | Check user roles and permissions via API or YAML files |
| Keycloak/Okta `user-domains` mismatch | Verify the user's email domain matches the configured domains |
| Wrong JWT keys | All nodes in HA must use the same RS256 key pair (`meta.jwt.private-key-path`, `meta.jwt.public-key-path`). A token signed by one node must be verifiable by every other node. |
| Auth provider disabled | Check `GET /api/v1/auth/providers` for provider status |

---

### Proxy Repository Returns 502/504

**Symptoms:** Proxy repositories return HTTP 502 Bad Gateway or 504 Gateway Timeout.

**Diagnosis:**

```bash
# Test upstream connectivity from inside the container
docker exec pantera curl -I https://repo1.maven.org/maven2

# Check DNS resolution
docker exec pantera nslookup repo1.maven.org

# Check proxy timeout settings
docker logs pantera 2>&1 | jq 'select(.message | contains("timeout"))'
```

**Common causes:**

| Cause | Solution |
|-------|----------|
| Upstream unreachable | Verify network connectivity from the container |
| DNS resolution failure | Check `/etc/resolv.conf` inside the container; add explicit DNS servers |
| Proxy timeout too short | Increase `proxy_timeout` in `meta.http_client` (default: 120s) |
| Connection pool exhausted | Increase `max_connections_per_destination` or `connection_acquire_timeout` |
| Corporate firewall blocking | Configure HTTP proxy or whitelist upstream domains |
| SSL/TLS certificate issues | Ensure upstream certificates are trusted by the JVM |

---

### Out of Memory (OOM)

**Symptoms:** Container killed by OOM killer, `java.lang.OutOfMemoryError` in logs.

**Diagnosis:**

```bash
# Check if OOM killed the container
docker inspect pantera --format='{{.State.OOMKilled}}'

# Check heap dump (generated automatically on OOM)
ls -la /var/pantera/logs/dumps/

# Check current memory usage
docker stats pantera --no-stream
```

**Common causes:**

| Cause | Solution |
|-------|----------|
| Insufficient container memory | Increase to 6 GB minimum for production |
| Too many threads | Reduce `PANTERA_IO_READ_THREADS` and `PANTERA_IO_WRITE_THREADS` |
| Netty buffer leak | Set `-Dio.netty.leakDetection.level=advanced` temporarily to identify the source |
| Large artifact buffering | Check `PANTERA_BUF_ACCUMULATOR_MAX_BYTES` (default: 100 MB) |
| Unbounded response bodies | Ensure all proxy response bodies are consumed, even on error paths |

**Recovery:**

1. Increase container memory limit.
2. Copy the heap dump for analysis: `docker cp pantera:/var/pantera/logs/dumps/heapdump.hprof .`
3. Analyze with Eclipse MAT or VisualVM.
4. Restart the container.

---

### Slow Search Queries

**Symptoms:** Search API returns slowly or times out.

**Diagnosis:**

```bash
# Check search index stats
curl http://localhost:8086/api/v1/search/stats \
  -H "Authorization: Bearer $TOKEN"

# Check PostgreSQL query performance
docker exec -it pantera-db psql -U pantera -d pantera \
  -c "SELECT count(*) FROM artifacts;"

# Check for missing indexes
docker exec -it pantera-db psql -U pantera -d pantera \
  -c "SELECT indexname FROM pg_indexes WHERE tablename = 'artifacts';"
```

**Common causes:**

| Cause | Solution |
|-------|----------|
| PostgreSQL under-resourced | Increase CPU and memory for the database |
| Search index stale | Run `POST /api/v1/search/reindex` to rebuild |
| LIKE fallback timeout | Increase `PANTERA_SEARCH_LIKE_TIMEOUT_MS` (default: 3000 ms) |
| Deep pagination | Limit page depth; pages > 100 degrade performance |
| Missing indexes | Run `V104__performance_indexes.sql` or upgrade to apply it |

---

### Docker Push/Pull Fails with Large Images

**Symptoms:** Docker push or pull operations fail with timeout or connection reset errors for large images.

**Diagnosis:**

```bash
# Check Nginx configuration
docker exec nginx cat /etc/nginx/nginx.conf | grep client_max_body_size

# Check Pantera timeout settings
docker logs pantera 2>&1 | jq 'select(.message | contains("timeout"))'
```

**Common causes:**

| Cause | Solution |
|-------|----------|
| Nginx body size limit | Set `client_max_body_size 0;` in Nginx configuration |
| Nginx timeout | Increase `proxy_read_timeout` and `proxy_send_timeout` to 300s+ |
| S3 multipart disabled | Enable multipart upload in the storage configuration |
| Chunked transfer encoding blocked | Ensure the load balancer supports chunked transfer encoding |
| Request timeout | Increase `meta.http_server.request_timeout` |

---

### Cooldown Blocking Legitimate Artifacts

**Symptoms:** Builds fail because needed artifacts are blocked by cooldown.

**Resolution:**

```bash
# Check current cooldown config
curl http://localhost:8086/api/v1/cooldown/config \
  -H "Authorization: Bearer $TOKEN"

# View blocked artifacts
curl "http://localhost:8086/api/v1/cooldown/blocked?search=package-name" \
  -H "Authorization: Bearer $TOKEN"

# Unblock a specific artifact
curl -X POST http://localhost:8086/api/v1/repositories/npm-proxy/cooldown/unblock \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"artifact":"package-name","version":"1.0.0"}'

# Unblock all in a repository
curl -X POST http://localhost:8086/api/v1/repositories/npm-proxy/cooldown/unblock-all \
  -H "Authorization: Bearer $TOKEN"
```

To prevent future issues, consider reducing `minimum_allowed_age` or disabling cooldown per repo type.

---

### Valkey Connection Failures

**Symptoms:** Warnings about Valkey connection failures in logs. Cache invalidation not propagating across nodes.

**Diagnosis:**

```bash
# Verify Valkey is reachable
docker exec pantera nc -z valkey 6379

# Check Valkey status
docker exec valkey valkey-cli ping

# Check Valkey memory usage
docker exec valkey valkey-cli info memory
```

**Common causes:**

| Cause | Solution |
|-------|----------|
| Valkey not running | Start Valkey: `docker compose up -d valkey` |
| Network unreachable | Check Docker network connectivity |
| Timeout too short | Increase `meta.caches.valkey.timeout` (default: 100ms) |
| Memory limit exceeded | Increase Valkey memory or configure eviction policy |

**Impact:** Pantera operates normally without Valkey. It falls back to local Caffeine cache only. L2 cache and cross-node cache invalidation are disabled until Valkey reconnects.

---

### S3 "SlowDown" Errors

**Symptoms:** S3 storage operations return 503 SlowDown errors.

**Diagnosis:**

```bash
docker logs pantera 2>&1 | jq 'select(.message | contains("SlowDown"))'
```

**Common causes:**

| Cause | Solution |
|-------|----------|
| Too many concurrent S3 requests | Reduce `http.max-concurrency` in S3 storage config |
| S3 request rate limit hit | Use the disk cache to reduce S3 API calls |
| Bucket in a single partition | Use key prefixes that distribute across S3 partitions |

The AWS SDK has adaptive retry built in (enabled by default). For persistent SlowDown errors, enable the S3 disk cache to reduce API calls for hot artifacts.

---

### Startup Failure: "Table already exists"

**Symptoms:** Pantera fails to start with a Flyway migration error about existing tables.

**Cause:** The database was partially migrated or migrated outside of Flyway.

**Resolution:**

```bash
# Check Flyway history
docker exec -it pantera-db psql -U pantera -d pantera \
  -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank;"

# If the table exists but Flyway does not know about it, baseline:
# WARNING: Only do this if you are certain the schema is correct
docker exec -it pantera-db psql -U pantera -d pantera \
  -c "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) VALUES (1, '100', 'baseline', 'BASELINE', '<< Flyway Baseline >>', NULL, 'pantera', 0, true);"
```

---

### Negative Cache Returning Stale 404s

**Symptoms:** A newly published artifact returns 404 from a proxy repository even though it exists upstream.

**Cause:** The artifact was previously cached as "not found" in the negative cache.

**Resolution:**

1. Wait for the negative cache TTL to expire (default: 24 hours).
2. Restart the Pantera node to clear the L1 Caffeine cache.
3. If Valkey is configured, the L2 entry will also need to expire or be cleared.
4. To reduce future impact, lower the negative cache TTL in `meta.caches.negative.ttl`.

---

## Key Metrics for Troubleshooting

| Metric | Alert Threshold | Indicates |
|--------|----------------|-----------|
| `pantera.pool.read.queue` | > 100 | READ pool saturated |
| `pantera.pool.write.queue` | > 50 | WRITE pool saturated |
| `hikaricp_connections_pending` | > 10 | Database pool exhaustion |
| `jvm_memory_used_bytes{area="heap"}` | > 90% of max | OOM risk |
| `http_server_requests_seconds` (p99) | > 10s | Latency degradation |

---

## Related Pages

- [Logging](logging.md) -- Log configuration and filtering
- [Monitoring](monitoring.md) -- Metrics and health checks
- [Performance Tuning](performance-tuning.md) -- Resolving resource constraints
- [Cooldown](cooldown.md) -- Managing the cooldown system
- [High Availability](high-availability.md) -- Multi-node specific issues
