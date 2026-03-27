# Installation

> **Guide:** Admin Guide | **Section:** Installation

This page covers the three supported deployment methods for Pantera: Docker standalone, Docker Compose (production), and JAR file.

---

## Prerequisites

| Requirement | Minimum | Notes |
|-------------|---------|-------|
| Docker | 24+ | Required for container-based deployment |
| Docker Compose | v2+ | Required for production stack |
| JDK | 21+ (Temurin) | Required only for JAR file deployment |
| Maven | 3.4+ | Required only for building from source |

---

## Docker Standalone

The Docker image is based on `eclipse-temurin:21-jre-alpine` and runs as user `2021:2020` (pantera:pantera).

### Basic Run Command

```bash
docker run -d \
  --name pantera \
  -p 8080:8080 \
  -p 8086:8086 \
  -p 8087:8087 \
  -v /path/to/pantera.yml:/etc/pantera/pantera.yml \
  -v /path/to/data:/var/pantera \
  -e JWT_SECRET=your-secret-key \
  -e PANTERA_USER_NAME=admin \
  -e PANTERA_USER_PASS=changeme \
  pantera:2.0.0
```

### Minimal Configuration

Create a minimal `/etc/pantera/pantera.yml` on the host:

```yaml
meta:
  storage:
    type: fs
    path: /var/pantera/repo
  credentials:
    - type: env
```

### Verify the Instance

```bash
curl http://localhost:8080/.health
# {"status":"ok"}
```

### Ports

| Port | Purpose |
|------|---------|
| `8080` | Repository traffic (artifact push/pull, Docker registry API) |
| `8086` | REST management API |
| `8087` | Prometheus metrics |
| `8090` | Management UI (separate container) |

### Volumes

| Container Path | Purpose |
|----------------|---------|
| `/etc/pantera/pantera.yml` | Main configuration file |
| `/etc/pantera/log4j2.xml` | Logging configuration (optional) |
| `/var/pantera/repo` | Repository configuration YAML files |
| `/var/pantera/data` | Artifact data storage |
| `/var/pantera/security` | RBAC policy files |
| `/var/pantera/cache` | Cache directory (S3 disk cache, temp files) |
| `/var/pantera/logs` | Log files, GC logs, heap dumps |

### Container User

The container runs as `2021:2020` (pantera:pantera). All mounted volumes must be readable and writable by this UID/GID. Set ownership before starting:

```bash
sudo chown -R 2021:2020 /path/to/data
```

---

## Docker Compose (Production)

The production Docker Compose stack provides the full Pantera deployment with all supporting services.

### Quick Start

```bash
git clone https://github.com/auto1-oss/pantera.git
cd pantera/pantera-main/docker-compose
cp .env.example .env   # Edit with your secrets
docker compose up -d
```

### Stack Services

| Service | Image | Port | Description |
|---------|-------|------|-------------|
| Pantera | `pantera:2.0.0` | `8088` (mapped from 8080) | Artifact registry |
| API | -- | `8086` | REST management API |
| Metrics | -- | `8087` | Prometheus metrics endpoint |
| Nginx | `nginx:latest` | `8081` / `8443` | Reverse proxy (HTTP/HTTPS) |
| PostgreSQL | `postgres:17.8-alpine` | `5432` | Metadata and settings database |
| Valkey | `valkey/valkey:8.1.4` | `6379` | Distributed cache and pub/sub |
| Keycloak | `quay.io/keycloak/keycloak:26.0.0` | `8080` | Identity provider (SSO) |
| Prometheus | `prom/prometheus:latest` | `9090` | Metrics collection |
| Grafana | `grafana/grafana:latest` | `3000` | Monitoring dashboards |
| Pantera UI | Custom build | `8090` | Vue.js management interface |

### Resource Recommendations

For production workloads, allocate the following resources to the Pantera container:

```yaml
services:
  pantera:
    cpus: 4
    mem_limit: 6gb
    mem_reservation: 6gb
    ulimits:
      nofile:
        soft: 1048576
        hard: 1048576
      nproc:
        soft: 65536
        hard: 65536
```

| Resource | Recommended | Notes |
|----------|-------------|-------|
| CPUs | 4+ | Minimum for parallel request handling |
| Memory | 6 GB | Reservation and limit for the Pantera container |
| File descriptors | 1,048,576 | Required for concurrent proxy connections |
| Process limit | 65,536 | Maximum threads/processes |

### Environment File (.env)

The `.env` file configures all stack services. Key variables to set before first start:

| Variable | Example | Description |
|----------|---------|-------------|
| `PANTERA_VERSION` | `2.0.0` | Docker image tag |
| `PANTERA_USER_NAME` | `admin` | Bootstrap admin username |
| `PANTERA_USER_PASS` | `changeme` | Bootstrap admin password |
| `JWT_SECRET` | (generate a strong key) | JWT signing key |
| `POSTGRES_USER` | `pantera` | Database username |
| `POSTGRES_PASSWORD` | (set a strong password) | Database password |
| `KEYCLOAK_CLIENT_SECRET` | (from Keycloak console) | OIDC client secret |

