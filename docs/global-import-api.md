# Global Import API

Artipie exposes a resumable, idempotent import endpoint for bulk migrations. The API accepts
streaming uploads for any repository without modifying the underlying storage layout.

## Endpoint

```
PUT /.import/{repository}/{path...}
```

### Required Headers

| Header | Description |
| ------ | ----------- |
| `X-Artipie-Repo-Type` | Repository adapter type (e.g. `maven`, `npm`, `file`, `docker`). |
| `X-Artipie-Idempotency-Key` | Unique key per artifact upload (resend-safe). |
| `X-Artipie-Artifact-Name` | Logical artifact identifier. |
| `X-Artipie-Artifact-Version` | Version or coordinate (may be empty for file/generic repositories). |
| `X-Artipie-Artifact-Size` | Size in bytes. |
| `X-Artipie-Artifact-Created` | Upload timestamp in epoch milliseconds. |
| `X-Artipie-Checksum-Mode` | `COMPUTE`, `METADATA`, or `SKIP`. |

Optional headers:

- `X-Artipie-Artifact-Owner`
- `X-Artipie-Artifact-Release`
- `X-Artipie-Checksum-Sha1`
- `X-Artipie-Checksum-Sha256`
- `X-Artipie-Checksum-Md5`

### Responses

| Status | Description |
| ------ | ----------- |
| `201 Created` | Artifact stored successfully. |
| `200 OK` | Artifact already present (idempotent replay). |
| `409 Conflict` | Checksum mismatch: artifact quarantined and entry recorded in `import_sessions`. |
| `5xx` | Transient error – retry with backoff. |

Response payloads are JSON objects summarising the result and, when applicable, the quarantine location.

## Import Sessions Table

The importer persists state in the `import_sessions` table:

```
CREATE TABLE import_sessions (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(200) UNIQUE,
    repo_name VARCHAR NOT NULL,
    repo_type VARCHAR NOT NULL,
    artifact_path TEXT NOT NULL,
    checksum_policy VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    size_bytes BIGINT,
    checksum_sha1 VARCHAR(128),
    checksum_sha256 VARCHAR(128),
    checksum_md5 VARCHAR(128),
    last_error TEXT,
    quarantine_path TEXT
);
```

Recommended indexes:

```
CREATE INDEX idx_import_sessions_repo ON import_sessions(repo_name);
CREATE INDEX idx_import_sessions_status ON import_sessions(status);
CREATE INDEX idx_import_sessions_repo_path ON import_sessions(repo_name, artifact_path);
```

For large installations (100M+ artifacts) consider PostgreSQL table partitioning by
hashing `idempotency_key` or by repository name. The table is append-heavy and benefits from
`fillfactor=90` and periodic `VACUUM`/`ANALYZE`.

## Integrity Modes

- **COMPUTE**: Artipie recomputes SHA-1, SHA-256 and MD5 while streaming. Provided hashes are
  verified and mismatches are quarantined.
- **METADATA**: Existing checksum sidecar files (e.g. `.sha1`, `.sha256`) are reused. Missing
  metadata triggers a fallback to compute.
- **SKIP**: No verification; suitable for trusted data paths where checksums are unnecessary.

Checksum mismatches are written to per-repository failure logs and exposed via the CLI summary.

## Resumability

Uploads are idempotent. Re-sending the same request with identical idempotency key either returns
`200 OK` or reconciles metadata. The CLI persists a progress log and updates the `import_sessions`
status to `COMPLETED`, `QUARANTINED`, or `FAILED` for operational auditing.
