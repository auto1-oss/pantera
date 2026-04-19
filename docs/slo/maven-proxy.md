# SLO: maven-proxy

| Metric | Target |
|--------|--------|
| Availability | 99.9% (28-day rolling) |
| p50 latency | 25ms |
| p95 latency | 100ms |
| p99 latency | 250ms |
| Error budget (28d) | ~40 min |

## Burn-rate alerts
- Fast (5m/1h): consuming 14d budget in 1h -> page
- Slow (6h/1d): consuming 7d budget in 6h -> ticket

## Measurement
- Source: Prometheus `pantera_http_request_duration_seconds{repo="maven-proxy"}`
- Window: 28-day rolling
