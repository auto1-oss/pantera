# Performance Validation Plan

**Date:** November 23, 2024  
**Purpose:** Comprehensive performance testing and validation strategy for metadata-based fallback mechanism

---

## Executive Summary

This document outlines the performance validation strategy to ensure the metadata-based fallback mechanism meets the requirement of **minimal to near-zero performance impact** on package resolution and downloads.

**Performance Targets:**
- **Metadata filtering latency:** P99 < 50ms (small packages), P99 < 200ms (large packages)
- **Cache hit rate:** > 90%
- **Memory overhead:** < 500 MB per adapter
- **Throughput degradation:** < 5% compared to baseline (no filtering)
- **CPU overhead:** < 10% increase

---

## 1. Baseline Performance Measurement

### 1.1 Current System Benchmarks

**Objective:** Establish baseline performance metrics before implementing metadata filtering.

**Metrics to Measure:**

| Metric | Description | Target |
|--------|-------------|--------|
| **Metadata Request Latency** | Time to serve metadata (no filtering) | P50, P95, P99 |
| **Download Request Latency** | Time to serve artifact | P50, P95, P99 |
| **Throughput** | Requests per second | RPS |
| **Memory Usage** | Heap usage per adapter | MB |
| **CPU Usage** | CPU utilization | % |
| **Cache Hit Rate** | Percentage of cache hits | % |

**Test Scenarios:**

```java
@Benchmark
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class BaselineNpmMetadataRequest {
    
    @State(Scope.Benchmark)
    public static class BenchmarkState {
        CachedNpmProxySlice slice;
        
        @Setup
        public void setup() {
            // Initialize proxy slice without cooldown filtering
            this.slice = new CachedNpmProxySlice(/* ... */);
        }
    }
    
    @Benchmark
    public void metadataRequestSmallPackage(BenchmarkState state) {
        // Request metadata for package with ~50 versions
        state.slice.response(
            new RequestLine("GET", "/lodash", "HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
    }
    
    @Benchmark
    public void metadataRequestLargePackage(BenchmarkState state) {
        // Request metadata for package with 1000+ versions
        state.slice.response(
            new RequestLine("GET", "/@types/node", "HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
    }
}
```

**Execution:**
```bash
# Run JMH benchmarks
mvn clean install
java -jar target/benchmarks.jar BaselineNpmMetadataRequest -f 3 -wi 5 -i 10
```

**Expected Baseline Results:**

| Package Type | Package Size | P50 | P95 | P99 |
|-------------|--------------|-----|-----|-----|
| NPM (small) | ~50 versions | 10ms | 20ms | 30ms |
| NPM (large) | 1000+ versions | 50ms | 100ms | 150ms |
| PyPI | ~100 versions | 15ms | 30ms | 45ms |
| Maven | ~50 versions | 5ms | 10ms | 15ms |
| Composer | ~100 versions | 20ms | 40ms | 60ms |
| Go | ~20 versions | 2ms | 5ms | 8ms |

---

## 2. Component-Level Benchmarks

### 2.1 Parser Performance

**Objective:** Measure parsing overhead for each package type.

**Test Cases:**

```java
@Benchmark
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ParserBenchmarks {
    
    @State(Scope.Benchmark)
    public static class NpmParserState {
        byte[] smallMetadata;  // 10 KB
        byte[] mediumMetadata; // 100 KB
        byte[] largeMetadata;  // 5 MB
        NpmMetadataParser parser;
        
        @Setup
        public void setup() throws IOException {
            this.smallMetadata = loadResource("npm-metadata-small.json");
            this.mediumMetadata = loadResource("npm-metadata-medium.json");
            this.largeMetadata = loadResource("npm-metadata-large.json");
            this.parser = new NpmMetadataParser();
        }
    }
    
    @Benchmark
    public void parseSmallNpmMetadata(NpmParserState state) {
        state.parser.parse(state.smallMetadata);
    }
    
    @Benchmark
    public void parseMediumNpmMetadata(NpmParserState state) {
        state.parser.parse(state.mediumMetadata);
    }
    
    @Benchmark
    public void parseLargeNpmMetadata(NpmParserState state) {
        state.parser.parse(state.largeMetadata);
    }
}
```

