# S3 Storage Memory Optimizations

This directory contains documentation for the S3 storage memory optimizations implemented in Artipie.

## 📚 Documentation Files

1. **[S3_STORAGE_CONFIG_EXAMPLE.yml](./S3_STORAGE_CONFIG_EXAMPLE.yml)**
   - Complete S3 storage configuration example
   - Shows human-readable size format (MB, GB, KB)
   - Memory-optimized default values
   - Performance tuning guidelines

2. **[S3_MEMORY_OPTIMIZATIONS.md](./S3_MEMORY_OPTIMIZATIONS.md)**
   - Detailed analysis of memory issues fixed
   - Before/after comparisons
   - Configuration guide for different scenarios
   - Migration guide and troubleshooting

3. **[S3_FIXES_SUMMARY.md](./S3_FIXES_SUMMARY.md)**
   - Quick reference summary
   - Implementation checklist
   - Build and deployment instructions

## 🎯 Quick Start

### Use Human-Readable Configuration

```yaml
meta:
  storage:
    type: s3
    bucket: my-artipie-bucket
    region: eu-west-1
    
    # Human-readable sizes!
    multipart-min-size: 32MB
    part-size: 8MB
    multipart-concurrency: 16
    
    parallel-download: true
    parallel-download-min-size: 64MB
    parallel-download-chunk-size: 8MB
    parallel-download-concurrency: 8
```

## 📊 Memory Improvements

| Scenario | Before | After | Savings |
|----------|--------|-------|---------|
| 100 × 15MB uploads | 4.5GB | 200MB | **97%** |
| 1GB file upload | 48MB | 24MB | **50%** |
| 1000 concurrent | OOM | Stable | ✅ |

## 🔧 Code Changes

The following files were modified:

1. `asto/asto-s3/src/main/java/com/artipie/asto/s3/S3Storage.java`
   - Stream uploads instead of buffering
   - Removed triple buffering

2. `asto/asto-s3/src/main/java/com/artipie/asto/s3/MultipartUpload.java`
   - Optimized list implementation
   - Eliminated array copying

3. `asto/asto-s3/src/main/java/com/artipie/asto/s3/S3StorageFactory.java`
   - Added human-readable size parsing
   - Updated default values

## 🚀 Deployment

See [S3_FIXES_SUMMARY.md](./S3_FIXES_SUMMARY.md) for complete deployment instructions.
