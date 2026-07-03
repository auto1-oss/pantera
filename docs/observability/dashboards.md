# Grafana Dashboards

Pantera ships pre-built Grafana 10+ dashboards as JSON under
`pantera-main/src/main/resources/grafana/`. Each dashboard targets a Prometheus
datasource with UID `prometheus` — adjust the UID at import time if your
deployment uses a different name.

## Reference

| Dashboard                               | File                                                                      | Purpose                                                                              |
|-----------------------------------------|---------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| **Upstream Circuit Breaker** (T-O01)    | `grafana/upstream-circuit-breaker.json`                                   | Per-host breaker state, trip frequency, fast-fail rate, time-since-last-trip.        |
| **Proxy Phase Latency** (T-O04)         | `grafana/proxy-phase-latency.json`                                        | Stacked p99 of `proxy_phase_duration_seconds` per repo. Drives cold-bench debugging. |

## Importing

```bash
# Grafana 10+ — POST to /api/dashboards/db
curl -X POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $GRAFANA_TOKEN" \
  -d @pantera-main/src/main/resources/grafana/upstream-circuit-breaker.json \
  https://grafana.example.com/api/dashboards/db
```

The bundled `docker-compose/grafana/provisioning/dashboards/` directory carries
older dashboards already auto-provisioned in the local stack. New dashboards
under `src/main/resources/grafana/` ship inside the JAR and can be imported in
any Grafana instance without docker-compose.

## Metric coverage

The Upstream Circuit Breaker dashboard consumes three metrics produced by
`MicrometerMetrics` in `pantera-core`:

- `pantera_circuit_breaker_state{upstream_host}` — gauge, 1=open / 0=closed.
- `pantera_circuit_breaker_trips_total{upstream_host}` — counter, incremented
  on every closed → open transition.
- `pantera_circuit_breaker_fastfail_total{upstream_host}` — counter,
  incremented on every synthetic 502 returned by the fast-fail path.

The Proxy Phase Latency dashboard consumes `proxy_phase_duration_seconds`,
a histogram broken out by `(phase, repo)` recorded by
`BaseCachedProxySlice.recordProxyPhase(...)`.

## Operational use

Read the **Upstream Circuit Breaker** dashboard whenever the
`upstream-circuit-breaker-open` alert fires
(see `docs/runbooks/upstream-circuit-breaker-open.md`). The per-host state
table tells you immediately which upstream is broken; the trip-count panel
tells you how often the breaker has cycled in the last 24 h; the fast-fail
rate tells you how much client traffic is being absorbed without reaching the
broken upstream.

Read the **Proxy Phase Latency** dashboard before launching any further
performance work. The dominant phase identifies the bottleneck — work on
that one rather than guessing.
