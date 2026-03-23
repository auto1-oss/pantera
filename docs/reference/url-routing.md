# URL Routing Patterns

> See [Admin Guide > Configuration](../admin-guide/configuration.md) for repository setup.

Pantera supports multiple URL patterns for accessing repositories. The routing engine resolves the repository name from the URL and dispatches the request.

---

## Supported Access Patterns

### For Most Repository Types

Applies to: conan, conda, deb, docker, file, gem, go, helm, hexpm, npm, nuget, php, pypi.

| Pattern | Example | Description |
|---------|---------|-------------|
| `/<repo_name>/<path>` | `/maven/com/example/lib/1.0/lib-1.0.jar` | Direct access by repository name |
| `/<prefix>/<repo_name>/<path>` | `/test_prefix/maven/com/example/...` | Prefixed access (requires `global_prefixes`) |
| `/api/<repo_name>/<path>` | `/api/maven/com/example/...` | API-routed access |
| `/<prefix>/api/<repo_name>/<path>` | `/test_prefix/api/maven/...` | Prefixed API access |
| `/api/<repo_type>/<repo_name>/<path>` | `/api/npm/my-npm-repo/lodash` | Type-qualified API access |
| `/<prefix>/api/<repo_type>/<repo_name>/<path>` | `/test_prefix/api/npm/my-npm-repo/...` | Prefixed type-qualified API access |

### For Maven, Gradle, and RPM (Limited Support)

These types only support the first four patterns above. They do **not** support `/api/<repo_type>/<repo_name>` routing.

| Pattern | Example |
|---------|---------|
| `/<repo_name>/<path>` | `/my-maven/com/example/artifact/1.0/artifact-1.0.jar` |
| `/api/<repo_name>/<path>` | `/api/my-maven/com/example/...` |

---

## Repository Type URL Aliases

When using the `/api/<repo_type>/...` pattern, the following type names are recognized:

| URL Type | Maps to `repo.type` |
|----------|---------------------|
| `conan` | conan |
| `conda` | conda |
| `debian` | deb |
| `docker` | docker |
| `storage` | file |
| `gems` | gem |
| `go` | go |
| `helm` | helm |
| `hex` | hexpm |
| `npm` | npm |
| `nuget` | nuget |
| `composer` | php |
| `pypi` | pypi |

---

## Disambiguation Rules

When the first segment after `/api/` matches both a known repository type and a repository name, Pantera checks the second segment against the repository registry. If the second segment is a known repository name, the type-qualified interpretation is used. Otherwise, the first segment is treated as the repository name.

---

## Special Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/.health` | GET | Health check (returns 200 OK) |
| `/.version` | GET | Returns Pantera version information |
| `/.import/<path>` | PUT/POST | Bulk artifact import API |
