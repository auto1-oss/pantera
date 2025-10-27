#!/bin/bash
# Artipie HTTP Client Leak Profiler using jattach
# Profiles JVM running inside Docker container to detect HTTP client leaks

set -e

CONTAINER="${CONTAINER:-artipie}"
PROXY_PATH="${PROXY_PATH:-/artifactory/maven-central}"
ARTIPIE_HOST="${ARTIPIE_HOST:-localhost}"
ARTIPIE_PORT="${ARTIPIE_PORT:-8081}"
ARTIPIE_USER="${ARTIPIE_USER:-ayd}"
ARTIPIE_PASS="${ARTIPIE_PASS:-ayd}"
LOAD_ITERATIONS="${LOAD_ITERATIONS:-100}"
CONCURRENT_REQUESTS="${CONCURRENT_REQUESTS:-20}"

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║     Artipie HTTP Client Leak Profiler (jattach)          ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""
echo "Configuration:"
echo "  Container: $CONTAINER"
echo "  Proxy: $ARTIPIE_HOST:$ARTIPIE_PORT$PROXY_PATH"
echo "  Auth: $ARTIPIE_USER:***"
echo "  Load: $LOAD_ITERATIONS iterations × $CONCURRENT_REQUESTS concurrent"
echo ""

# Check if container exists and is running
if ! docker ps --format "{{.Names}}" | grep -q "^${CONTAINER}$"; then
    echo "❌ ERROR: Container '$CONTAINER' not found or not running"
    echo ""
    echo "Available containers:"
    docker ps --format "table {{.Names}}\t{{.Status}}"
    exit 1
fi

echo "✓ Container found: $CONTAINER"
echo ""

# Find Java PID in container
echo "Finding Java process..."
JAVA_PID=$(docker exec "$CONTAINER" pgrep -f "java" | head -1)

if [ -z "$JAVA_PID" ]; then
    echo "❌ ERROR: No Java process found in container"
    exit 1
fi

echo "✓ Java PID: $JAVA_PID"
echo ""

# Download and install jattach in container
echo "═══════════════════════════════════════════════════════════"
echo "Installing jattach"
echo "═══════════════════════════════════════════════════════════"
echo ""

# Try different locations for jattach
JATTACH_LOCATIONS=("/tmp/jattach" "/var/tmp/jattach" "/opt/jattach" "./jattach")
JATTACH_PATH=""

# Check if jattach already exists in any location
for loc in "${JATTACH_LOCATIONS[@]}"; do
    if docker exec "$CONTAINER" test -f "$loc" && docker exec "$CONTAINER" test -x "$loc" 2>/dev/null; then
        JATTACH_PATH="$loc"
        echo "✓ jattach found at $JATTACH_PATH"
        break
    fi
done