**Performance Targets:**

| Parser | Small (10KB) | Medium (100KB) | Large (5MB) |
|--------|--------------|----------------|-------------|
| NPM (Jackson) | < 5ms | < 20ms | < 100ms |
| PyPI (Jsoup) | < 5ms | < 15ms | N/A |
| Maven (DOM4J) | < 2ms | < 10ms | N/A |
| Composer (Jackson) | < 5ms | < 20ms | < 100ms |
| Go (Text) | < 1ms | < 2ms | < 5ms |

### 2.2 Filter Performance

**Objective:** Measure filtering overhead.

**Test Cases:**

```java
@Benchmark
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class FilterBenchmarks {
    
    @State(Scope.Benchmark)
    public static class FilterState {
        JsonNode metadata;
        Set<String> blockedVersions;
        NpmMetadataFilter filter;
        
        @Setup
        public void setup() {
            // Metadata with 100 versions
            this.metadata = loadParsedMetadata("npm-100-versions.json");
            // Block 10% of versions
            this.blockedVersions = Set.of("4.17.21", "4.17.20", "4.17.19", /* ... */);
            this.filter = new NpmMetadataFilter();
        }
    }
    
    @Benchmark
    public void filterNpmMetadata(FilterState state) {
        state.filter.filter(state.metadata, state.blockedVersions);
    }
}
```

**Performance Targets:**

| Filter | 50 versions | 100 versions | 1000 versions |
|--------|-------------|--------------|---------------|
| NPM | < 1ms | < 2ms | < 10ms |
| PyPI | < 1ms | < 2ms | < 10ms |
| Maven | < 1ms | < 2ms | < 5ms |
| Composer | < 1ms | < 2ms | < 10ms |
| Go | < 0.5ms | < 1ms | < 2ms |

### 2.3 Rewriter Performance

**Objective:** Measure serialization overhead.

**Test Cases:**

```java
@Benchmark
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class RewriterBenchmarks {
    
    @State(Scope.Benchmark)
    public static class RewriterState {
        JsonNode filteredMetadata;
        NpmMetadataRewriter rewriter;
        
        @Setup
        public void setup() {
            this.filteredMetadata = loadFilteredMetadata("npm-filtered.json");
            this.rewriter = new NpmMetadataRewriter();
        }
    }
    
    @Benchmark
    public void rewriteNpmMetadata(RewriterState state) {
        state.rewriter.rewrite(state.filteredMetadata);
    }
}
```

**Performance Targets:**

| Rewriter | Small | Medium | Large |
|----------|-------|--------|-------|
| NPM | < 5ms | < 20ms | < 100ms |
| PyPI | < 5ms | < 15ms | N/A |
| Maven | < 2ms | < 10ms | N/A |
| Composer | < 5ms | < 20ms | < 100ms |
| Go | < 1ms | < 2ms | < 5ms |

### 2.4 Cooldown Check Performance

**Objective:** Measure database/cache lookup overhead.

**Test Cases:**

```java
@Benchmark
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CooldownCheckBenchmarks {
    
    @State(Scope.Benchmark)
    public static class CooldownState {
        CooldownCache cache;
        List<String> versions;
        
        @Setup
        public void setup() {
            this.cache = new CooldownCache(/* ... */);
            this.versions = List.of("1.0.0", "1.0.1", "1.0.2", /* ... 100 versions */);
            
            // Pre-populate cache with 90% hit rate
            for (int i = 0; i < 90; i++) {
                cache.put("repo", "package", versions.get(i), false);
            }
        }
    }
    
    @Benchmark
    public void checkSingleVersionCacheHit(CooldownState state) {
        state.cache.isBlocked("repo", "package", "1.0.0").join();
    }
    
    @Benchmark
    public void checkSingleVersionCacheMiss(CooldownState state) {
        state.cache.isBlocked("repo", "package", "1.0.99").join();
    }
    
    @Benchmark
    public void checkMultipleVersionsParallel(CooldownState state) {
        CompletableFuture.allOf(
            state.versions.stream()
                .map(v -> state.cache.isBlocked("repo", "package", v))
                .toArray(CompletableFuture[]::new)
        ).join();
    }
}
```

