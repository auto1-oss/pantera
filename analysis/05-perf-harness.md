# Phase 6 (cont) — Perf-test plan that would have caught this regression

## Why the existing benches missed it

The team's cold-bench-10x ran on 2026-05-05 (HEAD `1f3675d95`) and
reported p50=13.34 s. By the team's own measurement on 2026-05-13 the
same command returned 37-39 s. Between the two runs:

- The prefetch subsystem stabilised and the npm cap was raised 4 → 32.
- The team's IP (or production's IP) accumulated enough rate-limit
  history that Maven Central started returning 429.

The bench did not detect the regression because:

1. **It measured the wrong primary metric.** Wall time is a lagging
   indicator. The leading indicator is the outbound request count, which
   wasn't recorded.
2. **It did not control the upstream rate-limit environment.** A free run
   against the public Maven Central is non-deterministic — the same
   Pantera build can be fast in one test window and slow in another. CI
   cannot gate on a non-deterministic signal.
3. **It only exercised the cold cache.** Real production also has the
   speculative prefetch operating *between* cold walks, which trains
   Maven Central to throttle us before the next cold walk starts.

## What the new harness must measure

Per cold walk:

| Metric | Source | Pass criteria |
|---|---|---|
| Wall clock | mvn output | p99 ≤ 1.5× direct-mvn baseline measured against the same fixture upstream |
| `pantera_upstream_requests_total{caller_tag="foreground"}` | Pantera Prometheus | exact 1× the count of client artifact requests, deduplicated by `(method, path)` |
| `pantera_upstream_requests_total{caller_tag!="foreground"}` | Pantera Prometheus | 0 (no prefetch, no cooldown HEAD, no `MavenHeadSource`) — only after the rebuild's prefetch removal |
| `pantera_upstream_requests_total{outcome="429"}` | Pantera Prometheus | 0 (any 429 fails CI) |
| `pantera_upstream_amplification_ratio` | Recording rule | ≤ 1.5 over the full bench window |
| `pantera_proxy_phase_seconds_p99` per phase | Phase 7.5 histogram | each phase < its budget (defined per phase) |

The wall-clock metric is preserved (we still want to know if we got slower)
but it's the *least* important — a build that meets the upstream-count
budget is correct by construction; one that doesn't is wrong even if it
happens to be fast in this CI run.

## Mediator: Toxiproxy + a fixture upstream

Run a fixture container that serves a static `maven-metadata.xml` + N
POMs + N JARs for the sonar-maven-plugin tree. Put `toxiproxy` in front
of it with:

- **Rate limit**: `connection-rate 20rps`. Mimics Maven Central's
  documented per-IP limit.
- **Latency**: 100 ms (mean across Maven Central's CDN edge).
- **Bandwidth cap**: 50 Mbps (consumer-grade).

A correctly-behaved Pantera produces zero 429s against this fixture
because it stays under 20 rps. The Toxiproxy rate-limit IS the gate. CI
runs `cold-bench-10x.sh` against `http://toxiproxy/maven_proxy` and
asserts on the metrics above.

## CI workflow shape

```yaml
- name: Start fixture stack
  run: docker compose -f performance/fixtures/toxiproxy.yml up -d
- name: Wait for fixture ready
  run: ./performance/scripts/wait-for-ready.sh
- name: Run cold-bench-10x against fixture
  run: TOXIPROXY=1 ./performance/scripts/cold-bench-10x.sh
- name: Scrape Prometheus, compute amplification ratio
  run: ./performance/scripts/perf-gate.sh \
         --max-amplification-ratio 1.5 \
         --max-429-count 0 \
         --max-p99-wall-seconds 13.5
- name: Tear down fixture
  if: always()
  run: docker compose -f performance/fixtures/toxiproxy.yml down -v
```

`perf-gate.sh` reads the in-bench Prometheus, fails the build if any
threshold is breached, and writes a `.md` summary to artifacts.

## Concurrent-dedup test

Separately from the cold bench, add an integration test:

```
PUT 50 concurrent GETs of /maven-group/.../foo-1.0.pom (uncached)
ASSERT pantera_upstream_requests_total{caller_tag="foreground"} delta == 1
```

This is the regression test for Finding #2. Lives in
`pantera-main/src/test/java/com/auto1/pantera/integration/SingleFlightUpstreamTest.java`
(new file). Uses TestContainers + a stub upstream that counts requests.

## Smoke tests for the rate limiter

```
ASSERT outbound rate to upstream X never exceeds settings.rateLimit(X)
       over any 10-second window during a 10-minute soak test
```

`performance/scripts/rate-limit-soak.sh` — fires a stream of synthetic
artifact requests at varying QPS, scrapes Prometheus, asserts a hard
upper bound.

## Test fixture for npm / composer / go

The Maven fixture above generalises:

- `npm` fixture: 50 packuments + 50 tarballs, served by a stub
  registry-shape HTTP server. Same Toxiproxy in front, same rate limit.
- `composer`: 30 `packages.json` + 30 `provider-includes`.
- `go`: 20 `?go-get=1` HTML + 20 `.zip` modules.

One CI job per ecosystem, identical gate criteria, different fixtures.

## Existing perf-pack — what to keep

The Docker-compose-based `performance/` harness is correctly structured
and need not be rebuilt; the gaps are at the metric and gate layers:

- `performance/k6/` — keep, useful for warm-cache throughput measurement.
- `performance/scripts/cold-bench-10x.sh` — keep, extend to scrape
  Prometheus + write the amplification metric.
- `performance/results/*.md` — keep as the archival format.

The new content lives under `performance/fixtures/` (toxiproxy + stub
upstreams) and `performance/scripts/perf-gate.sh`.

## Timeline

- Hour 1-4: write toxiproxy compose file + stub upstream.
- Hour 4-8: rewrite `cold-bench-10x.sh` to scrape Prometheus.
- Hour 8-12: write `perf-gate.sh`.
- Hour 12-16: integration test for SingleFlight upstream dedup.
- Hour 16-20: rate-limit soak test.
- Hour 20-24: wire into the existing `.github/workflows` CI.

One engineer-day for the harness + gate; the per-ecosystem fixtures are
incremental.
