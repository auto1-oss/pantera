# Import and Migration

> **Guide:** User Guide | **Section:** Import and Migration

This page covers how to import artifacts into Pantera from other registries, how to use the bulk import API, and how to use the backfill CLI tool to populate the search index.

---

## Global Import API

Pantera provides a dedicated import endpoint for bulk artifact ingestion with checksum verification. This is the primary mechanism for migrating artifacts from another registry into Pantera.

### Endpoint

```
PUT http://pantera-host:8080/.import/<repo-name>/<artifact-path>
```

### Basic Example

```bash
curl -X PUT \
  -H "Authorization: Basic $(echo -n admin:your-jwt-token | base64)" \
  -H "Content-Type: application/octet-stream" \
  -H "X-Pantera-Repo-Type: maven" \
  -H "X-Pantera-Idempotency-Key: import-mylib-1.0-$(date +%s)" \
  --data-binary @mylib-1.0.jar \
  http://pantera-host:8080/.import/maven-local/com/example/mylib/1.0/mylib-1.0.jar
```

### Required Headers

| Header | Description |
|--------|-------------|
| `X-Pantera-Repo-Type` | Repository type: `maven`, `npm`, `pypi`, `docker`, etc. |
| `X-Pantera-Idempotency-Key` | Unique key to prevent duplicate imports |

### Optional Headers

| Header | Description |
|--------|-------------|
| `X-Pantera-Artifact-Name` | Logical artifact name |
| `X-Pantera-Artifact-Version` | Artifact version string |
| `X-Pantera-Artifact-Size` | Size in bytes (falls back to Content-Length) |
| `X-Pantera-Artifact-Owner` | Owner/publisher name |
| `X-Pantera-Artifact-Created` | Created timestamp (milliseconds since epoch) |
| `X-Pantera-Checksum-Sha256` | Expected SHA-256 checksum for verification |
| `X-Pantera-Checksum-Sha1` | Expected SHA-1 checksum |
| `X-Pantera-Checksum-Md5` | Expected MD5 checksum |
| `X-Pantera-Checksum-Mode` | Checksum policy: `VERIFY` (default), `STORE`, or `NONE` |
| `X-Pantera-Metadata-Only` | If `true`, only index metadata without storing bytes |

### Response Codes

| Status | Meaning |
|--------|---------|
| `201 Created` | New artifact imported successfully |
| `200 OK` | Artifact already exists (idempotent replay) |
| `409 Conflict` | Checksum mismatch -- provided checksum does not match uploaded content |
| `400 Bad Request` | Missing required headers or invalid metadata |
| `503 Service Unavailable` | Import queue is full; retry after a few seconds |

### Example Response (201 Created)

```json
{
  "status": "CREATED",
  "message": "Artifact imported successfully",
  "size": 123456,
  "digests": {
    "sha1": "abc...",
    "sha256": "def...",
    "md5": "ghi..."
  }
}
```

### Checksum Verification

By default, if you provide a `X-Pantera-Checksum-Sha256` header, Pantera computes the checksum of the uploaded bytes and compares it. On mismatch, the import is rejected with `409 Conflict`. This protects against corrupted transfers.

### Idempotency

The `X-Pantera-Idempotency-Key` header ensures that retrying a failed import does not create duplicates. If an artifact with the same idempotency key has already been imported, Pantera returns `200 OK` with status `ALREADY_PRESENT`.

---

## Scripted Bulk Import

For migrating many artifacts, write a script that iterates over your source registry and calls the import API for each file. Example:

```bash
#!/usr/bin/env bash
# Migrate Maven artifacts from a local directory to Pantera
PANTERA="http://pantera-host:8080"
AUTH="$(echo -n admin:your-jwt-token | base64)"

find /path/to/maven-repo -type f \( -name "*.jar" -o -name "*.pom" \) | while read FILE; do
  # Strip the base path to get the Maven path
  RELPATH="${FILE#/path/to/maven-repo/}"

  curl -X PUT \
    -H "Authorization: Basic $AUTH" \
    -H "X-Pantera-Repo-Type: maven" \
    -H "X-Pantera-Idempotency-Key: import-${RELPATH}" \
    --data-binary "@${FILE}" \
    "${PANTERA}/.import/maven-local/${RELPATH}"
done
```

---

## Backfill CLI Tool

The `pantera-backfill` CLI tool scans existing artifact directories on disk and populates the PostgreSQL metadata database. This is useful when:

- You have copied artifacts directly to storage (filesystem or S3) and need to index them.
- The database was rebuilt and needs to be repopulated from existing storage.

### Single Repository Mode

```bash
java -jar pantera-backfill.jar \
  --type maven \
  --path /var/pantera/data/maven-local \
  --repo-name maven-local \
  --db-url jdbc:postgresql://localhost:5432/pantera \
  --db-user pantera \
  --db-password secret
```

### Bulk Mode

Scans all repository configuration files and indexes all repositories at once:

```bash
java -jar pantera-backfill.jar \
  --config-dir /var/pantera/repo \
  --storage-root /var/pantera/data \
  --db-url jdbc:postgresql://localhost:5432/pantera \
  --db-user pantera \
  --db-password secret
```

### Options

| Flag | Description | Default |
|------|-------------|---------|
| `--type` | Repository type (single mode only) | -- |
| `--path` | Path to artifact data directory | -- |
| `--repo-name` | Repository name (single mode only) | -- |
| `--config-dir` | Path to repository YAML configs (bulk mode) | -- |
| `--storage-root` | Root path for artifact data (bulk mode) | -- |
| `--db-url` | PostgreSQL JDBC URL | -- |
| `--db-user` | Database username | -- |
| `--db-password` | Database password | -- |
| `--batch-size` | Insert batch size | 1000 |
| `--log-interval` | Progress log interval (rows) | 10000 |

---

## Migrating from Other Registries

### General Approach

1. **Export** artifacts from your current registry (Nexus, Artifactory, etc.) to a local directory or download them via the registry's API.
2. **Import** into Pantera using the bulk import API or by copying files to Pantera's storage and running the backfill tool.
3. **Update client configurations** to point to Pantera (see the per-format guides).
4. **Verify** that builds succeed with the new registry.

### Tips

- Start with a **parallel deployment**: run Pantera alongside your existing registry, configure group repositories that proxy both, and gradually move clients over.
- Use the **import API with checksum verification** to ensure data integrity during migration.
- For Docker images, use `skopeo copy` to transfer images between registries:
  ```bash
  skopeo copy \
    docker://old-registry.example.com/myimage:1.0 \
    docker://pantera-host:8080/docker-local/myimage:1.0 \
    --dest-creds your-username:your-jwt-token
  ```
- For Maven, consider using `mvn dependency:copy-dependencies` to download all transitive dependencies, then bulk-import them.
- Import sessions are tracked in the database for auditing and can be resumed if interrupted.

---

## Related Pages

- [User Guide Index](index.md)
- [Getting Started](getting-started.md) -- Obtaining JWT tokens
- [REST API Reference](../rest-api-reference.md) -- Import endpoint details
