# Disk Cache Periodic Cleanup Configuration

**Date:** October 23, 2025  
**Component:** DiskCacheStorage (S3 Storage with Disk Cache)

---

## Overview

The periodic cleanup is **automatically configured** when you enable disk caching for S3 storage. It runs in the background to:

1. **Evict old cached files** when disk usage exceeds high watermark
2. **Clean up orphaned .part- files** from crashed writes (NEW)
3. **Clean up orphaned .meta files** without data files (NEW)

---

## Configuration Location

**File:** Repository YAML configuration (e.g., `_storages.yaml` or individual repo configs)

**Section:** Under `storage` → `cache`

---

## Configuration Parameters

### Basic Configuration

```yaml
storage:
  type: s3
  bucket: my-bucket
  region: us-east-1
  
  # Enable disk cache
  cache:
    enabled: true                          # Enable/disable caching
    path: /var/artipie/cache              # Cache directory path
    max-bytes: 10737418240                # 10GB max cache size
```

### Advanced Configuration

```yaml
storage:
  type: s3
  bucket: my-bucket
  region: us-east-1
  
  cache:
    enabled: true
    path: /var/artipie/cache
    max-bytes: 10737418240                # 10GB (default)
    
    # Cleanup scheduling
    cleanup-interval-millis: 300000       # 5 minutes (default)
    
    # Watermark thresholds
    high-watermark-percent: 90            # Start cleanup at 90% (default)
    low-watermark-percent: 80             # Clean down to 80% (default)
    
    # Eviction policy
    eviction-policy: LRU                  # LRU or LFU (default: LRU)
    
    # Validation
    validate-on-read: false               # Disable for performance (default: true)
```

---

## Cleanup Parameters Explained

### 1. `cleanup-interval-millis`

**What it does:** How often the cleanup task runs

**Default:** `300000` (5 minutes)

**Options:**
- `60000` = 1 minute (aggressive cleanup)
- `300000` = 5 minutes (default, balanced)
- `600000` = 10 minutes (less aggressive)
- `1800000` = 30 minutes (minimal overhead)
- `0` = Disable periodic cleanup (NOT RECOMMENDED)

**Recommendation:**
- **High traffic:** 60000-300000 (1-5 minutes)
- **Medium traffic:** 300000-600000 (5-10 minutes)
- **Low traffic:** 600000-1800000 (10-30 minutes)

**Example:**
```yaml
cache:
  cleanup-interval-millis: 60000  # Run every 1 minute
```

---

### 2. `high-watermark-percent`

**What it does:** Cleanup starts when cache usage exceeds this percentage

**Default:** `90`

**Options:** 50-95

**How it works:**
- If `max-bytes: 10GB` and `high-watermark-percent: 90`
- Cleanup triggers when cache reaches 9GB

**Recommendation:**
- **Aggressive cleanup:** 70-80%
- **Balanced:** 85-90% (default)
- **Minimal cleanup:** 90-95%

**Example:**
```yaml
cache:
  max-bytes: 10737418240        # 10GB
  high-watermark-percent: 85    # Cleanup at 8.5GB
```

---

### 3. `low-watermark-percent`

**What it does:** Cleanup stops when cache usage drops to this percentage

**Default:** `80`

**Options:** 50-90 (must be < high-watermark-percent)

**How it works:**
- If `max-bytes: 10GB` and `low-watermark-percent: 80`
- Cleanup deletes files until cache is 8GB

**Recommendation:**
- Keep 5-10% gap from high watermark
- `high: 90, low: 80` (default)
- `high: 85, low: 75` (more aggressive)

**Example:**
```yaml
cache:
  high-watermark-percent: 90
  low-watermark-percent: 75     # Clean down to 7.5GB
```

---

### 4. `eviction-policy`

**What it does:** Determines which files to delete first

**Default:** `LRU` (Least Recently Used)

**Options:**
- **`LRU`** - Delete files not accessed recently (time-based)
- **`LFU`** - Delete files accessed least frequently (hit-count-based)

**When to use:**
- **LRU:** Most common, good for general use
- **LFU:** When you want to keep frequently accessed files