**Performance Targets:**

| Operation | Target |
|-----------|--------|
| Single version check (cache hit) | < 1ms |
| Single version check (cache miss) | < 10ms |
| 100 versions parallel check (90% hit rate) | < 50ms |

---

## 3. End-to-End Performance Testing

### 3.1 Load Testing

**Objective:** Measure system performance under realistic load.

**Tool:** Apache JMeter or Gatling

**Test Scenarios:**

```scala
// Gatling load test scenario
class MetadataFilteringLoadTest extends Simulation {
  
  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("application/json")
  
  val scn = scenario("NPM Metadata Requests")
    .exec(
      http("Get lodash metadata")
        .get("/npm-proxy/lodash")
        .check(status.is(200))
        .check(jsonPath("$.versions").exists)
    )
    .pause(1)
  
  setUp(
    scn.inject(
      rampUsersPerSec(10) to 100 during (60 seconds),
      constantUsersPerSec(100) during (300 seconds)
    )
  ).protocols(httpProtocol)
}
```

**Load Profiles:**

| Profile | Users | Duration | RPS | Description |
|---------|-------|----------|-----|-------------|
| Light | 10 | 5 min | 10 | Normal usage |
| Medium | 50 | 10 min | 50 | Peak hours |
| Heavy | 100 | 15 min | 100 | High load |
| Stress | 200 | 10 min | 200 | Stress test |

**Metrics to Collect:**

- Response time (P50, P95, P99)
- Throughput (RPS)
- Error rate (%)
- CPU usage (%)
- Memory usage (MB)
- Cache hit rate (%)
- Database query count

**Success Criteria:**

| Metric | Light | Medium | Heavy | Stress |
|--------|-------|--------|-------|--------|
| P99 latency | < 100ms | < 150ms | < 200ms | < 300ms |
| Error rate | < 0.1% | < 0.5% | < 1% | < 5% |
| CPU usage | < 30% | < 50% | < 70% | < 90% |
| Memory | < 2GB | < 3GB | < 4GB | < 6GB |

### 3.2 Soak Testing

**Objective:** Verify system stability over extended periods.

**Duration:** 24 hours

**Load:** Constant 50 RPS

**Metrics to Monitor:**

- Memory leaks (heap growth over time)
- Cache eviction rate
- Database connection pool exhaustion
- Thread pool saturation
- GC pause times

**Success Criteria:**

- No memory leaks (heap stable after warmup)
- No connection pool exhaustion
- GC pause time < 100ms (P99)
- No errors or crashes

---

## 4. Cache Performance Testing

### 4.1 Cache Hit Rate Analysis

**Objective:** Validate cache hit rate meets > 90% target.

**Test Scenarios:**

```java
@Test
public void testMetadataCacheHitRate() {
    // Simulate realistic access pattern
    List<String> popularPackages = List.of("lodash", "react", "express", /* ... */);
    List<String> lessPopularPackages = List.of("obscure-pkg-1", /* ... */);
    
    // 80% requests to popular packages, 20% to less popular
    for (int i = 0; i < 10000; i++) {
        String pkg = (i % 5 == 0) 
            ? lessPopularPackages.get(random.nextInt(lessPopularPackages.size()))
            : popularPackages.get(random.nextInt(popularPackages.size()));
        
        fetchMetadata(pkg);
    }
    
    double hitRate = cache.getHitRate();
    assertThat(hitRate).isGreaterThan(0.90);
}
```

**Cache Configurations to Test:**

