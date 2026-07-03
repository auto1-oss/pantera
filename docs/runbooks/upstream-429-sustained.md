# Runbook — `PanteraUpstream429Sustained`

**Severity**: warn · **Component**: rate-limit gate · **Alert source**:
[`alert-rules.yml`](../../pantera-main/src/main/resources/prometheus/alert-rules.yml)

## What it means

Pantera's outbound traffic to an upstream is being **rate-limited** by that
upstream (HTTP 429). Pantera has its own self-imposed token-bucket gate that's
supposed to keep outbound traffic below the upstream's documented or observed
throttle — if a 429 reaches us, that gate is set higher than the upstream
tolerates.

## Confirm

```promql
# Which (repo, upstream) pair is being rate-limited?
sum by (upstream_host, repo_name) (
  rate(pantera_proxy_429_total[5m])
)

# Caller-tag breakdown — who's generating the load?
sum by (upstream_host, caller_tag) (
  rate(pantera_upstream_requests_total[5m])
)

# Is the rate limiter doing its job at all?
sum by (upstream_host) (
  rate(pantera_outbound_rate_limited_total[5m])
)
```

The `caller_tag` breakdown is the key diagnostic — a sustained 429 against an
upstream with `caller_tag="foreground"` dominating means real client traffic
is exceeding the per-host budget. `caller_tag="background"` dominating means
a Pantera-internal subsystem (refresh, prewarm) is the culprit.

## Mitigate

1. **Lower the per-host budget**. Set `PANTERA_RATE_LIMIT_<HOST_UPPERCASED>`
   to a value below the upstream's true tolerance and restart. The default
   bucket size lives in `RateLimitConfig.defaults()`.
2. **If a non-foreground caller is hammering**: identify the caller from
   `caller_tag`, investigate why it's over-fetching. Common offenders:
   * A retry loop without backoff in a `RetrySlice` decorator chain.
   * A negative-cache TTL set so low that 404s round-trip on every request.
   * A metadata-refresh interval too aggressive for the upstream
     (T-P10/P11 conditional-GET work fixes this).
3. **If foreground**: client traffic genuinely exceeds the upstream's budget.
   Consider:
   * Raising the per-host budget cautiously (test against the upstream's
     documented rate limit first).
   * Adding more proxy capacity (horizontal scaling).
   * Asking the upstream vendor for a higher quota.

## Recovery signal

```promql
sum by (upstream_host) (
  rate(pantera_proxy_429_total[5m])
) == 0
```

The counter is monotonic; a flat curve (no new 429s) for 5+ minutes is
recovery. Cross-check with `pantera_outbound_rate_limited_total{reason="gate_closed"}`
on the same host — if that's also flat, the upstream is happy again.

## After-action

* If this fired on a brief upstream traffic spike: nothing to do — the
  rate limiter absorbed it.
* If it fired because the upstream tightened its quota: update the per-host
  budget permanently in `pantera.yml`; file a "upstream quota changed" note
  in the deployment runbook.
* If it fired because of a runaway internal caller: track the regression
  back through git; the offending feature has a bug.