**Example:**
```yaml
cache:
  eviction-policy: LFU  # Keep popular files
```

---

### 5. `validate-on-read`

**What it does:** Checks if cached file matches remote on every read

**Default:** `true`

**Options:**
- `true` - Validate every read (slower, safer)
- `false` - Trust cache (faster, recommended)

**Performance Impact:**
- `true`: Adds 50-200ms per request (remote metadata check)
- `false`: No overhead, serves from cache immediately

**Recommendation:** Set to `false` for production performance

**Example:**
```yaml
cache:
  validate-on-read: false  # Better performance
```

---

## Shared Cleanup Thread Pool

### Architecture

All DiskCacheStorage instances share a **single thread pool** for cleanup:

```java
private static final ScheduledExecutorService SHARED_CLEANER = 
    Executors.newScheduledThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 4)
    );
```

### Thread Count

**Formula:** `max(2, CPU_cores / 4)`

**Examples:**
- 4 CPU cores → 2 threads
- 8 CPU cores → 2 threads
- 16 CPU cores → 4 threads
- 32 CPU cores → 8 threads

**Why shared?**
- Prevents thread proliferation (100 repos = 100 threads)
- Reduces memory overhead
- Better resource utilization

---

## What Gets Cleaned Up

### During Periodic Cleanup (Every `cleanup-interval-millis`)

1. **Orphaned .part- files** (NEW FIX)
   - Files older than 1 hour
   - Left behind by crashes/kills
   - Pattern: `*.part-<UUID>`

2. **Orphaned .meta files** (NEW FIX)
   - Metadata files without corresponding data files
   - Pattern: `*.meta` without matching data file

3. **Cache eviction** (existing)
   - When cache exceeds high watermark
   - Deletes files based on eviction policy
   - Stops at low watermark

---

## Configuration Examples

### Example 1: High-Traffic Production

```yaml
storage:
  type: s3
  bucket: production-artifacts
  region: us-east-1
  
  cache:
    enabled: true
    path: /var/artipie/cache
    max-bytes: 53687091200              # 50GB
    cleanup-interval-millis: 60000      # 1 minute (aggressive)
    high-watermark-percent: 85          # Start at 42.5GB
    low-watermark-percent: 75           # Clean to 37.5GB
    eviction-policy: LRU
    validate-on-read: false             # Performance
```

**Behavior:**
- Checks every 1 minute
- Cleans when cache > 42.5GB
- Removes ~5GB of old files
- No validation overhead

---

### Example 2: Medium-Traffic Staging

```yaml
storage:
  type: s3
  bucket: staging-artifacts
  
  cache:
    enabled: true
    path: /var/artipie/cache
    max-bytes: 21474836480              # 20GB
    cleanup-interval-millis: 300000     # 5 minutes (default)
    high-watermark-percent: 90          # Start at 18GB
    low-watermark-percent: 80           # Clean to 16GB
    eviction-policy: LRU
    validate-on-read: false
```

**Behavior:**
- Checks every 5 minutes
- Cleans when cache > 18GB
- Removes ~2GB of old files

---

### Example 3: Low-Traffic Development

```yaml
storage:
  type: s3
  bucket: dev-artifacts
  
  cache:
    enabled: true
    path: /var/artipie/cache
    max-bytes: 5368709120               # 5GB
    cleanup-interval-millis: 600000     # 10 minutes
    high-watermark-percent: 90          # Start at 4.5GB
    low-watermark-percent: 80           # Clean to 4GB
    eviction-policy: LRU
    validate-on-read: true              # Can afford validation
```

**Behavior:**
- Checks every 10 minutes
- Cleans when cache > 4.5GB
- Removes ~500MB of old files
- Validates on read (slower but safer)

---

### Example 4: Minimal Cleanup (Not Recommended)

```yaml
storage:
  type: s3
  bucket: archive-artifacts
  
  cache:
    enabled: true
    path: /var/artipie/cache
    max-bytes: 107374182400             # 100GB
    cleanup-interval-millis: 1800000    # 30 minutes
    high-watermark-percent: 95          # Start at 95GB
    low-watermark-percent: 90           # Clean to 90GB
    eviction-policy: LFU                # Keep popular files
    validate-on-read: false
```

