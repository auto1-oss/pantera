# Authorization

> **Guide:** Admin Guide | **Section:** Authorization

Pantera uses role-based access control (RBAC) to govern what authenticated users can do. This page covers the permission model, role definitions, user-role assignment, and management via both YAML files and the REST API.

---

## Permission Model

Pantera permissions are scoped into two categories:

- **API permissions** -- Control access to management API operations (repository CRUD, user management, role management, cooldown management, search, storage alias management).
- **Adapter permissions** -- Control per-repository artifact operations (read, write, delete).

A user's effective permissions are the union of all permissions from their assigned roles plus any inline permissions defined directly on the user.

---

## Permission Types

The keys of a role's `permissions` object are the permission types below; repository names go *inside* a type, never at the top level. `PUT /api/v1/roles/:name` refuses a role whose keys are not registered types with `400`, because the policy cannot load such a role and it would grant nothing.

### adapter_basic_permissions

Controls read, write, and delete access to repositories.

```yaml
adapter_basic_permissions:
  my-maven:         # Repository name
    - read
    - write
  npm-local:
    - read
  "*":               # Wildcard: all repositories
    - read
```

| Value | Description |
|-------|-------------|
| `read` | Download artifacts, browse repository contents |
| `write` | Upload artifacts, deploy packages. On a repository whose `immutable` setting is `false`, also overwrite stored artifacts |
| `delete` | Delete artifacts: `DELETE /<repo>/<path>` on a local repository (and the format's native deletes, such as npm unpublish), and cache eviction with the same request on a `file`, `maven`, `gradle`, `npm`, `pypi`, `go` or `php` proxy |
| `*` | All of the above |

Deleting does not depend on the repository's `immutable` setting; overwriting does. See [Immutable artifacts](../configuration-reference.md#immutable-artifacts). Since 2.2.10, `delete` (and `*`) on a repository also covers HTTP `DELETE` on the repository URL; grant it only to users who may remove artifacts or evict proxy caches.

### docker_repository_permissions

Controls Docker-specific operations at the repository level within a registry.

```yaml
docker_repository_permissions:
  "*":               # Registry name (wildcard = all)
    "*":             # Repository name (wildcard = all)
      - pull
      - push
```

| Value | Description |
|-------|-------------|
| `pull` | Pull Docker images |
| `push` | Push Docker images: upload blobs, push new tags and new digests, and re-push a tag with the manifest it already points at |
| `overwrite` | Move an existing tag to a different manifest. A push that would change the digest of an existing tag without `overwrite` fails with `403 DENIED`. Grant it (together with `push`) to CI users that re-push mutable tags such as `latest` |
| `delete` | Delete through the registry API on local (`docker`) repositories: `DELETE /v2/<repo>/<image>/manifests/<tag>` (that tag only), `.../manifests/<digest>` (the manifest and every tag pointing at it, as `skopeo delete` sends) and `.../blobs/<digest>`. Not implied by `pull`, `push` or `overwrite` |
| `*` | All Docker operations, including `overwrite` and `delete` |

Since 2.2.10 `*` includes `delete`: a role granted `["*"]` can delete images. To grant everything except deletion, list `pull`, `push` and `overwrite` explicitly. The role editor in the UI offers all four actions.

### docker_registry_permissions

Controls Docker registry-level access.

```yaml
docker_registry_permissions:
  "*":
    - base
```

| Value | Description |
|-------|-------------|
| `base` | Access the Docker V2 API base endpoint |
| `catalog` | List the images of a repository: `GET /v2/<repo>/_catalog`. A group's catalog needs `read` on the group and lists the members on which the user holds `catalog` |
| `*` | Both of the above |

### all_permission

Grants unrestricted access to everything -- all repositories, all API operations.

```yaml
all_permission: {}
```

Use this only for admin roles. It is equivalent to a superuser.

---

## API Permission Domains

When a user authenticates to the REST API, Pantera resolves the following API permission domains from their roles. These control access to the management API endpoints.

| Domain | Values | Controls |
|--------|--------|----------|
| `api_repository_permissions` | `read`, `create`, `update`, `delete`, `move` | Repository CRUD |
| `api_user_permissions` | `read`, `create`, `update`, `delete`, `enable`, `change_password` | User management |
| `api_role_permissions` | `read`, `create`, `update`, `delete`, `enable` | Role management |
| `api_storage_alias_permissions` | `read`, `create`, `delete` | Storage alias management |
| `api_cooldown_permissions` | `read`, `write` | Cooldown configuration and unblocking |
| `api_search_permissions` | `read`, `write` | Search queries and reindexing |

Users with `all_permission` automatically have all API permissions.

---

## Role Definitions

### YAML File Format

Role files are YAML files stored under the policy storage path, typically inside a `roles/` subdirectory. The filename (minus extension) is the role name.

**Admin role (full access):**

```yaml
# /var/pantera/security/roles/admin.yaml
permissions:
  all_permission: {}
```

**Read-only role:**

```yaml
# /var/pantera/security/roles/readers.yaml
permissions:
  adapter_basic_permissions:
    "*":
      - read
```

**Custom deployer role:**

```yaml
# /var/pantera/security/roles/deployer.yaml
permissions:
  adapter_basic_permissions:
    maven:
      - read
      - write
    npm:
      - read
      - write
    docker_local:
      - read
      - write
  docker_repository_permissions:
    "*":
      "*":
        - pull
        - push
```

### Managing Roles via REST API

Roles can also be managed via the REST API when PostgreSQL is configured.

**Create a role:**

```bash
curl -X PUT http://pantera-host:8086/api/v1/roles/reader \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"permissions":{"adapter_basic_permissions":{"*":["read"]}}}'
```

**List roles:**

```bash
curl "http://pantera-host:8086/api/v1/roles?page=0&size=50" \
  -H "Authorization: Bearer $TOKEN"
```

**Delete a role:**

```bash
curl -X DELETE http://pantera-host:8086/api/v1/roles/old-role \
  -H "Authorization: Bearer $TOKEN"
```

**Enable/disable a role:**

```bash
curl -X POST http://pantera-host:8086/api/v1/roles/developer/disable \
  -H "Authorization: Bearer $TOKEN"
```

See the [REST API Reference](../rest-api-reference.md#6-role-management) for the full role API.

---

## User-Role Assignment

### YAML File Format

User files are stored under the policy storage path, typically in a `users/` subdirectory. The filename (minus extension) is the username.

**User with roles:**

```yaml
# /var/pantera/security/users/bob.yaml
type: plain
pass: s3cret
roles:
  - readers
  - deployer
```

**User with inline permissions (no roles):**

```yaml
# /var/pantera/security/users/alice.yaml
type: plain
pass: s3cret
permissions:
  adapter_basic_permissions:
    my-maven:
      - "*"
    my-npm:
      - read
      - write
```

**User with both roles and inline permissions:**

```yaml
# /var/pantera/security/users/charlie.yaml
type: plain
pass: xyz
email: charlie@example.com
roles:
  - readers
permissions:
  adapter_basic_permissions:
    special-repo:
      - write
```

### User File Keys

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `type` | string | Yes | -- | Password encoding: `plain` |
| `pass` | string | Yes | -- | User password |
| `email` | string | No | -- | User email address |
| `roles` | list | No | `[]` | Assigned role names |
| `enabled` | boolean | No | `true` | Whether the account is active |
| `permissions` | map | No | -- | Inline permissions (merged with role permissions) |

### Managing Users via REST API

**Create a user with roles:**

```bash
curl -X PUT http://pantera-host:8086/api/v1/users/newuser \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "plain",
    "pass": "securePassword",
    "email": "newuser@example.com",
    "roles": ["reader", "deployer"]
  }'
```

**Change a user's password** (your own needs `old_pass`; resetting someone else's needs `change_password`, no `old_pass`, and — unless you hold `all_permission` — a target that holds only roles you hold and no `all_permission`):

```bash
curl -X POST http://pantera-host:8086/api/v1/users/newuser/password \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"old_pass":"securePassword","new_pass":"newSecurePassword"}'
```

**Disable a user:**

```bash
curl -X POST http://pantera-host:8086/api/v1/users/newuser/disable \
  -H "Authorization: Bearer $TOKEN"
```

See the [REST API Reference](../rest-api-reference.md#5-user-management) for the full user API.

---

## SSO Group-to-Role Mapping

When using Okta, group memberships from the id_token are automatically mapped to Pantera roles:

```yaml
credentials:
  - type: okta
    group-roles:
      - pantera_readers: "reader"
      - pantera_developers: "deployer"
      - pantera_admins: "admin"
```

SSO users are auto-provisioned on first login. Their roles are updated on each authentication based on the current group mappings.

---

## Policy Cache

Permission lookups are cached to reduce database and file I/O overhead. The cache TTL is configured in `meta.policy`:

```yaml
meta:
  policy:
    type: pantera
    eviction_millis: 180000    # 3 minutes
    storage:
      type: fs
      path: /var/pantera/security
```

After changing role or user files, the updated permissions take effect within `eviction_millis` milliseconds. For immediate effect, restart the Pantera instance.

In HA deployments with Valkey, cache invalidation is propagated across nodes automatically.

---

## Common Role Patterns

| Role | Use Case | Permissions |
|------|----------|-------------|
| `admin` | Full access | `all_permission: {}` |
| `reader` | Read-only access to all repos | `adapter_basic_permissions: {"*": ["read"]}` |
| `deployer` | CI/CD pipeline | `adapter_basic_permissions: {"maven": ["read","write"], "npm": ["read","write"]}` |
| `docker-user` | Docker pull/push of new tags | `docker_repository_permissions: {"*": {"*": ["pull","push"]}}` |
| `docker-ci` | Docker pull/push, may move existing tags (`latest`) | `docker_repository_permissions: {"*": {"*": ["pull","push","overwrite"]}}` |
| `docker-cleanup` | Delete old images and tags (`skopeo delete`, registry GC) | `docker_repository_permissions: {"*": {"*": ["pull","delete"]}}` |
| `maven-cleanup` | Delete artifacts and evict proxy caches by path | `adapter_basic_permissions: {"maven": ["read","delete"], "maven-proxy": ["read","delete"]}` |
| `security-admin` | Cooldown management only | API permissions for cooldown read/write |

---

## Related Pages

- [Authentication](authentication.md) -- Auth provider configuration
- [Configuration Reference](../configuration-reference.md#5-user-files) -- User file format reference
- [Configuration Reference](../configuration-reference.md#6-role--permission-files) -- Role file format reference
- [REST API Reference](../rest-api-reference.md#5-user-management) -- User API endpoints
- [REST API Reference](../rest-api-reference.md#6-role-management) -- Role API endpoints
