# Backup and Recovery

> **Guide:** Admin Guide | **Section:** Backup and Recovery

This page covers backup strategies for all Pantera components -- database, configuration, and artifact storage -- along with disaster recovery procedures.

---

## Overview

Pantera stores state in three locations that must all be backed up:

| Component | Location | Contents |
|-----------|----------|----------|
| PostgreSQL database | `pantera-db:5432` | Repository configs, users, roles, artifact metadata, search index, cooldown records, API tokens, settings, Quartz tables |
| Configuration files | `/etc/pantera/pantera.yml`, `/var/pantera/repo/`, `/var/pantera/security/` | Main config, repository YAML definitions, RBAC policy files |
| Artifact storage | `/var/pantera/data/` (filesystem) or S3 bucket | Actual artifact binaries |

---

## Database Backup

### Manual Backup with pg_dump

**Full database dump (custom format, compressed):**

```bash
pg_dump \
  -h pantera-db -p 5432 \
  -U pantera -d pantera \
  -Fc --no-owner --no-acl \
  -f pantera-backup-$(date +%Y%m%d-%H%M%S).dump
```

**Plain SQL dump (for portability):**

```bash
pg_dump \
  -h pantera-db -p 5432 \
  -U pantera -d pantera \
  --no-owner --no-acl \
  -f pantera-backup-$(date +%Y%m%d-%H%M%S).sql
```

**Dump from inside Docker:**

```bash
docker exec pantera-db pg_dump \
  -U pantera -d pantera \
  -Fc --no-owner --no-acl \
  -f /tmp/pantera-backup.dump

docker cp pantera-db:/tmp/pantera-backup.dump ./pantera-backup.dump
```

### Scheduled Backups

Use cron to automate daily backups:

```bash
# /etc/cron.d/pantera-backup
0 2 * * * pantera /usr/local/bin/pantera-db-backup.sh
```

**Example backup script (`pantera-db-backup.sh`):**

```bash
#!/bin/bash
set -euo pipefail

BACKUP_DIR="/var/backups/pantera"
RETENTION_DAYS=30
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/pantera-${TIMESTAMP}.dump"

mkdir -p "${BACKUP_DIR}"

pg_dump \
  -h pantera-db -p 5432 \
  -U pantera -d pantera \
  -Fc --no-owner --no-acl \
  -f "${BACKUP_FILE}"

# Remove backups older than retention period
find "${BACKUP_DIR}" -name "pantera-*.dump" -mtime +${RETENTION_DAYS} -delete

echo "Backup complete: ${BACKUP_FILE} ($(du -h "${BACKUP_FILE}" | cut -f1))"
```

### Point-in-Time Recovery (PITR)

For production deployments requiring minimal data loss:

1. **Enable WAL archiving** in PostgreSQL:

```
wal_level = replica
archive_mode = on
archive_command = 'cp %p /var/lib/postgresql/wal_archive/%f'
```

2. **Take a base backup** periodically:

```bash
pg_basebackup -h pantera-db -U pantera -D /var/backups/pantera-base -Fp -Xs -P
```

3. **To recover**, restore the base backup and replay WAL files up to the desired point in time.

For managed database services (AWS RDS, Google Cloud SQL), PITR is built-in. Enable automated backups in your cloud provider's console and set the retention period.

### Key Tables to Verify After Restore

| Table | Verification |
|-------|-------------|
| `repositories` | `SELECT count(*) FROM repositories;` |
| `users` | `SELECT count(*) FROM users;` |
| `roles` | `SELECT count(*) FROM roles;` |
| `artifacts` | `SELECT count(*) FROM artifacts;` |
| `artifact_cooldowns` | `SELECT count(*) FROM artifact_cooldowns;` |
| `user_tokens` | `SELECT count(*) FROM user_tokens;` |
| `storage_aliases` | `SELECT count(*) FROM storage_aliases;` |

---

## Configuration Backup

### Files to Back Up

| Path | Contents |
|------|----------|
| `/etc/pantera/pantera.yml` | Main configuration |
| `/etc/pantera/log4j2.xml` | Logging configuration (if customized) |
| `/var/pantera/repo/*.yaml` | Repository definition files |
| `/var/pantera/repo/_storages.yaml` | Storage aliases |
| `/var/pantera/security/users/*.yaml` | User files |
| `/var/pantera/security/roles/*.yaml` | Role definitions |

### Backup Command

```bash
tar czf pantera-config-$(date +%Y%m%d-%H%M%S).tar.gz \
  /etc/pantera/ \
  /var/pantera/repo/ \
  /var/pantera/security/
```

### Version Control

Store configuration files in a Git repository for version history and change tracking:

```bash
cd /var/pantera
git init
git add repo/ security/
git commit -m "Pantera configuration snapshot $(date +%Y%m%d)"
```

---

## Artifact Storage Backup

### Filesystem Storage

For filesystem-backed storage, use `rsync` for incremental backups:

