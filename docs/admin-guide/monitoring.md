# Monitoring

> **Guide:** Admin Guide | **Section:** Monitoring

Pantera exposes Prometheus-compatible metrics, lightweight health checks on both ports, and integrates with Grafana for dashboards. This page covers metrics configuration, key metrics to monitor, health check endpoints, and alerting recommendations.

---

## Prometheus Configuration

### Enable Metrics in pantera.yml

```yaml
meta:
  metrics:
    endpoint: /metrics/vertx
    port: 8087
    types:
      - jvm       # Heap usage, GC, threads, classloader
      - storage    # Storage operation counts and latency
      - http       # HTTP request/response metrics
```

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `endpoint` | string | Yes | URL path for metrics scraping (must start with `/`) |
| `port` | int | Yes | Dedicated metrics port |
| `types` | list | No | Metric categories to enable: `jvm`, `storage`, `http` |

### Prometheus Scrape Configuration

Add the following to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'pantera'
    metrics_path: '/metrics/vertx'
    scrape_interval: 15s
    static_configs:
      - targets: ['pantera:8087']
```

For HA deployments with multiple nodes:

```yaml
scrape_configs:
  - job_name: 'pantera'
    metrics_path: '/metrics/vertx'
    scrape_interval: 15s
    static_configs:
      - targets:
          - 'pantera-1:8087'
          - 'pantera-2:8087'
          - 'pantera-3:8087'
```

---

## Key Metrics

### Thread Pool Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `pantera.pool.read.active` | Gauge | Active threads in READ pool |
| `pantera.pool.write.active` | Gauge | Active threads in WRITE pool |
| `pantera.pool.list.active` | Gauge | Active threads in LIST pool |
| `pantera.pool.read.queue` | Gauge | Queue depth of READ pool |
| `pantera.pool.write.queue` | Gauge | Queue depth of WRITE pool |
| `pantera.pool.list.queue` | Gauge | Queue depth of LIST pool |

### JVM Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `jvm_memory_used_bytes` | Gauge | JVM memory usage by area (heap, non-heap) |
| `jvm_memory_max_bytes` | Gauge | Maximum memory available |
| `jvm_gc_pause_seconds` | Summary | GC pause durations |
| `jvm_threads_live_threads` | Gauge | Live thread count |
| `jvm_threads_peak_threads` | Gauge | Peak thread count |

### HTTP Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `http_server_requests_seconds` | Timer | HTTP request latency distribution |
| `vertx_http_server_active_connections` | Gauge | Current active HTTP connections |

### Database Metrics (HikariCP)

| Metric | Type | Description |
|--------|------|-------------|
| `hikaricp_connections_active` | Gauge | Active database connections |
| `hikaricp_connections_idle` | Gauge | Idle database connections |
| `hikaricp_connections_pending` | Gauge | Threads waiting for a connection |
| `hikaricp_connections_max` | Gauge | Maximum pool size |

---

## Grafana Dashboards

The Docker Compose stack includes pre-configured Grafana with dashboards for:

- JVM memory and GC metrics
- HTTP request rates and latency
- Storage operation throughput
- Thread pool utilization
- Database connection pool status

Access Grafana at `http://pantera-host:3000` (default credentials from `.env`).

### Custom Dashboards

Import the Pantera dashboard JSON or create custom panels using the metrics above. Recommended panels:

| Panel | Metrics | Visualization |
|-------|---------|---------------|
| Request Rate | `rate(http_server_requests_seconds_count[5m])` | Time series |
| Request Latency (p99) | `histogram_quantile(0.99, http_server_requests_seconds_bucket)` | Time series |
| Pool Queue Depth | `pantera.pool.{read,write,list}.queue` | Time series |
| Heap Usage | `jvm_memory_used_bytes{area="heap"}` | Gauge |
| DB Pool Utilization | `hikaricp_connections_active / hikaricp_connections_max` | Gauge |
| GC Pause Time | `rate(jvm_gc_pause_seconds_sum[5m])` | Time series |

---

## Health Checks

Pantera provides lightweight health endpoints on both the repository port and the API port. Both are suitable for load balancer and orchestrator health probes.

### Repository Port Health Check

```bash
curl http://pantera-host:8080/.health
# {"status":"ok"}
```

- **Port:** 8080
- **Authentication:** None
- **Behavior:** Returns HTTP 200 immediately. No I/O, no probes, no blocking. Returns OK as long as the JVM is running and the Vert.x event loop is responsive.

### API Port Health Check

```bash
curl http://pantera-host:8086/api/v1/health
# {"status":"ok"}
```

- **Port:** 8086
- **Authentication:** None
- **Behavior:** Returns HTTP 200. Same lightweight check.

### Version Endpoint

```bash
curl http://pantera-host:8080/.version
# [{"version":"2.0.0"}]
```

### Health Check Usage

| Environment | Endpoint | Interval |
|-------------|----------|----------|
| Docker Compose | `GET /.health` on port 8080 | 10s |
| Kubernetes liveness | `GET /.health` on port 8080 | 15s |
| Kubernetes readiness | `GET /api/v1/health` on port 8086 | 10s |
| Load balancer (NLB/ALB) | `GET /.health` on port 8080 | 10s |

---

## Alerting Recommendations

The following alert rules are recommended for production Pantera deployments. Adapt thresholds to your workload.

### Critical Alerts

| Alert | Condition | Description |
|-------|-----------|-------------|
| Instance Down | `up{job="pantera"} == 0` for 2m | Pantera instance is not responding to Prometheus scrapes |
| High Heap Usage | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.9` for 5m | Heap is above 90% -- risk of OOM |
| DB Pool Exhaustion | `hikaricp_connections_pending > 10` for 2m | Database connection pool is saturated |
| Health Check Failing | Probe to `/.health` returns non-200 for 30s | Instance is unresponsive |

### Warning Alerts

| Alert | Condition | Description |
|-------|-----------|-------------|
| Read Pool Saturated | `pantera.pool.read.queue > 100` for 5m | Read thread pool is backlogged; increase `PANTERA_IO_READ_THREADS` |
| Write Pool Saturated | `pantera.pool.write.queue > 50` for 5m | Write thread pool is backlogged; increase `PANTERA_IO_WRITE_THREADS` |
| High GC Pause | `rate(jvm_gc_pause_seconds_sum[5m]) > 0.1` | GC is consuming more than 10% of time |
| Elevated Error Rate | `rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 1` | More than 1 server error per second |
| High Request Latency | `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])) > 10` | p99 latency exceeds 10 seconds |

### Informational Alerts

| Alert | Condition | Description |
|-------|-----------|-------------|
| Cooldown Blocks High | Cooldown blocked count > 100 (via API polling) | Many artifacts are being held by cooldown |
| Disk Cache Full | Disk cache usage approaching `max-bytes` | Consider increasing cache size |

---

## Metrics Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_METRICS_MAX_REPOS` | `50` | Maximum distinct repository names in metrics labels |
| `PANTERA_METRICS_PERCENTILES_HISTOGRAM` | `false` | Enable percentile histograms (increases cardinality) |

See [Environment Variables](environment-variables.md) for the complete list.

---

## Related Pages

- [Configuration](configuration.md) -- meta.metrics section
- [Logging](logging.md) -- Structured logging for operational visibility
- [Performance Tuning](performance-tuning.md) -- Thread pool sizing based on metrics
- [High Availability](high-availability.md) -- Multi-node monitoring
- [Troubleshooting](troubleshooting.md) -- Using metrics to diagnose issues
