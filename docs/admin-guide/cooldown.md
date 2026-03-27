# Cooldown System

> **Guide:** Admin Guide | **Section:** Cooldown

The cooldown system provides supply chain security by blocking freshly-published artifacts from upstream registries for a configurable period. This gives security teams time to review new versions before they are consumed by builds.

---

## How It Works

1. When a proxy repository fetches a new artifact version from upstream, Pantera records its publication timestamp.
2. If the artifact was published less than `minimum_allowed_age` ago, the download is blocked and the artifact is recorded in the cooldown database.
3. After the cooldown period expires, the artifact becomes available automatically.
4. Administrators can manually unblock individual artifacts or entire repositories at any time.

Cooldown decisions use artifact metadata (publish date, version) extracted from upstream registry responses. The metadata is cached in the `cooldown-metadata` cache tier for efficient re-evaluation without repeated upstream requests.

---

## Configuration

Configure cooldown in `pantera.yml`:

```yaml
meta:
  cooldown:
    enabled: false              # Global default
    minimum_allowed_age: 7d     # Default quarantine duration
    repo_types:
      npm-proxy:
        enabled: true           # Enable only for npm proxy repos
      maven-proxy:
        enabled: true
        minimum_allowed_age: 3d # Override per repo type
      pypi-proxy:
        enabled: true
        minimum_allowed_age: 5d
```

### Configuration Keys

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `false` | Global enable/disable |
| `minimum_allowed_age` | string | -- | Default quarantine duration |
| `repo_types` | map | -- | Per-repository-type overrides |
| `repo_types.<type>.enabled` | boolean | inherits global | Enable for this repo type |
| `repo_types.<type>.minimum_allowed_age` | string | inherits global | Override duration for this type |

---

## Duration Format

Durations are specified with a numeric value followed by a suffix:

| Suffix | Meaning | Example |
|--------|---------|---------|
| `m` | Minutes | `30m` (30 minutes) |
| `h` | Hours | `24h` (1 day) |
| `d` | Days | `7d` (1 week) |

---

## API Management

Cooldown can be configured and managed at runtime via the REST API. Changes take effect immediately without restart.

### View Current Configuration

```bash
curl http://pantera-host:8086/api/v1/cooldown/config \
  -H "Authorization: Bearer $TOKEN"
```

### Update Configuration (Hot Reload)

```bash
curl -X PUT http://pantera-host:8086/api/v1/cooldown/config \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "minimum_allowed_age": "7d",
    "repo_types": {
      "npm-proxy": {"enabled": true, "minimum_allowed_age": "3d"},
      "maven-proxy": {"enabled": true}
    }
  }'
```

When cooldown is disabled for a repo type via the API, all active blocks for that type are automatically released.

### View Blocked Artifacts

```bash
curl "http://pantera-host:8086/api/v1/cooldown/blocked?page=0&size=50&search=lodash" \
  -H "Authorization: Bearer $TOKEN"
```

The response includes the artifact name, version, repository, block reason, blocked/unblock dates, and remaining hours.

### Unblock a Specific Artifact

```bash
curl -X POST http://pantera-host:8086/api/v1/repositories/npm-proxy/cooldown/unblock \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"artifact":"lodash","version":"4.17.22"}'
```

### Unblock All Artifacts in a Repository

```bash
curl -X POST http://pantera-host:8086/api/v1/repositories/npm-proxy/cooldown/unblock-all \
  -H "Authorization: Bearer $TOKEN"
```

### View Cooldown Overview

Shows per-repository block counts:

```bash
curl http://pantera-host:8086/api/v1/cooldown/overview \
  -H "Authorization: Bearer $TOKEN"
```

For the complete cooldown API specification, see the [REST API Reference](../rest-api-reference.md#10-cooldown-management).

---

## Monitoring

Cooldown state is persisted in the `artifact_cooldowns` PostgreSQL table. Monitor cooldown activity through:

- **REST API** -- `GET /api/v1/cooldown/overview` for per-repo block counts and `GET /api/v1/cooldown/blocked` for individual blocked artifacts.
- **Management UI** -- The Cooldown view in the Pantera UI (port 8090) provides a searchable, paginated list of blocked artifacts with one-click unblock.
- **Database queries** -- Direct SQL queries against the `artifact_cooldowns` table for custom reporting.
- **Logging** -- Cooldown block and unblock events are logged at INFO level under the `com.auto1.pantera` logger.

### Cache Configuration

Cooldown uses two cache tiers. Tune these based on your artifact volume:

```yaml
meta:
  caches:
    cooldown:
      ttl: 24h
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 1000
        l1Ttl: 24h
        l2MaxSize: 5000000
        l2Ttl: 7d
    cooldown-metadata:
      ttl: 30d
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 0
        l1Ttl: 30d
        l2MaxSize: 500000
        l2Ttl: 30d
```

| Cache | Purpose |
|-------|---------|
| `cooldown` | Stores cooldown evaluation results (blocked/allowed) |
| `cooldown-metadata` | Stores upstream artifact metadata (publish dates) for efficient re-evaluation |

---

## Operational Best Practices

1. **Start conservatively.** Begin with cooldown disabled globally and enable per repo type as needed.
2. **Choose appropriate durations.** 7 days is a good default for npm and PyPI; 3 days may suffice for Maven Central where package review is more stringent.
3. **Monitor the blocked list.** Review blocked artifacts regularly via the API or UI. Legitimate artifacts that are blocked can be unblocked manually.
4. **Use the UI for incident response.** During a supply chain incident, the UI provides a quick way to review and manage blocked packages.
5. **Adjust per team workflow.** If CI/CD pipelines frequently need newly published packages, consider shorter durations or selective unblocking.

---

## Related Pages

- [Configuration](configuration.md) -- meta.cooldown section overview
- [Configuration Reference](../configuration-reference.md#19-metacooldown) -- Complete cooldown key reference
- [REST API Reference](../rest-api-reference.md#10-cooldown-management) -- Cooldown API endpoints
- [Monitoring](monitoring.md) -- Observability for cooldown events
