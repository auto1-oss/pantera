# API Routing Support

Artipie now supports multiple URL patterns for accessing repositories, similar to Artifactory's API structure.

## Supported URL Patterns

### For Most Repository Types
(Applies to: conan, conda, deb, docker, file, gem, go, helm, hexpm, npm, nuget, php, pypi)

1. `/<repo_name>` - Direct access
2. `/<global_prefix>/<repo_name>` - With global prefix (e.g., `/artifactory/my_repo`)
3. `/api/<repo_name>` - API format
4. `/<global_prefix>/api/<repo_name>` - API with prefix
5. `/api/<repo_type>/<repo_name>` - API with repository type
6. `/<global_prefix>/api/<repo_type>/<repo_name>` - API with type and prefix

### For Maven, Gradle, and RPM (Limited Support)

1. `/<repo_name>` - Direct access
2. `/<global_prefix>/<repo_name>` - With global prefix
3. `/api/<repo_name>` - API format
4. `/<global_prefix>/api/<repo_name>` - API with prefix

## Repository Type Mappings

The URL `repo_type` parameter maps to internal repository types as follows:

| URL Type   | Internal Type | Description                    |
|------------|---------------|--------------------------------|
| `conan`    | conan         | Conan packages                 |
| `conda`    | conda         | Conda packages                 |
| `debian`   | deb           | Debian packages                |
| `docker`   | docker        | Docker images                  |
| `storage`  | file          | Generic file storage           |
| `gems`     | gem           | Ruby gems                      |
| `go`       | go            | Go modules                     |
| `helm`     | helm          | Helm charts                    |
| `hex`      | hexpm         | Hex packages                   |
| `npm`      | npm           | NPM packages                   |
| `nuget`    | nuget         | NuGet packages                 |
| `composer` | php           | Composer/PHP packages          |
| `pypi`     | pypi          | Python packages                |

## Examples

### Composer/PHP Repository

All of these URLs access the same repository `my_php_repo`:

```
GET /my_php_repo/packages.json
GET /artifactory/my_php_repo/packages.json
GET /api/my_php_repo/packages.json
GET /artifactory/api/my_php_repo/packages.json
GET /api/composer/my_php_repo/packages.json
GET /artifactory/api/composer/my_php_repo/packages.json
```

### NPM Repository

```
GET /my_npm_repo/express
GET /api/npm/my_npm_repo/express
GET /artifactory/api/npm/my_npm_repo/express
```

### Maven Repository (Limited Support)

```
GET /my_maven_repo/com/example/artifact/1.0.0/artifact-1.0.0.jar
GET /api/my_maven_repo/com/example/artifact/1.0.0/artifact-1.0.0.jar
```

Note: Maven does **not** support `/api/maven/my_maven_repo` pattern.

## Implementation

The routing is handled by `ApiRoutingSlice` which:

1. Intercepts requests matching `/api/*` patterns
2. Extracts the repository name and optional type
3. Rewrites the request to the canonical `/<repo_name>` format
4. Forwards to the appropriate repository slice

## Routing Order

```
MainSlice
  → DockerRoutingSlice    (handles Docker-specific routing)
  → ApiRoutingSlice       (handles API routing for all types)
  → SliceByPath           (routes to specific repositories)
```

## Configuration

No additional configuration is required. The routing is automatically enabled for all repository types based on their type configuration.
