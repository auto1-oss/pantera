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
| Valkey | Distributed cache: L2 negative cache, L2 auth cache, cache invalidation pub/sub, cluster event bus |
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
- Node registry with heartbeats

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

1. Node A modifies data (e.g., updates a repository config).
2. Node A publishes an invalidation message to Valkey channel `pantera:cache:invalidate`.
3. Message format: `{instanceId}|{cacheType}|{key}` (or `*` for invalidateAll).
4. Nodes B and C receive the message and evict the matching entry from their local Caffeine caches.
5. Each node filters out its own messages (by instanceId) to avoid double-processing.

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

## Cluster Event Bus

Pantera uses the Vert.x event bus combined with Valkey pub/sub for cross-node event propagation. Events include:

- Repository create, update, and delete
- User and role changes
- Cache invalidation
- Settings updates

Each node registers itself in the `pantera_nodes` PostgreSQL table with a unique node ID, hostname, and heartbeat timestamp. Nodes that miss heartbeats are considered dead and excluded from cluster operations.

### Node Registration

The node registry is automatic. On startup, each Pantera instance:

1. Generates a unique node ID (UUID).
2. Registers in `pantera_nodes` with hostname, port, and startup timestamp.
3. Publishes periodic heartbeats.
4. On shutdown, removes its registration.

---

## Quartz Scheduler Clustering

In HA mode, Pantera uses Quartz JDBC job store for clustered scheduling. Background jobs (cleanup, reindex, etc.) are distributed across nodes with only one node executing each job at a time.

Quartz clustering requires:

- A shared PostgreSQL database (same as Pantera's main database)
- The `QRTZ_*` tables (created automatically by Pantera on first start)

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