if [ -z "$JATTACH_PATH" ]; then
    echo "Downloading jattach..."
    
    # Detect container architecture
    ARCH=$(docker exec "$CONTAINER" uname -m)
    case "$ARCH" in
        x86_64)
            JATTACH_ARCH="x64"
            ;;
        aarch64|arm64)
            JATTACH_ARCH="arm64"
            ;;
        *)
            echo "❌ Unsupported architecture: $ARCH"
            exit 1
            ;;
    esac
    
    echo "  Architecture: $ARCH (using $JATTACH_ARCH)"
    
    # Download jattach binary
    JATTACH_VERSION="2.2"
    JATTACH_URL="https://github.com/jattach/jattach/releases/download/v${JATTACH_VERSION}/jattach"
    
    # Download to host first
    HOST_JATTACH="/tmp/jattach-host-$$"
    if [ ! -f "$HOST_JATTACH" ]; then
        echo "  Downloading from GitHub..."
        curl -L -o "$HOST_JATTACH" "$JATTACH_URL" 2>/dev/null || {
            echo "❌ Failed to download jattach"
            echo "   Trying to build from source instead..."
            
            # Fallback: try to compile in container
            docker exec "$CONTAINER" sh -c '
                if command -v gcc > /dev/null 2>&1; then
                    cd /var/tmp 2>/dev/null || cd /tmp
                    wget -q https://github.com/jattach/jattach/archive/refs/tags/v2.2.tar.gz || exit 1
                    tar xzf v2.2.tar.gz
                    cd jattach-2.2
                    gcc -O2 -o jattach src/posix/jattach.c || exit 1
                    # Try to copy to writable location
                    if cp jattach /var/tmp/jattach 2>/dev/null; then
                        echo "/var/tmp/jattach"
                    elif cp jattach /opt/jattach 2>/dev/null; then
                        echo "/opt/jattach"
                    elif cp jattach ./jattach 2>/dev/null; then
                        pwd && echo "/jattach"
                    else
                        echo "ERROR: Cannot copy jattach to writable location" >&2
                        exit 1
                    fi
                    cd ..
                    rm -rf jattach-2.2 v2.2.tar.gz
                else
                    echo "ERROR: gcc not available in container" >&2
                    exit 1
                fi
            ' > /tmp/jattach_build_result_$$ 2>&1
            
            if [ $? -eq 0 ] && [ -s /tmp/jattach_build_result_$$ ]; then
                JATTACH_PATH=$(cat /tmp/jattach_build_result_$$)
                echo "✓ Built jattach at: $JATTACH_PATH"
                rm /tmp/jattach_build_result_$$
            else
                cat /tmp/jattach_build_result_$$ 2>/dev/null
                rm /tmp/jattach_build_result_$$ 2>/dev/null
                echo ""
                echo "Unable to install jattach. Falling back to basic profiling..."
                USE_JATTACH=false
                JATTACH_PATH=""
            fi
        }
        chmod +x "$HOST_JATTACH" 2>/dev/null || true
    fi
    
    # Copy to container if download succeeded
    if [ -f "$HOST_JATTACH" ] && [ "${USE_JATTACH:-true}" = "true" ] && [ -z "$JATTACH_PATH" ]; then
        # Try multiple locations to find a writable one
        for target_loc in "/var/tmp/jattach" "/opt/jattach" "/tmp/jattach"; do
            docker cp "$HOST_JATTACH" "$CONTAINER:$target_loc" 2>/dev/null && {
                # Try to make executable
                if docker exec "$CONTAINER" chmod +x "$target_loc" 2>/dev/null; then
                    JATTACH_PATH="$target_loc"
                    echo "✓ jattach installed to container:$JATTACH_PATH"
                    break
                elif docker exec "$CONTAINER" test -x "$target_loc" 2>/dev/null; then
                    # Already executable (maybe inherited from host)
                    JATTACH_PATH="$target_loc"
                    echo "✓ jattach copied to container:$JATTACH_PATH (already executable)"
                    break
                else
                    # Try to execute directly - some containers ignore +x but allow execution
                    if docker exec "$CONTAINER" "$target_loc" -h > /dev/null 2>&1; then
                        JATTACH_PATH="$target_loc"
                        echo "⚠️  jattach at $JATTACH_PATH (chmod failed but may work)"
                        break
                    fi
                fi
            }
        done
        
        if [ -z "$JATTACH_PATH" ]; then
            echo "❌ Failed to install jattach to container"
            echo "   Falling back to basic profiling..."
            USE_JATTACH=false
        fi
        
        rm "$HOST_JATTACH" 2>/dev/null || true
    fi
fi

# Test jattach
echo ""
echo "Testing jattach connection..."
if [ -n "$JATTACH_PATH" ]; then
    if docker exec "$CONTAINER" "$JATTACH_PATH" "$JAVA_PID" properties > /dev/null 2>&1; then
        echo "✓ jattach working"
        USE_JATTACH=true
    else
        echo "⚠️  jattach failed to attach, will use basic monitoring"
        USE_JATTACH=false
    fi
else
    echo "⚠️  jattach not available, will use basic monitoring"
    USE_JATTACH=false
fi

echo ""

# Create output directory
OUTPUT_DIR="profiling-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUTPUT_DIR"
echo "Output directory: $OUTPUT_DIR"
echo ""

# Baseline snapshot
echo "═══════════════════════════════════════════════════════════"
echo "Baseline Snapshot"
echo "═══════════════════════════════════════════════════════════"
echo ""

