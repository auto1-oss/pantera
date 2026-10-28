# WS7 — Observability

- **Status:** 📝 DRAFT
- **Depends on:** every other workstream (it tracks the metrics they introduce)
- **Blocks:** the release (a metric without a panel is a DoD violation per CLAUDE.md)
- **Decision-gated:** no
- **Size:** M (spread across the release, not a trailing task)

## 1. Problem & goal

Today the storage layer emits **no per-operation metrics** (`RepoConfig.java:62-65` deliberately skips `MicrometerStorage`), so "slow at scale" is undiagnosable — you cannot see S3 HEAD/GET/PUT rates, disk hit ratios, or write-back lag. More broadly, 2.3.0 introduces a large set of new state transitions and hot paths (storage rebuild, write-back queue, presign, HA propagation, cooldown coherence, conditional refresh) and the project rule is absolute: **a metric without a panel is invisible; a panel without a metric is a lie; every operationally significant state transition must be logged, not just counted.**

**Goal:** every metric introduced by WS1–WS6 exists with a bounded-tag Micrometer meter, a verified Grafana panel (exact name + tags checked against a live `:8087/metrics/vertx` scrape), and — for state transitions — an `EcsLogger` log line; every new alert ships a runbook; and the release load test has a dashboard proving the 1000 req/s claim.

## 2. Current state (evidence)

- Storage not wrapped in `MicrometerStorage`/`LoggingStorage`; `StorageMetricsRecorder` initialized but never invoked from the storage chain (`RepoConfig.java:62-65`).
- Transfer + control-plane SLO ladders already defined in `VertxMain` (histogram boundaries gated on `PANTERA_METRICS_PERCENTILES_HISTOGRAM=true`) — reuse them, don't invent new ladders.
- Metric conventions fixed: `pantera.<area>.<thing>`, dots→underscores, counters gain `_total`, tags bounded (`RepoNameMeterFilter` caps repo tags; never add path/version/artifact-name tags), every recording guarded by `MicrometerMetrics.isInitialized()`.
- Dashboards provisioned from `pantera-main/docker-compose/grafana/provisioning/dashboards/`; quantile panels need `PANTERA_METRICS_PERCENTILES_HISTOGRAM=true`.

## 3. Target — the metric/panel/runbook inventory

This spec is the **registry** every other workstream files its observability into. Required coverage:

### WS7-storage (from WS1)
- Meters: blob-store op count+latency+error/throttle by op (`GET/HEAD/PUT/LIST`) — transfer ladder; disk cache hit ratio; **redirect-vs-stream ratio** + presign issuance rate; write-back queue depth + oldest-pending-age; eviction bytes/sec + admission-rejections; index size + rebuild duration.
- Logs: index rebuild start/finish, write-back backpressure engaged/cleared, eviction-under-pressure, presign-fallback-to-stream.
- Panels: a **Storage** dashboard; the load-test view lives here.

### WS7-ha (from WS2)
- Meters: revocation-set size + hydration-on-boot duration; event-queue depth + drain lag; readiness state (per dependency: DB/S3/Valkey); settings-propagation lag; cache-invalidation pub/sub publish/receive by cache.
- Logs (state transitions — mandatory): revocation hydrate, event-pipeline drain start/stall, readiness flip (+ reason), pre-stop drain begin/complete, authz/settings invalidation broadcast.
- Runbooks: "event-queue drain lag high," "node not-ready," "revocation set not hydrated."

### WS7-format (from WS4/WS5/WS6)
- Cooldown: filter duration (already partly present via `CooldownMetrics`), cooldown-origin-404 count (WS5, confirm not negative-cached), per-request eval-cap truncations.
- Upstream efficiency: conditional-refresh `304` ratio per format, serve-stale-on-outage count, resolution-surface cache hit ratio (WS6).
- Security features: PGP/attestation/referrer verification outcomes (success/tampered/untrusted), digest-verify rejections on upload/cache-store.

### WS7-panels (the gate)
- One dashboard per area, following the existing files' conventions (datasource shape, `{job="pantera"}` filter, unique ids, gridPos). `sum/count` averages for bucket-less timers; `histogram_quantile` only where the transfer/control ladder applies.
- **Every query verified against a live scrape** before commit (several past panels charted metric names that never existed — do not repeat).

## 4. Implementation plan

- **Not a phase — a running obligation.** Each WS1–WS6 sub-item that adds a metric adds its panel + runbook **in the same change** (project DoD). This spec is the checklist; a reviewer rejects any workstream PR that adds a metric without its panel.
- **WS7.1 (does have standalone work):** the storage-tier metrics decorator itself (reverse the `RepoConfig` skip for the blob tier) — this is the one net-new instrumentation surface and belongs with WS1.6.
- **WS7.2:** the release **load-test dashboard** + a committed load harness (k6/gatling/JMH-style) proving ≥1000 req/s R+W against a MinIO/S3 backend in both stream and redirect modes, with the storage/latency panels that make the claim legible. Lives under `docs/slo/` + the grafana provisioning dir.

## 5. Acceptance criteria

1. **No metric without a panel:** an automated check (extend the existing ECS log-schema drift gate style) or a review checklist confirms every `pantera.*` meter added in 2.3.0 appears in a provisioned dashboard.
2. **No panel without a metric:** every new panel's query resolves against a live `:8087/metrics/vertx` scrape (documented verification per panel).
3. **State transitions logged:** each mandatory log line above is emitted with valid ECS fields (`event.category/action/outcome`, `log.source=application`) and appears in a smoke run.
4. **Load-test dashboard** renders the 1000 req/s R+W run with store-op rate near zero on the read hot-set (proves WS1).
5. **Runbooks exist** for every new alert (`docs/runbooks/`).

## 6. Test requirements

- Metric-emission unit tests use the recording-registry pattern (assert a counter/timer was recorded with the expected bounded tags) — not scrapes.
- The live-scrape verification is a manual/CI step against the local stack (`:8087/metrics/vertx`), documented per panel.
- Log-line presence asserted in the relevant workstream's integration smoke test.

## 7. Out of scope

- New SLO ladders (reuse the two in `VertxMain`).
- Tracing backend changes (trace.id correlation already exists; new async hops must preserve it per CLAUDE.md, but that's enforced in each workstream, not here).

## 8. Risks & rollback

- Unbounded tags are the classic footgun — every new meter's tags must be bounded (no path/version/artifact-name); review gate enforces it.
- Bucket shapes are fixed at meter creation; any timer that wants quantiles must pick the correct existing ladder (transfer vs control-plane) at creation — a later change needs a restart.

## 9. Docs & observability

- `docs/observability/` — the 2.3.0 metric inventory (this spec, promoted); `docs/slo/` — the load-test method + dashboard.
- `docs/runbooks/` — one page per new alert.
- CHANGELOG: observability additions noted under the relevant feature bullets (not a separate section).
