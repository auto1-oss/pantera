# Runbook — `PanteraUpstreamCircuitBreakerOpen`

**Severity**: page · **Component**: upstream circuit breaker · **Alert source**:
[`alert-rules.yml`](../../pantera-main/src/main/resources/prometheus/alert-rules.yml)

## What it means

Pantera's per-upstream circuit breaker has been **open** for more than 5
minutes — meaning every outbound call to that upstream is fast-failing with a
synthetic 502 (`X-Pantera-Circuit-Open: true`) instead of reaching the wire.

Open is a recovery state: it kicks in after a sustained burst of 5xx / network
errors from the upstream. A *brief* open period (seconds) is healthy — that's
the breaker absorbing a flap. Five minutes is not.

## Confirm

```promql
# Hosts currently in OPEN state
pantera_circuit_breaker_state == 1

# Trip frequency in the last hour (was it flapping or one decisive trip?)
increase(pantera_circuit_breaker_trips_total[1h])

# Underlying upstream failure shape that drove the trip
sum by (upstream_host, outcome) (
  rate(pantera_upstream_requests_total[10m])
)
```

If `pantera_circuit_breaker_state{upstream_host="X"}` is genuinely 1 and has
been there for ≥ 5 minutes, the breaker is doing its job — the question is
why the upstream hasn't recovered.

## Mitigate

1. **External outage check first**. `curl -v https://<upstream>/` from outside
   Pantera (locally on your laptop or from a different VM). If the upstream is
   broken globally, page the upstream vendor and wait. The breaker will
   auto-close once the HEAD probe succeeds at the next backoff interval.
2. **Reachability check**. From a Pantera box: `curl -v https://<upstream>/`.
   If THIS fails but a laptop curl works, you have a DNS / firewall / TLS
   issue specific to the Pantera deployment. Investigate the network path.
3. **Credential check**. If outbound auth is configured (private upstream),
   verify the credential is still valid. The breaker treats 401 / 407 as trip
   conditions on purpose — a rotated secret will trip the breaker.
4. **Reset manually if needed**. There is no admin endpoint to force-close the
   breaker (per design — manual override defeats the purpose). To recover
   quickly without waiting for the next probe, restart the Pantera node; the
   in-memory breaker state is per-process.
5. **If a permanent upstream change**: update the repository config to point
   at the replacement upstream and `pantera-cli reload`.

## Recovery signal

```promql
# Time since last trip on the affected host
time() - timestamp(pantera_circuit_breaker_trips_total != bool 0)
```

A growing value (no new trips) plus `pantera_circuit_breaker_state == 0`
means the breaker recovered. The Grafana **Upstream Circuit Breaker**
dashboard surfaces both in the "Recovery signals" row.

## After-action

* If this fired on a transient upstream wobble: nothing to do.
* If this fired because OUR config drifted (stale cert, rotated secret,
  changed firewall rule): file the post-incident ticket; update the
  configuration playbook.
* If this fired because the upstream is permanently retired: open a deprecation
  PR removing the upstream entry from `pantera.yml`.