For the full list of `.env` variables, see the [Configuration Reference](../configuration-reference.md#8-docker-compose-environment-env).

---

## JAR File

Run Pantera directly from the JAR without Docker. This requires JDK 21+ installed on the host.

### Build from Source

```bash
git clone https://github.com/auto1-oss/pantera.git
cd pantera
mvn clean install -DskipTests
```

The resulting JAR and dependencies are placed under `pantera-main/target/`.

### Run Command

```bash
java \
  -XX:+UseG1GC -XX:MaxRAMPercentage=75.0 \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.security=ALL-UNNAMED \
  -cp pantera.jar:lib/* \
  com.auto1.pantera.VertxMain \
  --config-file=/etc/pantera/pantera.yml \
  --port=8080 \
  --api-port=8086
```

### CLI Options

| Option | Long Form | Default | Description |
|--------|-----------|---------|-------------|
| `-f` | `--config-file` | -- | Path to pantera.yml (required) |
| `-p` | `--port` | `80` | Repository server port |
| `-ap` | `--api-port` | `8086` | REST API port |

### Directory Structure

Create the same directory layout used by the Docker image:

```bash
sudo mkdir -p /etc/pantera /var/pantera/{repo,data,security,cache/tmp,logs/dumps}
sudo chown -R $(whoami) /var/pantera /etc/pantera
```

### Systemd Service (Optional)

For production JAR deployments, create a systemd service unit:

```ini
[Unit]
Description=Pantera Artifact Registry
After=network.target postgresql.service

[Service]
Type=simple
User=pantera
Group=pantera
Environment=JVM_ARGS=-XX:+UseG1GC -XX:MaxRAMPercentage=75.0
ExecStart=/usr/bin/java \
  ${JVM_ARGS} \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.security=ALL-UNNAMED \
  -cp /usr/lib/pantera/pantera.jar:/usr/lib/pantera/lib/* \
  com.auto1.pantera.VertxMain \
  --config-file=/etc/pantera/pantera.yml \
  --port=8080 \
  --api-port=8086
Restart=on-failure
RestartSec=10
LimitNOFILE=1048576
LimitNPROC=65536

[Install]
WantedBy=multi-user.target
```

---

## Database Setup: Dashboard Materialized Views

The dashboard (artifact count, storage usage, top repositories) reads from two PostgreSQL materialized views. **These views must be refreshed on a schedule via `pg_cron`.** Without this, the dashboard will show zeros.

> **Why pg_cron and not the application?** Pantera deploys one verticle per CPU core. Each verticle previously issued `REFRESH` independently, causing up to 25 simultaneous refresh sessions and severe `Lock:Relation` contention on the database. Delegating to `pg_cron` gives a single, predictable refresh with no application-side locking.

### 1. Enable pg_cron

**Amazon RDS / Aurora** — add to the DB parameter group and reboot:

```
shared_preload_libraries = pg_cron
cron.database_name = <your_database_name>
```

**Self-managed PostgreSQL:**

```bash
# Debian/Ubuntu
apt-get install postgresql-<version>-cron

# RHEL/CentOS
yum install pg_cron_<version>
```

Add to `postgresql.conf`, then restart:

```
shared_preload_libraries = 'pg_cron'
cron.database_name = 'artifacts'
```

### 2. Create the Extension

Run once as a superuser against your Pantera database:

```sql
CREATE EXTENSION IF NOT EXISTS pg_cron;
GRANT USAGE ON SCHEMA cron TO pantera;   -- replace 'pantera' with your app user
```

### 3. Schedule Refresh Jobs

Connect as the `pantera` application user (owner of the materialized views):

```sql
-- Refresh global totals every 30 minutes
SELECT cron.schedule(
  'refresh-mv-artifact-totals',
  '*/30 * * * *',
  $$REFRESH MATERIALIZED VIEW CONCURRENTLY mv_artifact_totals$$
);

-- Refresh per-repo stats every 30 minutes
SELECT cron.schedule(
  'refresh-mv-artifact-per-repo',
  '*/30 * * * *',
  $$REFRESH MATERIALIZED VIEW CONCURRENTLY mv_artifact_per_repo$$
);
```

For high-traffic environments reduce to `*/15 * * * *`; for low-traffic increase to `0 * * * *`.

### 4. Trigger an Initial Refresh

The views start empty on first deploy. Populate them immediately:

```sql
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_artifact_totals;
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_artifact_per_repo;
```

### 5. Schedule Cooldown Expiry Cleanup

Expired cooldown blocks (`blocked_until` in the past) are hidden from the UI automatically, but they accumulate in the database. Add a pg_cron job to purge them periodically:

```sql
SELECT cron.schedule(
  'cleanup-expired-cooldowns',
  '0 * * * *',   -- every hour
  $$DELETE FROM artifact_cooldowns
    WHERE status = 'ACTIVE' AND blocked_until < EXTRACT(EPOCH FROM NOW()) * 1000$$
);
```

### 6. Verify

```sql
-- Confirm jobs are registered
SELECT jobid, jobname, schedule, active FROM cron.job;

-- Check recent run history (pg_cron 1.4+)
SELECT jobid, status, start_time, return_message
FROM cron.job_run_details
ORDER BY start_time DESC LIMIT 10;
```

---

## Post-Installation Verification

After starting Pantera by any method, verify the deployment:

```bash
# Health check (repository port)
curl http://localhost:8080/.health

# Health check (API port)
curl http://localhost:8086/api/v1/health

# Version check
curl http://localhost:8080/.version

# Obtain a token (if env auth is configured)
curl -X POST http://localhost:8086/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"name":"admin","pass":"changeme"}'
```

---

## Related Pages

- [Configuration](configuration.md) -- Configure pantera.yml after installation
- [Environment Variables](environment-variables.md) -- All tunable environment variables
- [High Availability](high-availability.md) -- Multi-node production deployment
- [Performance Tuning](performance-tuning.md) -- Resource allocation and JVM tuning
