#!/usr/bin/env bash
# T-P14 cold-bench harness — runs a parameterised cold-cache
# dependency:resolve through a running Pantera and prints summary
# statistics in a CI-parseable form. Wraps cold-bench-10x.sh so the
# heavy lifting (state reset between iterations, metrics scrape) stays
# in one place.
#
# Usage:
#   run-cold-bench.sh <artifact-shorthand> [-i <iterations>]
#
#   artifact-shorthand : "sonar-maven-plugin"   → org.codehaus.mojo:sonar-maven-plugin:4.0.0.4121
#                        "<group>:<id>:<version>" → use that GAV directly
#   -i <iterations>    : number of cold runs (default 5)
#
# Environment:
#   SETTINGS, LOCAL_REPO, DB_CONTAINER, COMPOSE — same semantics as
#   performance/scripts/cold-bench-10x.sh.
#
# Output (stdout, last three lines, machine-parseable):
#   COLD_BENCH_MEDIAN_SECONDS=<seconds>
#   COLD_BENCH_P95_SECONDS=<seconds>
#   COLD_BENCH_ITERATIONS=<n>
#
# Exit status: 0 if the run completed; non-zero if the harness itself
# failed (Pantera unreachable, all mvn invocations failed, etc.).
# Threshold enforcement is left to the caller (e.g. perf-gate-check.sh
# or the CI workflow YAML) so this script is reusable across nightly
# observability runs and PR-level pass/fail gates.

set -euo pipefail

artifact_arg="${1:-sonar-maven-plugin}"
shift || true

iterations=5
while [ "$#" -gt 0 ]; do
    case "$1" in
        -i|--iterations)
            iterations="$2"; shift 2;;
        -h|--help)
            grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
        *)
            echo "Unknown arg: $1" >&2; exit 64;;
    esac
done

case "$artifact_arg" in
    sonar-maven-plugin)
        gav="org.codehaus.mojo:sonar-maven-plugin:4.0.0.4121";;
    *:*:*)
        gav="$artifact_arg";;
    *)
        echo "Bad artifact shorthand: '$artifact_arg' (use 'sonar-maven-plugin' or '<g>:<a>:<v>')" >&2
        exit 64;;
esac

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
work_dir="$(mktemp -d -t pantera-cold-bench.XXXXXX)"
results_csv="${work_dir}/cold-bench.csv"
summary_md="${work_dir}/cold-bench.md"

# Re-use cold-bench-10x.sh — already verified by repeated runs.
RUNS="${iterations}" \
RESULTS_FILE="${results_csv}" \
SUMMARY_FILE="${summary_md}" \
ARTIFACT_GAV="${gav}" \
    "${repo_root}/performance/scripts/cold-bench-10x.sh"

# Parse median + p95 from the summary markdown.
median="$(awk -F'[: ]' '/^- p50:/ { gsub("s","",$NF); print $NF }' "${summary_md}")"
p95="$(awk -F'[: ]' '/^- p95:/ { gsub("s","",$NF); print $NF }' "${summary_md}")"
if [ -z "${median}" ] || [ -z "${p95}" ]; then
    echo "ERROR: failed to parse median/p95 from ${summary_md}" >&2
    cat "${summary_md}" >&2
    exit 1
fi

echo "COLD_BENCH_MEDIAN_SECONDS=${median}"
echo "COLD_BENCH_P95_SECONDS=${p95}"
echo "COLD_BENCH_ITERATIONS=${iterations}"
echo "COLD_BENCH_CSV=${results_csv}"
echo "COLD_BENCH_SUMMARY=${summary_md}"
