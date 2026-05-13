# Pantera Scaling Benchmark

Local Docker harness that measures Pantera's **saturation-rps** and **SLO-rps** (p99 ≤ 200 ms) at four CPU/RAM configurations plus two variant spot-checks.

**Design spec:** [`../docs/superpowers/specs/2026-04-18-pantera-scaling-bench-design.md`](../docs/superpowers/specs/2026-04-18-pantera-scaling-bench-design.md)
**Implementation plan:** [`../docs/superpowers/plans/2026-04-18-pantera-scaling-bench.md`](../docs/superpowers/plans/2026-04-18-pantera-scaling-bench.md)

## Prerequisites

- macOS with **Docker Desktop** (11 CPU / 24+ GB RAM allocated)
- Homebrew — `brew install k6`
- Python 3 (stdlib only)

## Quick start

```bash
# from repo root:
cd performance

# one-time: generate mock-upstream body files (~12 MB, uncommitted, git-ignored)
make setup

# one smoke cell (~15–20 min wall time)
make smoke

# full matrix (6 cells, ~2 h wall time)
make matrix
```

All targets are defined in [Makefile](./Makefile). Run `make help` to see them.

## Matrix

| Cell | CPU | RAM | Variant |
|---|---|---|---|
| C1-V0 | 2 | 4 G | warm cache + cooldown ON |
| C2-V0 | 4 | 8 G | warm cache + cooldown ON |
| C3-V0 | 6 | 12 G | warm cache + cooldown ON |
| C4-V0 | 8 | 16 G | warm cache + cooldown ON |
| C2-V1 | 4 | 8 G | **cold** + cooldown ON |
| C2-V2 | 4 | 8 G | warm + cooldown **OFF** |

## What each cell measures

A **stepped ramp** via k6 `ramping-arrival-rate`:

- 180 s warm-up @ 100 rps (V0 only; skipped for V1 cold)
- 60 s steps: 150, 300, 450, … up to 3000 rps
- 30 s cool-down

Two derived metrics per cell:

- **Saturation rps:** first step where p99 > 1 s **or** error rate > 1 %.
- **SLO rps:** highest step where p99 ≤ 200 ms **and** errors ≤ 0.1 %.

## Traffic mix (encoded in `k6/scenario.js`)

```
90 %  reads
  72 %  group reads (80 % of reads)
    14.4 %  targeted local-member group
    57.6 %  hot group with 5 proxy members
      11.5 %  upstream miss
      46.1 %  already cached
  18.0 %  direct local-repo read
10 %  writes (PUT to local repo)
```

Artifact name pool: 1 000 names per repo (`pkg-00000` … `pkg-00999`), uniform selection; sizes 70 % 100 KB / 20 % 1 MB / 10 % 10 MB, deterministic per package name.

## Outputs

```
results/
├── C*-V*.json        # k6 summary (percentiles, throughput)
├── C*-V*.ndjson      # k6 per-request metrics
├── C*-V*.stats.csv   # docker stats samples
├── scaling-raw.csv   # per-step latency/rps for every cell (after `make matrix` or `make summary`)
└── scaling-summary.md
```

`results/` is `.gitignore`-d; the raw output is not committed by default.

## Caveats

1. **macOS Docker ≠ Linux prod.** Absolute rps numbers are ±30 % indicative; ratios between configs are more trustworthy.
2. **C4 runs at 11/11 host CPUs** — any macOS background load contaminates the C4 number.
3. **100 k artifact fixture ≠ 3 M prod.** Index cost grows as log₂ N (17 levels at 100 k vs 22 at 3 M — similar but not identical).
4. **Single-host network** — no NLB, no PROXYv2, no TLS.
5. **Synthetic uniform traffic.** Real-world usage is Zipf-skewed toward a hot subset.

## Troubleshooting

- **SUT never becomes healthy** → check `docker compose -f docker-compose-scaling.yml logs pantera-sut`. Most common cause: schema mismatch between `seed.sql` and Pantera's Flyway migrations (Pantera runs its own Flyway on boot; our fixture pre-creates the table with a narrower schema that may collide). Fix: drop the `CREATE TABLE` in `seed.sql` and let Flyway create it; seed via `INSERT` only.
- **k6 `connection refused`** during ramp → SUT died mid-run. Check the last row of `results/<cell>.stats.csv` for an OOM (mem_pct near 100 %).
- **WireMock returning 404** on upstream requests → the `bodyFileName` templating may not be rendering. Fall back to 3 separate mappings with fixed `bodyFileName` per URL shard (see `wiremock/mappings/catchall.json`).
- **`make matrix` aborts partway through** → `results/` still contains the partial runs; you can `make summary` to render what you have so far.

## Running a single cell

```bash
make cell CFG=C3 VAR=V0      # one cell
make cell CFG=C2 VAR=V2      # cooldown-off variant
```

## Ad-hoc inspection

```bash
make inspect                  # k6 parses the scenario, prints stage schedule
```
