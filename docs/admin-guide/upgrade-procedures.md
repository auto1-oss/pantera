# Upgrade Procedures

> **Guide:** Admin Guide | **Section:** Upgrade Procedures

This page covers the process for upgrading Pantera to a new version, including pre-upgrade checks, upgrade steps, database migrations, and rollback procedures.

---

## Pre-Upgrade Checklist

Before upgrading, complete the following:

| Step | Action | Verified |
|------|--------|----------|
| 1 | Review the release notes for the target version | |
| 2 | Back up the PostgreSQL database (see [Backup and Recovery](backup-and-recovery.md)) | |
| 3 | Back up configuration files (pantera.yml, repo YAMLs, security files) | |
| 4 | Test the upgrade in a staging environment first | |
| 5 | Verify sufficient disk space for database migrations | |
| 6 | Schedule a maintenance window (if zero-downtime is not possible) | |
| 7 | Note the current version for potential rollback | |
| 8 | Verify health check passes on current version | |

### Pre-Upgrade Database Backup

```bash
pg_dump \
  -h pantera-db -p 5432 \
  -U pantera -d pantera \
  -Fc --no-owner --no-acl \
  -f pantera-pre-upgrade-$(date +%Y%m%d-%H%M%S).dump
```

### Note Current Version

```bash
curl http://localhost:8080/.version
# Record the current version for rollback reference
```

---

## Upgrade Steps

### Docker Compose Upgrade

**Step 1: Pull the new image.**

```bash
docker pull pantera:2.0.0
```

Or build from source:

```bash
cd pantera
git fetch --all
git checkout v2.0.0
mvn clean package -DskipTests
cd pantera-main
docker build -t pantera:2.0.0 --build-arg JAR_FILE=pantera-main-2.0.0.jar .
```

**Step 2: Update the .env file.**

```bash
# In your docker-compose directory
sed -i 's/PANTERA_VERSION=.*/PANTERA_VERSION=2.0.0/' .env
```

**Step 3: Stop the current instance.**

```bash
docker compose stop pantera
```

**Step 4: Start the new version.**

```bash
docker compose up -d pantera
```

**Step 5: Verify the upgrade.**

```bash
# Health check
curl http://localhost:8080/.health

# Version check
curl http://localhost:8080/.version

# API health check
curl http://localhost:8086/api/v1/health

# Check logs for startup errors
docker logs --tail 50 pantera
```

### Docker Standalone Upgrade

```bash
# Stop the current container
docker stop pantera
docker rm pantera

# Start with the new image (use your existing run command with new tag)
docker run -d \
  --name pantera \
  -p 8080:8080 -p 8086:8086 -p 8087:8087 \
  -v /path/to/pantera.yml:/etc/pantera/pantera.yml \
  -v /path/to/data:/var/pantera \
  -e JWT_SECRET=your-secret-key \
  pantera:2.0.0
```

### JAR Upgrade

```bash
# Stop the current instance
systemctl stop pantera

# Replace JAR and dependencies
cd pantera
git fetch --all
git checkout v2.0.0
mvn clean package -DskipTests
cp pantera-main/target/pantera-main-2.0.0.jar /usr/lib/pantera/pantera.jar
cp -r pantera-main/target/dependency/* /usr/lib/pantera/lib/

# Start the new version
systemctl start pantera
```

### HA (Rolling Upgrade)

For zero-downtime upgrades in HA deployments:

1. Remove Node 1 from the load balancer.
2. Stop Pantera on Node 1.
3. Deploy the new version on Node 1.
4. Start Pantera on Node 1.
5. Verify health check passes on Node 1.
6. Add Node 1 back to the load balancer.
7. Repeat for Node 2, Node 3, etc.

Database migrations run on the first node that starts with the new version. Subsequent nodes detect that migrations have already been applied and skip them.

---

## Database Migrations

Pantera uses Flyway for automatic database schema migrations. Migrations are applied at startup before the application begins serving requests.

### Migration Files

Migrations are located in `pantera-main/src/main/resources/db/migration/`:

| Migration | Description |
|-----------|-------------|
| `V100__create_settings_tables.sql` | Creates `repositories`, `users`, `roles`, `user_roles`, `storage_aliases`, `auth_providers` tables |
| `V101__create_user_tokens_table.sql` | Creates `user_tokens` table for API token management |
| `V102__rename_artipie_auth_provider_to_local.sql` | Renames legacy auth provider values |
| `V103__rename_artipie_nodes_to_pantera_nodes.sql` | Renames node registry table |
| `V104__performance_indexes.sql` | Adds performance indexes identified by production audit |

