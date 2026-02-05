# Vert.x HTTP Client Consolidation

**Date:** 2026-01-30
**Status:** Approved
**Author:** Claude + ayd

## Overview

Replace Jetty HTTP client with Vert.x WebClient as the sole HTTP client for proxy operations. This eliminates context switching between Vert.x server and Jetty client threads, reduces memory footprint, and simplifies the codebase.

## Problem Statement

1. Current `VertxClientSlice` in Docker image has ALPN not configured, causing "Must enable ALPN when using H2" errors
2. Jetty client runs on separate thread pool from Vert.x server, causing context switching overhead
3. Two HTTP client frameworks to maintain (Jetty + Vert.x)
4. Complex TeeStream implementation for cache-through failed

## Goals

- Single HTTP client framework (Vert.x)
- Proper HTTP/2 with ALPN support
- Configurable retry with exponential backoff
- Circuit breaker per destination (80% failure threshold)
- Write-through caching for both artifacts and metadata
- Background metadata refresh (stale-while-revalidate)
- Support 1000 req/s with spikes

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Artipie Server                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Shared Vert.x Instance                            │   │
│  │  ┌─────────────────┐    ┌─────────────────┐    ┌────────────────┐   │   │
│  │  │  HTTP Server    │    │  HTTP Client    │    │  Event Loop    │   │   │
│  │  │  (inbound)      │◄──►│  (outbound)     │◄──►│  (shared)      │   │   │
│  │  └─────────────────┘    └─────────────────┘    └────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│  ┌─────────────────────────────────▼───────────────────────────────────┐   │
│  │                    VertxClientSlices (implements ClientSlices)       │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐   │   │
│  │  │ Connection   │  │ Retry Policy │  │ Circuit Breaker          │   │   │
│  │  │ Pool Manager │  │ (exp backoff)│  │ (per destination)        │   │   │
│  │  └──────────────┘  └──────────────┘  └──────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Configuration

```yaml
meta:
  http_client:
    # Connection settings
    connection_timeout: 15000           # ms, default 15s
    idle_timeout: 30000                 # ms, default 30s
    read_idle_timeout: 60000            # ms, for slow downloads, default 60s

    # Pool settings
    max_connections_per_destination: 64
    max_total_connections: 512
    max_requests_queued_per_destination: 256

    # Protocol
    http2_enabled: true                 # HTTP/2 with HTTP/1.1 fallback
    trust_all: false
    follow_redirects: true

    # Retry (exponential backoff + jitter)
    retry:
      max_attempts: 3
      initial_delay: 100                # ms
      max_delay: 1000                   # ms cap
      multiplier: 2.0
      jitter: 0.2                       # ±20%
      retryable_status_codes: [502, 503, 504]

    # Circuit breaker (per destination)
    circuit_breaker:
      enabled: true
      failure_threshold: 5
      success_threshold: 3
      timeout: 30000                    # ms before half-open
      failure_rate_threshold: 80        # percentage to open
      sliding_window_size: 10

    # Cache strategy
    cache:
      artifact_ttl: -1                  # forever (immutable)
      metadata_ttl: 300                 # 5 minutes
      metadata_stale_ttl: 3600          # serve stale for 1 hour
      metadata_refresh_ahead: 60        # background refresh 1 min before expiry
      negative_ttl: 900                 # cache 404s for 15 minutes

    # Proxy timeout
    proxy_timeout: 120                  # seconds
```

## Cache Strategy

### Artifacts (Immutable)
- Write-through to storage
- Cache forever (TTL = -1)
- Never refresh from upstream once cached

### Metadata (Mutable)
- Write-through to storage
- TTL = 5 minutes (configurable)
- Background refresh before expiry
- Serve stale up to 1 hour if upstream down

```
Request ──► Cache hit? ──Yes──► Check TTL
                │                    │
                No              ┌────┴────────────────┐
                │               │         │           │
                ▼            FRESH    REFRESH     STALE
        Fetch upstream         │      ZONE          │
                │              │         │           │
                ▼              ▼         ▼           ▼
        Save to storage    Serve    Serve +      Serve +
                │                   background   refresh
                ▼                   refresh      (async)
        Serve from storage
```

### Metadata Detection by Repository Type

| Type | Metadata Patterns |
|------|-------------------|
| Maven | `maven-metadata.xml`, `-SNAPSHOT` |
| NPM | `package.json`, non-tarball paths |
| Go | `@v/list`, `@latest`, `.info` |
| PyPI | `/simple/` paths |
| Docker | `/manifests/` (tags only, not digests) |

## Class Structure

```
http-client/src/main/java/com/artipie/http/client/vertx/
├── VertxClientSlices.java       # Main factory (implements ClientSlices)
├── VertxClientSlice.java        # Single destination slice
├── RetryPolicy.java             # Exponential backoff + jitter
├── CircuitBreaker.java          # Per-destination circuit breaker
├── CircuitBreakerConfig.java    # Circuit breaker settings
└── package-info.java

artipie-core/src/main/java/com/artipie/cache/
├── CachePolicy.java             # Artifact vs metadata detection
├── StorageCache.java            # Write-through cache with TTL
└── BackgroundRefresh.java       # Async metadata refresh
```

## Files to Remove

```
http-client/src/main/java/com/artipie/http/client/jetty/
├── JettyClientSlices.java
├── JettyClientSlice.java
└── package-info.java

Dependencies from http-client/pom.xml:
- org.eclipse.jetty:jetty-http
- org.eclipse.jetty.http3:jetty-http3-client
- org.eclipse.jetty.http3:jetty-http3-client-transport
- org.eclipse.jetty.http3:jetty-http3-qpack
```

## Files to Modify

- `artipie-main/src/main/java/com/artipie/RepositorySlices.java` - Use VertxClientSlices
- `http-client/src/main/java/com/artipie/http/client/HttpClientSettings.java` - Add retry/circuit breaker config
- All proxy adapter tests - Update to use VertxClientSlices

## Success Criteria

1. `mvn clean install -U` passes all tests including integration tests
2. Go proxy works: `go get github.com/google/uuid`
3. Docker proxy works: `docker pull localhost:8081/library/busybox`
4. No ALPN errors in logs
5. Circuit breaker triggers on upstream failures
6. Metadata refreshes in background

## Decisions

| Component | Decision |
|-----------|----------|
| HTTP Client | Vert.x WebClient (remove Jetty) |
| Protocol | HTTP/2 with HTTP/1.1 fallback, ALPN enabled |
| Retry | Exponential backoff (100→200→400ms) + 20% jitter |
| Circuit Breaker | Opens at 80% failure rate, 10-request window |
| Connection Pool | Per-destination (64) + global limit (512) |
| Artifact Cache | Write-through, cache forever |
| Metadata Cache | Write-through, TTL 5min, background refresh |
| Streaming | Vert.x native backpressure, storage-first |
