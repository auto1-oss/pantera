# Pantera REST API Reference

**Version:** 2.1.0
**Base URL:** `http://localhost:8086/api/v1`
**Repository Port:** `8080` (artifact operations, health, version, import)
**Metrics Port:** `8087` (Prometheus metrics)

All Management API endpoints are served on port **8086** under the `/api/v1` prefix.
Repository-facing endpoints (health, version, import, artifact serving) are on port **8080**.

---

## Table of Contents

1. [Authentication](#1-authentication)
2. [Current User](#2-current-user)
3. [API Token Management](#3-api-token-management)
4. [Repository Management](#4-repository-management)
5. [User Management](#5-user-management)
6. [Role Management](#6-role-management)
7. [Storage Alias Management](#7-storage-alias-management)
8. [Artifact Operations](#8-artifact-operations)
9. [Search](#9-search)
10. [Cooldown Management](#10-cooldown-management)
11. [Settings](#11-settings)
12. [Auth Provider Management](#12-auth-provider-management)
13. [Admin: Auth Settings](#13-admin-auth-settings)
14. [Dashboard](#14-dashboard)
15. [Health and System](#15-health-and-system)
16. [Import](#16-import)
17. [Error Format](#17-error-format)
18. [Pagination](#18-pagination)
19. [Admin: Cache Tools](#19-admin-cache-tools)

---

## 1. Authentication

All `/api/v1/*` endpoints require a JWT Bearer token in the `Authorization` header, except for:

- `POST /api/v1/auth/token` (login)
- `GET /api/v1/auth/providers` (list auth providers)
- `GET /api/v1/auth/providers/:name/redirect` (SSO redirect URL)
- `POST /api/v1/auth/callback` (SSO code exchange)
- `GET /api/v1/repositories/:name/artifact/download-direct` (uses HMAC token)
- `GET /api/v1/health` (public health check)

CORS is enabled for all `/api/v1/*` routes with `Access-Control-Allow-Origin: *`.

### POST /api/v1/auth/token

Authenticate with username and password. Returns an RS256-signed access token and a refresh token. Auth providers are tried in priority order (local, Okta, Keycloak).

**Authentication:** None required.

**Request Body:**

```json
{
  "name": "admin",
  "pass": "password123",
  "mfa_code": "123456"
}
```

| Field      | Type   | Required | Description                                      |
|------------|--------|----------|--------------------------------------------------|
| `name`     | string | Yes      | Username                                         |
| `pass`     | string | Yes      | Password                                         |
| `mfa_code` | string | No       | Okta MFA verification code (required if MFA is enabled) |

**Response (200):**

```json
{
  "token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 3600
}
```

| Field           | Type    | Description                                              |
|-----------------|---------|----------------------------------------------------------|
| `token`         | string  | RS256-signed access token (type: `access`). Use as Bearer or as JWT password. |
| `refresh_token` | string  | RS256-signed refresh token (type: `refresh`). Store securely; used only to obtain new access tokens via `POST /api/v1/auth/refresh` — it is not accepted as a Bearer credential or password on any other API route or repository endpoint. |
| `expires_in`    | integer | Access token lifetime in seconds (matches `access-token-expiry-seconds`). |

> **Breaking change from v2.0:** The response previously returned only `{"token": "..."}`. Clients that stored the token for long-lived use must now use API tokens (`POST /api/v1/auth/token/generate`) or refresh the access token via `POST /api/v1/auth/refresh`.

**Response (401):**

```json
{
  "error": "UNAUTHORIZED",
  "message": "Invalid credentials",
  "status": 401
}
```

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"name": "admin", "pass": "password123"}'
```

---

### POST /api/v1/auth/refresh

Exchange a refresh token for a new token pair (an access token plus a rotated refresh token). The refresh token must not be expired or revoked.

**Authentication:** the **refresh** token (not an access or API token) as `Authorization: Bearer <refresh_token>`. The request has no body; a refresh token sent in a JSON body is not read, and the call answers `401`.

**Response (200):**

```json
{
  "token": "eyJhbGciOiJSUzI1NiIs...",
  "refresh_token": "eyJhbGciOiJSUzI1NiIs...",
  "expires_in": 3600
}
```

**Response (401):** the refresh token is missing, expired or revoked, or the bearer token is not a refresh token.

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/auth/refresh \
  -H "Authorization: Bearer $REFRESH_TOKEN"
```

---

### GET /api/v1/auth/providers

List configured authentication providers (local, Okta, Keycloak).

**Authentication:** None required.

**Response (200):**

```json
{
  "providers": [
    { "type": "local", "enabled": true },
    { "type": "okta", "enabled": true },
    { "type": "keycloak", "enabled": false }
  ]
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/auth/providers
```

---

### GET /api/v1/auth/providers/:name/redirect

Build the OAuth2 authorization URL for an SSO provider (Okta or Keycloak). Used by the UI to initiate the SSO login flow.

**Authentication:** None required.

**Query Parameters:**

| Parameter      | Required | Description                              |
|----------------|----------|------------------------------------------|
| `callback_url` | Yes      | The URL the IdP should redirect back to  |

**Response (200):**

```json
{
  "url": "https://your-org.okta.com/oauth2/v1/authorize?client_id=...&response_type=code&scope=openid+profile&redirect_uri=...&state=...",
  "state": "a1b2c3d4e5f6"
}
```

**curl example:**

```bash
curl "http://localhost:8086/api/v1/auth/providers/okta/redirect?callback_url=http://localhost:3000/callback"
```

---

### POST /api/v1/auth/callback

Exchange an OAuth2 authorization code for a Pantera JWT. The server performs the token exchange with the IdP, extracts the user identity and groups from the `id_token`, maps groups to Pantera roles, and provisions the user.

**Authentication:** None required.

**Request Body:**

```json
{
  "code": "authorization_code_from_idp",
  "provider": "okta",
  "callback_url": "http://localhost:3000/callback",
  "state": "a1b2c3d4e5f6"
}
```

| Field          | Type   | Required | Description                       |
|----------------|--------|----------|-----------------------------------|
| `code`         | string | Yes      | OAuth2 authorization code         |
| `provider`     | string | Yes      | Provider type name (e.g. "okta")  |
| `callback_url` | string | Yes      | The redirect URI used in the authorize request |
| `state`        | string | Yes      | The opaque `state` returned by `GET /api/v1/auth/providers/:name/redirect`, sent back verbatim so Pantera can match the pending login it started (shared across cluster nodes over Valkey since 2.2.9). Single-use and expiring. |

**Response (200):**

```json
{
  "token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 3600
}
```

**Response (401):** the `state` is missing, unknown, already redeemed, or expired — restart the login from the `/redirect` step.

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/auth/callback \
  -H "Content-Type: application/json" \
  -d '{"code": "abc123", "provider": "okta", "callback_url": "http://localhost:3000/callback", "state": "a1b2c3d4e5f6"}'
```

---

## 2. Current User

### GET /api/v1/auth/me

Get the currently authenticated user's profile, including resolved permissions across all API permission domains.

**Authentication:** JWT Bearer token required.

**Response (200):**

```json
{
  "name": "admin",
  "context": "local",
  "email": "admin@example.com",
  "groups": ["developers"],
  "permissions": {
    "api_repository_permissions": ["read", "create", "update", "delete", "move"],
    "api_user_permissions": ["read", "create", "update", "delete", "enable", "change_password"],
    "api_role_permissions": ["read", "create", "update", "delete", "enable"],
    "api_alias_permissions": ["read", "create", "delete"],
    "api_cooldown_permissions": ["read", "write"],
    "api_search_permissions": ["read", "write"],
    "can_delete_artifacts": true
  }
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/auth/me \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

## 3. API Token Management

### POST /api/v1/auth/token/generate

Generate a long-lived API token for programmatic access. The authenticated user does not need to provide their password again, since they already hold a valid JWT session.

**Authentication:** JWT Bearer **session** (access) token required. An API token cannot mint further API tokens, and a refresh token is refused (`401`).

**Request Body:**

```json
{
  "label": "CI/CD Pipeline Token",
  "expiry_days": 90
}
```

| Field         | Type    | Required | Description                                        |
|---------------|---------|----------|----------------------------------------------------|
| `label`       | string  | No       | Human-readable label (default: "API Token")        |
| `expiry_days` | integer | No       | Days until expiry (default: 30, 0 = non-expiring)  |

**Response (200):**

```json
{
  "token": "eyJhbGciOi...",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "label": "CI/CD Pipeline Token",
  "expires_at": "2026-06-20T12:00:00Z",
  "permanent": false
}
```

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/auth/token/generate \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"label": "CI/CD Pipeline Token", "expiry_days": 90}'
```

---

### GET /api/v1/auth/tokens

List all API tokens belonging to the authenticated user. Token values are not returned -- only metadata.

**Authentication:** JWT Bearer token required.

**Response (200):**

```json
{
  "tokens": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "label": "CI/CD Pipeline Token",
      "created_at": "2026-03-22T10:00:00Z",
      "expires_at": "2026-06-20T10:00:00Z",
      "expired": false
    },
    {
      "id": "660e8400-e29b-41d4-a716-446655440001",
      "label": "Permanent Token",
      "created_at": "2026-01-15T08:00:00Z",
      "permanent": true
    }
  ]
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/auth/tokens \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### DELETE /api/v1/auth/tokens/:tokenId

Revoke an API token. Only the owner of the token can revoke it.

**Authentication:** JWT Bearer token required.

**Path Parameters:**

| Parameter | Description                    |
|-----------|--------------------------------|
| `tokenId` | UUID of the token to revoke   |

**Response (204):** No content on success.

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "Token not found",
  "status": 404
}
```

**curl example:**

```bash
curl -X DELETE http://localhost:8086/api/v1/auth/tokens/550e8400-e29b-41d4-a716-446655440000 \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

## 4. Repository Management

### GET /api/v1/repositories

List all repositories with pagination, optional type filtering, and name search. Results are filtered by the caller's `read` permission on each repository.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read`

**Query Parameters:**

| Parameter | Type    | Default | Description                              |
|-----------|---------|---------|------------------------------------------|
| `page`    | integer | 0       | Zero-based page number                   |
| `size`    | integer | 20      | Items per page (max 100)                 |
| `type`    | string  | --      | Filter by repository type (substring)    |
| `q`       | string  | --      | Filter by repository name (substring)    |

**Response (200):**

```json
{
  "items": [
    { "name": "maven-central", "type": "maven-proxy" },
    { "name": "npm-local", "type": "npm" }
  ],
  "page": 0,
  "size": 20,
  "total": 2,
  "hasMore": false
}
```

**curl example:**

```bash
curl "http://localhost:8086/api/v1/repositories?type=maven&page=0&size=10" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/repositories/:name

Get the configuration of a specific repository.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read` **and** `adapter_basic_permissions` `read` on `:name` (2.2.9 — the same per-repository filter the list endpoint applies; a repository the caller cannot list cannot be read here either).

**Secrets are write-only (2.2.9).** Secret-bearing fields anywhere in the returned document — `remotes[].password`, storage `credentials.secretAccessKey` / `sessionToken`, tokens, API keys — are returned as the mask `"***"`, never in plaintext. Submitting a document that still contains `"***"` in a secret field via `PUT` keeps the stored value (so an edit of an unrelated field does not clobber the secret); submitting a new value replaces it.

**Path Parameters:**

| Parameter | Description      |
|-----------|------------------|
| `name`    | Repository name  |

**Response (200):**

```json
{
  "repo": {
    "type": "maven-proxy",
    "storage": "default",
    "remotes": [
      { "url": "https://repo1.maven.org/maven2" }
    ]
  }
}
```

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "Repository 'nonexistent' not found",
  "status": 404
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/repositories/maven-central \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### HEAD /api/v1/repositories/:name

Check whether a repository exists. Returns 200 if found, 404 if not.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read`

**Response:** 200 (exists) or 404 (not found). No body.

**curl example:**

```bash
curl -I http://localhost:8086/api/v1/repositories/maven-central \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### PUT /api/v1/repositories/:name

Create a new repository or update an existing one. If the repository exists, the `update` permission is required; otherwise `create` is required.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:create` (new) or `api_repository_permissions:update` (existing)

**Path Parameters:**

| Parameter | Description      |
|-----------|------------------|
| `name`    | Repository name  |

**Request Body:**

```json
{
  "repo": {
    "type": "maven-proxy",
    "storage": "default",
    "remotes": [
      { "url": "https://repo1.maven.org/maven2" }
    ]
  }
}
```

| Field          | Type   | Required | Description                                            |
|----------------|--------|----------|--------------------------------------------------------|
| `repo.type`    | string | Yes      | Repository type (e.g. `maven`, `npm`, `docker-proxy`)  |
| `repo.storage` | string | Yes      | Storage alias name (e.g. `default`)                    |

Supported `repo.type` values: `file`, `file-proxy`, `file-group`, `maven`, `maven-proxy`, `maven-group`, `gradle`, `gradle-proxy`, `gradle-group`, `npm`, `npm-proxy`, `npm-group`, `pypi`, `pypi-proxy`, `pypi-group`, `docker`, `docker-proxy`, `docker-group`, `go`, `go-proxy`, `go-group`, `php`, `php-proxy`, `php-group`, `gem`, `gem-group`, `helm`, `rpm`, `nuget`, `deb`, `conda`, `conan`, `hexpm`.

**Response (200):** Empty body on success.

**Response (400):** Invalid body, including a `repo.type` that is not supported (the message lists the supported types).

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/repositories/maven-central \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{
    "repo": {
      "type": "maven-proxy",
      "storage": "default",
      "remotes": [{"url": "https://repo1.maven.org/maven2"}]
    }
  }'
```

---

### DELETE /api/v1/repositories/:name

Delete a repository and its data.

The repository's own data (everything under `<storage root>/<name>/`, including directories left empty on a filesystem storage; a symbolic link to a directory is kept) is removed first, then its rows in the search index, then its configuration; uploads still waiting to be indexed when the delete runs are not indexed afterwards; other repositories that share the same storage root are not touched. The storage is found the same way requests are served, including a storage given as an alias name (global or repository-scoped, from the database or `_storages.yaml`). A group repository has no data of its own, so only its configuration is removed. Re-creating a repository with the same name starts empty.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:delete`

The response waits up to 5 seconds for the removal. A large repository takes longer (one storage delete per object), so the response can be `202` while the removal continues on the server; the repository stays listed until its data is removed and disappears when the delete finishes. The final outcome is written to the application log (`event.action=repository_delete`) and the audit log either way. While a removal is running on a node, another `DELETE` of the same name sent to that node answers `202` without starting a second removal, and `PUT` or `move` of that name (or a move onto it) answers `409`.

**Response (200):** Empty body. The data, index rows and configuration were removed.

**Response (202):** The removal is still running.

```json
{
  "status": "deleting",
  "message": "Repository 'old-repo' is being deleted; it disappears from the list when its data is removed"
}
```

**Response (500):** The data could not be removed within the wait. The repository is kept so the delete can be retried. A removal that fails after a `202` also keeps the repository; the failure is in the log and audit record.

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "Repository 'nonexistent' not found",
  "status": 404
}
```

**curl example:**

```bash
curl -X DELETE http://localhost:8086/api/v1/repositories/old-repo \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### PUT /api/v1/repositories/:name/move

Rename/move a repository to a new name.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:move`

**Request Body:**

```json
{
  "new_name": "maven-central-v2"
}
```

**Response (200):** Empty body on success.

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "Repository 'nonexistent' not found",
  "status": 404
}
```

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/repositories/maven-central/move \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"new_name": "maven-central-v2"}'
```

---

### GET /api/v1/repositories/:name/members

List members of a group repository: the member repository names from the group's `members` list, in the declared (resolution) order.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read`

**Response (200):**

```json
{
  "type": "maven-group",
  "members": [
    "maven-local",
    "maven-central"
  ]
}
```

For non-group repositories:

```json
{
  "type": "not-a-group",
  "members": []
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/repositories/maven-group/members \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

## 5. User Management

### GET /api/v1/users

List all users with pagination, optional search, and server-side sorting.

**Authentication:** JWT Bearer token required.
**Permission:** `api_user_permissions:read`

**Query Parameters:**

| Parameter  | Type    | Default    | Description                                                       |
|------------|---------|------------|-------------------------------------------------------------------|
| `page`     | integer | 0          | Zero-based page number                                            |
| `size`     | integer | 20         | Items per page (max 100)                                          |
| `q`        | string  | --         | Search filter (case-insensitive substring on username and email)  |
| `sort`     | string  | `username` | Sort field: `username`, `email`, `enabled`, or `auth_provider`   |
| `sort_dir` | string  | `asc`      | Sort direction: `asc` or `desc`                                   |

**Response (200):**

```json
{
  "items": [
    { "name": "admin", "type": "plain", "email": "admin@example.com" },
    { "name": "reader", "type": "plain" }
  ],
  "page": 0,
  "size": 20,
  "total": 2,
  "hasMore": false
}
```

**curl example:**

```bash
curl "http://localhost:8086/api/v1/users?page=0&size=50" \
  -H "Authorization: Bearer eyJhbGciOi..."

# Search for users whose name or email contains "alice", sorted by email descending
curl "http://localhost:8086/api/v1/users?q=alice&sort=email&sort_dir=desc" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/users/:name

Get details for a specific user.

**Authentication:** JWT Bearer token required.
**Permission:** `api_user_permissions:read`

**Response (200):**

```json
{
  "type": "plain",
  "email": "admin@example.com",
  "roles": ["admin"],
  "enabled": true
}
```

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "User 'nonexistent' not found",
  "status": 404
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/users/admin \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### PUT /api/v1/users/:name

Create a new user or update an existing one. If the user exists, the `update` permission is required; otherwise `create` is required. The field `password` is accepted as an alias for `pass`.

**Authentication:** JWT Bearer token required.
**Permission:** `api_user_permissions:create` (new) or `api_user_permissions:update` (existing)

**Privilege ceiling (2.2.9).** `create`/`update` is a delegated authoring right, not root; unless the caller holds `all_permission`:
- An **existing** user can only be modified if they hold no `all_permission` and only roles the caller also holds — a delegated manager cannot edit, strip the roles of, or reset an administrator or a user above their own roles.
- Resetting an **existing** user's password (`pass`/`password` on an existing user) additionally requires `api_user_permissions:change_password`; it revokes that user's live tokens. Your **own** password is changed with `POST /api/v1/users/:name/password` and your current password, not through this endpoint.
- `roles` may only name roles the caller **already holds** — a caller cannot assign (to themselves or anyone) a role above their own, so the built-in `admin` role cannot be self-assigned.
- `type` may only be a password format (`plain`, `sha256`); setting an identity-provider type is reserved to administrators.

Requests exceeding the ceiling are refused with `403`. For every caller:
- `sso_subject` and `auth_provider` in the body are ignored — the SSO login flow alone binds an identity.
- Send the password once, as `pass` or `password`; both with different values is refused with `400`.
- Any password (on creation or reset) must satisfy the password policy; otherwise the request is refused with `400 WEAK_PASSWORD` and nothing is written. A reset updates the other fields and the password in one transaction.

**Request Body:**

```json
{
  "type": "plain",
  "pass": "securePassword123",
  "email": "user@example.com",
  "roles": ["reader", "developer"]
}
```

| Field    | Type     | Required | Description                                  |
|----------|----------|----------|----------------------------------------------|
| `type`   | string   | No       | Auth type (default: `plain`)                 |
| `pass`   | string   | Yes*     | Password (*or use `password` field alias)    |
| `email`  | string   | No       | User email address                           |
| `roles`  | string[] | No       | List of role names to assign                 |

**Response (201):** Empty body on success.

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/users/newuser \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"type": "plain", "pass": "securePassword123", "email": "newuser@example.com"}'
```

---

### DELETE /api/v1/users/:name

Delete a user.

**Authentication:** JWT Bearer token required.
**Permission:** `api_user_permissions:delete`

**Response (200):** Empty body on success.

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "User 'nonexistent' not found",
  "status": 404
}
```

**curl example:**

```bash
curl -X DELETE http://localhost:8086/api/v1/users/olduser \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### POST /api/v1/users/:name/password

Change a user's password.

- **Your own password** (`:name` is you): no permission is needed, but `old_pass` must be your current local password (checked against the stored password only — a token is not accepted). A wrong `old_pass` is refused with `403`.
- **Another user's password** (reset): requires `api_user_permissions:change_password`, no `old_pass`, and the privilege ceiling of `PUT /api/v1/users/:name` — unless you hold `all_permission`, the target must hold no `all_permission` and only roles you also hold (`403` otherwise).

Either way the new password must satisfy the password policy (`400 WEAK_PASSWORD`), and every live token of the user is revoked.

**Authentication:** JWT Bearer token required.
**Permission:** none for your own password; `api_user_permissions:change_password` to reset another user's

**Request Body:**

```json
{
  "old_pass": "currentPassword",
  "new_pass": "newSecurePassword"
}
```

**Response (200):** Empty body on success.

**Response (403):**

```json
{
  "error": "FORBIDDEN",
  "message": "Current password is incorrect.",
  "status": 403
}
```

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/users/admin/password \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"old_pass": "currentPassword", "new_pass": "newSecurePassword"}'
```

---

### POST /api/v1/users/:name/enable

Enable a disabled user account.

**Authentication:** JWT Bearer token required.
**Permission:** `api_user_permissions:enable`

**Response (200):** Empty body on success.

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "User 'nonexistent' not found",
  "status": 404
}
```

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/users/jdoe/enable \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### POST /api/v1/users/:name/disable

Disable a user account.

**Authentication:** JWT Bearer token required.
**Permission:** `api_user_permissions:enable`

**Response (200):** Empty body on success.

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "User 'nonexistent' not found",
  "status": 404
}
```

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/users/jdoe/disable \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

## 6. Role Management

### GET /api/v1/roles

List all roles with pagination, optional search, and server-side sorting.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:read`

**Query Parameters:**

| Parameter  | Type    | Default | Description                                                |
|------------|---------|---------|------------------------------------------------------------|
| `page`     | integer | 0       | Zero-based page number                                     |
| `size`     | integer | 20      | Items per page (max 100)                                   |
| `q`        | string  | --      | Search filter (case-insensitive substring on role name)    |
| `sort`     | string  | `name`  | Sort field: `name` or `enabled`                            |
| `sort_dir` | string  | `asc`   | Sort direction: `asc` or `desc`                            |

**Response (200):**

```json
{
  "items": [
    { "name": "admin", "enabled": true, "permissions": { "all_permission": {} } },
    { "name": "reader", "enabled": true, "permissions": { "adapter_basic_permissions": { "*": ["read"] } } }
  ],
  "page": 0,
  "size": 20,
  "total": 2,
  "hasMore": false
}
```

**curl example:**

```bash
curl "http://localhost:8086/api/v1/roles?page=0&size=50" \
  -H "Authorization: Bearer eyJhbGciOi..."

# Search for roles whose name contains "dev", sorted by name
curl "http://localhost:8086/api/v1/roles?q=dev&sort=name&sort_dir=asc" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/roles/:name

Get details for a specific role.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:read`

**Response (200):**

```json
{
  "permissions": {
    "adapter_basic_permissions": {
      "maven-central": ["read"],
      "npm-local": ["read", "write"]
    }
  },
  "enabled": true
}
```

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "Role 'nonexistent' not found",
  "status": 404
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/roles/developer \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### PUT /api/v1/roles/:name

Create a new role or update an existing one. If the role exists, the `update` permission is required; otherwise `create` is required.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:create` (new) or `api_role_permissions:update` (existing)

**Privilege ceiling (2.2.9).** Unless the caller holds `all_permission`: the built-in `admin` role cannot be modified; `all_permission` cannot be authored into a role; and every permission the role would grant must already be implied by the caller's own effective permissions (evaluated on the materialised permissions, not the raw JSON). A role editor therefore cannot grant themselves or others anything above what they hold. Requests exceeding the ceiling are refused with `403`.

**Request Body:** the keys of `permissions` are permission types, not repository names. Repository access goes under `adapter_basic_permissions`, keyed by repository name (`*` for every repository), with the actions `read`, `write` and `delete` (`download`/`upload` are accepted aliases of `read`/`write`). Docker repositories additionally use `docker_repository_permissions` and `docker_registry_permissions`; management-API access uses the `api_*_permissions` types. The full list of types and actions is in the [authorization guide](admin-guide/authorization.md).

```json
{
  "permissions": {
    "adapter_basic_permissions": {
      "maven-central": ["read"],
      "npm-local": ["read", "write", "delete"]
    }
  }
}
```

**Response (201):** Empty body on success.

**Response (400):** the body is not JSON, or a key of `permissions` is not a registered permission type (for example a repository name used as a top-level key) or has a malformed value. Nothing is stored.

```json
{
  "error": "BAD_REQUEST",
  "message": "Invalid role permissions: Permission type maven-central is not found. The keys of 'permissions' must be permission types such as adapter_basic_permissions, keyed by repository inside",
  "status": 400
}
```

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/roles/developer \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"permissions": {"adapter_basic_permissions": {"maven-central": ["read"], "npm-local": ["read", "write", "delete"]}}}'
```

---

### DELETE /api/v1/roles/:name

Delete a role.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:delete`

**Response (200):** Empty body on success.

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "Role 'nonexistent' not found",
  "status": 404
}
```

**curl example:**

```bash
curl -X DELETE http://localhost:8086/api/v1/roles/old-role \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### POST /api/v1/roles/:name/enable

Enable a disabled role.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:enable`

**Response (200):** Empty body on success.

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/roles/developer/enable \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### POST /api/v1/roles/:name/disable

Disable a role.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:enable`

**Response (200):** Empty body on success.

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/roles/developer/disable \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

## 7. Storage Alias Management

### GET /api/v1/storages

List all global storage aliases.

**Authentication:** JWT Bearer token required.
**Permission:** `api_storage_alias_permissions:read`

Backend credentials in each alias `config` (`secretAccessKey`, `sessionToken`, tokens, passwords) are returned masked as `"***"` (2.2.9) — the same write-only rule as repository configuration.

**Response (200):**

```json
[
  {
    "name": "default",
    "config": {
      "type": "fs",
      "path": "/var/pantera/data"
    }
  },
  {
    "name": "s3-prod",
    "config": {
      "type": "s3",
      "bucket": "pantera-artifacts",
      "region": "eu-west-1"
    }
  }
]
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/storages \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### PUT /api/v1/storages/:name

Create or update a global storage alias.

**Authentication:** JWT Bearer token required.
**Permission:** `api_storage_alias_permissions:create`

**Request Body:**

```json
{
  "type": "fs",
  "path": "/var/pantera/data"
}
```

**Response (200):** Empty body on success.

**Response (400):** A local-filesystem (`fs`, `vertx-file`) `path` outside the approved roots (`fs_storage_roots`), or an S3 `endpoint` the egress policy refuses. An update that keeps the alias's saved path is not re-validated.

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/storages/default \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"type": "fs", "path": "/var/pantera/data"}'
```

---

### DELETE /api/v1/storages/:name

Delete a global storage alias. Fails with 409 if any repositories reference it.

**Authentication:** JWT Bearer token required.
**Permission:** `api_storage_alias_permissions:delete`

**Response (200):** Empty body on success.

**Response (409):**

```json
{
  "error": "CONFLICT",
  "message": "Cannot delete alias 'default': used by repositories: maven-central, npm-local",
  "status": 409
}
```

**curl example:**

```bash
curl -X DELETE http://localhost:8086/api/v1/storages/old-storage \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/repositories/:name/storages

List storage aliases scoped to a specific repository.

**Authentication:** JWT Bearer token required.
**Permission:** `api_storage_alias_permissions:read` **and** `adapter_basic_permissions` `read` on `:name` (2.2.9 — per-repository scope is enforced in addition to the global bit).

**Response (200):**

```json
[
  {
    "name": "local",
    "config": {
      "type": "fs",
      "path": "/var/pantera/data/custom"
    }
  }
]
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/repositories/maven-central/storages \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### PUT /api/v1/repositories/:name/storages/:alias

Create or update a storage alias scoped to a repository.

**Authentication:** JWT Bearer token required.
**Permission:** `api_storage_alias_permissions:create` **and** `adapter_basic_permissions` `write` on `:name` (2.2.9 — this route previously accepted the read-only alias grant; an alias write rewrites the repository's backing storage and now requires the create bit plus repository-scoped write).

**Request Body:**

```json
{
  "type": "fs",
  "path": "/var/pantera/data/maven-custom"
}
```

**Response (200):** Empty body on success.

**Response (400):** A local-filesystem (`fs`, `vertx-file`) `path` outside the approved roots (`fs_storage_roots`), or an S3 `endpoint` the egress policy refuses. An update that keeps the alias's saved path is not re-validated.

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/repositories/maven-central/storages/local \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"type": "fs", "path": "/var/pantera/data/maven-custom"}'
```

---

### DELETE /api/v1/repositories/:name/storages/:alias

Delete a repository-scoped storage alias.

**Authentication:** JWT Bearer token required.
**Permission:** `api_storage_alias_permissions:delete` **and** `adapter_basic_permissions` `delete` on `:name` (2.2.9).

**Response (200):** Empty body on success.

**curl example:**

```bash
curl -X DELETE http://localhost:8086/api/v1/repositories/maven-central/storages/local \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

## 8. Artifact Operations

### GET /api/v1/repositories/:name/tree

Browse the storage contents of a repository. Returns a shallow directory listing at the given path.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read`

**Query Parameters:**

| Parameter | Type   | Default | Description                              |
|-----------|--------|---------|------------------------------------------|
| `path`    | string | `/`     | Directory path within the repository     |

**Response (200):**

```json
{
  "items": [
    { "name": "com", "path": "com", "type": "directory" },
    { "name": "maven-metadata.xml", "path": "maven-metadata.xml", "type": "file" }
  ],
  "marker": null,
  "hasMore": false
}
```

**curl example:**

```bash
curl "http://localhost:8086/api/v1/repositories/maven-local/tree?path=/com/example" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/repositories/:name/artifact

Get metadata for a specific artifact file in a repository.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read`

**Query Parameters:**

| Parameter | Type   | Required | Description                    |
|-----------|--------|----------|--------------------------------|
| `path`    | string | Yes      | Artifact path in the repository |

**Response (200):**

```json
{
  "path": "com/example/lib/1.0/lib-1.0.jar",
  "name": "lib-1.0.jar",
  "size": 15234,
  "modified": "2026-03-20T14:30:00Z",
  "checksums": {
    "md5": "d41d8cd98f00b204e9800998ecf8427e"
  }
}
```

**curl example:**

```bash
curl "http://localhost:8086/api/v1/repositories/maven-local/artifact?path=com/example/lib/1.0/lib-1.0.jar" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/repositories/:name/artifact/pull

Get technology-specific pull/install instructions for an artifact.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read`

**Query Parameters:**

| Parameter | Type   | Required | Description                    |
|-----------|--------|----------|--------------------------------|
| `path`    | string | Yes      | Artifact path in the repository |

**Response (200):**

```json
{
  "type": "maven-proxy",
  "instructions": [
    "mvn dependency:get -Dartifact=com.example:lib:1.0",
    "curl -O <pantera-url>/maven-central/com/example/lib/1.0/lib-1.0.jar"
  ]
}
```

The generated instructions are technology-aware: Maven produces `mvn` commands, npm produces `npm install`, Docker produces `docker pull <pantera-host>/<repo>/<image>:<tag>` (the tag named by a `_manifests/tags/<tag>` path, `@sha256:<digest>` for a `_manifests/revisions` path, a `<tag>` placeholder otherwise), PyPI produces `pip install`, Helm produces `helm` commands, NuGet produces `dotnet add package`, Go produces `go get`, and generic repositories produce `curl`/`wget` commands.

**curl example:**

```bash
curl "http://localhost:8086/api/v1/repositories/maven-local/artifact/pull?path=com/example/lib/1.0/lib-1.0.jar" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/repositories/:name/artifact/download

Download an artifact file. Streams the content directly from storage with `Content-Disposition: attachment`.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read`

**Query Parameters:**

| Parameter | Type   | Required | Description                    |
|-----------|--------|----------|--------------------------------|
| `path`    | string | Yes      | Artifact path in the repository |

**Response (200):** Binary file content with headers:
- `Content-Disposition: attachment; filename="<filename>"` (RFC 6266: quotes, backslashes, control and non-ASCII characters in the quoted name are replaced by `_`, and `filename*=UTF-8''<percent-encoded name>` carries the exact name whenever it differs)
- `Content-Type: application/octet-stream`
- `Content-Length: <size>` (when available)

**curl example:**

```bash
curl -OJ "http://localhost:8086/api/v1/repositories/maven-local/artifact/download?path=com/example/lib/1.0/lib-1.0.jar" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### POST /api/v1/repositories/:name/artifact/download-token

Generate a short-lived (60 seconds), **single-use** HMAC-signed download token bound to the repository, the path and the issuing user. This enables native browser downloads without requiring the JWT in the URL. The UI calls this first, then opens the `download-direct` URL in a new tab. The signing key comes from `PANTERA_DOWNLOAD_TOKEN_SECRET` or a persisted random key shared by all nodes (see the environment-variables reference); it is never derived from process metadata.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read`

**Query Parameters:**

| Parameter | Type   | Required | Description                    |
|-----------|--------|----------|--------------------------------|
| `path`    | string | Yes      | Artifact path in the repository |

**Response (200):**

```json
{
  "token": "bWF2ZW4tY2VudHJhbA..."
}
```

**curl example:**

```bash
curl -X POST "http://localhost:8086/api/v1/repositories/maven-local/artifact/download-token?path=com/example/lib/1.0/lib-1.0.jar" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/repositories/:name/artifact/download-direct

Download an artifact using an HMAC download token instead of JWT authentication. Tokens are valid for 60 seconds (future-dated timestamps are rejected), are scoped to a specific repository and path, are spent on first use, and the user who issued the token must still hold read permission on the repository at redemption — a token proves possession, not authorization.

**Authentication:** HMAC token in query parameter (no JWT required).

**Query Parameters:**

| Parameter | Type   | Required | Description                       |
|-----------|--------|----------|-----------------------------------|
| `token`   | string | Yes      | HMAC download token from `/download-token` |

**Response (200):** Binary file content with headers:
- `Content-Disposition: attachment; filename="<filename>"` (RFC 6266: quotes, backslashes, control and non-ASCII characters in the quoted name are replaced by `_`, and `filename*=UTF-8''<percent-encoded name>` carries the exact name whenever it differs)
- `Content-Type: application/octet-stream`
- `Content-Length: <size>` (when available)

**Response (401):** Token expired, not yet valid, already used, invalid signature, or malformed token.

**Response (403):** Token issued for a different repository, or the issuing user no longer holds read permission on this repository.

**curl example:**

```bash
curl -OJ "http://localhost:8086/api/v1/repositories/maven-local/artifact/download-direct?token=bWF2ZW4tY2VudHJhbA..."
```

---

### DELETE /api/v1/repositories/:name/artifacts

Delete a specific artifact from a repository. The search index, format metadata and audit trail are updated as described under [DELETE /api/v1/repositories/:name/packages](#delete-apiv1repositoriesnamepackages).

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:delete`

**Request Body:**

```json
{
  "path": "com/example/lib/1.0/lib-1.0.jar"
}
```

**Response (204):** No content on success.

**Response (404):** the path is neither stored nor indexed.

**curl example:**

```bash
curl -X DELETE http://localhost:8086/api/v1/repositories/maven-local/artifacts \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"path": "com/example/lib/1.0/lib-1.0.jar"}'
```

---

### DELETE /api/v1/repositories/:name/packages

Delete an entire package folder (directory and all contents) from a repository. Only the folder and the keys under it are removed; a sibling whose name merely starts the same (`lib-extra` next to `lib`) is untouched.

Both delete endpoints keep what is derived from storage consistent, and write an `artifact_delete` audit record:

- the search index rows indexed from the deleted path are removed (search and locate stop returning them), including rows of uploads to that path still waiting to be indexed when the delete ran;
- on a filesystem storage, directories left empty by the delete are removed (a symbolic link to a directory, e.g. a repository directory placed on another volume, is never removed);
- in a local `maven`/`gradle` repository, versions that no longer exist are removed from the artifact's `maven-metadata.xml` (`latest`/`release` move to the highest remaining version, checksums are rewritten, a metadata file left with no version is removed);
- in a local `php` repository, versions whose archive was deleted are removed from `p2/<vendor>/<package>.json`;
- in a local `pypi` repository, the cached simple indexes are dropped and regenerated from storage on the next request;
- in a local `conda` repository, packages whose file was deleted are removed from their subdir's `repodata.json`;
- in a local `gem` repository, `specs.4.8`, `latest_specs.4.8` (the highest remaining version of each gem), `prerelease_specs.4.8` and their `.gz` variants are rebuilt from the gems left, and the `quick/Marshal.4.8/<name>-<version>.gemspec.rz` of each deleted gem is removed;
- in a local `npm` repository, a version whose tarball (`<pkg>/-/<pkg>-<version>.tgz`, or the whole `<pkg>/-` folder) was deleted is unpublished: it leaves the packument and the dist-tags pointing at it are dropped (`latest` falls back to the highest remaining version);
- in a local `helm` repository, chart versions whose archive was deleted are removed from `index.yaml` (a chart left with no version is removed);
- in a local `nuget` repository, versions whose `.nupkg` or `.nuspec` was deleted are removed from `<id>/index.json` (removed when no version is left);
- in a local `go` repository, `<module>/@v/list` is rewritten to the versions whose `.zip` is still stored (removed when none is left);
- in a local `hexpm` repository, releases whose `tarballs/<name>-<version>.tar` was deleted are removed from `packages/<name>` (removed when no release is left).

`deb` and `rpm` indexes (`Packages.gz`/`Release`/`InRelease`, `repodata/`) are not updated by these endpoints. Remove Debian and RPM packages with an HTTP `DELETE` of the package path on the repository itself (`/<repo>/<path>`), which updates those indexes (for RPM in the default `update: on: upload` mode).

Index updates for conda, gem, helm, nuget and hexpm are serialized with uploads to the same package or index (on every node sharing the storage); the go list rewrite is serialized with uploads on the same node. A concurrent upload is neither lost nor lists a deleted package again.

The search index rows and format metadata are cleaned up even when storage no longer holds the path (for example rows left behind by an earlier failure). The delete answers `204` when it removed files or search index rows, and `404` (`NOT_FOUND`) when the path is neither stored nor indexed.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:delete`

**Request Body:**

```json
{
  "path": "com/example/lib/1.0"
}
```

**Response (204):** No content on success.

**Response (404):** the path is neither stored nor indexed.

**curl example:**

```bash
curl -X DELETE http://localhost:8086/api/v1/repositories/maven-local/packages \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"path": "com/example/lib/1.0"}'
```

---

### POST /api/v1/pypi/:repo/:package/:version/yank

Yank a release of a hosted PyPI package (PEP 592). Every distribution file of
the version is marked yanked and the package's simple index is regenerated, so
pip and uv see the change on their next resolve: an unpinned requirement skips
the release, an exact pin still installs it with a warning. `:package` accepts
any PEP 503 spelling (`QA_Pkg`, `qa-pkg`).

**Authentication:** JWT Bearer token required.
**Permission:** `adapter_basic_permissions` `write` on `:repo`.

**Request Body (optional):**

```json
{
  "reason": "broken build"
}
```

The reason is optional, capped at 512 characters, and shown to clients as the
yank reason. Without a reason the release is still yanked.

**Response (204):** No content on success.

**Errors:** `404 NOT_FOUND` when the repository does not exist or the version
has no distribution files; `403` without `write` on the repository.

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/pypi/pypi/requests/2.31.0/yank \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"reason": "broken build"}'
```

---

### POST /api/v1/pypi/:repo/:package/:version/unyank

Reverse a yank: every distribution file of the version is marked not yanked and
the package's simple index is regenerated.

**Authentication:** JWT Bearer token required.
**Permission:** `adapter_basic_permissions` `write` on `:repo`.

**Response (204):** No content on success.

**Errors:** `404 NOT_FOUND` when the repository does not exist or the version
has no distribution files; `403` without `write` on the repository.

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/pypi/pypi/requests/2.31.0/unyank \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

## 9. Search

### GET /api/v1/search

Full-text search across all indexed artifacts. Results are filtered by the caller's `read` permission on each repository — and since 2.2.9 that scope also governs `total`, `hasMore`, `type_counts` and `repo_counts` (they are computed over the authorised set, so a caller with no repository read grant receives empty aggregates rather than global counts or the names of restricted repositories). Supports plain full-text search and structured field filters.

**Authentication:** JWT Bearer token required.
**Permission:** `api_search_permissions:read`

**Query Parameters:**

| Parameter | Type    | Default | Description                                                   |
|-----------|---------|---------|---------------------------------------------------------------|
| `q`       | string  | --      | Search query (required). Supports plain text and field filters (see below). |
| `page`    | integer | 0       | Zero-based page number                                        |
| `size`    | integer | 20      | Items per page (max 100)                                      |

**Structured Query Syntax:**

The `q` parameter supports field-prefixed filters in addition to plain full-text search:

| Prefix | Match type | Example |
|--------|-----------|---------|
| `name:value` | Case-insensitive substring on artifact name | `name:spring-boot` |
| `version:value` | Case-insensitive substring on version | `version:3.2` |
| `repo:value` | Exact match on repository name | `repo:maven-central` |
| `type:value` | Prefix match on repository type (strips `-proxy`/`-group`) | `type:maven` |

Combine with `AND` / `OR` and parentheses:

```
name:pydantic AND version:2.12
name:pydantic AND (version:2.12 OR version:2.11)
repo:pypi-proxy AND type:pypi
```

Plain text without prefixes triggers full-text search as before:

```
spring boot
```

**Pagination limits:** The effective SQL offset (`page * size`) is capped at 10,000. Requests exceeding this limit are rejected with `400 Bad Request`. Use field filters to narrow results instead of paginating deeply.

**Response (200):**

```json
{
  "items": [
    {
      "repo_type": "maven-proxy",
      "repo_name": "maven-central",
      "artifact_path": "com/example/lib/1.0/lib-1.0.jar",
      "artifact_name": "lib",
      "version": "1.0",
      "size": 15234,
      "created_at": "2026-03-20T14:30:00Z",
      "owner": "admin",
      "path_prefix": "com/example/lib/1.0/lib-1.0.jar"
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1,
  "hasMore": false
}
```

`path_prefix` is present only when the indexing writer recorded the artifact's real storage key. `artifact_path` is always a display name (a package name, a synthetic identifier, or a real path depending on the format) and must not be assumed to be resolvable to a storage location; `path_prefix`, when present, always is. Currently populated by the npm/pypi/go/maven-or-gradle/composer proxy processors and by gem, hexpm, and conda uploads; absent for other writers (older rows, local/hosted uploads of other formats, docker, helm, debian, rpm, files, conan, and the bulk importer) — clients must fall back to `artifact_path`-based logic when it is absent.

**Response (400):**

```json
{
  "code": 400,
  "message": "Missing 'q' parameter"
}
```

**curl example:**

```bash
# Plain full-text search
curl "http://localhost:8086/api/v1/search?q=guava&page=0&size=10" \
  -H "Authorization: Bearer eyJhbGciOi..."

# Structured search: name filter + version OR
curl "http://localhost:8086/api/v1/search?q=name%3Aguava+AND+%28version%3A32.1+OR+version%3A33.0%29&page=0&size=10" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/search/locate

Locate which repositories contain an artifact at a given path.

**Authentication:** JWT Bearer token required.
**Permission:** `api_search_permissions:read`

**Query Parameters:**

| Parameter | Type   | Required | Description          |
|-----------|--------|----------|----------------------|
| `path`    | string | Yes      | Artifact path to locate |

**Response (200):**

```json
{
  "repositories": ["maven-central", "maven-local"],
  "count": 2
}
```

**curl example:**

```bash
curl "http://localhost:8086/api/v1/search/locate?path=com/google/guava/guava/32.1.3-jre/guava-32.1.3-jre.jar" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### POST /api/v1/search/reindex

Start a full rebuild of the artifact search index. The request returns at
once; the rebuild runs in the background on a dedicated thread. Follow its
progress with `GET /api/v1/search/reindex`.

A rebuild has two phases:

1. **Prune.** Deletes the index rows of every repository that no longer
   exists, in batches of 500. This phase is skipped when no repository is
   configured, so a failed repository listing cannot empty the index.
2. **Rebuild.** For each repository whose storage is on the local file
   system (`fs` or `vertx-file`), Pantera scans the storage with the same
   per-format scanners the `pantera-backfill` CLI uses. It upserts one row per
   artifact it finds, then deletes that repository's artifact rows for files
   that are no longer in storage. The following are left as they are:
   - rows an upload wrote after the rebuild of that repository started;
   - checksum, signature and metadata rows.

   An existing row keeps its owner and creation time. The rebuild only
   updates its size and storage key.

Repositories are **skipped** (their rows are left untouched, and the reason is
logged) when:

- they are group repositories;
- their storage is not on the local file system, such as S3;
- their type has no scanner (Conan, RPM, NuGet);
- their storage directory does not exist yet.

Only one rebuild runs at a time: a second request on the same node gets
`409`. In a cluster, a node that finds the rebuild lock held by another
node finishes at once, and its status reports that in `last_error`.

**Authentication:** JWT Bearer token required.
**Permission:** `api_search_permissions:write`

**Response (202):** The response contains the status fields of
`GET /api/v1/search/reindex`, plus:

```json
{
  "status": "started",
  "message": "Full reindex initiated",
  "state": "running",
  "started_at": "2026-09-24T10:00:00Z",
  "finished_at": null,
  "repos_total": 0,
  "repos_done": 0,
  "repos_skipped": 0,
  "repos_failed": 0,
  "rows_pruned": 0,
  "rows_upserted": 0,
  "rows_removed": 0,
  "last_error": null
}
```

**Response (409):** a rebuild is already running. The body has the same
status fields, with `"code": 409` and `"status": "running"`.

**Response (503):** no database is configured, so there is no index to rebuild.

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/search/reindex \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/search/reindex

Status of the current or last index rebuild on this node.

**Authentication:** JWT Bearer token required.
**Permission:** `api_search_permissions:write`

**Response (200):**

```json
{
  "state": "idle",
  "started_at": "2026-09-24T10:00:00Z",
  "finished_at": "2026-09-24T10:03:12Z",
  "repos_total": 42,
  "repos_done": 42,
  "repos_skipped": 6,
  "repos_failed": 0,
  "rows_pruned": 1830,
  "rows_upserted": 51200,
  "rows_removed": 12,
  "last_error": null
}
```

| Field | Description |
|-------|-------------|
| `state` | `running` or `idle` |
| `started_at` / `finished_at` | ISO-8601 start and end of the current or last run. `null` before the first run; `finished_at` is `null` while running |
| `repos_total` / `repos_done` | Repositories in the run, and how many are finished (rebuilt, skipped or failed) |
| `repos_skipped` / `repos_failed` | Repositories skipped (see above) and repositories whose rebuild failed |
| `rows_pruned` | Rows deleted because their repository no longer exists |
| `rows_upserted` | Rows inserted or changed from storage |
| `rows_removed` | Rows deleted because their artifact is no longer in storage |
| `last_error` | Last error of the run (a failed repository, or a run that could not start), else `null` |

**Response (503):** no database is configured.

**curl example:**

```bash
curl http://localhost:8086/api/v1/search/reindex \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/search/stats

Get artifact index statistics (total documents, index size, etc.).

**Authentication:** JWT Bearer token required.
**Permission:** `api_search_permissions:read`

**Response (200):**

```json
{
  "total_documents": 145230,
  "index_size_bytes": 52428800,
  "last_indexed": "2026-03-22T10:00:00Z"
}
```

The exact fields depend on the index implementation (PostgreSQL full-text or in-memory).

**curl example:**

```bash
curl http://localhost:8086/api/v1/search/stats \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

## 10. Cooldown Management

Cooldown prevents recently-published upstream artifacts from being cached in
proxy repositories for a configurable period, protecting against supply-chain
attacks involving newly uploaded malicious packages.

Cooldown is enforced at two layers:

1. **Metadata filtering** -- on every metadata response served from a proxy
   repository (port 8080), blocked versions are silently removed so client
   resolvers (npm, pip, go, docker, composer, mvn) never see them. This covers
   both direct installs and transitive-dependency resolution. See
   [Cooldown Metadata Filtering](cooldown-metadata-filtering.md) for the full
   adapter coverage matrix.
2. **Artifact fetch gate** -- a direct request for a specific blocked artifact
   returns a format-appropriate 403 (or 404 `MANIFEST_UNKNOWN` for Docker tags)
   with a `Retry-After` header. For `file-proxy`, which has no metadata
   filtering, this is the only enforcement layer and triggers based on the
   artifact's cached-at / remote-modified timestamp.

The management API endpoints below (served on port 8086) control policy and
state -- the actual filtering happens transparently on the repository port.

### GET /api/v1/cooldown/config

Get the current cooldown configuration, including global settings and per-repository-type overrides.

**Authentication:** JWT Bearer token required.
**Permission:** `api_cooldown_permissions:read`

**Response (200):**

```json
{
  "enabled": true,
  "minimum_allowed_age": "7d",
  "repo_types": {
    "maven-proxy": {
      "enabled": true,
      "minimum_allowed_age": "3d"
    },
    "npm-proxy": {
      "enabled": false,
      "minimum_allowed_age": "7d"
    }
  }
}
```

Duration values are formatted as `Nd` (days), `Nh` (hours), or `Nm` (minutes).

**curl example:**

```bash
curl http://localhost:8086/api/v1/cooldown/config \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### PUT /api/v1/cooldown/config

Update cooldown configuration with hot reload. Changes take effect immediately without restart. When cooldown is disabled for a repo type, all active blocks for that type are automatically released.

**Authentication:** JWT Bearer token required.
**Permission:** `api_cooldown_permissions:write`

**Request Body:**

```json
{
  "enabled": true,
  "minimum_allowed_age": "7d",
  "repo_types": {
    "maven-proxy": {
      "enabled": true,
      "minimum_allowed_age": "3d"
    },
    "npm-proxy": {
      "enabled": false,
      "minimum_allowed_age": "7d"
    }
  }
}
```

**Response (200):**

```json
{
  "status": "saved"
}
```

Additional optional fields (v2.2.1+):
- `history_retention_days` (int, `(0, 3650]`) -- days to retain archive rows before auto-purge. Default 90.
- `cleanup_batch_limit` (int, `(0, 100000]`) -- max rows moved from live to history per cleanup tick. Default 10000.

Out-of-range values return `400 BAD_REQUEST` with an explanatory message.

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/cooldown/config \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"enabled": true, "minimum_allowed_age": "5d"}'
```

---

### GET /api/v1/cooldown/overview

List all proxy repositories that have cooldown enabled, including the active block count for each.

**Authentication:** JWT Bearer token required.
**Permission:** `api_cooldown_permissions:read`

**Response (200):**

```json
{
  "repos": [
    {
      "name": "maven-central",
      "type": "maven-proxy",
      "cooldown": "7d",
      "active_blocks": 23
    },
    {
      "name": "npm-proxy",
      "type": "npm-proxy",
      "cooldown": "3d",
      "active_blocks": 5
    }
  ]
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/cooldown/overview \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/cooldown/blocked

Get a paginated list of currently blocked artifacts. Supports server-side search filtering.

**Authentication:** JWT Bearer token required.
**Permission:** `api_cooldown_permissions:read`

**Query Parameters:**

| Parameter   | Type    | Default | Description                                      |
|-------------|---------|---------|--------------------------------------------------|
| `page`      | integer | 0       | Zero-based page number                           |
| `size`      | integer | 50      | Items per page (max 100)                         |
| `search`    | string  | --      | Filter by artifact name, repo, or version        |
| `repo`      | string  | --      | Exact match on repository name                   |
| `repo_type` | string  | --      | Exact match on repository type (e.g. `npm-proxy`)|

**Response (200):**

```json
{
  "items": [
    {
      "package_name": "com.example:malicious-lib",
      "version": "1.0.0",
      "repo": "maven-central",
      "repo_type": "maven-proxy",
      "reason": "FRESH_RELEASE",
      "blocked_date": "2026-03-22T08:00:00Z",
      "blocked_until": "2026-03-29T08:00:00Z",
      "remaining_hours": 168
    }
  ],
  "page": 0,
  "size": 50,
  "total": 1,
  "hasMore": false
}
```

**curl example:**

```bash
curl "http://localhost:8086/api/v1/cooldown/blocked?page=0&size=50&search=guava" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/cooldown/history

Returns a paginated, permission-scoped list of archived cooldown entries
(expired or manually unblocked blocks retained for audit).

**Authentication:** JWT Bearer token required.
**Required permission:** `api_cooldown_history_permissions.read` (global)
plus `adapter_basic_permissions.read` on a repo to see its rows.

**Query params** (all optional):
- `page` (int, default 0)
- `size` (int, default 50)
- `search` (string, ILIKE match on artifact/version/repo)
- `repo` (string, exact match)
- `repo_type` (string, exact match, e.g. `npm-proxy`)
- `sort_by` (one of `package_name`, `version`, `repo`, `repo_type`,
  `reason`, `archived_at`, `archive_reason`; default `archived_at`)
- `sort_dir` (`asc`|`desc`, default `desc`)

**Response:**

    {
      "items": [
        {
          "package_name": "lodash",
          "version": "4.17.20",
          "repo": "npm-central",
          "repo_type": "npm-proxy",
          "reason": "FRESH_RELEASE",
          "blocked_date": "2026-03-15T09:00:00Z",
          "blocked_until": "2026-03-22T09:00:00Z",
          "archived_at": "2026-03-22T09:00:02Z",
          "archive_reason": "EXPIRED",
          "archived_by": "system"
        }
      ],
      "total": 1,
      "page": 0,
      "size": 50
    }

---

### POST /api/v1/repositories/:name/cooldown/unblock

Manually unblock a specific artifact version in a repository. The release
holds until the version's original `blocked_until`: the entry is archived to
cooldown history (`MANUAL_UNBLOCK`), removed from `GET /api/v1/cooldown/blocked`,
and the version is not re-blocked by later requests. Filtered metadata for the
package is invalidated before the response. Unblocking a version that has no
active block is a no-op that still returns `204`.

`artifact` is the name the block is listed under in `GET /api/v1/cooldown/blocked`
(`package_name`). For a `maven`/`gradle` repository that is the dotted
`groupId.artifactId`; the `groupId:artifactId` form used below is accepted and
normalised to it. See the per-format naming table in the
[cooldown admin guide](admin-guide/cooldown.md).

**Authentication:** JWT Bearer token required.
**Permission:** `api_cooldown_permissions:write`

**Request Body:**

```json
{
  "artifact": "com.example:lib",
  "version": "1.0.0"
}
```

**Response (204):** No content on success.

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/repositories/maven-central/cooldown/unblock \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"artifact": "com.example:lib", "version": "1.0.0"}'
```

---

### POST /api/v1/repositories/:name/cooldown/unblock-all

Unblock all currently blocked artifacts in a repository. Each release holds
until that version's original `blocked_until`, exactly as for a single unblock.

**Authentication:** JWT Bearer token required.
**Permission:** `api_cooldown_permissions:write`

**Response (204):** No content on success.

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/repositories/maven-central/cooldown/unblock-all \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/cooldown/inspect

Explain, per version of one package, what the cooldown state and every cache
layer say, and flag where they disagree. For every proxy and group repository
of the format (or only `repo` and everything a group `repo` reaches) the
version listing is fetched in-process through the repository's own slice with
the caller's credentials -- exactly what a client of that repository is served
right now -- next to the cooldown-filtered envelope (this node's L1 and the
shared L2), the negative-cache entries and the package's live and archived
cooldown records.

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin` (admin only)

**Query Parameters:**

| Parameter  | Type   | Required | Description |
|------------|--------|----------|-------------|
| `repoType` | string | yes      | Format family: `npm`, `pypi`, `maven`, `gradle`, `go`, `php`, `docker`, ... |
| `package`  | string | yes      | Package as a client names it (`lodash`, `@scope/pkg`, `requests`, `com.example:lib`, `vendor/pkg`, module path) |
| `repo`     | string | no       | Limit to one repository (404 when not configured) |

Version listings are read for npm (packument `versions`), pypi (simple-index
file links), maven/gradle (`maven-metadata.xml`), go (`@v/list`) and composer
(`p2`); other formats answer `metadata.unsupported: true`.

**Response (200):**

```json
{
  "package": "openai",
  "repoType": "npm",
  "node": "pantera-1",
  "repos": [
    {
      "name": "npm_proxy",
      "type": "npm-proxy",
      "mode": "proxy",
      "metadata": {"status": 200, "fetchedVia": "in-process", "path": "/openai", "visibleVersions": ["4.0.0"]},
      "envelope": {"l1": {"present": true, "ageMs": 5120}, "l2": {"present": true, "ttlRemainingMs": 43100000}},
      "negativeCache": [
        {"key": {"scope": "npm_proxy", "repoType": "npm-proxy", "artifactName": "openai", "artifactVersion": "4.1.0"}, "l1": false, "l2": true}
      ]
    },
    {"name": "npm_group", "type": "npm-group", "mode": "group", "members": ["npm_proxy"], "metadata": {"status": 200, "fetchedVia": "in-process", "path": "/openai", "visibleVersions": ["4.0.0"]}, "envelope": {"l1": {"present": false, "ageMs": null}, "l2": {"present": false, "ttlRemainingMs": null}}, "negativeCache": []}
  ],
  "versions": [
    {
      "version": "4.1.0",
      "cooldown": {"state": "released", "blockedUntil": "2026-09-25T10:00:00Z", "reason": "FRESH_RELEASE", "repo": "npm_proxy", "source": "live"},
      "visibleIn": [],
      "hiddenIn": ["npm_proxy", "npm_group"],
      "mismatch": true,
      "mismatchReason": "released but hidden in npm_proxy"
    }
  ]
}
```

`cooldown.state` is `blocked`, `released` (manually unblocked), `expired` or
`none`. A version is a `mismatch` when it is `released`/`expired` yet missing
from the listing of the repository it was blocked in, when a group hides it
while a member lists it, or when it is `blocked` yet still listed by the
repository holding the block. `metadata.status` is `0` with `metadata.error`
when the in-process request failed.

When nothing is known under the exact name -- no version and no repository
answering `200` for its metadata -- the response also carries `didYouMean`:
up to 5 suggestions in the shape of
[`GET /api/v1/cooldown/inspect/suggest`](#get-apiv1cooldowninspectsuggest),
found by searching for the name (then, if that finds nothing, for its parts
split at `- _ . / : @`). The typed name itself is never suggested.

```json
{
  "package": "jackson-databind",
  "repoType": "maven",
  "repos": [],
  "versions": [],
  "didYouMean": [
    {"package": "com.fasterxml.jackson.core:jackson-databind", "display": "com.fasterxml.jackson.core:jackson-databind", "repoType": "maven", "sources": ["index"], "repos": ["maven_proxy"]}
  ]
}
```

**curl example:**

```bash
curl "http://localhost:8086/api/v1/cooldown/inspect?repoType=npm&package=openai" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/cooldown/inspect/suggest

Type-ahead for the package inspector: package names that contain what the
operator typed, in the form `GET /api/v1/cooldown/inspect` accepts. Names come
from the artifacts index and from the live and archived cooldown records.

The text is split on whitespace into words; a package matches when every word
is a case-insensitive substring of its stored name or of its inspector form
(`jackson databind`, `http5`, `@types node`, `core:jackson`). A single word is
the same substring match (`ILIKE '%word%'`) the cooldown list `search` and
the package search use, so every package they return for that text is
suggested too. Results are ranked exact name, exact last segment (maven
artifactId, npm name after the scope), prefix, word at the start of a name
part, then any substring; ties alphabetically. One entry per package, merged
over repositories and sources.

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin` (admin only)

**Query Parameters:**

| Parameter  | Type    | Required | Description |
|------------|---------|----------|-------------|
| `q`        | string  | yes      | Text to search for (at most 200 characters) |
| `repoType` | string  | no       | Format family or repository type (`npm`, `npm-proxy`, `pypi`, ...); `maven` and `gradle` both cover every maven-layout repository. Omit to search every format |
| `limit`    | integer | no       | Maximum suggestions, default `20`, capped at `50` |

**Response (200):**

```json
{
  "suggestions": [
    {
      "package": "com.fasterxml.jackson.core:jackson-databind",
      "display": "com.fasterxml.jackson.core:jackson-databind",
      "repoType": "maven",
      "sources": ["index", "cooldown"],
      "repos": ["maven_group", "maven_proxy"]
    }
  ],
  "node": "pantera-1"
}
```

| Field      | Description |
|------------|-------------|
| `package`  | Name to pass to `inspect`: maven/gradle `groupId:artifactId` (derived from the index path), pypi PEP 503 normalised, docker the image name as stored (`library/ubuntu`), others as stored. A maven name known only in the dotted form with no index path stays dotted |
| `display`  | Human form; for an unresolved maven name it adds `(groupId:artifactId not in the index)` |
| `repoType` | Format family of the repositories it was found in |
| `sources`  | `index` (artifacts index) and/or `cooldown` (live or archived cooldown records) |
| `repos`    | Repositories it was found in, sorted |

Returns `400` for a missing or over-long `q` or a non-numeric or non-positive
`limit`. Without a database the list is empty.

**curl example:**

```bash
curl "http://localhost:8086/api/v1/cooldown/inspect/suggest?repoType=maven&q=jackson%20databind" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### POST /api/v1/cooldown/refresh-package

Clear every cache layer that can hide or stale-serve one package, on every
node, then inspect it again. In order: each proxy in scope revalidates its
cached raw upstream metadata through the adapter's own refresh path (npm:
conditional packument refresh; pypi: cached simple-index pages; maven: the
artifact's `maven-metadata.xml` cache entries -- other formats report
`unsupported`); the cooldown-filtered envelopes are dropped from this node's
L1, from L2 and from peers' L1; the package's negative-cache entries are
dropped in every scope and tier. Audit-logged as `COOLDOWN_REFRESH_PACKAGE`.

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin` (admin only)

**Request Body:**

```json
{"repoType": "npm", "package": "openai", "repo": "npm_group"}
```

`repo` is optional; the scope is the same as for `GET /api/v1/cooldown/inspect`.

**Response (200):**

```json
{
  "package": "openai",
  "repoType": "npm",
  "node": "pantera-1",
  "revalidated": [{"repo": "npm_proxy", "outcome": "revalidated"}],
  "cleared": {"envelopes": {"l1": 2, "l2": 2}, "negativeCache": {"l1": 0, "l2": 1}},
  "before": { "...": "inspect document" },
  "after": { "...": "inspect document" }
}
```

Revalidation outcomes: `revalidated`, `refreshed`, `not_modified`,
`not_cached`, `invalidated`, `upstream_gone`, `not_found`,
`upstream_status_<code>`, `unsupported`, `failed: <reason>` (several are
joined with `,` when a repository has more than one remote or index variant).

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/cooldown/refresh-package \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"repoType": "npm", "package": "openai"}'
```

---

## 11. Settings

### GET /api/v1/settings

Get the full Pantera configuration, including port, JWT settings, HTTP client/server settings, metrics, cooldown, auth providers (with secrets masked), database status, and cache status.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:read`

**Response (200):**

```json
{
  "port": 8080,
  "version": "2.0.0",
  "prefixes": ["test_prefix"],
  "jwt": {
    "algorithm": "RS256",
    "access_token_expiry_seconds": 3600,
    "refresh_token_expiry_seconds": 604800
  },
  "http_client": {
    "proxy_timeout": 30000,
    "connection_timeout": 15000,
    "idle_timeout": 60000,
    "follow_redirects": true,
    "connection_acquire_timeout": 30000,
    "max_connections_per_destination": 64,
    "max_requests_queued_per_destination": 128
  },
  "http_server": {
    "request_timeout": "PT60S"
  },
  "metrics": {
    "enabled": true,
    "jvm": true,
    "http": true,
    "storage": true,
    "endpoint": "/metrics/vertx",
    "port": 8087
  },
  "cooldown": {
    "enabled": true,
    "minimum_allowed_age": "7d"
  },
  "credentials": [
    {
      "id": 1,
      "type": "jwt-password",
      "priority": 1,
      "enabled": true,
      "config": {
        "client-secret": "ab***yz"
      }
    }
  ],
  "database": {
    "configured": true
  },
  "caches": {
    "valkey_configured": false
  }
}
```

Secret values in auth provider configs are automatically masked (e.g., `"ab***yz"`).

**curl example:**

```bash
curl http://localhost:8086/api/v1/settings \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/settings/ui

Get the UI-facing settings. Readable by any authenticated user: it holds only the values the UI needs to render links and client setup instructions (Set Me Up).

**Authentication:** JWT Bearer token required.
**Permission:** none beyond authentication.

**Response (200):**

```json
{
  "ui": {
    "prefixes": ["test_prefix"],
    "grafana_url": "https://grafana.example.com",
    "registry_url": "https://registry.example.com"
  }
}
```

| Field | Description |
|-------|-------------|
| `ui.prefixes` | Global path prefixes (`meta.global_prefixes`), always present; `[]` when none are configured. Set Me Up appends the first one to the registry URL. |
| `ui.grafana_url` | Grafana link shown on the Dashboard. Present only when set. |
| `ui.registry_url` | Client-facing registry address used in Set Me Up snippets; overrides the UI container's `REGISTRY_URL`. Present only when set. |

`grafana_url` and `registry_url` are saved with `PUT /api/v1/settings/ui` (see [PUT /api/v1/settings/:section](#put-apiv1settingssection)); prefixes are managed with `PUT /api/v1/settings/prefixes`.

**curl example:**

```bash
curl http://localhost:8086/api/v1/settings/ui \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### PUT /api/v1/settings/prefixes

Update the global URL prefixes list. Changes are persisted to both the YAML config file and the database (when available).

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:update`

**Request Body:**

```json
{
  "prefixes": ["test_prefix", "v2"]
}
```

**Response (200):** Empty body on success.

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/settings/prefixes \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"prefixes": ["test_prefix", "v2"]}'
```

---

### PUT /api/v1/settings/:section

Update a specific settings section by name. The section is persisted to the database. Requires a configured database.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:update`

**Path Parameters:**

| Parameter | Description                                              |
|-----------|----------------------------------------------------------|
| `section` | Settings section name (e.g., `http_client`, `jwt`)      |

**Request Body:** JSON object with the section-specific fields.

**Response (200):**

```json
{
  "status": "saved"
}
```

**Response (503):**

```json
{
  "error": "UNAVAILABLE",
  "message": "Database not configured; settings updates require database",
  "status": 503
}
```

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/settings/http_client \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"proxy_timeout": 60000, "connection_timeout": 30000}'
```

---

## 12. Auth Provider Management

### PUT /api/v1/auth-providers/:id/toggle

Enable or disable an authentication provider.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:update`

**Path Parameters:**

| Parameter | Description                                   |
|-----------|-----------------------------------------------|
| `id`      | Numeric auth provider ID (from settings)      |

**Request Body:**

```json
{
  "enabled": true
}
```

**Response (200):**

```json
{
  "status": "saved"
}
```

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/auth-providers/2/toggle \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'
```

---

### PUT /api/v1/auth-providers/:id/config

Update the configuration of an authentication provider.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:update`

**Path Parameters:**

| Parameter | Description                                   |
|-----------|-----------------------------------------------|
| `id`      | Numeric auth provider ID (from settings)      |

**Request Body:** JSON object with provider-specific configuration fields (e.g., `issuer`, `client-id`, `client-secret` for Okta).

**Response (200):**

```json
{
  "status": "saved"
}
```

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/auth-providers/2/config \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"issuer": "https://your-org.okta.com", "client-id": "abc123"}'
```

---

## 13. Admin: Auth Settings

Admin endpoints for managing JWT token policy and performing user revocation. All endpoints require admin-level permissions.

### GET /api/v1/admin/auth-settings

Retrieve the current token policy settings.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:read`

**Response (200):**

```json
{
  "access_token_expiry_seconds": 3600,
  "refresh_token_expiry_seconds": 604800,
  "api_token_max_expiry_days": 90,
  "allow_permanent_tokens": false
}
```

| Field | Type | Description |
|-------|------|-------------|
| `access_token_expiry_seconds` | integer | Access token TTL in seconds |
| `refresh_token_expiry_seconds` | integer | Refresh token TTL in seconds |
| `api_token_max_expiry_days` | integer | Maximum allowed `expiry_days` for user-generated API tokens |
| `allow_permanent_tokens` | boolean | Whether users may request non-expiring API tokens (`expiry_days: 0`) |

**curl example:**

```bash
curl http://localhost:8086/api/v1/admin/auth-settings \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

### PUT /api/v1/admin/auth-settings

Update token policy settings. Changes take effect immediately for all new tokens. Existing tokens are not invalidated.

**Authentication:** JWT Bearer token required.
**Permission:** `api_role_permissions:update`

**Request Body:**

```json
{
  "access_token_expiry_seconds": 1800,
  "refresh_token_expiry_seconds": 86400,
  "api_token_max_expiry_days": 30,
  "allow_permanent_tokens": false
}
```

All fields are optional; omitted fields retain their current values.

**Response (200):**

```json
{
  "status": "saved"
}
```

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/admin/auth-settings \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"access_token_expiry_seconds": 1800, "allow_permanent_tokens": false}'
```

---

### GET /api/v1/admin/client-base-url-settings

Retrieve the current client-facing base URL derivation settings — governs how `ClientBaseUrl` (pantera-core) builds absolute URLs Pantera emits (e.g. npm `dist.tarball`) for a repository with no explicit `url:` configured.

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin`

**Response (200):**

```json
{
  "trust_forwarded_headers": "false",
  "client_base_host_allowlist": "",
  "client_base_url": ""
}
```

| Field | Type | Description |
|-------|------|-------------|
| `client_base_url` | string (absolute URL) | Canonical origin (+ optional path prefix), e.g. `https://reg.example.com/artifactory`. Empty string is unset (the default). When non-empty it is ENFORCED for every repository without an explicit `url:` — `trust_forwarded_headers` and `client_base_host_allowlist` below stop being consulted for those repositories. Takes precedence over both fields below; a repository's own `url:` still wins over this setting. |
| `trust_forwarded_headers` | string (`"true"`/`"false"`) | Whether `X-Forwarded-Proto`/`-Host`/`-Prefix` are honoured. Default `"false"`. Ignored while `client_base_url` is set. |
| `client_base_host_allowlist` | string (comma-separated) | `Host` header values permitted for base-URL derivation. Empty string is PERMISSIVE — any `Host` is honoured (the default). Ignored while `client_base_url` is set. |

**curl example:**

```bash
curl http://localhost:8086/api/v1/admin/client-base-url-settings \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

### PUT /api/v1/admin/client-base-url-settings

Update the client-facing base URL derivation settings. All three fields are optional — partial updates are accepted; omitted fields retain their current values. Values are validated (round-tripped through the settings record) before anything is written; an invalid `trust_forwarded_headers` (anything other than `"true"`/`"false"`) or a `client_base_url` that doesn't parse as an absolute `http`/`https` URL is rejected with `400` and nothing is persisted. Takes effect on the very next request — every `ClientBaseUrl` reads the current settings on construction, no restart required; in a cluster, the change is broadcast to every node.

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin`

**Request Body:**

```json
{
  "client_base_url": "https://reg.example.com/artifactory",
  "trust_forwarded_headers": "false",
  "client_base_host_allowlist": "registry.example.com,registry.example.com:8443"
}
```

**Response (204):** No content.

**Response (400):**

```json
{
  "error": "BAD_REQUEST",
  "message": "trust_forwarded_headers must be \"true\" or \"false\"",
  "status": 400
}
```

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/admin/client-base-url-settings \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"client_base_host_allowlist": "registry.example.com"}'
```

**curl example (canonical override):**

```bash
curl -X PUT http://localhost:8086/api/v1/admin/client-base-url-settings \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"client_base_url": "https://reg.example.com/artifactory"}'
```

---

### GET /api/v1/admin/request-limits-settings

Retrieve the request &amp; storage limits (2.2.9): the hard cap on a single request body and the directories a local-filesystem (`fs`, `vertx-file`) repository or alias storage path may live under. Environment fallbacks `PANTERA_MAX_REQUEST_BODY_BYTES` / `PANTERA_FS_STORAGE_ROOTS` apply only while no row has been saved.

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin`

**Response (200):** every key is always present; all values are strings.

```json
{
  "max_request_body_bytes": "10737418240",
  "fs_storage_roots": "/var/pantera/data"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `max_request_body_bytes` | string (integer bytes, ≥ 1048576) | Hard cap on a single request body; a declared size above it answers `413` before any byte is read, chunked bodies are metered as they stream. Default `10737418240` (10 GiB). |
| `fs_storage_roots` | string (path-separator delimited absolute directories) | Approved roots for local-filesystem (`fs`, `vertx-file`) storage paths submitted through `PUT /api/v1/repositories/<name>`, the storage-alias `PUT` endpoints, or the UI; symlinks are followed before the containment check. Default `/var/pantera/data`. |

**curl example:**

```bash
curl http://localhost:8086/api/v1/admin/request-limits-settings \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

### PUT /api/v1/admin/request-limits-settings

Partial updates are accepted; omitted keys keep their current values. The merged result is validated (round-tripped through the setting's constructor) before anything is written: an unknown key, a `null` value, or a cap below 1 MiB, a non-integer, an empty root list or a relative root, is rejected with `400` and nothing is persisted. Takes effect on the very next request on every node: the HTTP server reads the cap per request and the repository API reads the roots per write. Every successful update is audit-logged (`event.category=configuration`).

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin`

**Request Body:** any subset of the fields above, values as strings.

**Response (204):** No content.

**Response (400):**

```json
{
  "error": "BAD_REQUEST",
  "message": "Invalid request-limits setting: ..."
}
```

---

### GET /api/v1/admin/egress-settings

Retrieve the outbound egress policy (2.2.9) applied to every connection Pantera makes on its own behalf (proxy remotes, index links, bearer-token realms, storage-alias endpoints) and the hosts trusted to receive upstream credentials. Link-local and cloud-metadata destinations are refused regardless of these settings. Environment fallbacks `PANTERA_EGRESS_BLOCK_PRIVATE` / `PANTERA_EGRESS_ALLOW_HOSTS` / `PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS` apply only while no row has been saved.

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin`

**Response (200):** every key is always present; all values are strings.

```json
{
  "egress_block_private": "false",
  "egress_allow_hosts": "",
  "upstream_credential_allow_hosts": ""
}
```

| Field | Type | Description |
|-------|------|-------------|
| `egress_block_private` | string (`"true"`/`"false"`) | Also refuse loopback and private ranges (RFC1918, `fc00::/7` unique-local, `100.64.0.0/10` shared address space). Default `"false"`. |
| `egress_allow_hosts` | string (comma-separated host names) | Hosts exempt from the strict-mode loopback/private-range refusal; never from the metadata, link-local, any-local or multicast refusal. Default empty. |
| `upstream_credential_allow_hosts` | string (comma-separated host names) | Additional hosts a bearer-token realm may live on before an upstream's credentials are released to it; by default only the upstream host or a host under its parent domain. Default empty. |

**curl example:**

```bash
curl http://localhost:8086/api/v1/admin/egress-settings \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

### PUT /api/v1/admin/egress-settings

Partial updates are accepted; omitted keys keep their current values. The merged result is validated (round-tripped through the setting's constructor) before anything is written: an unknown key, a `null` value, or a boolean other than `"true"`/`"false"` or an entry that is not a host name, is rejected with `400` and nothing is persisted. Takes effect on the next outbound connection and credential decision on every node; repository and storage-alias writes are validated against the same policy. Every successful update is audit-logged (`event.category=configuration`).

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin`

**Request Body:** any subset of the fields above, values as strings.

**Response (204):** No content.

**Response (400):**

```json
{
  "error": "BAD_REQUEST",
  "message": "Invalid egress setting: ..."
}
```

---

### GET /api/v1/admin/login-throttle-settings

Retrieve the password-login throttle (2.2.9): failures per (user, client IP) tolerated before further attempts are refused, and the window they count in. Environment fallbacks `PANTERA_LOGIN_THROTTLE_MAX_FAILURES` / `PANTERA_LOGIN_THROTTLE_WINDOW_SECONDS` apply only while no row has been saved.

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin`

**Response (200):** every key is always present; all values are strings.

```json
{
  "login_throttle_max_failures": "5",
  "login_throttle_window_seconds": "900"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `login_throttle_max_failures` | string (integer ≥ 1) | Sign-in attempts per (user, client address) before lockout; a user is also locked after 4× this many attempts from any address. Default `"5"`. |
| `login_throttle_window_seconds` | string (integer ≥ 1) | Window in seconds; a successful login clears the (user, client address) counter. Counters are per node, shared by all API workers on it. A refused login answers `429` with `Retry-After` set to the rest of the window. Default `"900"`. |

**curl example:**

```bash
curl http://localhost:8086/api/v1/admin/login-throttle-settings \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

### PUT /api/v1/admin/login-throttle-settings

Partial updates are accepted; omitted keys keep their current values. The merged result is validated (round-tripped through the setting's constructor) before anything is written: an unknown key, a `null` value, or a non-integer or a value below 1, is rejected with `400` and nothing is persisted. Takes effect on the next login attempt on every node. Every successful update is audit-logged (`event.category=configuration`).

**Authentication:** JWT Bearer token required.
**Permission:** `api_admin_permissions:admin`

**Request Body:** any subset of the fields above, values as strings.

**Response (204):** No content.

**Response (400):**

```json
{
  "error": "BAD_REQUEST",
  "message": "Invalid login-throttle setting: ..."
}
```

---

### POST /api/v1/admin/revoke-user/:username

Immediately revoke all tokens (access, refresh, and API) for the specified user. The revocation is propagated to all cluster nodes via Valkey pub/sub (sub-second propagation when Valkey is available; DB polling fallback otherwise).

> Note: Access tokens (which are not DB-stored) issued up to the moment of revocation are rejected until they expire naturally. Tokens issued afterwards are unaffected, so the user can sign in again immediately — to keep a user out, disable the account (`POST /api/v1/users/:name/disable`). The same revocation runs when a user's password is changed or reset.

**Authentication:** JWT Bearer token required.
**Permission:** `api_user_permissions:update`

**Path Parameters:**

| Parameter  | Description              |
|------------|--------------------------|
| `username` | Username to revoke       |

**Response (200):**

```json
{
  "status": "revoked",
  "username": "jdoe",
  "tokens_revoked": 3
}
```

**Response (404):**

```json
{
  "error": "NOT_FOUND",
  "message": "User 'jdoe' not found",
  "status": 404
}
```

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/admin/revoke-user/jdoe \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

## 14. Dashboard

Dashboard endpoints provide aggregated statistics for the Pantera UI. Responses are served from a 30-second in-memory cache.

### GET /api/v1/dashboard/stats

Get aggregated system statistics: repository count, artifact count, total storage usage, blocked artifact count, and top repositories by artifact count.

**Authentication:** JWT Bearer token required.

**Response (200):**

```json
{
  "repo_count": 15,
  "artifact_count": 145230,
  "total_storage": 52428800000,
  "blocked_count": 23,
  "top_repos": [
    {
      "name": "maven-central",
      "type": "maven-proxy",
      "artifact_count": 89000,
      "size": 32000000000
    }
  ]
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/dashboard/stats \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/dashboard/repos-by-type

Get repository count grouped by repository type.

**Authentication:** JWT Bearer token required.

**Response (200):**

```json
{
  "types": {
    "maven-proxy": 3,
    "npm-proxy": 2,
    "docker-proxy": 4,
    "maven": 2,
    "helm": 1
  }
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/dashboard/repos-by-type \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### GET /api/v1/dashboard/requests

Get request rate time series data. Currently returns a placeholder response.

**Authentication:** JWT Bearer token required.

**Query Parameters:**

| Parameter | Type   | Default | Description                  |
|-----------|--------|---------|------------------------------|
| `period`  | string | `24h`   | Time period (e.g., `24h`, `7d`) |

**Response (200):**

```json
{
  "period": "24h",
  "data": []
}
```

**curl example:**

```bash
curl "http://localhost:8086/api/v1/dashboard/requests?period=7d" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

## 15. Health and System

These endpoints are served on the **repository port** (default 8080), not the management API port.

### GET /.health

Lightweight health check for NLB/load-balancer probes. No authentication required. Returns 200 immediately with no I/O.

**Port:** 8080
**Authentication:** None required.

**Response (200):**

```json
{
  "status": "ok"
}
```

**curl example:**

```bash
curl http://localhost:8080/.health
```

---

### GET /.version

Get the Pantera application version.

**Port:** 8080
**Authentication:** None required.
**Method:** GET only.

**Response (200):**

```json
[
  { "version": "2.0.0" }
]
```

**curl example:**

```bash
curl http://localhost:8080/.version
```

---

### GET /api/v1/health

Management API health check endpoint (on the API port).

**Port:** 8086
**Authentication:** None required.

**Response (200):**

```json
{
  "status": "ok"
}
```

**curl example:**

```bash
curl http://localhost:8086/api/v1/health
```

---

### GET /metrics/vertx

Prometheus metrics endpoint. Exposes JVM, HTTP, and storage metrics when enabled in the Pantera configuration.

**Port:** 8087 (configurable)
**Authentication:** None required.

**Response (200):** Prometheus text format.

**curl example:**

```bash
curl http://localhost:8087/metrics/vertx
```

---

## 16. Import

The import endpoint is served on the **repository port** (default 8080). It provides a bulk import mechanism for migrating artifacts from external registries into Pantera.

### PUT /.import/:repository/:path

Import an artifact into a repository. Supports idempotent uploads with checksum verification. Imports may target only local/hosted repositories, and published files are immutable (re-importing different bytes for an existing file is refused; identical bytes replay as `200 ALREADY_PRESENT`).

**Port:** 8080
**Method:** PUT or POST
**Authentication:** Repository-level authentication (Basic or Bearer).

**Path Parameters:**

| Parameter    | Description                                   |
|--------------|-----------------------------------------------|
| `repository` | Target repository name                        |
| `path`       | Artifact storage path (e.g., `com/example/lib/1.0/lib-1.0.jar`) |

**Required Headers:**

| Header                        | Description                                     |
|-------------------------------|-------------------------------------------------|
| `X-Pantera-Repo-Type`        | Repository type (e.g., `maven`, `npm`, `pypi`)  |
| `X-Pantera-Idempotency-Key`  | Unique key for idempotent uploads               |

**Optional Headers:**

| Header                        | Description                                      |
|-------------------------------|--------------------------------------------------|
| `X-Pantera-Artifact-Name`    | Logical artifact name. When absent, the name a native publish of the format records is derived from the artifact path (for example `groupId.artifactId` for Maven) |
| `X-Pantera-Artifact-Version` | Artifact version. When absent, derived from the artifact path like the name; `UNKNOWN` when the path carries none |
| `X-Pantera-Artifact-Size`    | Size in bytes (falls back to `Content-Length`)    |
| `X-Pantera-Artifact-Owner`   | Ignored; the owner and audit `user.name` are the authenticated caller |
| `X-Pantera-Artifact-Created` | Created timestamp (milliseconds since epoch)     |
| `X-Pantera-Artifact-Release` | Release timestamp (milliseconds since epoch)     |
| `X-Pantera-Checksum-Sha1`   | Expected SHA-1 checksum                          |
| `X-Pantera-Checksum-Sha256` | Expected SHA-256 checksum                        |
| `X-Pantera-Checksum-Md5`    | Expected MD5 checksum                            |
| `X-Pantera-Checksum-Mode`   | Checksum policy (`COMPUTE` default, `METADATA`, `SKIP`) |
| `X-Pantera-Metadata-Only`   | If `true`, only index metadata without storing bytes |

**Request Body:** Raw artifact binary content.

**Response (201) -- Created:**

```json
{
  "status": "CREATED",
  "message": "Artifact imported successfully",
  "size": 15234,
  "digests": {
    "sha1": "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
    "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    "md5": "d41d8cd98f00b204e9800998ecf8427e"
  }
}
```

**Response (200) -- Already Present (idempotent replay):**

```json
{
  "status": "ALREADY_PRESENT",
  "message": "Artifact already exists with matching checksum",
  "size": 15234,
  "digests": { ... }
}
```

**Response (409) -- Checksum Mismatch:**

```json
{
  "status": "CHECKSUM_MISMATCH",
  "message": "SHA-256 mismatch: expected abc123... got def456...",
  "size": 15234,
  "digests": { ... }
}
```

**Response (409) -- Already Published (immutable):**

Re-importing *different* bytes for a file that is already stored is refused; identical bytes replay as `200 ALREADY_PRESENT`. Maven `-SNAPSHOT` versions and Composer dev branches stay writable.

```json
{
  "status": "CONFLICT",
  "message": "Import refused: artifact already exists with different content"
}
```

**Response (400) -- Not a Local Repository:**

The target is a proxy or group repository; imports may only target local/hosted repositories.

**Response (400) -- Invalid Metadata:**

```json
{
  "status": "INVALID_METADATA",
  "message": "Missing required header X-Pantera-Repo-Type",
  "size": 0,
  "digests": {}
}
```

**Response (503) -- Retry Later:**

```json
{
  "status": "RETRY_LATER",
  "message": "Import queue is full, retry after 5 seconds",
  "size": 0,
  "digests": {}
}
```

**curl example:**

```bash
curl -X PUT http://localhost:8080/.import/maven-local/com/example/lib/1.0/lib-1.0.jar \
  -H "X-Pantera-Repo-Type: maven" \
  -H "X-Pantera-Idempotency-Key: import-lib-1.0-$(date +%s)" \
  -H "X-Pantera-Artifact-Name: lib" \
  -H "X-Pantera-Artifact-Version: 1.0" \
  -H "X-Pantera-Checksum-Sha256: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08" \
  --data-binary @lib-1.0.jar
```

---

## 17. Error Format

All API errors follow a consistent JSON format:

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable error description",
  "status": 400
}
```

| Field     | Type    | Description                           |
|-----------|---------|---------------------------------------|
| `error`   | string  | Machine-readable error code           |
| `message` | string  | Human-readable description            |
| `status`  | integer | HTTP status code                      |

**Common Error Codes:**

| Code              | HTTP Status | Description                          |
|-------------------|-------------|--------------------------------------|
| `BAD_REQUEST`     | 400         | Invalid request body or parameters   |
| `UNAUTHORIZED`    | 401         | Missing or invalid credentials       |
| `FORBIDDEN`       | 403         | Insufficient permissions             |
| `NOT_FOUND`       | 404         | Resource not found                   |
| `CONFLICT`        | 409         | Resource conflict (e.g., dependency) |
| `INTERNAL_ERROR`  | 500         | Server-side error                    |
| `NOT_IMPLEMENTED` | 501         | Feature not available                |
| `UNAVAILABLE`     | 503         | Dependency unavailable (e.g., no DB) |

---

## 18. Pagination

All list endpoints use a consistent pagination format:

```json
{
  "items": [ ... ],
  "page": 0,
  "size": 20,
  "total": 150,
  "hasMore": true
}
```

| Field     | Type    | Description                                    |
|-----------|---------|------------------------------------------------|
| `items`   | array   | Array of result objects for the current page    |
| `page`    | integer | Current zero-based page number                  |
| `size`    | integer | Requested page size                             |
| `total`   | integer | Total number of items across all pages          |
| `hasMore` | boolean | Whether more pages exist after the current one  |

Default page size is 20. Maximum page size is 100. Requesting a page beyond the total returns an empty `items` array with `hasMore: false`.

---

## 19. Admin: Cache Tools

Diagnostics and invalidation for the negative (404) cache, plus the request
troubleshooter. All endpoints are admin only (`api_admin_permissions:admin`), and
every response names the answering `node`. With Valkey configured the
negative cache is cluster-wide: listings, probes and invalidations read and
write the shared L2 tier (cursor `SCAN` of `negative:*`, bounded at 100,000
keys) merged with the answering node's L1, and peers drop their L1 entries
over pub/sub. Without Valkey everything is the answering node's L1
(`"source": "L1-only"`). Mutations are audit-logged (`CACHE_CLEAR`).

A negative-cache key is `{scope, repoType, artifactName, artifactVersion}`:
the repository that cached the 404, its type, and the artifact as that
producer names it -- group repositories use the dotted/normalised
artifact name and a `<version>/<file>` version, proxies the URL form
(`com/example/lib`) and the bare version. An empty `artifactVersion` is a
metadata (version-less) key.

### GET /api/v1/admin/neg-cache

Paginated entries.

**Query Parameters:** `q` (case-insensitive substring over artifact name,
version and scope; `com.example:lib` also finds `com/example/lib`), `scope`
and `repoType` (exact), `page` (default 0), `pageSize` (default 20, max 100).

**Response (200):**

```json
{
  "items": [
    {
      "key": {"scope": "npm_group", "repoType": "npm-group", "artifactName": "lodash", "artifactVersion": "4.17.21/lodash-4.17.21.tgz"},
      "tiers": ["L1", "L2"],
      "tier": "L1",
      "ttlRemainingMs": 86012345
    }
  ],
  "page": 0, "size": 20, "pageSize": 20, "total": 1, "hasMore": false,
  "source": "L2+L1", "truncated": false, "node": "pantera-1"
}
```

`ttlRemainingMs` is the L2 TTL, `null` for an entry only in this node's L1.
`truncated` is `true` when the L2 scan hit its bound.

### GET /api/v1/admin/neg-cache/probe

Presence of every negative-cache key a request could be shadowed by. Forms:

| Parameters | Keys probed |
|------------|-------------|
| `url` = full client URL (host, optional global prefix and `api/` segment) or `/<repo>/<path>` | every key the serving path derives for it: the group resolver's key for each group on the walk and each leaf repository's proxy key |
| `key` = flat key `scope:repoType:artifactName:version` (`:` inside a field URL-encoded as `%3A`) | that key |
| `scope`, `repoType`, `artifactName`, optional `version` | that key |

An unknown repository in `url` answers 404; none of the forms answers 400.

**Response (200):**

```json
{
  "keys": [
    {"key": {"scope": "npm_group", "repoType": "npm-group", "artifactName": "lodash", "artifactVersion": "4.17.21/lodash-4.17.21.tgz"}, "flat": "npm_group:npm-group:lodash:4.17.21/lodash-4.17.21.tgz", "producer": "group", "l1": false, "l2": true, "ttlRemainingMs": 86000000},
    {"key": {"scope": "npm_proxy", "repoType": "npm-proxy", "artifactName": "lodash", "artifactVersion": "4.17.21"}, "flat": "npm_proxy:npm-proxy:lodash:4.17.21", "producer": "proxy", "l1": false, "l2": false, "ttlRemainingMs": null}
  ],
  "shadowed": true,
  "present": true,
  "repo": "npm_group", "repoType": "npm-group", "path": "/lodash/-/lodash-4.17.21.tgz",
  "node": "pantera-1"
}
```

The single-key forms also return `tiers` (`["L1","L2"]` subset).

### POST /api/v1/admin/neg-cache/invalidate

Invalidate one key on every node. Counts are honest: `l1` is what this node
removed, `l2` the Valkey `DEL` reply.

**Request Body:** `{"scope": "npm_group", "repoType": "npm-group", "artifactName": "lodash", "version": "4.17.21/lodash-4.17.21.tgz"}` -- `version` `""` (or omitted) is the metadata key; `scope`, `repoType`, `artifactName` are required.

**Response (200):**

```json
{"l1": 0, "l2": 1, "node": "pantera-1", "invalidated": {"l1": 0, "l2": 1}}
```

### POST /api/v1/admin/neg-cache/invalidate-package

Invalidate every entry of a package -- under any producer's spelling, in
every scope, in both tiers, on every node.

**Request Body:** `{"artifactName": "com.example:lib", "repoType": "maven"}` -- `repoType` (a family or a repository type) is optional and restricts the match to that format.

**Response (200):** same shape as `invalidate`.

### POST /api/v1/admin/neg-cache/invalidate-pattern

Invalidate by pattern across L2 and every node. Each of `scope`, `repoType`,
`artifactName`, `version` is optional: absent matches everything, a value
with `*` is a glob, anything else is exact. Rate-limited to 10 requests per
minute per admin (429 beyond).

**Request Body:** `{"scope": "npm_group", "artifactName": "@types/*"}`

**Response (200):** same shape as `invalidate`.

### GET /api/v1/admin/neg-cache/stats

**Response (200):**

```json
{
  "enabled": true, "l1Size": 1204, "l2Size": 5310, "l2SizeTruncated": false,
  "hitCount": 91231, "missCount": 1201, "hitRate": 0.987,
  "evictionCount": 0, "requestCount": 92432, "source": "L2+L1", "node": "pantera-1"
}
```

`l1Size` and the counters are the answering node's; `l2Size` counts the
shared L2 (`null` without Valkey). `hitRate` is a 0-1 fraction.

### GET /api/v1/admin/troubleshoot

Explain why a repository request fails or serves stale content. The request
is replayed in-process through the addressed repository's slice with the
caller's credentials (it answers what an authorized admin client would get),
then each layer is checked.

**Query Parameters:** `url` (required) -- a full client URL (host, optional
global prefix and `api/` segment) or a repository-relative path such as
`/npm_group/lodash`, `/pypi_group/simple/requests/`,
`/go_proxy/github.com/foo/bar/@v/list`,
`/maven_group/com/example/lib/maven-metadata.xml` or
`/php_proxy/p2/vendor/pkg.json`.

**Response (200):**

```json
{
  "url": "/npm_group/lodash/-/lodash-4.17.21.tgz",
  "node": "pantera-1",
  "repo": {"name": "npm_group", "type": "npm-group", "mode": "group", "members": ["npm_local", "npm_proxy"]},
  "parsed": {"package": "lodash", "version": "4.17.21", "kind": "artifact", "path": "/lodash/-/lodash-4.17.21.tgz"},
  "request": {"status": 404, "headers": {"content-length": "0"}, "bodySnippet": "", "bodyBytes": 0},
  "checks": [
    {"id": "repository", "layer": "repository", "status": "ok", "message": "Repository npm_group exists (npm-group, group)"},
    {"id": "request", "layer": "repository", "status": "problem", "message": "Request answers 404"},
    {"id": "group-walk", "layer": "group", "status": "problem", "message": "Member walk npm_local:404 → npm_proxy:404 — no member serves this path", "members": [{"name": "npm_local", "status": 404}, {"name": "npm_proxy", "status": 404}]},
    {"id": "negative-cache:npm_group", "layer": "negative-cache", "status": "problem", "message": "Cached 404 in npm_group for lodash 4.17.21/lodash-4.17.21.tgz (L2) — requests are answered 404 without asking the upstream",
     "fix": {"action": "invalidate", "endpoint": "/api/v1/admin/neg-cache/invalidate", "body": {"scope": "npm_group", "repoType": "npm-group", "artifactName": "lodash", "version": "4.17.21/lodash-4.17.21.tgz"}}},
    {"id": "cooldown", "layer": "cooldown", "status": "ok", "message": "Cooldown state of 4.17.21: none"},
    {"id": "metadata", "layer": "metadata", "status": "ok", "message": "4.17.21 is listed by npm_group"},
    {"id": "upstream-breaker:npm_proxy:https://registry.npmjs.org:443", "layer": "upstream", "status": "ok", "message": "Upstream breaker https://registry.npmjs.org:443 is closed"}
  ]
}
```

`checks[].status` is `ok`, `problem` or `info`; `layer` is one of
`repository`, `group`, `negative-cache`, `cooldown`, `metadata`, `upstream`.
A problem may carry a `fix` -- a relative API call (`action`, `endpoint`,
`body`) the UI runs as-is: `invalidate` (negative cache key), `unblock`
(`/api/v1/repositories/<repo>/cooldown/unblock`) or `refresh-package`.
When no configured repository is addressed, `repo`, `parsed` and `request`
are `null` and the only check is a `repository` problem. A missing `url`
answers 400.

**curl example:**

```bash
curl -G http://localhost:8086/api/v1/admin/troubleshoot \
  --data-urlencode "url=https://pantera.example.com/api/npm_group/lodash/-/lodash-4.17.21.tgz" \
  -H "Authorization: Bearer eyJhbGciOi..."
```
