#!/usr/bin/env python3
"""Render scaling-raw.csv into scaling-summary.md.

Usage: render-summary.py <input_csv> <output_md>
"""
import csv
import sys
from collections import defaultdict
from datetime import datetime, timezone

SAT_P99_MS = 1000
SAT_ERR_PCT = 1.0
SLO_P99_MS = 200
SLO_ERR_PCT = 0.1

CONFIG_SPECS = {
    "C1": (2, 4),
    "C2": (4, 8),
    "C3": (6, 12),
    "C4": (8, 16),
}


def load(path):
    rows = defaultdict(list)
    with open(path) as fh:
        for r in csv.DictReader(fh):
            r["step"] = int(r["step"])
            r["rps_target"] = int(r["rps_target"])
            r["p50_ms"] = float(r["p50_ms"])
            r["p95_ms"] = float(r["p95_ms"])
            r["p99_ms"] = float(r["p99_ms"])
            r["errors_pct"] = float(r["errors_pct"])
            rows[r["cell"]].append(r)
    for cell in rows:
        rows[cell].sort(key=lambda x: x["step"])
    return rows


def saturation_rps(steps):
    for s in steps:
        if s["p99_ms"] > SAT_P99_MS or s["errors_pct"] > SAT_ERR_PCT:
            return s["rps_target"]
    return None


def slo_rps(steps):
    best = 0
    for s in steps:
        if s["p99_ms"] <= SLO_P99_MS and s["errors_pct"] <= SLO_ERR_PCT:
            best = max(best, s["rps_target"])
    return best


def p50_at_rps(steps, target):
    for s in steps:
        if s["rps_target"] == target:
            return s["p50_ms"]
    return None


def render(rows, out_path):
    lines = []
    lines.append(f"# Pantera scaling baseline — {datetime.now(timezone.utc).isoformat()}")
    lines.append("")
    lines.append("Host: macOS, 11 CPU / 36 GB (Docker Desktop). k6 on host, stack in Compose.")
    lines.append("")
    lines.append("## Primary scaling curve (V0: warm + cooldown ON)")
    lines.append("")
    lines.append("| Config | CPU | RAM | Sat rps | SLO rps (p99 ≤ 200 ms) | p50 @ SLO rps |")
    lines.append("|---|---|---|---|---|---|")
    for cfg in ["C1", "C2", "C3", "C4"]:
        cpu, ram = CONFIG_SPECS[cfg]
        cell = f"{cfg}-V0"
        if cell not in rows:
            lines.append(f"| {cfg} | {cpu} | {ram} G | — | — | — (missing) |")
            continue
        sat = saturation_rps(rows[cell])
        slo = slo_rps(rows[cell])
        p50 = p50_at_rps(rows[cell], slo) if slo else None
        lines.append(
            f"| {cfg} | {cpu} | {ram} G | {sat or 'ceiling'} | {slo or 'none'} | "
            f"{p50 if p50 is not None else '—'} ms |"
        )
    lines.append("")

    # Variant deltas
    lines.append("## Variant deltas (at C2 = 4 CPU / 8 GB)")
    lines.append("")
    lines.append("| Variant | Sat rps | SLO rps |")
    lines.append("|---|---|---|")
    for v, name in [("V0", "warm + cooldown ON (baseline)"),
                    ("V1", "cold start"),
                    ("V2", "cooldown OFF")]:
        cell = f"C2-{v}"
        if cell not in rows:
            lines.append(f"| {name} | — | — |")
            continue
        lines.append(f"| {name} | {saturation_rps(rows[cell]) or 'ceiling'} | {slo_rps(rows[cell]) or 'none'} |")
    lines.append("")

    # Scaling ratios
    lines.append("## Scaling ratios")
    lines.append("")
    sloC1 = slo_rps(rows.get("C1-V0", []))
    sloC2 = slo_rps(rows.get("C2-V0", []))
    sloC3 = slo_rps(rows.get("C3-V0", []))
    sloC4 = slo_rps(rows.get("C4-V0", []))
    def r(a, b): return f"{a/b:.2f}×" if a and b else "—"
    lines.append("| Step | CPU change | SLO rps ratio |")
    lines.append("|---|---|---|")
    lines.append(f"| C1 → C2 | 2× | {r(sloC2, sloC1)} |")
    lines.append(f"| C2 → C3 | 1.5× | {r(sloC3, sloC2)} |")
    lines.append(f"| C3 → C4 | 1.33× | {r(sloC4, sloC3)} |")
    lines.append(f"| C1 → C4 | 4× | {r(sloC4, sloC1)} |")
    lines.append("")

    # Caveats
    lines.append("## Caveats")
    lines.append("")
    lines.append("- macOS Docker ≠ Linux prod: absolute rps ±30% indicative. Ratios are more trustworthy.")
    lines.append("- C4 runs at 11/11 host CPU allocation — any background macOS load leaks in.")
    lines.append("- 100 k artifact fixture ≠ 3 M prod scale.")
    lines.append("- Mock upstream latency is lognormal µ=80 ms — heavier real-world tails not simulated.")
    lines.append("")

    with open(out_path, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"wrote {out_path}")


if __name__ == "__main__":
    rows = load(sys.argv[1])
    render(rows, sys.argv[2])
