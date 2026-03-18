# HA Deployment

Artipie supports multi-instance deployment for high availability. Instances
coordinate through a shared PostgreSQL database and a shared Valkey (Redis-compatible)
instance so that metadata, events, and job scheduling remain consistent across the
cluster.

## Requirements

| Component             | Minimum version | Purpose                                 |
|-----------------------|-----------------|-----------------------------------------|
| PostgreSQL            | 15+             | Artifacts metadata, Quartz job store    |
| Valkey / Redis        | 7+              | Cross-instance event bus (pub/sub)      |
| S3-compatible storage | -               | Shared artifact storage                 |
| Load balancer (nginx) | -               | Request distribution and health checks  |

All instances must be able to reach the same PostgreSQL database, the same
Valkey instance, and the same S3-compatible storage bucket.

## Architecture

Each Artipie instance registers itself in the `artipie_nodes` table on startup and
sends periodic heartbeats so that the cluster knows which nodes are alive.

`ClusterEventBus` uses Valkey pub/sub channels to broadcast notifications across
instances. When one instance receives an artifact upload, the event is published to
Valkey so that other instances can update caches or indexes accordingly.

Quartz is configured with JDBC clustering (`org.quartz.jobStore.isClustered = true`)
to prevent duplicate execution of scheduled jobs such as metadata flush or proxy
cache verification. Only one instance in the cluster will execute a given job trigger
at any point in time.

## Configuration

An example HA configuration file is provided at `docs/ha-deployment/artipie-ha.yml`.
The key points are:

- **Storage must use S3** (not the local filesystem) so that all instances share the
  same artifact data. A filesystem backend would result in each instance having its own
  isolated copy of the data.

- **All instances must share the same PostgreSQL database.** The `artifacts_database`
  section in the main configuration file must point to the same host, port, and
  database name on every instance.

- **All instances must share the same Valkey instance.** The `valkey` section in the
  main configuration must use the same connection details everywhere.

- **JWT secrets must be identical across instances.** If one instance issues a JWT
  token, any other instance must be able to validate it. Configure the same
  `jwt_secret` value on every node.

```yaml
meta:
  storage:
    type: s3
    bucket: artipie-data
    region: us-east-1
    endpoint: http://minio:9000
    credentials:
      type: basic
      accessKeyId: minioadmin
      secretAccessKey: minioadmin
  artifacts_database:
    postgres_host: postgres
    postgres_port: 5432
    postgres_database: artipie
    postgres_user: artipie
    postgres_password: artipie
    threads_count: 4
    interval_seconds: 2
  valkey:
    host: valkey
    port: 6379
```

## nginx setup

An example nginx configuration is provided at `docs/ha-deployment/nginx-ha.conf`.
The recommended setup uses:

- `least_conn` load-balancing algorithm to distribute requests to the instance with
  the fewest active connections.
- `keepalive 64` on the upstream block to reuse connections to backend instances and
  reduce latency.
- Passive health checks via the `/.health` endpoint. nginx marks a backend as down
  after a configurable number of failed requests and re-checks periodically.

```nginx
upstream artipie {
    least_conn;
    server artipie-1:8080;
    server artipie-2:8080;
    server artipie-3:8080;
    keepalive 64;
}

server {
    listen 80;

    location / {
        proxy_pass http://artipie;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
    }

    location /.health {
        proxy_pass http://artipie;
        proxy_connect_timeout 5s;
        proxy_read_timeout 5s;
    }
}
```

## Docker Compose

A ready-to-use Docker Compose file for a 3-instance deployment is provided at
`docs/ha-deployment/docker-compose-ha.yml`. It includes:

- 3 Artipie instances behind an nginx load balancer
- PostgreSQL 15 for metadata and Quartz job store
- Valkey 7 for the cross-instance event bus
- MinIO for S3-compatible shared storage
- nginx configured with `least_conn` and passive health checks

To start the cluster:

```bash
cd docs/ha-deployment
docker compose -f docker-compose-ha.yml up -d
```

After startup, the Artipie API is available through the nginx load balancer on port 80
and each instance exposes its own health endpoint at `/.health`.

## Monitoring

The [health endpoint](Configuration-Health) at `GET /.health` returns per-component
status for each instance. In an HA deployment, configure your load balancer or external
monitoring system to poll `/.health` on each instance independently.

Recommended monitoring setup:

- **nginx passive health checks**: rely on the upstream `max_fails` / `fail_timeout`
  directives to automatically remove unhealthy instances from the pool.
- **External monitoring**: poll each instance's `/.health` endpoint every 10 seconds.
  Alert when any instance returns `unhealthy` (HTTP 503) for more than 3 consecutive
  checks.
- **Prometheus metrics**: each instance exposes its own `/metrics/vertx` endpoint (see
  [Metrics](Configuration-Metrics)). Aggregate across instances in Prometheus or
  Grafana for a cluster-wide view.
