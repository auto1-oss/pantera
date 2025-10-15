# Testing Guide

## Pre-Production Testing Checklist

### 1. Binary Verification
```bash
# Check binary exists and is executable
ls -lh target/release/artipie-import-cli
file target/release/artipie-import-cli

# Test help
./target/release/artipie-import-cli --help

# Test version
./target/release/artipie-import-cli --version
```

### 2. Dry Run Test
```bash
# Test scanning without uploading
./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir /mnt/artifactory_migration/ \
  --token YOUR_TOKEN \
  --dry-run
```

**Expected Output:**
```
INFO Artipie Import CLI v1.0.0
INFO Configuration:
INFO   Server: https://artipie.prod.services.auto1.team
INFO   Export dir: "/mnt/artifactory_migration/"
INFO   Batch size: 1000
INFO Scanning for artifacts...
INFO   Found 100000 artifacts...
INFO   Found 1000000 artifacts...
INFO Found 1926657 total artifacts in 45.23s
INFO DRY RUN - Would process 1926657 tasks
```

### 3. Small Batch Test (100 files)
```bash
# Create test directory with 100 files
mkdir -p test-export/test-repo
cp /mnt/artifactory_migration/some-repo/* test-export/test-repo/ | head -100

# Run import
./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir test-export/ \
  --token YOUR_TOKEN \
  --concurrency 10 \
  --batch-size 50 \
  --progress-log test-progress.log \
  --failures-dir test-failed \
  --report test-report.json \
  --verbose
```

**Verify:**
- All 100 files uploaded
- `test-progress.log` has 100 lines
- `test-report.json` shows correct stats
- No failures in `test-failed/`

### 4. Resume Test
```bash
# Start import
./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir test-export/ \
  --token YOUR_TOKEN \
  --progress-log test-progress.log

# Kill after 50 files (Ctrl+C)

# Resume
./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir test-export/ \
  --token YOUR_TOKEN \
  --progress-log test-progress.log \
  --resume
```

**Verify:**
- Second run skips first 50 files
- Completes remaining files
- No duplicates in progress log

### 5. Retry Test
```bash
# Simulate network issues by stopping server temporarily
# Run import with verbose logging
./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir test-export/ \
  --token YOUR_TOKEN \
  --max-retries 3 \
  --verbose
```

**Verify:**
- Retry messages appear in logs
- Files eventually succeed or fail after max retries

### 6. Concurrency Test
```bash
# Test with different concurrency levels
for concurrency in 10 50 100 200; do
  echo "Testing concurrency: $concurrency"
  time ./target/release/artipie-import-cli \
    --url https://artipie.prod.services.auto1.team \
    --export-dir test-export/ \
    --token YOUR_TOKEN \
    --concurrency $concurrency \
    --progress-log test-progress-$concurrency.log
done
```

**Verify:**
- Higher concurrency = faster completion
- No errors at high concurrency
- Memory usage stays reasonable

### 7. Memory Leak Test
```bash
# Monitor memory during import
./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir /mnt/artifactory_migration/ \
  --token YOUR_TOKEN \
  --concurrency 500 &

PID=$!

# Monitor memory every 10 seconds
while kill -0 $PID 2>/dev/null; do
  ps -p $PID -o rss,vsz,pmem,comm
  sleep 10
done
```

**Verify:**
- Memory stays constant (50-200 MB)
- No gradual increase over time

### 8. Large File Test
```bash
# Test with files > 1 GB
# Create test file
dd if=/dev/zero of=test-export/test-repo/large-file.bin bs=1M count=2048

./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir test-export/ \
  --token YOUR_TOKEN \
  --timeout 600 \
  --verbose
```

**Verify:**
- Large files upload successfully
- No timeout errors
- Memory usage stays reasonable

### 9. Failure Handling Test
```bash
# Test with invalid token (should fail)
./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir test-export/ \
  --token INVALID_TOKEN \
  --max-retries 2

# Check failures
cat test-failed/*.txt
```

**Verify:**
- Failures logged correctly
- Retry attempts visible in logs
- Exit code is non-zero

### 10. Progress Tracking Test
```bash
# Start import
./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir /mnt/artifactory_migration/ \
  --token YOUR_TOKEN \
  --progress-log progress.log &

# Monitor progress in another terminal
watch -n 5 'wc -l progress.log'
watch -n 5 'tail -1 progress.log'
```

**Verify:**
- Progress log updates in real-time
- Each line has correct format: `key|repo|path|status`
- No duplicate entries

## Performance Benchmarks

### Expected Performance (Reference)

| Artifact Count | Avg Size | Concurrency | Time | Files/sec | MB/sec |
|----------------|----------|-------------|------|-----------|--------|
| 1,000 | 1 MB | 50 | 10s | 100 | 100 |
| 10,000 | 1 MB | 100 | 1m | 166 | 166 |
| 100,000 | 1 MB | 200 | 5m | 333 | 333 |
| 1,000,000 | 1 MB | 500 | 30m | 555 | 555 |
| 10,000,000 | 1 MB | 1000 | 4h | 694 | 694 |