### How Migrations Work

1. On startup, Pantera checks the `flyway_schema_history` table in PostgreSQL.
2. Any migrations not yet recorded are applied in version order.
3. Each migration runs within a transaction -- it either succeeds completely or rolls back.
4. After successful migration, the version is recorded in `flyway_schema_history`.
5. If a migration fails, Pantera will not start. Check the logs for the specific error.

### Checking Migration Status

Connect to the database and query:

```sql
SELECT version, description, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

### Migration Failure Recovery

If a migration fails:

1. Check the Pantera startup logs for the specific SQL error.
2. Fix the underlying issue (e.g., insufficient disk space, permission problems).
3. If the migration is partially applied, you may need to restore from the pre-upgrade backup.
4. After fixing the issue, restart Pantera to retry the migration.

---

## Rollback Procedures

### Rolling Back the Application

If the new version has issues, roll back to the previous version:

**Docker Compose:**

```bash
# Update .env to the previous version
sed -i 's/PANTERA_VERSION=.*/PANTERA_VERSION=1.21.0/' .env

# Restart
docker compose stop pantera
docker compose up -d pantera
```

**Docker Standalone:**

```bash
docker stop pantera
docker rm pantera
# Start with the previous image tag
docker run -d --name pantera ... pantera:1.21.0
```

### Rolling Back Database Migrations

If the new version applied database migrations that are incompatible with the previous version:

1. **Stop Pantera.**
2. **Restore the database from the pre-upgrade backup:**

```bash
# Drop and recreate the database
dropdb -h pantera-db -U pantera pantera
createdb -h pantera-db -U pantera pantera

# Restore from backup
pg_restore \
  -h pantera-db -p 5432 \
  -U pantera -d pantera \
  --no-owner --no-acl \
  pantera-pre-upgrade-backup.dump
```

3. **Start the previous version of Pantera.**

Most Pantera migrations are additive (adding tables, columns, or indexes) and do not break backward compatibility. Check the release notes to determine if a database rollback is necessary.

---

## Version Compatibility

### General Compatibility Rules

- **Minor version upgrades** (e.g., 1.21.0 to 2.0.0): Generally safe. Migrations are additive. The previous version usually works with the new schema.
- **Major version upgrades** (e.g., 1.x to 2.x): May include breaking schema changes. Always test in staging and keep a database backup.
- **Skipping versions**: Flyway applies all missing migrations in order. Skipping from 1.19.0 to 2.0.0 applies V100 through V104 sequentially.

### Configuration Compatibility

New versions may introduce new configuration keys with sensible defaults. Existing `pantera.yml` files generally work without modification. Review release notes for:

- New required configuration keys (rare)
- Deprecated keys that should be updated
- Changed default values

### JWT Token Compatibility

JWT tokens generated by the previous version remain valid after upgrade, as long as `meta.jwt.secret` has not changed. No token rotation is needed for minor upgrades.

---

## Post-Upgrade Verification

After upgrading, verify the following:

```bash
# 1. Health checks
curl http://localhost:8080/.health
curl http://localhost:8086/api/v1/health

# 2. Version
curl http://localhost:8080/.version

# 3. Authentication
curl -X POST http://localhost:8086/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"name":"admin","pass":"changeme"}'

# 4. Repository listing
curl http://localhost:8086/api/v1/repositories \
  -H "Authorization: Bearer $TOKEN"

# 5. Search
curl "http://localhost:8086/api/v1/search?q=test" \
  -H "Authorization: Bearer $TOKEN"

# 6. Artifact access (test a known artifact)
curl -I http://localhost:8080/maven-central/org/example/lib/1.0/lib-1.0.jar

# 7. Check for errors in logs
docker logs --tail 100 pantera | jq 'select(.["log.level"] == "ERROR")'
```

---

## Related Pages

- [Backup and Recovery](backup-and-recovery.md) -- Backup procedures required before upgrade
- [Installation](installation.md) -- Initial deployment reference
- [Troubleshooting](troubleshooting.md) -- Diagnosing post-upgrade issues
- [Monitoring](monitoring.md) -- Verify metrics are flowing after upgrade
