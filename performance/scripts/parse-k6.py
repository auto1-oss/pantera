#!/usr/bin/env python3
"""Parse k6 ndjson + summary into per-step CSV rows.

Usage: parse-k6.py <results_dir> <output_csv>

Reads results/<cell>.ndjson for each cell, groups HTTP requests by 60s
rolling window matched to the ramp target rps, computes p50/p95/p99 +
error rate per step, and writes one CSV row per (cell, step).
"""
import json
import sys
import os
import glob
import math
from collections import defaultdict


def rps_target_for_step(idx: int, warmup: bool) -> int:
    if warmup and idx == 0:
        return 100
    base = 1 if warmup else 0
    n = idx - base
    return 150 * (n + 1)  # 150, 300, 450, ...


def percentile(sorted_values, pct):
    if not sorted_values:
        return 0.0
    k = (len(sorted_values) - 1) * pct / 100.0
    f = math.floor(k)
    c = min(f + 1, len(sorted_values) - 1)
    if f == c:
        return sorted_values[f]
    return sorted_values[f] + (k - f) * (sorted_values[c] - sorted_values[f])


def parse_ndjson(path: str, warmup: bool):
    """Yield per-step dicts: {step, rps_target, samples, p50, p95, p99, errors_pct}."""
    # k6 ndjson points include {metric: "http_req_duration", data:{time, value, tags:{status, route}}}
    buckets = defaultdict(lambda: {"samples": [], "errors": 0, "total": 0})
    t0 = None
    warmup_ms = 180_000 if warmup else 0
    with open(path) as fh:
        for line in fh:
            try:
                row = json.loads(line)
            except ValueError:
                continue
            if row.get("type") != "Point":
                continue
            d = row.get("data", {})
            metric = row.get("metric") or ""
            if metric != "http_req_duration":
                continue
            ts_ms = int(d["time_ms"]) if "time_ms" in d else None
            if ts_ms is None:
                # k6 >= 0.50 uses ISO time; parse fallback
                from datetime import datetime
                ts = datetime.fromisoformat(d["time"].replace("Z", "+00:00"))
                ts_ms = int(ts.timestamp() * 1000)
            if t0 is None:
                t0 = ts_ms
            rel = ts_ms - t0
            if rel < warmup_ms:
                continue
            step = (rel - warmup_ms) // 60_000
            b = buckets[step]
            b["samples"].append(float(d["value"]))
            b["total"] += 1
            status = int(d.get("tags", {}).get("status", 0))
            if status < 200 or status >= 400:
                b["errors"] += 1
    rows = []
    for step in sorted(buckets):
        b = buckets[step]
        if b["total"] == 0:
            continue
        s = sorted(b["samples"])
        rows.append({
            "step": step + 1,
            "rps_target": rps_target_for_step(step + 1, False),
            "samples": b["total"],
            "p50_ms": round(percentile(s, 50), 1),
            "p95_ms": round(percentile(s, 95), 1),
            "p99_ms": round(percentile(s, 99), 1),
            "errors_pct": round(100.0 * b["errors"] / max(1, b["total"]), 3),
        })
    return rows


def main():
    results_dir = sys.argv[1]
    out_csv = sys.argv[2]
    with open(out_csv, "w") as out:
        out.write("cell,step,rps_target,samples,p50_ms,p95_ms,p99_ms,errors_pct\n")
        for path in sorted(glob.glob(os.path.join(results_dir, "*.ndjson"))):
            cell = os.path.basename(path).replace(".ndjson", "")
            warmup = "V1" not in cell  # V1 = cold = no warmup
            for row in parse_ndjson(path, warmup):
                out.write(f"{cell},{row['step']},{row['rps_target']},{row['samples']},"
                          f"{row['p50_ms']},{row['p95_ms']},{row['p99_ms']},{row['errors_pct']}\n")
    print(f"wrote {out_csv}")


if __name__ == "__main__":
    main()