```bash
rsync -avz --delete \
  /var/pantera/data/ \
  /var/backups/pantera-artifacts/
```

For off-site backup:

```bash
rsync -avz --delete \
  /var/pantera/data/ \
  backup-server:/var/backups/pantera-artifacts/
```

### S3 Storage

For S3-backed storage, the artifacts are already stored in S3 with 99.999999999% durability. Additional protection options:

**S3 Versioning:**

Enable versioning on the bucket to protect against accidental deletions and overwrites:

```bash
aws s3api put-bucket-versioning \
  --bucket pantera-artifacts \
  --versioning-configuration Status=Enabled
```

**S3 Cross-Region Replication:**

For disaster recovery across regions:

```bash
aws s3api put-bucket-replication \
  --bucket pantera-artifacts \
  --replication-configuration file://replication-config.json
```

**S3 Object Lock:**

For compliance requirements, enable S3 Object Lock to prevent deletion for a retention period.

**S3 Lifecycle Policies:**

Configure lifecycle policies to transition old versions to cheaper storage classes:

```json
{
  "Rules": [
    {
      "ID": "archive-old-versions",
      "Status": "Enabled",
      "NoncurrentVersionTransitions": [
        {
          "NoncurrentDays": 30,
          "StorageClass": "GLACIER_IR"
        }
      ],
      "NoncurrentVersionExpiration": {
        "NoncurrentDays": 365
      }
    }
  ]
}
```

---

## Disaster Recovery Procedures

### Recovery Order

When recovering from a complete failure, restore components in this order:

1. **Database first** -- All other components depend on the database.
2. **Configuration files** -- pantera.yml, repository YAMLs, security files.
3. **Artifact storage** -- Restore filesystem data or verify S3 bucket accessibility.
4. **Start Pantera** -- Flyway applies any missing migrations automatically.
5. **Verify** -- Run health checks and test artifact access.

### Step-by-Step Recovery

**Step 1: Restore PostgreSQL**

```bash
# Create a fresh database
createdb -h pantera-db -U pantera pantera

# Restore from custom format dump
pg_restore \
  -h pantera-db -p 5432 \
  -U pantera -d pantera \
  --no-owner --no-acl \
  pantera-backup.dump
```

Or from a plain SQL dump:

```bash
psql -h pantera-db -U pantera -d pantera < pantera-backup.sql
```

**Step 2: Restore Configuration**

```bash
tar xzf pantera-config-backup.tar.gz -C /
```

Verify the configuration file is intact:

```bash
cat /etc/pantera/pantera.yml
ls /var/pantera/repo/*.yaml
ls /var/pantera/security/roles/*.yaml
```

**Step 3: Restore Artifact Storage**

For filesystem:

```bash
rsync -avz /var/backups/pantera-artifacts/ /var/pantera/data/
```

For S3, verify bucket access:

```bash
aws s3 ls s3://pantera-artifacts/ --summarize
```

**Step 4: Start Pantera**

```bash
docker compose up -d pantera
```

Or for JAR deployment:

```bash
systemctl start pantera
```

**Step 5: Verify**

```bash
# Health check
curl http://localhost:8080/.health

# API health check
curl http://localhost:8086/api/v1/health

# Verify repository count
curl http://localhost:8086/api/v1/repositories \
  -H "Authorization: Bearer $TOKEN"

# Verify search index
curl http://localhost:8086/api/v1/search/stats \
  -H "Authorization: Bearer $TOKEN"

# Trigger search reindex if needed
curl -X POST http://localhost:8086/api/v1/search/reindex \
  -H "Authorization: Bearer $TOKEN"
```

---

## Recovery Testing

Test your backup and recovery procedures regularly:

1. **Monthly**: Restore a database dump to a test instance and verify data integrity.
2. **Quarterly**: Perform a full disaster recovery drill -- restore all three components to a clean environment and verify artifact access.
3. **After major changes**: After upgrading Pantera, adding repositories, or changing storage backends, take a fresh backup and verify recoverability.

---

## Backup Summary

| Component | Method | Frequency | Retention |
|-----------|--------|-----------|-----------|
| Database | pg_dump (custom format) | Daily | 30 days minimum |
| Database (PITR) | WAL archiving + base backup | Continuous + weekly base | 7 days WAL, 30 days base |
| Configuration | tar archive or Git | On every change | Indefinite (version controlled) |
| Artifacts (filesystem) | rsync incremental | Daily or continuous | Match disk capacity |
| Artifacts (S3) | S3 versioning + replication | Automatic | Per lifecycle policy |

---

## Related Pages

- [Installation](installation.md) -- Initial deployment setup
- [Upgrade Procedures](upgrade-procedures.md) -- Pre-upgrade backup requirements
- [High Availability](high-availability.md) -- HA architecture reduces single-point-of-failure risk
- [Troubleshooting](troubleshooting.md) -- Diagnosing issues after recovery