### Your Benchmark Results

Run this to establish baseline:
```bash
# 1K files test
time ./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir test-1k/ \
  --token YOUR_TOKEN \
  --concurrency 50

# Record: _____ files/sec, _____ MB/sec
```

## Stress Testing

### Maximum Concurrency Test
```bash
# Find maximum stable concurrency
for concurrency in 100 500 1000 2000 5000; do
  echo "Testing concurrency: $concurrency"
  ./target/release/artipie-import-cli \
    --url https://artipie.prod.services.auto1.team \
    --export-dir test-export/ \
    --token YOUR_TOKEN \
    --concurrency $concurrency \
    --verbose 2>&1 | tee stress-test-$concurrency.log
  
  # Check for errors
  if grep -q "ERROR" stress-test-$concurrency.log; then
    echo "FAILED at concurrency $concurrency"
    break
  fi
done
```

### Long-Running Stability Test
```bash
# Run for 24 hours
timeout 86400 ./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir /mnt/artifactory_migration/ \
  --token YOUR_TOKEN \
  --concurrency 500 \
  --resume
```

**Monitor:**
- Memory usage over time
- CPU usage
- Network connections
- File descriptors

## Integration Testing

### Test with Real Artipie Server
```bash
# 1. Start local Artipie server
docker run -d -p 8080:8080 artipie/artipie:latest

# 2. Create test repository
curl -X PUT http://localhost:8080/api/repos/test-repo \
  -H "Authorization: Bearer test-token"

# 3. Run import
./target/release/artipie-import-cli \
  --url http://localhost:8080 \
  --export-dir test-export/ \
  --token test-token \
  --concurrency 10

# 4. Verify artifacts in Artipie
curl http://localhost:8080/test-repo/ \
  -H "Authorization: Bearer test-token"
```

## Automated Test Suite

Create `run-tests.sh`:
```bash
#!/bin/bash
set -e

echo "=== Artipie Import CLI Test Suite ==="

# Test 1: Binary exists
echo "[1/10] Testing binary..."
./target/release/artipie-import-cli --version

# Test 2: Help works
echo "[2/10] Testing help..."
./target/release/artipie-import-cli --help > /dev/null

# Test 3: Dry run
echo "[3/10] Testing dry run..."
./target/release/artipie-import-cli \
  --url https://artipie.example.com \
  --export-dir test-export/ \
  --token test \
  --dry-run

# Test 4: Small import
echo "[4/10] Testing small import..."
./target/release/artipie-import-cli \
  --url https://artipie.example.com \
  --export-dir test-export/ \
  --token test \
  --concurrency 5 \
  --progress-log test-progress.log

# Test 5: Resume
echo "[5/10] Testing resume..."
./target/release/artipie-import-cli \
  --url https://artipie.example.com \
  --export-dir test-export/ \
  --token test \
  --progress-log test-progress.log \
  --resume

# Test 6: Progress log format
echo "[6/10] Validating progress log..."
if ! grep -q "|" test-progress.log; then
  echo "ERROR: Invalid progress log format"
  exit 1
fi

# Test 7: Report generation
echo "[7/10] Validating report..."
if [ ! -f import_report.json ]; then
  echo "ERROR: Report not generated"
  exit 1
fi

# Test 8: JSON report format
echo "[8/10] Validating JSON report..."
jq . import_report.json > /dev/null

# Test 9: Memory usage
echo "[9/10] Testing memory usage..."
./target/release/artipie-import-cli \
  --url https://artipie.example.com \
  --export-dir test-export/ \
  --token test \
  --concurrency 100 &
PID=$!
sleep 5
MEM=$(ps -p $PID -o rss= | awk '{print $1/1024}')
kill $PID 2>/dev/null || true
if [ $(echo "$MEM > 500" | bc) -eq 1 ]; then
  echo "ERROR: Memory usage too high: ${MEM}MB"
  exit 1
fi

# Test 10: Cleanup
echo "[10/10] Cleanup..."
rm -f test-progress.log import_report.json
rm -rf test-failed/

echo "=== All Tests Passed ==="
```

Run tests:
```bash
chmod +x run-tests.sh
./run-tests.sh
```

## Production Readiness Checklist

- [ ] Binary builds successfully
- [ ] Help and version work
- [ ] Dry run completes without errors
- [ ] Small batch (100 files) uploads successfully
- [ ] Resume functionality works
- [ ] Retry logic works
- [ ] Progress log format is correct
- [ ] JSON report is valid
- [ ] Memory usage < 200 MB
- [ ] No memory leaks over 1 hour
- [ ] Handles large files (> 1 GB)
- [ ] Handles failures gracefully
- [ ] Concurrent uploads work (100+)
- [ ] Performance meets expectations
- [ ] Integration with Artipie works
- [ ] Documentation is complete

## Sign-Off

```
Tested by: _______________
Date: _______________
Environment: _______________
Results: PASS / FAIL
Notes: _______________
```

---

**Once all tests pass, the CLI is ready for production deployment!** ✅
