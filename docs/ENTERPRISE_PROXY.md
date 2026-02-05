# Enterprise Proxy Features

This document describes the enterprise-grade proxy features available in Artipie, providing production-ready reliability comparable to JFrog Artifactory and Sonatype Nexus.

## Overview

Enterprise proxy features address common production issues:

| Feature | Problem Solved |
|---------|----------------|
| **Request Deduplication** | Thundering herd - 50 requests for same asset = 50 upstream fetches |
| **Distributed Coordination** | Cluster deployments need cross-node deduplication |
| **Retry with Backoff** | Transient failures cause immediate request failures |
| **Backpressure Control** | Connection pool exhaustion under load |
| **Auto-Block (Circuit Breaker)** | Failing upstreams cascade failures to clients |

## Quick Start

### Default Configuration (Recommended)

```java
// Wrap any proxy slice with enterprise features
Slice origin = new PyProxySlice(clients, remote);
Slice enterprise = new EnterpriseProxySlice(
    origin,
    "pypi-proxy",
    "https://pypi.org",
    key -> storage.load(key)  // cache reader for waiters
);
```

This enables all features with sensible defaults:
- 3 retry attempts with exponential backoff
- 50 max concurrent requests
- Circuit breaker: 5 failures in 1 minute → 5 minute block
- 90-second request timeout
- Automatic distributed coordination when Valkey available

### Custom Configuration

```java
ProxyConfig config = ProxyConfig.builder()
    .requestTimeout(Duration.ofSeconds(120))
    .retryMaxAttempts(5)
    .backpressureMaxConcurrent(100)
    .autoBlockFailureThreshold(10)
    .build();

Slice enterprise = new EnterpriseProxySlice(
    origin,
    config,
    "npm-proxy",
    "https://registry.npmjs.org",
    key -> storage.load(key)
);
```

### Minimal Configuration (Deduplication Only)

```java
Slice enterprise = new EnterpriseProxySlice(
    origin,
    ProxyConfig.MINIMAL,  // Only deduplication enabled
    "maven-proxy",
    "https://repo1.maven.org",
    key -> storage.load(key)
);
```

## YAML Configuration

```yaml
proxy:
  # Request timeout for upstream calls
  request_timeout: 90s

  # Retry configuration
  retry:
    enabled: true
    max_attempts: 3           # Total attempts (including initial)
    initial_delay: 100ms      # Delay before first retry
    max_delay: 10s            # Maximum delay between retries
    multiplier: 2.0           # Exponential backoff multiplier
    jitter_factor: 0.25       # Randomization factor (0.0-1.0)

  # Backpressure (concurrent request limiting)
  backpressure:
    enabled: true
    max_concurrent: 50        # Max concurrent upstream requests
    queue_timeout: 30s        # How long to wait in queue

  # Circuit breaker for failing upstreams
  auto_block:
    enabled: true
    failure_threshold: 5      # Failures to trigger block
    window: 1m                # Time window for counting failures
    block_duration: 5m        # How long to block upstream

  # Request deduplication
  deduplication:
    enabled: true
    mode: auto                # auto, local, valkey
    timeout: 90s              # Max time to wait for leader
```

## Feature Details

### Request Deduplication

**Problem:** When 50 concurrent requests arrive for the same uncached asset, all 50 check storage (empty), all 50 fetch from upstream (50x bandwidth, potential rate limiting).

**Solution:** Single-flight pattern - first request becomes "leader" and fetches, others wait then read from cache.

```
Request 1 ──┐
Request 2 ──┼──→ [Leader fetches] ──→ [Save to cache] ──→ Response 1
Request 3 ──┤         ↓                      ↓
   ...      │    [Wait...]            [Read cache] ──→ Response 2,3,...50
Request 50 ─┘
```

**Modes:**
- `auto` (default): Uses Valkey/Redis if available, falls back to local
- `local`: In-memory ConcurrentHashMap (single-node only)
- `valkey`: Requires Valkey/Redis connection

### Distributed Coordination (Valkey/Redis)

For cluster deployments, deduplication must work across nodes.

**Setup Valkey connection:**

```yaml
# In artipie.yaml
meta:
  cache:
    valkey:
      host: valkey.internal
      port: 6379
      password: ${VALKEY_PASSWORD}  # Optional
      ssl: true                     # Optional
```

