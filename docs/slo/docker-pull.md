# SLO: docker-pull

| Metric | Target |
|--------|--------|
| Availability | 99.9% (28-day rolling) |
| p50 latency | 40ms |
| p95 latency | 150ms |
| p99 latency | 400ms |
| Error budget (28d) | ~40 min |

## Burn-rate alerts
- Fast (5m/1h): consuming 14d budget in 1h -> page
- Slow (6h/1d): consuming 7d budget in 6h -> ticket

## Measurement
- Source: Prometheus `pantera_http_request_duration_seconds{repo="docker-pull"}`
- Window: 28-day rolling
