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

## Adapter Coverage Matrix

Cooldown is enforced only on **proxy** repository types. The table below lists
every proxy adapter and which metadata endpoints are filtered (so blocked
versions are invisible to client resolvers) as of v2.2.0.

| Proxy adapter      | Metadata endpoints filtered |
|--------------------|-----------------------------|
| maven-proxy        | `maven-metadata.xml` -- `<versions>`, `<latest>`, `<release>` rewritten |
| gradle-proxy       | Same as maven-proxy (reuses Maven components) |
| npm-proxy          | `GET /{pkg}` (full and abbreviated packument), `GET /{pkg}/latest`. `dist-tags.latest` is rewritten; dist-tags pointing at blocked versions are dropped. |
| pypi-proxy         | `/simple/{pkg}/` and `/pypi/{pkg}/json`. `info.version` + `urls` rewritten using PEP 440 ordering. |
| docker-proxy       | `/v2/{name}/tags/list` filters the tags array; `/v2/{name}/manifests/{tag}` returns `MANIFEST_UNKNOWN` (404) when the tag is blocked or resolves to a blocked digest. |
| go-proxy           | `/{module}/@v/list` and `/{module}/@latest`. If `@latest` is blocked, the response is rewritten to the highest non-blocked version; 403 if every version is blocked. |
| php-proxy (Composer) | `/packages/{vendor}/{pkg}.json`, `/p2/{vendor}/{pkg}.json`, and root `/packages.json` / `/repo.json`. Lazy-providers schemes pass through -- per-package documents are filtered when Composer fetches them. |
| file-proxy         | **No metadata filtering.** See "file-proxy scope" below. |

### file-proxy scope: artifact-fetch layer only

`file-proxy` (generic / raw file proxies) has no version-resolution semantics
-- no tag list, no version list, no packument -- so there is nothing to
filter at the metadata layer. Cooldown for file-proxy applies **only at the
artifact-fetch layer**: if the file's cached-at / remote last-modified
timestamp falls within the cooldown window, the fetch is blocked with the
standard 403 envelope. Everything else (unblock API, admin UI listing,
retention) works the same as for other adapter types.

### Hosted-only adapters (out of scope)

The following adapters have **no `-proxy` variant** in Pantera, so cooldown
does not apply to them. They serve your own published artifacts, where
delaying visibility is counter-productive.

- `gem`, `helm`, `rpm`, `conan`, `conda`, `hexpm`, `nuget`, `deb`

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

## Verifying Filtering End-to-End

Use these commands against a proxy repository to confirm metadata filtering is
active. Replace `pantera-host:8080` and repo names with your own, and pick a
package whose latest version you have manually blocked (or that the system has
auto-blocked) for the clearest result.

```bash
# npm -- packument; look for blocked versions missing from "versions" and
# dist-tags.latest pointing at the highest non-blocked version.
curl -s http://pantera-host:8080/npm-proxy/lodash | jq '.["dist-tags"], (.versions | keys[])'

# npm -- unbounded-latest shortcut.
curl -s http://pantera-host:8080/npm-proxy/lodash/latest | jq '.version'

# PyPI -- simple index (HTML): blocked versions have no <a> tag.
curl -s http://pantera-host:8080/pypi-proxy/simple/requests/ | grep -Eo 'requests-[0-9.]+'

# PyPI -- JSON API: info.version and urls reflect the highest non-blocked version.
curl -s http://pantera-host:8080/pypi-proxy/pypi/requests/json | jq '.info.version, (.releases | keys)'

# Go -- version list.
curl -s http://pantera-host:8080/go-proxy/github.com/gorilla/mux/@v/list

# Go -- @latest (should rewrite to highest non-blocked when latest is blocked).
curl -s http://pantera-host:8080/go-proxy/github.com/gorilla/mux/@latest | jq .

# Docker -- tag list.
curl -s http://pantera-host:8080/docker-proxy/v2/library/nginx/tags/list | jq .

# Docker -- manifest by tag (expect 404 MANIFEST_UNKNOWN when tag is blocked).
curl -sv http://pantera-host:8080/docker-proxy/v2/library/nginx/manifests/latest

# Composer -- per-package.
curl -s http://pantera-host:8080/php-proxy/p2/monolog/monolog.json | jq '.packages."monolog/monolog" | keys'

# Composer -- root aggregation (inline packages filtered; lazy-providers pass-through).
curl -s http://pantera-host:8080/php-proxy/packages.json | jq .

# Maven -- metadata rewriting.
curl -s http://pantera-host:8080/maven-proxy/com/google/guava/guava/maven-metadata.xml
```

Cross-check with the admin API to confirm which versions are currently
blocked for the same package:

```bash
curl -s "http://pantera-host:8086/api/v1/cooldown/blocked?search=<package>" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

The blocked versions from the second query should be exactly the ones missing
from the first.

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