**Behavior:**
- Checks every 30 minutes
- Only cleans when cache > 95GB
- Minimal cleanup overhead
- Risk: .part- files accumulate longer

---

## Monitoring Cleanup

### Check Cleanup Activity

```bash
# Check cleanup thread
ps aux | grep disk-cache-cleaner

# Monitor cache size
du -sh /var/artipie/cache

# Count temp files
find /var/artipie/cache -name "*.part-*" | wc -l
find /var/artipie/cache -name "*.meta" | wc -l

# Check inode usage
df -i /var/artipie
```

### Log Messages

Look for these in Artipie logs:

```
[disk-cache-cleaner] Cleanup started for namespace: <hash>
[disk-cache-cleaner] Cleaned up 1234 orphaned .part- files
[disk-cache-cleaner] Cleaned up 567 orphaned .meta files
[disk-cache-cleaner] Cache usage: 9.2GB / 10GB (92%)
[disk-cache-cleaner] Evicted 234 files, freed 2.1GB
[disk-cache-cleaner] Cache usage after cleanup: 7.1GB / 10GB (71%)
```

---

## Troubleshooting

### Problem: Cache fills up too quickly

**Solution:**
```yaml
cache:
  cleanup-interval-millis: 60000    # Increase frequency
  high-watermark-percent: 80        # Lower threshold
```

---

### Problem: Too much cleanup overhead

**Solution:**
```yaml
cache:
  cleanup-interval-millis: 600000   # Decrease frequency
  high-watermark-percent: 95        # Higher threshold
```

---

### Problem: Orphaned files accumulating

**Check:**
```bash
find /var/artipie/cache -name "*.part-*" -mmin +60 | wc -l
```

**Solution:**
- Ensure `cleanup-interval-millis` is set (not 0)
- Check logs for cleanup errors
- Verify disk permissions

---

### Problem: High CPU during cleanup

**Solution:**
```yaml
cache:
  cleanup-interval-millis: 600000   # Less frequent
  max-bytes: 5368709120             # Smaller cache
```

---

## Performance Tuning

### For High IOPS

```yaml
cache:
  cleanup-interval-millis: 60000    # Frequent cleanup
  high-watermark-percent: 80        # Aggressive
  low-watermark-percent: 70
  validate-on-read: false           # No validation overhead
```

### For Low Memory

```yaml
cache:
  max-bytes: 2147483648             # 2GB only
  cleanup-interval-millis: 300000
  high-watermark-percent: 90
```

### For Maximum Performance

```yaml
cache:
  enabled: true
  path: /var/artipie/cache
  max-bytes: 53687091200            # 50GB
  cleanup-interval-millis: 60000    # 1 minute
  high-watermark-percent: 85
  low-watermark-percent: 75
  eviction-policy: LRU
  validate-on-read: false           # CRITICAL for performance
```

---

## Emergency Cleanup

If cleanup isn't working or cache is full:

```bash
#!/bin/bash
# Emergency manual cleanup

CACHE_DIR="/var/artipie/cache"

# Stop Artipie
docker-compose stop artipie

# Clean orphaned files
find "$CACHE_DIR" -name "*.part-*" -mmin +60 -delete
find "$CACHE_DIR" -name "*.meta" -exec sh -c 'test ! -f "${1%.meta}" && rm "$1"' _ {} \;

# Optional: Clear entire cache
# rm -rf "$CACHE_DIR"/*

# Start Artipie
docker-compose start artipie
```

---

## Summary

**Default Configuration (Balanced):**
```yaml
cache:
  enabled: true
  path: /var/artipie/cache
  max-bytes: 10737418240              # 10GB
  cleanup-interval-millis: 300000     # 5 minutes
  high-watermark-percent: 90
  low-watermark-percent: 80
  eviction-policy: LRU
  validate-on-read: false
```

**Cleanup runs automatically every 5 minutes by default!**

**What gets cleaned:**
- ✅ Orphaned .part- files (> 1 hour old)
- ✅ Orphaned .meta files (no data file)
- ✅ Old cached files (when over high watermark)

**No manual intervention needed** - just configure and forget! 🎉
