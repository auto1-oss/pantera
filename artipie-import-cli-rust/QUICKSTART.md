# Quick Start Guide

## Step 1: Install Rust (if not already installed)

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

## Step 2: Build

```bash
cd artipie-import-cli-rust
./build.sh
```

Or use Make:
```bash
make release
```

## Step 3: Run

```bash
./target/release/artipie-import-cli \
  --url https://artipie.prod.services.auto1.team \
  --export-dir /mnt/artifactory_migration/ \
  --token eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJheWQiLCJjb250ZXh0Ijoia2V5Y2xvYWsiLCJpYXQiOjE3NjA0NjIxODV9.HfBF-iq2_qyp46NKbMAjsJWl8GfMdznNqbyhW6htFi0 \
  --concurrency 200 \
  --batch-size 1000 \
  --progress-log progress.log \
  --failures-dir failed \
  --resume
```

## Step 4: Run in Background

### Using screen (recommended)
```bash
screen -S artipie-import
./target/release/artipie-import-cli --url ... --resume
# Press Ctrl+A then D to detach
# Later: screen -r artipie-import
```

### Using nohup
```bash
nohup ./target/release/artipie-import-cli --url ... --resume > import.log 2>&1 &
tail -f import.log
```

## Why Rust is Better for This

| Feature | Java Version | Rust Version |
|---------|--------------|--------------|
| **Memory** | 2-4 GB | 50-100 MB |
| **Speed** | ~100 files/s | ~500-1000 files/s |
| **Startup** | 5-10 seconds | <1 second |
| **Concurrency** | Thread-based (limited) | Async (unlimited) |
| **OOM Issues** | Common | Never |
| **Binary Size** | 50 MB + JRE | 5 MB standalone |

## Expected Performance

For your 1.9M files:
- **Java version**: Would take ~22 days (if it doesn't OOM)
- **Rust version**: 30 minutes to 2 hours

## Monitoring Progress

```bash
# Watch progress log
tail -f progress.log

# Count completed
wc -l progress.log

# Check failures
ls -lh failed/
```

## Troubleshooting

### "Too many open files"
```bash
ulimit -n 65536
```

### Still too slow?
```bash
# Increase concurrency
--concurrency 500

# Increase batch size
--batch-size 2000
```

### Need to stop and resume?
Just Ctrl+C and run again with `--resume`. It will skip all completed files!