BASELINE_THREADS=$(docker exec "$CONTAINER" ps -T -p "$JAVA_PID" 2>/dev/null | wc -l)
echo "Threads: $BASELINE_THREADS"

# Initialize variables
BASELINE_HTTPCLIENT_THREADS=0
HTTPCLIENT_COUNT=0
DESTINATION_COUNT=0

if [ "$USE_JATTACH" = "true" ]; then
    echo ""
    echo "JVM Properties:"
    docker exec "$CONTAINER" "$JATTACH_PATH" "$JAVA_PID" properties > "$OUTPUT_DIR/baseline-properties.txt" 2>&1
    grep -E "java.version|java.vm.name|java.runtime.version" "$OUTPUT_DIR/baseline-properties.txt" | sed 's/^/  /'
    
    echo ""
    echo "Taking baseline heap histogram..."
    docker exec "$CONTAINER" "$JATTACH_PATH" "$JAVA_PID" jcmd "GC.class_histogram" > "$OUTPUT_DIR/baseline-histogram.txt" 2>&1
    
    # Count specific classes
    HTTPCLIENT_COUNT=$(grep -i "httpclient" "$OUTPUT_DIR/baseline-histogram.txt" | awk '{sum+=$2} END {print sum+0}')
    DESTINATION_COUNT=$(grep -i "destination" "$OUTPUT_DIR/baseline-histogram.txt" | awk '{sum+=$2} END {print sum+0}')
    
    echo "  HttpClient instances: $HTTPCLIENT_COUNT"
    echo "  Destination instances: $DESTINATION_COUNT"
    
    echo ""
    echo "Taking baseline thread dump..."
    docker exec "$CONTAINER" "$JATTACH_PATH" "$JAVA_PID" threaddump > "$OUTPUT_DIR/baseline-threads.txt" 2>&1
    
    BASELINE_HTTPCLIENT_THREADS=$(grep -c "HttpClient@" "$OUTPUT_DIR/baseline-threads.txt" 2>/dev/null || echo "0")
    BASELINE_HTTPCLIENT_THREADS=$(echo "$BASELINE_HTTPCLIENT_THREADS" | tr -d '\n\r ')
    echo "  HttpClient threads: $BASELINE_HTTPCLIENT_THREADS"
fi

echo ""

# Define test artifacts
ARTIFACTS=(
    "org/apache/maven/maven/3.8.1/maven-3.8.1.pom"
    "org/springframework/boot/spring-boot/2.7.0/spring-boot-2.7.0.pom"
    "com/google/guava/guava/31.1-jre/guava-31.1-jre.pom"
    "junit/junit/4.13.2/junit-4.13.2.pom"
    "org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.pom"
    "ch/qos/logback/logback-classic/1.2.11/logback-classic-1.2.11.pom"
    "com/fasterxml/jackson/core/jackson-core/2.13.3/jackson-core-2.13.3.pom"
    "org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.pom"
)

# Load test phase
echo "═══════════════════════════════════════════════════════════"
echo "Load Test Phase"
echo "═══════════════════════════════════════════════════════════"
echo ""

LOG_FILE="$OUTPUT_DIR/load-test.log"
echo "Load test log: $LOG_FILE"
echo ""

SUCCESS=0
ERRORS=0
TIMEOUTS=0

echo "Starting load test: $LOAD_ITERATIONS iterations..."
echo ""

