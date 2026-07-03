# Runbook — `PanteraConditionalGetHitRateLow`

**Severity**: warn · **Component**: metadata cache · **Alert source**:
[`alert-rules.yml`](../../pantera-main/src/main/resources/prometheus/alert-rules.yml)

## What it means

Pantera's `MetadataCache` sends `If-None-Match` / `If-Modified-Since` on every
refresh against the upstream. A healthy proxy sees ≥ 70 % of refreshes return
**304 Not Modified** — the upstream confirms the cached body is still valid
and we skip the body transfer entirely. Less than 70 % means we're paying
full body cost on most refreshes; over a cold-bench window this is the single
biggest amplification source on Maven metadata.

## Confirm

```promql
# Hit-rate per repo over the last 10 minutes
sum by (repo) (rate(pantera_metadata_refresh_total{outcome="304"}[10m]))
/
clamp_min(sum by (repo) (rate(pantera_metadata_refresh_total[10m])), 0.001)

# Outcome breakdown — is it 200s, 404s, errors?
sum by (repo, outcome) (rate(pantera_metadata_refresh_total[10m]))

# Are we even sending the validators?
sum by (repo) (rate(pantera_metadata_refresh_sent_validators_total[10m]))
```

The third query asserts the validators are being emitted at all. If it's 0,
the `MetadataCache.load(...)` call site is not plumbing the stored ETag /
Last-Modified into the upstream fetcher.

## Common root causes

1. **Upstream stopped honouring the validators**. Some upstreams (older Nexus
   instances, some CDN configurations) strip `If-None-Match` at a proxy hop.
   Confirm with a manual curl from a Pantera box:

   ```bash
   curl -i -H 'If-None-Match: "<previous-etag>"' https://<upstream>/<path>
   ```

   Expected: `304 Not Modified`. If you get `200 OK` with a body identical to
   the previous fetch, the upstream isn't honouring our validator. Cache the
   problem on the deployment runbook and consider raising the metadata soft
   TTL to compensate.
2. **Cache eviction bug**. `pantera_metadata_refresh_sent_validators_total`
   < `pantera_metadata_refresh_total` means the stored ETag / Last-Modified
   were missing at refresh time — i.e., some eviction path dropped the
   sidecar between writes. File a bug.
3. **Soft TTL set too short**. If the soft TTL is shorter than the upstream's
   own change interval, every refresh will see content drift and return 200.
   Default soft TTL is 30 s; raise it (e.g. to 5 min) for stable metadata
   paths.
4. **Hot artifact churn**. If real upstream churn is high (snapshot repos),
   a low 304 rate is expected. The alert threshold (70 %) is calibrated for
   release repos; consider per-repo alert overrides for snapshot/-SNAPSHOT
   patterns.

## Mitigate

* For root cause 1 (upstream not honouring): raise the soft TTL.
* For root cause 2 (eviction bug): rollback / fix the eviction code.
* For root cause 3 (TTL too short): bump the soft TTL.
* For root cause 4 (real churn): file a per-repo alert override or accept
  the noise.

## Recovery signal

The same query > 0.7 sustained for 10 minutes is recovery. Cross-check by
looking at the body-byte rate on the affected upstream: a 304 carries no
body, so the recovery should also drop
`sum by (upstream_host) (rate(pantera_upstream_response_body_bytes_total[5m]))`.

## After-action

* If this fired because of upstream behaviour change: update the deployment
  runbook noting which upstream(s) don't honour validators.
* If this fired because of a code regression: cite the recording rule + this
  runbook in the bug ticket.
* If this fired because of a real traffic mix change (more snapshot repos
  brought online): tune the alert threshold per repo.
