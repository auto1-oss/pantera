# Health Checks

Artipie exposes a built-in health endpoint that reports the status of every major
subsystem. The endpoint is designed for load balancer integration and external
monitoring.

## Endpoint

```
GET /.health
```

No authentication is required. The endpoint is always available, even when JWT
authentication is configured for the rest of the API.

## Response format

```json
{
  "status": "healthy",
  "components": {
    "storage": {"status": "up"},
    "database": {"status": "up"},
    "valkey": {"status": "not_configured"},
    "quartz": {"status": "up"},
    "http_client": {"status": "up"}
  }
}
```

The top-level `status` field is a roll-up of the individual component statuses.
Each entry in `components` reports the state of a single subsystem.

## Status values

| Top-level status | Condition                                         | HTTP code |
|------------------|---------------------------------------------------|-----------|
| `healthy`        | All components report `up` (or `not_configured`)  | 200       |
| `degraded`       | Exactly one non-storage component is down          | 200       |
| `unhealthy`      | Storage is down, OR two or more components are down | 503       |

The distinction between `degraded` and `unhealthy` allows load balancers to keep
routing traffic to an instance that has a single non-critical failure (for example,
Valkey is temporarily unreachable) while removing instances that cannot serve
artifacts at all.

## Component details

### storage

Tests the primary artifact storage by calling `list(Key.ROOT)` with a 5-second
timeout. If the storage does not respond within the timeout, the component is
reported as `down`. Because storage is the most critical subsystem, a storage
failure alone is enough to mark the instance as `unhealthy`.

### database

Tests the PostgreSQL connection pool by calling `connection.isValid(5)` through
HikariCP. This component is only checked when `artifacts_database` is configured
in the main Artipie configuration file. If PostgreSQL is not configured, the
component is omitted from the response.

### valkey

Tests connectivity to the Valkey (Redis-compatible) instance used for the
cross-instance event bus. When Valkey is not configured (single-instance
deployments), the status is reported as `not_configured` rather than `down`.

### quartz

Checks the Quartz scheduler state:
`scheduler.isStarted() && !scheduler.isShutdown() && !scheduler.isInStandbyMode()`.
If the scheduler has been shut down or placed in standby mode, the component is
reported as `down`.

### http_client

Checks that the Jetty HTTP client used for proxy and remote operations is running
and operational. A `down` status typically indicates a resource exhaustion problem
(thread pool or connection pool).

## HTTP status codes

| Code | Meaning                                                     |
|------|-------------------------------------------------------------|
| 200  | Instance is `healthy` or `degraded` -- safe to route traffic |
| 503  | Instance is `unhealthy` -- remove from load balancer pool    |

## Load balancer integration

Use the `/.health` endpoint as the health check target for nginx, HAProxy, AWS ALB,
or any other load balancer.

### nginx example

```nginx
upstream artipie {
    least_conn;
    server artipie-1:8080 max_fails=3 fail_timeout=30s;
    server artipie-2:8080 max_fails=3 fail_timeout=30s;
    keepalive 64;
}
```

With the configuration above, nginx uses passive health checks: after 3 consecutive
failed requests (including 503 responses from `/.health`), the backend is marked as
unavailable for 30 seconds before nginx retries it.

### Recommended settings

| Parameter       | Recommended value | Description                           |
|-----------------|-------------------|---------------------------------------|
| Check interval  | 10s               | How often to poll `/.health`          |
| Failure threshold | 3               | Consecutive failures before marking down |
| Success threshold | 1               | Consecutive successes before marking up  |
| Timeout         | 5s                | Maximum time to wait for a response   |

For [HA deployments](Configuration-HA), configure health checks independently for
each Artipie instance so that a single unhealthy node is removed from the pool
without affecting the others.