for iter in $(seq 1 "$LOAD_ITERATIONS"); do
    # Send concurrent requests
    for i in $(seq 1 "$CONCURRENT_REQUESTS"); do
        ARTIFACT="${ARTIFACTS[$((RANDOM % ${#ARTIFACTS[@]}))]}"
        URL="http://${ARTIPIE_HOST}:${ARTIPIE_PORT}${PROXY_PATH}/${ARTIFACT}"
        
        (
            START=$(date +%s%N)
            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -u "$ARTIPIE_USER:$ARTIPIE_PASS" "$URL" 2>&1)
            END=$(date +%s%N)
            DURATION=$(( (END - START) / 1000000 ))
            
            case "$HTTP_CODE" in
                2*|3*) 
                    echo "S:$DURATION" >> /tmp/artipie_results_$$
                    ;;
                "000")
                    echo "T:$DURATION" >> /tmp/artipie_results_$$
                    ;;
                *)
                    echo "E:$HTTP_CODE:$DURATION" >> /tmp/artipie_results_$$
                    ;;
            esac
        ) &
    done
    
    # Wait for batch to complete
    wait
    
    # Aggregate results
    if [ -f /tmp/artipie_results_$$ ]; then
        while IFS=: read -r status rest; do
            case "$status" in
                S) SUCCESS=$((SUCCESS + 1)) ;;
                T) TIMEOUTS=$((TIMEOUTS + 1)) ;;
                E) ERRORS=$((ERRORS + 1)) ;;
            esac
        done < /tmp/artipie_results_$$
        rm /tmp/artipie_results_$$
    fi
    
    # Progress update every 10 iterations
    if [ $((iter % 10)) -eq 0 ]; then
        CURRENT_THREADS=$(docker exec "$CONTAINER" ps -T -p "$JAVA_PID" 2>/dev/null | wc -l)
        THREAD_DELTA=$((CURRENT_THREADS - BASELINE_THREADS))
        
        printf "[%3d/%3d] threads=%4d (Δ%+4d) | ✓%d ⏱%d ✗%d\n" \
            "$iter" "$LOAD_ITERATIONS" "$CURRENT_THREADS" "$THREAD_DELTA" "$SUCCESS" "$TIMEOUTS" "$ERRORS"
        
        # Take snapshot every 25 iterations if using jattach
        if [ "$USE_JATTACH" = "true" ] && [ $((iter % 25)) -eq 0 ]; then
            echo "  → Taking snapshot at iteration $iter..." | tee -a "$LOG_FILE"
            docker exec "$CONTAINER" "$JATTACH_PATH" "$JAVA_PID" jcmd "GC.class_histogram" > "$OUTPUT_DIR/histogram-iter${iter}.txt" 2>&1
        fi
    fi
    
    # Small delay between iterations
    sleep 0.1
done

echo ""
echo "Load test complete:"
echo "  Success: $SUCCESS"
echo "  Timeouts: $TIMEOUTS"
echo "  Errors: $ERRORS"
echo ""

# Immediate post-load analysis
echo "═══════════════════════════════════════════════════════════"
echo "Post-Load Analysis"
echo "═══════════════════════════════════════════════════════════"
echo ""

POSTLOAD_THREADS=$(docker exec "$CONTAINER" ps -T -p "$JAVA_PID" 2>/dev/null | wc -l)
POSTLOAD_DELTA=$((POSTLOAD_THREADS - BASELINE_THREADS))

echo "Immediate measurements:"
echo "  Threads: $POSTLOAD_THREADS (Δ +$POSTLOAD_DELTA)"