**How it works:**
1. Leader acquires distributed lock: `SET inflight:npm-proxy:/lodash/-/lodash-4.17.21.tgz <node-id> NX EX 90`
2. Waiters poll for lock release with exponential backoff
3. Lock auto-expires if leader crashes (90-second timeout)
4. Waiters read from cache after leader completes

### Retry with Exponential Backoff

**Problem:** Transient network failures (connection reset, timeout) cause immediate failures.

**Solution:** Automatic retry with exponential backoff and jitter.

```
Attempt 1: Request
Attempt 2: Wait 100ms ± 25% → Request
Attempt 3: Wait 200ms ± 25% → Request
Attempt 4: Wait 400ms ± 25% → Request (capped at max_delay)
```

**Jitter prevents thundering herd on retry:**
- Without jitter: All failed requests retry at exactly 100ms
- With 25% jitter: Requests retry between 75ms-125ms (spread out)

**Retryable errors (default):**
- `ConnectException`
- `SocketTimeoutException`
- `UnknownHostException`
- `TimeoutException`
- IOException with "Connection reset", "Connection refused", "timed out"

### Backpressure Control

**Problem:** 1000 concurrent requests overwhelm upstream connection pool (typically 100-200 connections), causing cascading timeouts.

**Solution:** Semaphore-based limiting with fair queue.

```
Max concurrent: 50
Queue timeout: 30s

Requests 1-50:   Execute immediately
Requests 51-100: Wait in fair queue
Request after 30s wait: Return 503 Service Unavailable
```

**Response when rejected:**
```http
HTTP/1.1 503 Service Unavailable
Retry-After: 5
Content-Type: text/plain

Too many concurrent requests
```

### Auto-Block (Circuit Breaker)

**Problem:** Upstream is down, but clients keep hammering it, wasting resources and delaying error responses.

**Solution:** Circuit breaker pattern.

```
States: CLOSED → OPEN → HALF_OPEN → CLOSED

CLOSED:  Normal operation, count failures
         5 failures in 1 minute → OPEN

OPEN:    Immediately reject requests (503)
         After 5 minutes → HALF_OPEN

HALF_OPEN: Allow one probe request
           Success → CLOSED
           Failure → OPEN (restart timer)
```

**Response when blocked:**
```http
HTTP/1.1 503 Service Unavailable
Retry-After: 287
Content-Type: text/plain

Upstream temporarily blocked due to failures
```

## Metrics

All features export Prometheus metrics via Micrometer:

### Retry Metrics
```
artipie_proxy_retries_total{repo="npm-proxy", upstream="registry.npmjs.org", attempt="2"} 42
```

### Deduplication Metrics
```
artipie_proxy_inflight{repo="npm-proxy"} 15
artipie_proxy_deduplications_total{repo="npm-proxy", upstream="registry.npmjs.org"} 1234
```

### Backpressure Metrics
```
artipie_proxy_backpressure_total{repo="npm-proxy", result="executed"} 10000
artipie_proxy_backpressure_total{repo="npm-proxy", result="queued"} 500
artipie_proxy_backpressure_total{repo="npm-proxy", result="rejected"} 12
artipie_proxy_backpressure_wait_seconds{repo="npm-proxy", quantile="0.95"} 0.5
artipie_proxy_backpressure_utilization{repo="npm-proxy"} 0.75
artipie_proxy_backpressure_queue_depth{repo="npm-proxy"} 10
```

### Circuit Breaker Metrics
```
artipie_proxy_autoblock_changes_total{repo="npm-proxy", upstream="registry.npmjs.org", blocked="true"} 2
artipie_proxy_autoblock_rejections_total{repo="npm-proxy", upstream="registry.npmjs.org"} 150
```

### Distributed Lock Metrics
```
artipie_proxy_distributed_lock_total{repo="npm-proxy", operation="acquire", result="leader"} 5000
artipie_proxy_distributed_lock_total{repo="npm-proxy", operation="acquire", result="waiter"} 45000
```

## Architecture

### Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        EnterpriseProxySlice                         │
│                                                                     │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────────┐│
│  │ AutoBlock    │──▶│ Backpressure │──▶│ Deduplication            ││
│  │ (Circuit     │   │ (Semaphore)  │   │ (DistributedInFlight)    ││
│  │  Breaker)    │   │              │   │                          ││
│  └──────────────┘   └──────────────┘   └──────────────────────────┘│
│         │                   │                      │                │
│         │                   │                      ▼                │
│         │                   │          ┌──────────────────────────┐│
│         │                   │          │ Leader?                  ││
│         │                   │          │ ├─ Yes → RetryPolicy     ││
│         │                   │          │ └─ No  → Wait + Cache    ││
│         │                   │          └──────────────────────────┘│
│         │                   │                      │                │
│         │                   │                      ▼                │
│         │                   │          ┌──────────────────────────┐│
│         │                   └─────────▶│ Origin Slice             ││
│         │                              │ (PyProxySlice, etc.)     ││
│         │                              └──────────────────────────┘│
│         │                                          │                │
│         └──────────────────────────────────────────┘                │
│                      Record success/failure                         │
└─────────────────────────────────────────────────────────────────────┘
```

### Request Flow

1. **Auto-Block Check:** Is upstream blocked? → 503 immediately
2. **Backpressure:** Acquire semaphore or wait in queue
3. **Deduplication:** Are we leader or waiter?
   - **Leader:** Fetch from upstream with retry
   - **Waiter:** Wait for leader, read from cache
4. **Retry:** On failure, retry with exponential backoff
5. **Record:** Update circuit breaker state based on result

## Tuning Guide

### High-Traffic Registries (npm, PyPI)

```yaml
proxy:
  request_timeout: 120s      # Large tarballs need time
  retry:
    max_attempts: 5          # More retries for flaky upstreams
    max_delay: 30s
  backpressure:
    max_concurrent: 100      # Higher limit for popular packages
    queue_timeout: 60s       # Longer queue for burst handling
  auto_block:
    failure_threshold: 10    # More tolerant of transient failures
    block_duration: 2m       # Shorter blocks, faster recovery
```

### Internal/Enterprise Registries

```yaml
proxy:
  request_timeout: 30s       # Internal network is fast
  retry:
    max_attempts: 2          # Fail fast
    initial_delay: 50ms
  backpressure:
    max_concurrent: 200      # Internal network can handle more
  auto_block:
    failure_threshold: 3     # Less tolerant, surface issues quickly
    block_duration: 10m      # Longer blocks, investigate issues
```

### Development/Testing

```yaml
proxy:
  retry:
    enabled: false           # Fail fast for debugging
  backpressure:
    enabled: false           # No limits during testing
  auto_block:
    enabled: false           # Always try upstream
```

## Comparison with JFrog/Nexus

| Feature | Artipie | JFrog Artifactory | Sonatype Nexus |
|---------|---------|-------------------|----------------|
| Request Deduplication | ✅ Single-flight | ✅ | ✅ |
| Distributed Coordination | ✅ Valkey/Redis | ✅ Hazelcast | ✅ Orient DB |
| Retry with Backoff | ✅ Exponential + jitter | ✅ | ✅ |
| Backpressure | ✅ Semaphore | ✅ | ⚠️ Limited |
| Circuit Breaker | ✅ Auto-block | ✅ | ✅ |
| Configurable Timeouts | ✅ Per-feature | ✅ | ✅ |
| Metrics | ✅ Prometheus | ✅ Prometheus/JMX | ✅ Prometheus/JMX |

## Troubleshooting

### High Retry Rate

**Symptoms:** `artipie_proxy_retries_total` increasing rapidly

**Causes:**
- Upstream rate limiting
- Network instability
- Upstream overloaded

**Solutions:**
1. Check `artipie_proxy_autoblock_*` metrics for upstream health
2. Increase `backpressure.max_concurrent` if upstream can handle it
3. Reduce `retry.max_attempts` if upstream is genuinely slow

### Many Requests Queued

**Symptoms:** `artipie_proxy_backpressure_queue_depth` consistently high

**Causes:**
- `max_concurrent` too low for traffic
- Upstream response time increased
- Thundering herd event

**Solutions:**
1. Increase `backpressure.max_concurrent`
2. Check upstream health
3. Enable/verify deduplication is working (check `deduplications_total`)

### Circuit Breaker Flapping

**Symptoms:** Rapid OPEN→HALF_OPEN→OPEN cycles

**Causes:**
- `failure_threshold` too low
- `window` too short
- Intermittent upstream issues

**Solutions:**
1. Increase `auto_block.failure_threshold`
2. Increase `auto_block.window`
3. Investigate root cause of upstream failures

### Memory Growth

**Symptoms:** Heap growing, possible OOM

**Causes:**
- In-flight entries not being cleaned up (leak)
- Response bodies not being consumed

**Solutions:**
1. Check `artipie_proxy_inflight` metric - should stay bounded
2. Verify Valkey connectivity for distributed mode
3. Check for Vert.x connection leak warnings in logs

## API Reference

### EnterpriseProxySlice

```java
// Constructors
new EnterpriseProxySlice(origin, repoName, upstreamUrl, cacheReader)
new EnterpriseProxySlice(origin, config, repoName, upstreamUrl, cacheReader)

