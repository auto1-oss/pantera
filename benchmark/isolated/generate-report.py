#!/usr/bin/env python3
"""
Generate Markdown benchmark comparison report from hey JSON results.
"""
import json
import os
import glob
import csv
from datetime import datetime

RESULTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")

def load_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return None

def load_stats(path):
    """Load docker-stats.csv and compute averages."""
    rows = []
    try:
        with open(path) as f:
            reader = csv.DictReader(f)
            for row in reader:
                cpu = row.get('cpu_pct', '0%').replace('%', '')
                mem_pct = row.get('mem_pct', '0%').replace('%', '')
                try:
                    rows.append({'cpu': float(cpu), 'mem_pct': float(mem_pct)})
                except ValueError:
                    pass
    except Exception:
        pass
    if not rows:
        return {'avg_cpu': 0, 'max_cpu': 0, 'avg_mem_pct': 0, 'max_mem_pct': 0, 'samples': 0}
    cpus = [r['cpu'] for r in rows]
    mems = [r['mem_pct'] for r in rows]
    return {
        'avg_cpu': round(sum(cpus) / len(cpus), 1),
        'max_cpu': round(max(cpus), 1),
        'avg_mem_pct': round(sum(mems) / len(mems), 1),
        'max_mem_pct': round(max(mems), 1),
        'samples': len(rows)
    }

def load_image_info(path):
    info = {}
    try:
        with open(path) as f:
            for line in f:
                if '=' in line:
                    k, v = line.strip().split('=', 1)
                    info[k] = v
    except Exception:
        pass
    return info

def load_results(label):
    """Load all benchmark results for a label (old/new)."""
    base = os.path.join(RESULTS_DIR, label)
    results = {}
    for jf in sorted(glob.glob(os.path.join(base, "hey-*-summary.json"))):
        name = os.path.basename(jf).replace('-summary.json', '')
        data = load_json(jf)
        if data:
            results[name] = data
    stats = load_stats(os.path.join(base, "docker-stats.csv"))
    image_info = load_image_info(os.path.join(base, "image-info.txt"))
    return results, stats, image_info

def avg_runs(results, rps_target):
    """Average run1 and run2 for a given RPS target."""
    run1_key = f"hey-{rps_target}rps-run1"
    run2_key = f"hey-{rps_target}rps-run2"
    r1 = results.get(run1_key)
    r2 = results.get(run2_key)
    if r1 and r2:
        avg = {}
        for key in r1:
            if isinstance(r1[key], (int, float)) and isinstance(r2.get(key), (int, float)):
                avg[key] = round((r1[key] + r2[key]) / 2, 3)
            else:
                avg[key] = r1[key]
        avg['run1_rps'] = r1.get('rps', 0)
        avg['run2_rps'] = r2.get('rps', 0)
        avg['rps_variance'] = abs(r1.get('rps', 0) - r2.get('rps', 0))
        return avg
    return r1 or r2 or {}

def delta_pct(old_val, new_val):
    if old_val == 0:
        return "N/A"
    d = ((new_val - old_val) / old_val) * 100
    return f"{d:+.1f}%"

def delta_str(old_val, new_val, unit="", lower_is_better=True):
    """Format comparison: value (delta%)"""
    d = delta_pct(old_val, new_val)
    if old_val == 0 and new_val == 0:
        return "0", "="
    direction = ""
    if isinstance(d, str) and d != "N/A":
        pct = float(d.replace('%', '').replace('+', ''))
        if lower_is_better:
            direction = "better" if pct < -1 else ("worse" if pct > 1 else "~same")
        else:
            direction = "better" if pct > 1 else ("worse" if pct < -1 else "~same")
    return d, direction