if [ "$USE_JATTACH" = "true" ]; then
    echo ""
    echo "Taking post-load snapshots..."
    
    # Thread dump
    echo "  1. Thread dump..."
    docker exec "$CONTAINER" "$JATTACH_PATH" "$JAVA_PID" threaddump > "$OUTPUT_DIR/postload-threads.txt" 2>&1
    
    POSTLOAD_HTTPCLIENT_THREADS=$(grep -c "HttpClient@" "$OUTPUT_DIR/postload-threads.txt" 2>/dev/null || echo "0")
    POSTLOAD_HTTPCLIENT_THREADS=$(echo "$POSTLOAD_HTTPCLIENT_THREADS" | tr -d '\n\r ')
    HTTPCLIENT_THREAD_DELTA=$((${POSTLOAD_HTTPCLIENT_THREADS:-0} - ${BASELINE_HTTPCLIENT_THREADS:-0}))
    echo "     HttpClient threads: $POSTLOAD_HTTPCLIENT_THREADS (Δ +$HTTPCLIENT_THREAD_DELTA)"
    
    # Heap histogram
    echo "  2. Heap histogram..."
    docker exec "$CONTAINER" "$JATTACH_PATH" "$JAVA_PID" jcmd "GC.class_histogram" > "$OUTPUT_DIR/postload-histogram.txt" 2>&1
    
    POSTLOAD_HTTPCLIENT_COUNT=$(grep -i "httpclient" "$OUTPUT_DIR/postload-histogram.txt" | awk '{sum+=$2} END {print sum+0}')
    POSTLOAD_DESTINATION_COUNT=$(grep -i "destination" "$OUTPUT_DIR/postload-histogram.txt" | awk '{sum+=$2} END {print sum+0}')
    
    echo "     HttpClient instances: $POSTLOAD_HTTPCLIENT_COUNT (baseline: $HTTPCLIENT_COUNT)"
    echo "     Destination instances: $POSTLOAD_DESTINATION_COUNT (baseline: $DESTINATION_COUNT)"
    
    # Heap dump (small)
    echo "  3. Heap dump (live objects only)..."
    docker exec "$CONTAINER" "$JATTACH_PATH" "$JAVA_PID" jcmd "GC.heap_dump -live /tmp/heap-postload.hprof" > "$OUTPUT_DIR/heapdump-postload.log" 2>&1
    
    # Copy heap dump from container
    if docker exec "$CONTAINER" test -f /tmp/heap-postload.hprof; then
        docker cp "$CONTAINER:/tmp/heap-postload.hprof" "$OUTPUT_DIR/" 2>/dev/null || echo "     ⚠️  Failed to copy heap dump"
        docker exec "$CONTAINER" rm -f /tmp/heap-postload.hprof
        
        if [ -f "$OUTPUT_DIR/heap-postload.hprof" ]; then
            HEAP_SIZE=$(du -h "$OUTPUT_DIR/heap-postload.hprof" | cut -f1)
            echo "     ✓ Heap dump saved: $OUTPUT_DIR/heap-postload.hprof ($HEAP_SIZE)"
        fi
    fi
fi

echo ""
echo "Waiting 30 seconds for cleanup to settle..."
sleep 30

# Final analysis
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "Final Analysis (after 30s cooldown)"
echo "═══════════════════════════════════════════════════════════"
echo ""

FINAL_THREADS=$(docker exec "$CONTAINER" ps -T -p "$JAVA_PID" 2>/dev/null | wc -l)
FINAL_DELTA=$((FINAL_THREADS - BASELINE_THREADS))

echo "Thread count:"
echo "  Baseline:      $BASELINE_THREADS"
echo "  Post-load:     $POSTLOAD_THREADS (Δ +$POSTLOAD_DELTA)"
echo "  After 30s:     $FINAL_THREADS (Δ +$FINAL_DELTA)"

if [ "$USE_JATTACH" = "true" ]; then
    echo ""
    echo "Taking final snapshots..."
    
    docker exec "$CONTAINER" "$JATTACH_PATH" "$JAVA_PID" threaddump > "$OUTPUT_DIR/final-threads.txt" 2>&1
    docker exec "$CONTAINER" "$JATTACH_PATH" "$JAVA_PID" jcmd "GC.class_histogram" > "$OUTPUT_DIR/final-histogram.txt" 2>&1
    
    FINAL_HTTPCLIENT_THREADS=$(grep -c "HttpClient@" "$OUTPUT_DIR/final-threads.txt" 2>/dev/null || echo "0")
    FINAL_HTTPCLIENT_THREADS=$(echo "$FINAL_HTTPCLIENT_THREADS" | tr -d '\n\r ')
    FINAL_HTTPCLIENT_COUNT=$(grep -i "httpclient" "$OUTPUT_DIR/final-histogram.txt" | awk '{sum+=$2} END {print sum+0}')
    FINAL_DESTINATION_COUNT=$(grep -i "destination" "$OUTPUT_DIR/final-histogram.txt" | awk '{sum+=$2} END {print sum+0}')
    
    echo ""
    echo "HttpClient metrics:"
    echo "  Threads:       $BASELINE_HTTPCLIENT_THREADS → $POSTLOAD_HTTPCLIENT_THREADS → $FINAL_HTTPCLIENT_THREADS"
    echo "  Instances:     $HTTPCLIENT_COUNT → $POSTLOAD_HTTPCLIENT_COUNT → $FINAL_HTTPCLIENT_COUNT"
    echo "  Destinations:  $DESTINATION_COUNT → $POSTLOAD_DESTINATION_COUNT → $FINAL_DESTINATION_COUNT"
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "LEAK ASSESSMENT"
echo "═══════════════════════════════════════════════════════════"
echo ""

