# High Availability

> **Guide:** Admin Guide | **Section:** High Availability

Pantera supports multi-node HA deployment with shared state via PostgreSQL, Valkey, and S3. This page covers the architecture, shared services configuration, load balancer setup, and cluster event propagation.

---

## Architecture

```
              +-------------------+
              |   Load Balancer   |
              | (Nginx / NLB)     |
              +--------+----------+
                       |
          +------------+------------+
          |            |            |
    +-----v----+ +----v-----+ +----v-----+
    | Pantera  | | Pantera  | | Pantera  |
    | Node 1   | | Node 2   | | Node 3   |
    +-----+----+ +----+-----+ +----+-----+
          |            |            |
    +-----v------------v------------v-----+
    |           Shared Services           |
    |  +----------+  +---------+          |
    |  |PostgreSQL|  | Valkey  |  +----+  |
    |  +----------+  +---------+  | S3 |  |
    |                             +----+  |
    +-------------------------------------+
```

All Pantera nodes are stateless application servers. State is held in three shared services:

| Service | Role |
|---------|------|
| PostgreSQL | Persistent state: repository configs, users, roles, artifact metadata, search index, cooldown records, import sessions, Quartz scheduler tables |
| Valkey | Distributed cache: L2 negative cache, L2 auth cache, cache invalidation pub/sub |
| S3 | Shared artifact storage: all nodes read and write to the same bucket |

---

## PostgreSQL Shared State

All nodes connect to the same PostgreSQL instance (or cluster). It holds:

- Repository configuration (JSONB)
- User and role definitions (RBAC)
- Artifact metadata and full-text search index (tsvector)
- Cooldown block records
- Import session state
- Settings and auth provider configuration
- Quartz JDBC scheduler tables (for clustered job scheduling)
- Revocation blocklist (`revocation_blocklist` table) — the durable source of
  truth for token revocation; see [Token Revocation](#token-revocation) below

### PostgreSQL HA

For PostgreSQL itself, consider:

- **Managed services** -- AWS RDS, Google Cloud SQL, Azure Database for PostgreSQL
- **Streaming replication** -- Primary with one or more read replicas
- **Patroni** -- For self-managed PostgreSQL HA with automatic failover

Pantera requires a single writable PostgreSQL endpoint. Read replicas are not used.

---

## Valkey Pub/Sub Cache Invalidation

When one node updates a cache entry (e.g., after a repository config change), it publishes an invalidation message via Valkey pub/sub. All other nodes subscribe and evict the stale entry from their local Caffeine caches.

### Valkey Configuration

```yaml
meta:
  caches:
    valkey:
      enabled: true
      host: valkey-cluster.internal
      port: 6379
      timeout: 100ms
```

All nodes must connect to the same Valkey instance (or cluster) for cache invalidation to work.

### How Cache Invalidation Works

1. Node A modifies data (e.g., updates a repository config, a role's
   permissions, or a user's enabled state).
2. Node A publishes an invalidation message to Valkey channel `pantera:cache:invalidate`.
3. Message format: `{instanceId}|{cacheType}|{key}` (or `*` for invalidateAll).
4. Nodes B and C receive the message and evict the matching entry from their local Caffeine caches.
5. Each node filters out its own messages (by instanceId) to avoid double-processing.

Cache types broadcast this way: `auth` (credentials), `filters` (repository
filter config), `policy` (roles/permissions — every `RoleHandler`/
`UserHandler` mutation), `revocation` (token revocation, see below),
`circuit-breaker-settings` / `upstream-breaker-settings` (admin-tunable
breaker thresholds). The policy cache also carries a bounded
`expireAfterWrite` (3 minutes) local backstop, so a node that misses a
broadcast entirely still converges without a restart.

### Token Revocation

Multi-node token revocation is **DB-durable and Valkey-accelerated**: the
`revocation_blocklist` table is always the source of truth, and Valkey
pub/sub is purely an acceleration layer on top of it — never the only copy
of a revocation.

- **Revoke** writes the DB row first, then publishes over Valkey pub/sub
  with the token's real remaining TTL embedded in the message (not a fixed
  default), so peers expire the cached entry at the correct time.
- **Boot** — a node hydrates its full active-revocation set from the DB
  before serving any request. A node that boots after a revocation rejects
  the token immediately; it does not depend on having been online to
  receive the original pub/sub message.
- **Reconciliation** — every revocation check also triggers a throttled
  (5 s) incremental DB poll. A peer that missed a pub/sub message (a Valkey
  blip, a dropped connection) still picks up the revocation within one poll
  interval.
- **Valkey outage** — revocation checks degrade to DB-poll speed. They never
  fail open: a Valkey outage cannot cause an already-revoked token to be
  honored again.
- Single-instance deployments (no Valkey configured) use a DB-polling-only
  blocklist with the same DB table and the same reconciliation semantics,
  just without the pub/sub fast path.

### L2 Cache

Beyond pub/sub invalidation, Valkey also serves as the L2 tier for several caches (negative cache, auth cache, cooldown cache). This means cache hits can be served from Valkey when the local L1 (Caffeine) cache has evicted the entry.

### Valkey Failure Handling

Pantera operates without Valkey. If Valkey becomes unavailable:

- L2 cache lookups fail silently; all cache operations fall back to L1 Caffeine only.
- Cache invalidation stops propagating across nodes (each node uses its local TTL).
- No data loss occurs; Valkey is a cache, not a data store.

---

## S3 Shared Storage

All nodes share a single S3 bucket for artifact data. This is the required storage backend for HA deployments.

### Repository Configuration

```yaml
# Each repository uses S3 storage (or a storage alias pointing to S3)
repo:
  type: maven
  storage:
    type: s3
    bucket: pantera-artifacts
    region: eu-central-1
```

Or using a storage alias:

```yaml
# _storages.yaml
storages:
  default:
    type: s3
    bucket: pantera-artifacts
    region: eu-central-1

# Repository file
repo:
  type: maven
  storage: default
```

### S3 Consistency

S3 provides strong read-after-write consistency for PUT and DELETE operations. Pantera relies on this guarantee for safe concurrent access from multiple nodes.

### Disk Cache in HA

Each node can maintain its own local disk cache in front of S3. The cache is node-local (not shared), so different nodes may have different hot artifacts cached. This is by design -- the disk cache reduces S3 API calls for frequently accessed artifacts on each node.

```yaml
storage:
  type: s3
  bucket: pantera-artifacts
  region: eu-central-1
  cache:
    enabled: true
    path: /var/pantera/cache/s3
    max-bytes: 10737418240
```

---

## Load Balancer Configuration

Configure a Layer 4 or Layer 7 load balancer in front of the Pantera nodes.

### Requirements

| Requirement | Details |
|-------------|---------|
| Health check | `GET /.health` on port 8080 (returns HTTP 200, no auth) |
| Protocol | HTTP/1.1 (HTTP/2 optional) |
| Sticky sessions | Not required but recommended for Docker multi-request flows |
| Body size limit | Unlimited (`client_max_body_size 0`) for large artifact uploads |
| Timeouts | At least 300 seconds for proxy read/send |

### Nginx Example

```nginx
upstream pantera_repo {
    server pantera-1:8080;
    server pantera-2:8080;
    server pantera-3:8080;
}

upstream pantera_api {
    server pantera-1:8086;
    server pantera-2:8086;
    server pantera-3:8086;
}

server {
    listen 443 ssl;
    server_name artifacts.example.com;

    ssl_certificate     /etc/ssl/certs/pantera.crt;
    ssl_certificate_key /etc/ssl/private/pantera.key;

    client_max_body_size 0;

    location / {
        proxy_pass http://pantera_repo;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    location /api/ {
        proxy_pass http://pantera_api;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }
}
```

### AWS NLB / ALB

For AWS deployments:

- **NLB (Network Load Balancer)** -- Preferred for TCP-level load balancing with lowest latency. Configure target group health check to `GET /.health` on port 8080.
- **ALB (Application Load Balancer)** -- Use if you need path-based routing or WAF integration. Set the health check path to `/.health`.

---


## Quartz Scheduler Clustering

In HA mode, Pantera uses Quartz JDBC job store for clustered scheduling.
Genuinely shared, idempotent/DB-guarded background work (cleanup, reindex,
etc.) is distributed across nodes through the shared `QRTZ_*` tables, with
only one node executing a given firing at a time.

Quartz clustering requires:

- A shared PostgreSQL database (same as Pantera's main database)
- The `QRTZ_*` tables (created automatically by Pantera on first start)

**Node-local work never goes through the clustered store.** Clustered
Quartz does not pin a repeating trigger to the node that scheduled it — any
node's scheduler thread can acquire and fire it. Work that depends on
in-memory state only one node actually has (a queue, a connection, an
in-flight object) is scheduled on that node's own dedicated timer instead —
for example, the artifact-events queue that feeds the search index and
`artifact_publish` audit records is drained by a per-node scheduler, never
by a clustered Quartz job. A small number of per-repository proxy
background jobs still use the clustered store with a node-local registry
lookup; if a node's scheduler thread acquires a trigger whose data isn't in
its own registry, it skips that firing (logged) rather than deleting the
job — the owning node's next acquisition of the same trigger processes
normally.

---

## Known Gaps

Multi-node correctness for token revocation, the artifact-events pipeline,
and authorization/settings propagation is covered (above). Not yet
implemented as of 2.3.0 — do not rely on the following in a multi-node
deployment:

- **Readiness.** The health check (`GET /.health`) returns 200 with no
  dependency checks — a node with a dead database pool or unreachable S3
  still reports healthy and keeps taking traffic.
- **Graceful drain.** SIGTERM does not flip readiness before the socket
  starts rejecting connections, so a rolling deploy can 503 in-flight
  requests the load balancer had already routed.
- **`DbConsumer` shutdown flush.** A clean shutdown can still drop the last
  buffered window (up to a few seconds) of `artifact_publish` audit
  records; the search index self-heals independently, so this is an audit
  gap, not a data-loss gap.
- **Download tokens.** Cross-node validation requires
  `PANTERA_DOWNLOAD_TOKEN_SECRET` pinned identically on every node (the
  per-node default secret does not work in HA); "single-use" is
  advertised but not enforced; comparison is not constant-time.
- **Fleet visibility.** There is no `pantera_nodes` heartbeat registry.
  Leader election and Valkey Cluster (vs. Sentinel) are explicitly out of
  scope — sharding adds nothing given the global-key, single-channel pub/sub
  design.

---

## Deployment Checklist

1. Provision a PostgreSQL instance accessible from all Pantera nodes.
2. Provision a Valkey instance accessible from all Pantera nodes.
3. Create an S3 bucket (or use an existing one) with appropriate IAM permissions.
4. Deploy identical `pantera.yml` to all nodes (same JWT secret, same database credentials, same S3 bucket).
5. Configure the load balancer with health checks on `/.health`.
6. Start Pantera nodes; verify each passes health checks.
7. Verify cross-node cache invalidation by creating a repository on one node and listing it from another.

---

## Related Pages

- [Installation](installation.md) -- Docker Compose production stack
- [Storage Backends](storage-backends.md) -- S3 configuration details
- [Configuration](configuration.md) -- Valkey cache configuration
- [Monitoring](monitoring.md) -- Cluster health monitoring
- [Performance Tuning](performance-tuning.md) -- Connection pooling for HA
