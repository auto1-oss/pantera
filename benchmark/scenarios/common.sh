#!/usr/bin/env bash
##
## Shared timing and measurement functions for benchmark scenarios.
## Source this file from each scenario script.
##
## Provides:
##   now_ms          - Current time in milliseconds
##   run_timed       - Run a command, output "duration_ms exit_code"
##   run_benchmark   - Run N ops at given concurrency, collect timings
##   compute_stats   - From timing file, compute mean/p50/p95/p99/max
##   csv_header      - Write CSV header
##   csv_row         - Append a CSV row from stats
##

# Millisecond-precision timestamp (works on macOS + Linux)
now_ms() {
    perl -MTime::HiRes=time -e 'printf "%d\n", time*1000'
}

# Run a command and record timing.
# Outputs: <duration_ms> <exit_code>
# Usage: run_timed <cmd> [args...]
run_timed() {
    local start end rc
    start=$(now_ms)
    "$@" >/dev/null 2>&1
    rc=$?
    end=$(now_ms)
    echo "$((end - start)) $rc"
}

# Run a benchmark: execute a command function at the given concurrency.
# Each parallel worker writes its timing to $timing_dir.
#
# Usage: run_benchmark <concurrency> <total_ops> <timing_dir> <cmd_fn> [args...]
#   cmd_fn is called as: cmd_fn <worker_id> [args...]
#   It must be a function (exported with export -f).
#   total_ops is split into rounds of $concurrency parallel workers.
#
# Output files in $timing_dir: one line per op with "duration_ms exit_code"
run_benchmark() {
    local conc="$1" total_ops="$2" timing_dir="$3"
    shift 3
    local cmd_fn="$1"
    shift

    mkdir -p "$timing_dir"
    find "$timing_dir" -name '*.txt' -delete 2>/dev/null || true

    local rounds=$(( (total_ops + conc - 1) / conc ))
    local op_id=0

    for round in $(seq 1 "$rounds"); do
        local batch_size=$conc
        local remaining=$(( total_ops - op_id ))
        [[ $remaining -lt $batch_size ]] && batch_size=$remaining
        [[ $batch_size -le 0 ]] && break

        for worker in $(seq 1 "$batch_size"); do
            op_id=$((op_id + 1))
            (
                local start end rc
                start=$(now_ms)
                "$cmd_fn" "$op_id" "$@" >/dev/null 2>&1
                rc=$?
                end=$(now_ms)
                echo "$((end - start)) $rc" >> "$timing_dir/op-${op_id}.txt"
            ) &
        done
        wait
    done
}

# Compute statistics from a timing directory.
# Reads all op-*.txt files, extracts durations.
# Outputs: mean p50 p95 p99 max error_count total_count
compute_stats() {
    local timing_dir="$1"

    # Collect all timings into one file
    local all_file="${timing_dir}/_all.txt"
    find "$timing_dir" -name 'op-*.txt' -exec cat {} + > "$all_file" 2>/dev/null || true

    if [[ ! -s "$all_file" ]]; then
        echo "0 0 0 0 0 0 0"
        return
    fi

    # Sort durations externally (macOS awk lacks asort), then compute stats
    local sorted_file="${timing_dir}/_sorted.txt"
    awk '{print $1, $2}' "$all_file" | sort -n -k1,1 > "$sorted_file"

    awk '
    {
        d[NR] = $1
        if ($2 != 0) errors++
        sum += $1
        total++
    }
    END {
        if (total == 0) { print "0 0 0 0 0 0 0"; exit }
        n = total
        mean = sum / n
        p50_idx = int(n * 0.50) + 1; if (p50_idx > n) p50_idx = n
        p95_idx = int(n * 0.95) + 1; if (p95_idx > n) p95_idx = n
        p99_idx = int(n * 0.99) + 1; if (p99_idx > n) p99_idx = n
        printf "%.0f %.0f %.0f %.0f %.0f %d %d\n", mean, d[p50_idx], d[p95_idx], d[p99_idx], d[n], errors+0, total
    }' "$sorted_file"
}

# Compute wall-clock time for all ops in a timing directory.
# This is the total elapsed time from first op start to last op end,
# approximated as the max duration in the last round.
# For throughput: ops / wall_time_seconds
compute_wall_time_ms() {
    local timing_dir="$1"
    # Sum of round wall times ≈ total wall time
    # Each round's wall time = max duration in that round
    # For simplicity, sum all durations / concurrency gives approximate wall time
    # But actually: we track total elapsed externally. Just output it.
    find "$timing_dir" -name 'op-*.txt' -exec cat {} + 2>/dev/null | awk '
    { if ($1 > max) max = $1; total++ }
    END { print (total > 0 ? max : 0) }
    '
}

CSV_HEADER="scenario,version,size,concurrency,ops,rps,latency_mean_ms,latency_p50_ms,latency_p95_ms,latency_p99_ms,latency_max_ms,error_pct,transfer_mbps"

csv_header() {
    local csv_file="$1"
    echo "$CSV_HEADER" > "$csv_file"
}

# Append a CSV row from benchmark results.
# Usage: csv_row <csv_file> <scenario> <version> <size> <concurrency> <timing_dir> <wall_time_ms> <bytes_per_op>
csv_row() {
    local csv_file="$1" scenario="$2" version="$3" size="$4" conc="$5"
    local timing_dir="$6" wall_time_ms="$7" bytes_per_op="${8:-0}"

    local stats
    stats=$(compute_stats "$timing_dir")
    read -r mean p50 p95 p99 max errors total <<< "$stats"

    local rps="0"
    if [[ "$wall_time_ms" -gt 0 ]]; then
        rps=$(echo "scale=2; $total * 1000 / $wall_time_ms" | bc 2>/dev/null || echo "0")
    fi

    local error_pct="0"
    if [[ "$total" -gt 0 ]]; then
        error_pct=$(echo "scale=1; $errors * 100 / $total" | bc 2>/dev/null || echo "0")
    fi

    local transfer_mbps="0"
    local successful=$((total - errors))
    if [[ "$wall_time_ms" -gt 0 && "$bytes_per_op" -gt 0 && "$successful" -gt 0 ]]; then
        transfer_mbps=$(echo "scale=2; $successful * $bytes_per_op / 1048576 / ($wall_time_ms / 1000)" | bc 2>/dev/null || echo "0")
    fi

    echo "${scenario},${version},${size},${conc},${total},${rps},${mean},${p50},${p95},${p99},${max},${error_pct},${transfer_mbps}" \
        >> "$csv_file"
}

# Log helper
bench_log() {
    echo "[$(basename "$0" .sh)] $*"
}