| Config | L1 Size | L1 TTL | L2 Size | L2 TTL | Expected Hit Rate |
|--------|---------|--------|---------|--------|-------------------|
| Small | 1000 | 5 min | 10K | 15 min | > 85% |
| Medium | 5000 | 10 min | 50K | 30 min | > 90% |
| Large | 10K | 15 min | 100K | 60 min | > 95% |

### 4.2 Cache Invalidation Performance

**Objective:** Measure impact of cache invalidation events.

**Test Scenarios:**

```java
@Benchmark
public void cacheInvalidationLatency() {
    // Measure time to invalidate metadata cache
    long start = System.nanoTime();
    metadataCache.invalidate("repo", "package");
    long duration = System.nanoTime() - start;
    
    // Should be < 1ms
    assertThat(duration).isLessThan(1_000_000);
}

@Test
public void cacheInvalidationPropagation() {
    // Measure time for invalidation to propagate across instances
    instance1.blockVersion("repo", "pkg", "1.0.0");
    
    long start = System.currentTimeMillis();
    while (instance2.metadataCache.contains("repo", "pkg")) {
        Thread.sleep(10);
        if (System.currentTimeMillis() - start > 5000) {
            fail("Cache invalidation did not propagate within 5 seconds");
        }
    }
    
    long propagationTime = System.currentTimeMillis() - start;
    // Should propagate within 1 second
    assertThat(propagationTime).isLessThan(1000);
}
```

---

## 5. Memory Profiling

### 5.1 Heap Analysis

**Objective:** Identify memory overhead and potential leaks.

**Tools:**
- JProfiler
- VisualVM
- Eclipse Memory Analyzer (MAT)

**Test Procedure:**

1. Start Artipie with profiler attached
2. Run load test for 1 hour
3. Take heap dump
4. Analyze heap dump for:
   - Retained heap by class
   - Duplicate strings
   - Large collections
   - Potential leaks

**Expected Results:**

| Component | Heap Usage |
|-----------|------------|
| L1 Cache (Caffeine) | < 200 MB |
| Parsed metadata objects | < 100 MB |
| Parser/filter/rewriter instances | < 50 MB |
| Database connections | < 50 MB |
| **Total overhead** | **< 500 MB** |

### 5.2 GC Analysis

**Objective:** Ensure GC pauses don't impact latency.

**Metrics:**

- GC pause time (P99)
- GC frequency
- Young generation size
- Old generation size
- GC algorithm efficiency

**JVM Flags:**

```bash
-XX:+UseG1GC
-XX:MaxGCPauseMillis=50
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-Xlog:gc*:file=gc.log
```

**Success Criteria:**

- P99 GC pause < 50ms
- No full GCs during normal operation
- Young GC frequency < 1/second

---

## 6. Comparison Testing

### 6.1 Before vs. After Comparison

**Objective:** Quantify performance impact of metadata filtering.

**Test Matrix:**

| Scenario | Baseline (No Filtering) | With Filtering | Overhead |
|----------|------------------------|----------------|----------|
| NPM metadata (small) | 15ms | ? | Target: < 5ms |
| NPM metadata (large) | 80ms | ? | Target: < 20ms |
| PyPI metadata | 20ms | ? | Target: < 5ms |
| Maven metadata | 8ms | ? | Target: < 2ms |
| Composer metadata | 25ms | ? | Target: < 5ms |
| Go metadata | 3ms | ? | Target: < 1ms |

**Acceptance Criteria:**

- Overhead < 10% for all package types
- P99 latency increase < 20ms
- Throughput degradation < 5%

### 6.2 A/B Testing in Production

**Objective:** Validate performance in production environment.

**Approach:**

1. Deploy with feature flag disabled (baseline)
2. Collect metrics for 1 week
3. Enable feature flag for 10% of traffic
4. Compare metrics between control and treatment groups
5. Gradually increase to 100% if metrics are acceptable

**Metrics to Compare:**

- Request latency (P50, P95, P99)
- Error rate
- Cache hit rate
- User-reported issues

---

## 7. Performance Regression Testing

### 7.1 Continuous Performance Testing

**Objective:** Detect performance regressions in CI/CD pipeline.