LEAK_DETECTED=false
LEAK_SEVERITY="NONE"

# Assess leak based on thread count
if [ "$FINAL_DELTA" -gt 100 ]; then
    LEAK_SEVERITY="CRITICAL"
    LEAK_DETECTED=true
    echo "🔴 CRITICAL LEAK DETECTED"
    echo ""
    echo "   Thread increase: +$FINAL_DELTA threads persisting"
    
elif [ "$FINAL_DELTA" -gt 50 ]; then
    LEAK_SEVERITY="SEVERE"
    LEAK_DETECTED=true
    echo "🔴 SEVERE LEAK DETECTED"
    echo ""
    echo "   Thread increase: +$FINAL_DELTA threads persisting"
    
elif [ "$FINAL_DELTA" -gt 20 ]; then
    LEAK_SEVERITY="MODERATE"
    LEAK_DETECTED=true
    echo "⚠️  MODERATE LEAK DETECTED"
    echo ""
    echo "   Thread increase: +$FINAL_DELTA threads persisting"
    
elif [ "$FINAL_DELTA" -gt 10 ]; then
    LEAK_SEVERITY="MINOR"
    echo "⚠️  MINOR LEAK POSSIBLE"
    echo ""
    echo "   Thread increase: +$FINAL_DELTA threads (borderline)"
    
else
    echo "✅ NO SIGNIFICANT LEAK DETECTED"
    echo ""
    echo "   Thread increase: +$FINAL_DELTA threads (within normal range)"
fi

# Additional leak indicators
if [ "$USE_JATTACH" = "true" ]; then
    HTTPCLIENT_THREAD_INCREASE=$((${FINAL_HTTPCLIENT_THREADS:-0} - ${BASELINE_HTTPCLIENT_THREADS:-0}))
    
    if [ "$HTTPCLIENT_THREAD_INCREASE" -gt 20 ]; then
        echo ""
        echo "🔴 HttpClient thread accumulation: +$HTTPCLIENT_THREAD_INCREASE threads"
        LEAK_DETECTED=true
    fi
fi

# Provide recommendations
if [ "$LEAK_DETECTED" = "true" ]; then
    echo ""
    echo "─────────────────────────────────────────────────────────"
    echo "ROOT CAUSE ANALYSIS"
    echo "─────────────────────────────────────────────────────────"
    echo ""
    echo "Based on the analysis, the leak is likely caused by:"
    echo ""
    echo "1. RepositorySlices cache retention (Primary Issue)"
    echo "   Location: artipie-main/src/main/java/com/artipie/RepositorySlices.java"
    echo "   Problem:  expireAfterAccess(30, MINUTES) keeps JettyClientSlices alive too long"
    echo "   Impact:   Each cache entry holds a full HttpClient with connection pools"
    echo ""
    echo "2. Missing cache size limit"
    echo "   Problem:  No maximumSize() on cache = unbounded growth"
    echo "   Impact:   Multiple proxy repo variations accumulate"
    echo ""
    echo "3. JettyClientSlice chunk retention"
    echo "   Location: http-client/src/main/java/com/artipie/http/client/jetty/JettyClientSlice.java"
    echo "   Problem:  Demander.run() may not release chunks on all error paths"
    echo "   Impact:   Memory pressure and potential deadlocks"
    echo ""
    echo "─────────────────────────────────────────────────────────"
    echo "RECOMMENDED FIXES"
    echo "─────────────────────────────────────────────────────────"
    echo ""
    echo "1. Reduce cache expiration in RepositorySlices.java:65"
    echo "   Change:   expireAfterAccess(30, MINUTES)"
    echo "   To:       expireAfterAccess(5, MINUTES)"
    echo ""
    echo "2. Add cache size limit in RepositorySlices.java:65"
    echo "   Add:      .maximumSize(50)"
    echo ""
    echo "3. Add explicit cleanup on cache eviction"
    echo "   Add removalListener to call JettyClientSlices.stop() + destroy()"
    echo ""
    echo "4. Fix chunk cleanup in JettyClientSlice.java Demander"
    echo "   Ensure chunk.release() is called in all error paths"
    echo ""
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "PROFILING COMPLETE"
echo "═══════════════════════════════════════════════════════════"
echo ""

