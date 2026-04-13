# Search and Browse

> **Guide:** User Guide | **Section:** Search and Browse

This page covers how to find artifacts across all repositories using Pantera's full-text search, artifact locate, and browsing capabilities.

---

## Full-Text Search via API

Search across all indexed artifacts by name, path, version, or any text token:

```bash
curl "http://pantera-host:8086/api/v1/search?q=spring-boot&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

Response:

```json
{
  "items": [
    {
      "repo_type": "maven-proxy",
      "repo_name": "maven-central",
      "artifact_path": "org/springframework/boot/spring-boot/3.2.0/spring-boot-3.2.0.jar",
      "artifact_name": "spring-boot",
      "version": "3.2.0",
      "size": 1523456,
      "created_at": "2024-11-15T10:30:00Z",
      "owner": "system"
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1,
  "hasMore": false
}
```

Results are automatically filtered by your permissions -- you only see artifacts in repositories you have access to.

### Structured Search Syntax

In addition to plain full-text search, you can use field prefixes to narrow results:

| Syntax | Description | Example |
|--------|-------------|---------|
| `pydantic` | Full-text search (all fields) | `pydantic` |
| `name:value` | Filter by exact artifact name (case-insensitive) | `name:pydantic` |
| `version:value` | Filter by version (case-insensitive, supports partial match) | `version:2.12` |
| `repo:value` | Filter by repository name (exact match) | `repo:pypi-proxy` |
| `type:value` | Filter by repository type (prefix match, strips `-proxy`/`-group`) | `type:maven` |

Combine filters with `AND` and `OR`:

```
name:pydantic AND version:2.12
name:pydantic AND (version:2.12 OR version:2.11)
repo:pypi-proxy AND type:pypi
```

Mixing plain text with field filters is supported:

```
pydantic AND repo:pypi-proxy
```

Queries without any prefix work exactly as before (plain full-text search).

### Search Tips

- Search is case-insensitive.
- Dots, dashes, slashes, and underscores are treated as word separators, so searching `spring-boot` also matches `spring.boot` and `spring/boot`.
- If the full-text search returns no results, Pantera falls back to substring matching automatically.
- The `type:` prefix matches repository type prefixes, so `type:maven` matches both `maven` and `maven-proxy` repositories.

### Pagination

| Parameter | Default | Maximum |
|-----------|---------|---------|
| `q` | (required) | -- |
| `page` | 0 | limited by max offset (10,000 rows) |
| `size` | 20 | 100 |

Deep pagination is capped at an effective offset of 10,000 rows. Requests that would exceed this limit are rejected with `400 Bad Request`. Use narrower search filters to locate specific results beyond this limit.

---

## Locate Artifacts

Find which repositories contain a specific artifact:

```bash
curl "http://pantera-host:8086/api/v1/search/locate?path=org/example/mylib" \
  -H "Authorization: Bearer $TOKEN"
```

Response:

```json
{
  "repositories": ["maven-local", "maven-proxy"],
  "count": 2
}
```

This is useful when you need to know which repository a dependency is being resolved from.

---

## Browse via Management UI

The Management UI at `http://pantera-host:8090/` provides a visual interface for searching and browsing:

### Search View

1. Navigate to **Search** in the sidebar.
2. Type your query in the search bar. Results appear as you type (with debounced auto-search).
3. Filter results by package type using the left sidebar (Maven, npm, Docker, etc.).
4. Click **Browse** on any result to navigate to the artifact in its repository.

### Repository Browser

1. Navigate to **Repositories** in the sidebar.
2. Click on a repository name to open its detail page.
3. Browse the directory tree by clicking folders.
4. Click on a file to view its metadata (path, size, modification date).
5. Download artifacts directly from the detail dialog.

For proxy repositories, the browser shows only artifacts that have been cached locally (previously requested through the proxy).

For group repositories, the browser shows the member repository list. Click a member to browse its contents.

---

## Directory Browsing via HTTP

Some repository types support directory browsing via HTTP. Access a directory path directly:

```bash
# List root of a file repository
curl http://pantera-host:8080/bin/

# List a subdirectory
curl http://pantera-host:8080/bin/releases/v1.0/
```

This works for generic file repositories. Maven, npm, and Docker repositories use their own metadata formats for discovery (e.g., `maven-metadata.xml`, npm package index).

---

## Index Statistics

Check the current state of the search index:

```bash
curl http://pantera-host:8086/api/v1/search/stats \
  -H "Authorization: Bearer $TOKEN"
```

Response:

```json
{
  "total_documents": 145230,
  "index_size_bytes": 52428800,
  "last_indexed": "2026-03-22T10:00:00Z"
}
```

---

## Trigger Reindex (Admin)

If search results seem stale or incomplete, an administrator can trigger a full reindex:

```bash
curl -X POST http://pantera-host:8086/api/v1/search/reindex \
  -H "Authorization: Bearer $TOKEN"
# Returns 202 Accepted
```

The reindex runs asynchronously in the background.

For full endpoint documentation, see the [REST API Reference](../rest-api-reference.md).

---

## Related Pages

- [User Guide Index](index.md)
- [Management UI](ui-guide.md) -- Visual search and browsing
- [REST API Reference](../rest-api-reference.md) -- Search API endpoints