// Access internal components for metrics
Optional<BackpressureController> backpressure()
Optional<AutoBlockService> autoBlock()
DistributedInFlight inFlight()
```

### ProxyConfig.Builder

```java
ProxyConfig.builder()
    // Request timeout
    .requestTimeout(Duration.ofSeconds(90))

    // Retry
    .retryEnabled(true)
    .retryMaxAttempts(3)
    .retryInitialDelay(Duration.ofMillis(100))
    .retryMaxDelay(Duration.ofSeconds(10))
    .retryMultiplier(2.0)
    .retryJitterFactor(0.25)

    // Backpressure
    .backpressureEnabled(true)
    .backpressureMaxConcurrent(50)
    .backpressureQueueTimeout(Duration.ofSeconds(30))

    // Auto-block
    .autoBlockEnabled(true)
    .autoBlockFailureThreshold(5)
    .autoBlockWindow(Duration.ofMinutes(1))
    .autoBlockBlockDuration(Duration.ofMinutes(5))

    // Deduplication
    .deduplicationEnabled(true)
    .deduplicationMode(DeduplicationConfig.Mode.AUTO)
    .deduplicationTimeout(Duration.ofSeconds(90))

    .build();
```

### RetryPolicy

```java
// Pre-built policies
RetryPolicy.DEFAULT  // 3 attempts, 100ms initial, 2x backoff, 25% jitter
RetryPolicy.NO_RETRY // Single attempt, no retry

// Custom policy
RetryPolicy.builder()
    .maxAttempts(5)
    .initialDelay(Duration.ofMillis(200))
    .maxDelay(Duration.ofSeconds(30))
    .multiplier(1.5)
    .jitterFactor(0.3)
    .retryOn(e -> e instanceof SocketTimeoutException)
    .build();

// Execute with retry
CompletableFuture<Response> result = policy.execute(() -> fetchFromUpstream());
```

### BackpressureController

```java
BackpressureController controller = new BackpressureController(
    50,                        // max concurrent
    Duration.ofSeconds(30),    // queue timeout
    "npm-proxy"                // name for metrics
);

// Execute with backpressure
CompletableFuture<Response> result = controller.execute(() -> doRequest());

// Try without waiting (immediate reject if full)
CompletableFuture<Response> result = controller.tryExecute(() -> doRequest());

// Metrics
long active = controller.activeCount();
long queued = controller.queuedCount();
long completed = controller.completedCount();
long rejected = controller.rejectedCount();
double utilization = controller.utilization();  // 0.0 - 1.0
```

### AutoBlockService

```java
AutoBlockService autoBlock = new AutoBlockService(
    5,                         // failure threshold
    Duration.ofMinutes(1),     // evaluation window
    Duration.ofMinutes(5)      // block duration
);

// Check before request
if (autoBlock.isBlocked("https://registry.npmjs.org")) {
    Duration remaining = autoBlock.remainingBlockTime("https://registry.npmjs.org");
    return new Response(503, "Retry-After: " + remaining.getSeconds());
}

// Record result after request
autoBlock.recordSuccess("https://registry.npmjs.org");
// or
autoBlock.recordFailure("https://registry.npmjs.org", exception);

// Get state
AutoBlockService.State state = autoBlock.state("https://registry.npmjs.org");
// State: CLOSED, OPEN, HALF_OPEN
```

### DistributedInFlight

```java
DistributedInFlight inFlight = new DistributedInFlight(
    "npm-proxy",               // namespace
    Duration.ofSeconds(90)     // timeout
);

// Try to acquire leadership
InFlightResult result = inFlight.tryAcquire(key).join();

if (result.isWaiter()) {
    // Wait for leader, then read from cache
    boolean success = result.waitForLeader().join();
    return success ? readFromCache(key) : notFound();
}

// We are leader - fetch from upstream
try {
    Response response = fetchFromUpstream(key);
    result.complete(true);  // Signal success to waiters
    return response;
} catch (Exception e) {
    result.complete(false); // Signal failure to waiters
    throw e;
}
```