echo "All artifacts saved to: $OUTPUT_DIR/"
echo ""
echo "Files generated:"
ls -lh "$OUTPUT_DIR" | tail -n +2 | awk '{printf "  %-40s %8s\n", $9, $5}'

echo ""
echo "Next steps:"
echo ""
echo "  1. Analyze thread dumps for stuck threads:"
echo "     grep -A 10 'BLOCKED\\|WAITING' $OUTPUT_DIR/*threads.txt"
echo ""
echo "  2. Compare heap histograms:"
echo "     diff -u $OUTPUT_DIR/baseline-histogram.txt $OUTPUT_DIR/final-histogram.txt | grep '^[+-]' | grep -i 'http\\|jetty\\|destination'"
echo ""
echo "  3. Analyze heap dump (if available) with:"
echo "     jvisualvm --openfile $OUTPUT_DIR/heap-postload.hprof"
echo "     OR"
echo "     mat $OUTPUT_DIR/heap-postload.hprof  # Eclipse Memory Analyzer"
echo ""
echo "  4. Check container logs:"
echo "     docker logs $CONTAINER > $OUTPUT_DIR/container.log"
echo ""
echo "  5. Review fixes in HTTP_CLIENT_LEAK_ANALYSIS.md"
echo ""

# Generate summary report
cat > "$OUTPUT_DIR/SUMMARY.txt" << EOF
Artipie HTTP Client Leak Profiling Summary
═══════════════════════════════════════════════════════════

Date: $(date)
Container: $CONTAINER
Proxy: $ARTIPIE_HOST:$ARTIPIE_PORT$PROXY_PATH

Test Configuration:
  Iterations: $LOAD_ITERATIONS
  Concurrent requests: $CONCURRENT_REQUESTS
  Total requests: $((LOAD_ITERATIONS * CONCURRENT_REQUESTS))

Results:
  Success: $SUCCESS
  Timeouts: $TIMEOUTS
  Errors: $ERRORS

Thread Analysis:
  Baseline:    $BASELINE_THREADS threads
  Post-load:   $POSTLOAD_THREADS threads (Δ +$POSTLOAD_DELTA)
  Final (30s): $FINAL_THREADS threads (Δ +$FINAL_DELTA)

EOF

if [ "$USE_JATTACH" = "true" ]; then
    cat >> "$OUTPUT_DIR/SUMMARY.txt" << EOF
HttpClient Analysis:
  Threads:     ${BASELINE_HTTPCLIENT_THREADS:-0} → ${POSTLOAD_HTTPCLIENT_THREADS:-0} → ${FINAL_HTTPCLIENT_THREADS:-0}
  Instances:   ${HTTPCLIENT_COUNT:-0} → ${POSTLOAD_HTTPCLIENT_COUNT:-0} → ${FINAL_HTTPCLIENT_COUNT:-0}
  Destinations: ${DESTINATION_COUNT:-0} → ${POSTLOAD_DESTINATION_COUNT:-0} → ${FINAL_DESTINATION_COUNT:-0}

EOF
fi

cat >> "$OUTPUT_DIR/SUMMARY.txt" << EOF
Leak Assessment: $LEAK_SEVERITY
$([ "$LEAK_DETECTED" = "true" ] && echo "Status: LEAK DETECTED" || echo "Status: NO LEAK")

Files Generated:
$(ls -1 "$OUTPUT_DIR" | grep -v SUMMARY.txt | sed 's/^/  - /')

EOF

echo "Summary report: $OUTPUT_DIR/SUMMARY.txt"
echo ""