def generate_markdown():
    old_results, old_stats, old_info = load_results("old")
    new_results, new_stats, new_info = load_results("new")

    if not old_results and not new_results:
        print("No results found. Run benchmarks first.")
        return

    rps_targets = ["800", "900", "1000"]

    lines = []
    def w(s=""): lines.append(s)

    w("# Performance Benchmark Report")
    w(f"**Generated:** {datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S UTC')}")
    w()

    # === Overview ===
    w("## Overview")
    w()
    w("This report compares the performance of two container images under sustained HTTP load:")
    w()
    w(f"- **Old:** `{old_info.get('image', 'artipie:1.20.12')}`")
    w(f"- **New:** `{new_info.get('image', 'pantera:2.0.0')}`")
    w()
    w("Each image was benchmarked **in complete isolation** — only one image ran at a time,")
    w("with dedicated infrastructure (PostgreSQL, Valkey) restarted between runs.")
    w()

    # === Test Environment ===
    w("## Test Environment")
    w()
    w("| Parameter | Value |")
    w("|-----------|-------|")
    w(f"| CPU limit | {old_info.get('cpus', '8')} cores |")
    w(f"| Memory limit | {old_info.get('memory', '16g')} |")
    w("| JVM heap | 10g fixed (-Xms10g -Xmx10g) |")
    w("| GC | G1GC (MaxGCPauseMillis=300, G1HeapRegionSize=16m) |")
    w("| Direct memory | 2g |")
    w("| IO threads | 14 |")
    w("| Load tool | hey (HTTP load generator) |")
    w("| PostgreSQL | 17.8-alpine (tmpfs) |")
    w("| Valkey | 8.1.4 (256MB, allkeys-lru) |")
    w()

    # === Methodology ===
    w("## Methodology")
    w()
    w("1. Start shared infrastructure (PostgreSQL + Valkey)")
    w("2. Start SUT with `--cpus=8 --memory=16g` and specified JVM args")
    w("3. Seed Maven repository with test artifacts (1KB + 1MB JARs)")
    w("4. Warmup: 20 concurrent connections for 15 seconds")
    w("5. Wait 5 seconds for JVM stabilization")
    w("6. For each rate (800, 900, 1000 req/s):")
    w("   - Run 1: 60 seconds sustained load")
    w("   - Cool down 10 seconds")
    w("   - Run 2: 60 seconds sustained load (to measure variance)")
    w("   - Cool down 10 seconds")
    w("7. Mixed workload: 1MB artifact at 500 req/s for 30 seconds")
    w("8. Collect GC logs, container stats, final inspect")
    w("9. Tear down everything, pause 15 seconds")
    w("10. Repeat for second image")
    w()

    # === Workload Details ===
    w("## Workload Details")
    w()
    w("| Parameter | Value |")
    w("|-----------|-------|")
    w("| Endpoint | `GET /maven/com/bench/artifact/1.0/artifact-1.0.jar` (1KB) |")
    w("| Auth | HTTP Basic (precomputed header) |")
    w("| Rate targets | 800, 900, 1000 req/s |")
    w("| Duration per run | 60 seconds |")
    w("| Runs per rate | 2 (averaged) |")
    w("| Warmup | 15s at 20 connections |")
    w("| Large artifact | 1MB at 500 req/s for 30s |")
    w()

    # === Per-image results ===
    for label, results, stats, info_d in [
        ("Old Image", old_results, old_stats, old_info),
        ("New Image", new_results, new_stats, new_info)
    ]:
        w(f"## {label} Results")
        w()
        w(f"**Image:** `{info_d.get('image', 'N/A')}`")
        w()

        w("### Throughput and Latency")
        w()
        w("| Rate Target | Achieved RPS | Avg (ms) | Median (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Max (ms) | Errors | Error % |")
        w("|-------------|-------------|----------|-------------|----------|----------|----------|----------|--------|---------|")
        for rps in rps_targets:
            avg = avg_runs(results, rps)
            if not avg:
                w(f"| {rps} | — | — | — | — | — | — | — | — | — |")
                continue
            w(f"| {rps} "
              f"| {avg.get('rps', 0):.1f} "
              f"| {avg.get('latency_avg_ms', 0):.2f} "
              f"| {avg.get('p50_ms', 0):.2f} "
              f"| {avg.get('p90_ms', 0):.2f} "
              f"| {avg.get('p95_ms', 0):.2f} "
              f"| {avg.get('p99_ms', 0):.2f} "
              f"| {avg.get('latency_slowest_ms', 0):.1f} "
              f"| {avg.get('error_count', 0):.0f} "
              f"| {avg.get('error_rate_pct', 0):.2f}% |")
        w()

        # Run variance
        w("### Run Variance")
        w()
        w("| Rate | Run 1 RPS | Run 2 RPS | Delta |")
        w("|------|-----------|-----------|-------|")
        for rps in rps_targets:
            avg = avg_runs(results, rps)
            if avg and 'run1_rps' in avg:
                w(f"| {rps} | {avg['run1_rps']:.1f} | {avg['run2_rps']:.1f} | {avg.get('rps_variance', 0):.1f} |")
        w()

        # Large artifact
        large = results.get("hey-500rps-large")
        if large:
            w("### Large Artifact (1MB at 500 req/s)")
            w()
            w(f"- RPS: {large.get('rps', 0):.1f}")
            w(f"- Avg latency: {large.get('latency_avg_ms', 0):.2f} ms")
            w(f"- P99 latency: {large.get('p99_ms', 0):.2f} ms")
            w(f"- Errors: {large.get('error_count', 0)}")
            w()

        # Resource usage
        w("### Resource Usage")
        w()
        w(f"- Avg CPU: {stats['avg_cpu']}%")
        w(f"- Max CPU: {stats['max_cpu']}%")
        w(f"- Avg Memory: {stats['avg_mem_pct']}%")
        w(f"- Max Memory: {stats['max_mem_pct']}%")
        w(f"- Samples: {stats['samples']}")
        w()

    # === Side-by-side Comparison ===
    w("## Side-by-Side Comparison")
    w()
    w("### Throughput")
    w()
    w("| Rate Target | Old RPS | New RPS | Delta | Verdict |")
    w("|-------------|---------|---------|-------|---------|")
    for rps in rps_targets:
        old_avg = avg_runs(old_results, rps)
        new_avg = avg_runs(new_results, rps)
        if old_avg and new_avg:
            d, v = delta_str(old_avg.get('rps', 0), new_avg.get('rps', 0), lower_is_better=False)
            w(f"| {rps} | {old_avg.get('rps', 0):.1f} | {new_avg.get('rps', 0):.1f} | {d} | {v} |")
    w()

    w("### Latency (lower is better)")
    w()
    w("| Rate | Metric | Old (ms) | New (ms) | Delta | Verdict |")
    w("|------|--------|----------|----------|-------|---------|")
    for rps in rps_targets:
        old_avg = avg_runs(old_results, rps)
        new_avg = avg_runs(new_results, rps)
        if not old_avg or not new_avg:
            continue
        for metric, key in [
            ("Average", "latency_avg_ms"),
            ("Median (P50)", "p50_ms"),
            ("P90", "p90_ms"),
            ("P95", "p95_ms"),
            ("P99", "p99_ms"),
            ("Max", "latency_slowest_ms"),
        ]:
            ov = old_avg.get(key, 0)
            nv = new_avg.get(key, 0)
            d, v = delta_str(ov, nv, lower_is_better=True)
            w(f"| {rps} | {metric} | {ov:.2f} | {nv:.2f} | {d} | {v} |")
    w()

    w("### Error Rate")
    w()
    w("| Rate | Old Errors | Old % | New Errors | New % | Verdict |")
    w("|------|-----------|-------|-----------|-------|---------|")
    for rps in rps_targets:
        old_avg = avg_runs(old_results, rps)
        new_avg = avg_runs(new_results, rps)
        if old_avg and new_avg:
            oe = old_avg.get('error_count', 0)
            op = old_avg.get('error_rate_pct', 0)
            ne = new_avg.get('error_count', 0)
            np_val = new_avg.get('error_rate_pct', 0)
            v = "~same" if abs(op - np_val) < 0.1 else ("better" if np_val < op else "worse")
            w(f"| {rps} | {oe:.0f} | {op:.2f}% | {ne:.0f} | {np_val:.2f}% | {v} |")
    w()

    w("### Resource Efficiency")
    w()
    w("| Metric | Old | New | Delta | Verdict |")
    w("|--------|-----|-----|-------|---------|")
    for metric, key, lib in [
        ("Avg CPU %", "avg_cpu", True),
        ("Max CPU %", "max_cpu", True),
        ("Avg Mem %", "avg_mem_pct", True),
        ("Max Mem %", "max_mem_pct", True),
    ]:
        ov = old_stats.get(key, 0)
        nv = new_stats.get(key, 0)
        d, v = delta_str(ov, nv, lower_is_better=lib)
        w(f"| {metric} | {ov} | {nv} | {d} | {v} |")
    w()

    # === Bottlenecks and Anomalies ===
    w("## Bottlenecks and Anomalies")
    w()

    # Check for anomalies
    anomalies = []
    for rps in rps_targets:
        for label_name, results in [("old", old_results), ("new", new_results)]:
            avg = avg_runs(results, rps)
            if avg:
                if avg.get('error_rate_pct', 0) > 1:
                    anomalies.append(f"- **{label_name} at {rps} req/s**: Error rate {avg['error_rate_pct']:.2f}% exceeds 1% threshold")
                if avg.get('p99_ms', 0) > 500:
                    anomalies.append(f"- **{label_name} at {rps} req/s**: P99 latency {avg['p99_ms']:.1f}ms exceeds 500ms threshold")
                if avg.get('rps_variance', 0) > avg.get('rps', 1) * 0.1:
                    anomalies.append(f"- **{label_name} at {rps} req/s**: Run-to-run variance {avg['rps_variance']:.1f} exceeds 10% of target")

    if old_stats.get('max_cpu', 0) > 750:
        anomalies.append(f"- **old**: CPU peaked at {old_stats['max_cpu']}% (near {8*100}% limit for 8 cores)")
    if new_stats.get('max_cpu', 0) > 750:
        anomalies.append(f"- **new**: CPU peaked at {new_stats['max_cpu']}% (near {8*100}% limit for 8 cores)")

    if anomalies:
        for a in anomalies:
            w(a)
    else:
        w("No significant anomalies detected.")
    w()

    # === Risk Assessment ===
    w("## Risk Assessment")
    w()
    regressions = []
    improvements = []
    for rps in rps_targets:
        old_avg = avg_runs(old_results, rps)
        new_avg = avg_runs(new_results, rps)
        if not old_avg or not new_avg:
            continue
        # Throughput regression
        if new_avg.get('rps', 0) < old_avg.get('rps', 0) * 0.95:
            regressions.append(f"Throughput regression at {rps} target: {old_avg['rps']:.1f} -> {new_avg['rps']:.1f}")
        elif new_avg.get('rps', 0) > old_avg.get('rps', 0) * 1.05:
            improvements.append(f"Throughput improvement at {rps} target: {old_avg['rps']:.1f} -> {new_avg['rps']:.1f}")
        # Latency regression
        for pct in ['p50_ms', 'p90_ms', 'p95_ms', 'p99_ms']:
            ov = old_avg.get(pct, 0)
            nv = new_avg.get(pct, 0)
            if ov > 0 and nv > ov * 1.2:
                regressions.append(f"{pct} regression at {rps}: {ov:.2f}ms -> {nv:.2f}ms ({delta_pct(ov, nv)})")
            elif ov > 0 and nv < ov * 0.8:
                improvements.append(f"{pct} improvement at {rps}: {ov:.2f}ms -> {nv:.2f}ms ({delta_pct(ov, nv)})")

    if regressions:
        w("### Regressions")
        for r in regressions:
            w(f"- {r}")
        w()
    if improvements:
        w("### Improvements")
        for i in improvements:
            w(f"- {i}")
        w()
    if not regressions and not improvements:
        w("No significant regressions or improvements detected (all within 5-20% bands).")
        w()

    # === Final Recommendation ===
    w("## Final Recommendation")
    w()
    if regressions:
        w("**Caution recommended.** Performance regressions were detected. Review the regressions")
        w("listed above before promoting to production. Consider profiling under production-like conditions.")
    elif improvements:
        w("**Safe to promote.** The new image shows performance improvements with no regressions detected.")
        w("The benchmark results support promoting `pantera:2.0.0` from a performance standpoint.")
    else:
        w("**Safe to promote.** Performance is comparable between versions. No significant regressions detected.")
    w()

    # === Executive Summary ===
    w("## Executive Summary")
    w()
    w("### For Technical and Non-Technical Stakeholders")
    w()

    # Compute overall summary
    total_old_rps = sum(avg_runs(old_results, r).get('rps', 0) for r in rps_targets if avg_runs(old_results, r))
    total_new_rps = sum(avg_runs(new_results, r).get('rps', 0) for r in rps_targets if avg_runs(new_results, r))
    avg_old_p50 = sum(avg_runs(old_results, r).get('p50_ms', 0) for r in rps_targets if avg_runs(old_results, r)) / max(len(rps_targets), 1)
    avg_new_p50 = sum(avg_runs(new_results, r).get('p50_ms', 0) for r in rps_targets if avg_runs(new_results, r)) / max(len(rps_targets), 1)
    avg_old_p99 = sum(avg_runs(old_results, r).get('p99_ms', 0) for r in rps_targets if avg_runs(old_results, r)) / max(len(rps_targets), 1)
    avg_new_p99 = sum(avg_runs(new_results, r).get('p99_ms', 0) for r in rps_targets if avg_runs(new_results, r)) / max(len(rps_targets), 1)

    if total_old_rps > 0:
        rps_change = ((total_new_rps - total_old_rps) / total_old_rps) * 100
        w(f"**Overall throughput change:** {rps_change:+.1f}% across all tested load levels")
    w()

    if avg_old_p50 > 0:
        p50_change = ((avg_new_p50 - avg_old_p50) / avg_old_p50) * 100
        w(f"**Median latency change:** {p50_change:+.1f}% (lower is better)")
    if avg_old_p99 > 0:
        p99_change = ((avg_new_p99 - avg_old_p99) / avg_old_p99) * 100
        w(f"**P99 latency change:** {p99_change:+.1f}% (lower is better)")
    w()

    w(f"**Key numbers:**")
    for rps in rps_targets:
        old_avg = avg_runs(old_results, rps)
        new_avg = avg_runs(new_results, rps)
        if old_avg and new_avg:
            w(f"- At {rps} req/s target: old achieved {old_avg.get('rps', 0):.0f}, new achieved {new_avg.get('rps', 0):.0f} req/s")
    w()

    if regressions:
        w("**Verdict:** Performance regressions detected. Review before promoting.")
        w()
        w("**Regressions:**")
        for r in regressions:
            w(f"- {r}")
    elif improvements:
        w("**Verdict:** `pantera:2.0.0` improves performance over `artipie:1.20.12`. Safe to promote.")
    else:
        w("**Verdict:** Performance is stable. `pantera:2.0.0` is safe to promote.")
    w()

    w("**Caveats:**")
    w("- Benchmarked on a single machine (Docker Desktop). Production may differ.")
    w("- Workload is Maven artifact download (1KB). Real traffic includes uploads, proxying, etc.")
    w("- JVM args were identical and specified by the user. Default image JVM args were overridden.")
    w("- Network is Docker bridge (localhost). Real latency will be higher.")
    w()

    # Write report
    report_path = os.path.join(RESULTS_DIR, "BENCHMARK-REPORT.md")
    with open(report_path, 'w') as f:
        f.write('\n'.join(lines))
    print(f"Report written to {report_path}")

    # Also write CSV summary
    csv_path = os.path.join(RESULTS_DIR, "comparison.csv")
    with open(csv_path, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['rate_target', 'label', 'rps', 'avg_ms', 'p50_ms', 'p90_ms', 'p95_ms', 'p99_ms', 'max_ms', 'errors', 'error_pct'])
        for rps in rps_targets:
            for label_name, results in [("old", old_results), ("new", new_results)]:
                avg = avg_runs(results, rps)
                if avg:
                    writer.writerow([
                        rps, label_name,
                        f"{avg.get('rps', 0):.1f}",
                        f"{avg.get('latency_avg_ms', 0):.2f}",
                        f"{avg.get('p50_ms', 0):.2f}",
                        f"{avg.get('p90_ms', 0):.2f}",
                        f"{avg.get('p95_ms', 0):.2f}",
                        f"{avg.get('p99_ms', 0):.2f}",
                        f"{avg.get('latency_slowest_ms', 0):.1f}",
                        f"{avg.get('error_count', 0):.0f}",
                        f"{avg.get('error_rate_pct', 0):.2f}"
                    ])
    print(f"CSV written to {csv_path}")

if __name__ == "__main__":
    generate_markdown()
