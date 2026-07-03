# SLO: file-raw

| Metric | Target |
|--------|--------|
| Availability | 99.95% (28-day rolling) |
| p50 latency | 10ms |
| p95 latency | 40ms |
| p99 latency | 100ms |
| Error budget (28d) | ~20 min |

## Burn-rate alerts
- Fast (5m/1h): consuming 14d budget in 1h -> page
- Slow (6h/1d): consuming 7d budget in 6h -> ticket

## Measurement
- Source: Prometheus `pantera_http_request_duration_seconds{repo="file-raw"}`
- Window: 28-day rolling
