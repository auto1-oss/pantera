# SLO: maven-group

| Metric | Target |
|--------|--------|
| Availability | 99.9% (28-day rolling) |
| p50 latency | 35ms |
| p95 latency | 140ms |
| p99 latency | 350ms |
| Error budget (28d) | ~40 min |

## Burn-rate alerts
- Fast (5m/1h): consuming 14d budget in 1h -> page
- Slow (6h/1d): consuming 7d budget in 6h -> ticket

## Measurement
- Source: Prometheus `pantera_http_request_duration_seconds{repo="maven-group"}`
- Window: 28-day rolling