**Integration:**

```yaml
# .github/workflows/performance-tests.yml
name: Performance Tests

on:
  pull_request:
    branches: [main]

jobs:
  benchmark:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Run JMH benchmarks
        run: |
          mvn clean install
          java -jar target/benchmarks.jar -rf json -rff results.json
      
      - name: Compare with baseline
        run: |
          python scripts/compare_benchmarks.py \
            --baseline baseline-results.json \
            --current results.json \
            --threshold 10
      
      - name: Fail if regression detected
        if: steps.compare.outputs.regression == 'true'
        run: exit 1
```

**Regression Thresholds:**

- Latency increase > 10%: Warning
- Latency increase > 20%: Failure
- Throughput decrease > 5%: Warning
- Throughput decrease > 10%: Failure

---

## 8. Performance Monitoring in Production

### 8.1 Metrics Collection

**Metrics to Track:**

```java
// Prometheus metrics
Counter metadataRequestsTotal = Counter.build()
    .name("artipie_metadata_requests_total")
    .help("Total metadata requests")
    .labelNames("package_type", "cache_hit")
    .register();

Histogram metadataFilteringDuration = Histogram.build()
    .name("artipie_metadata_filtering_duration_seconds")
    .help("Metadata filtering duration")
    .labelNames("package_type", "size_category")
    .buckets(0.001, 0.005, 0.01, 0.05, 0.1, 0.2, 0.5)
    .register();

Gauge metadataCacheSize = Gauge.build()
    .name("artipie_metadata_cache_size")
    .help("Metadata cache size")
    .labelNames("cache_tier")
    .register();
```

### 8.2 Dashboards

**Grafana Dashboard Panels:**

1. **Metadata Request Latency** (P50, P95, P99) by package type
2. **Cache Hit Rate** by package type
3. **Filtering Overhead** (time spent in parse/filter/rewrite)
4. **Memory Usage** (heap, cache sizes)
5. **Error Rate** by error type
6. **Throughput** (requests per second)

### 8.3 Alerts

**Alert Rules:**

```yaml
groups:
  - name: metadata_filtering_performance
    rules:
      - alert: HighMetadataLatency
        expr: histogram_quantile(0.99, artipie_metadata_filtering_duration_seconds) > 0.2
        for: 5m
        annotations:
          summary: "P99 metadata filtering latency > 200ms"
      
      - alert: LowCacheHitRate
        expr: rate(artipie_metadata_requests_total{cache_hit="true"}[5m]) / rate(artipie_metadata_requests_total[5m]) < 0.9
        for: 10m
        annotations:
          summary: "Metadata cache hit rate < 90%"
```

---

## 9. Performance Optimization Validation

### 9.1 Optimization Candidates

**If performance targets are not met, test these optimizations:**

1. **Streaming parsers** for large NPM packages
2. **Parallel version checks** with batch database queries
3. **Metadata cache warming** for popular packages
4. **Connection pooling** optimization
5. **JVM tuning** (heap size, GC algorithm)

### 9.2 Optimization Impact Measurement

**For each optimization:**

1. Implement optimization
2. Run full benchmark suite
3. Compare with baseline
4. Document improvement
5. Decide whether to keep or revert

---

## Summary

**Performance validation checklist:**

- [ ] Baseline measurements collected
- [ ] Component benchmarks pass targets
- [ ] Load testing passes all profiles
- [ ] Soak testing shows no leaks
- [ ] Cache hit rate > 90%
- [ ] Memory overhead < 500 MB
- [ ] GC pauses < 50ms (P99)
- [ ] Before/after comparison shows < 10% overhead
- [ ] A/B testing in production successful
- [ ] Performance regression tests in CI/CD
- [ ] Production monitoring dashboards created
- [ ] Alert rules configured

**Next Steps:**

1. Implement benchmarking infrastructure
2. Collect baseline measurements
3. Implement metadata filtering
4. Run performance validation suite
5. Optimize if needed
6. Deploy to production with monitoring

