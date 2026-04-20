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
| `refresh_token` | string  | RS256-signed refresh token (type: `refresh`). Store securely; used to obtain new access tokens. |
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

Exchange a refresh token for a new access token. The refresh token must not be expired or revoked.

**Authentication:** None required.

**Request Body:**

```json
{
  "refresh_token": "eyJhbGciOiJSUzI1NiIs..."
}
```

| Field           | Type   | Required | Description          |
|-----------------|--------|----------|----------------------|
| `refresh_token` | string | Yes      | Valid refresh token  |

**Response (200):**

```json
{
  "token": "eyJhbGciOiJSUzI1NiIs...",
  "expires_in": 3600
}
```

**Response (401):**

```json
{
  "error": "UNAUTHORIZED",
  "message": "Refresh token expired or revoked",
  "status": 401
}
```

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refresh_token": "eyJhbGciOiJSUzI1NiIs..."}'
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
  "callback_url": "http://localhost:3000/callback"
}
```

| Field          | Type   | Required | Description                       |
|----------------|--------|----------|-----------------------------------|
| `code`         | string | Yes      | OAuth2 authorization code         |
| `provider`     | string | Yes      | Provider type name (e.g. "okta")  |
| `callback_url` | string | Yes      | The redirect URI used in the authorize request |

**Response (200):**

```json
{
  "token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 3600
}
```

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/auth/callback \
  -H "Content-Type: application/json" \
  -d '{"code": "abc123", "provider": "okta", "callback_url": "http://localhost:3000/callback"}'
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

**Authentication:** JWT Bearer token required.

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

Get the full configuration of a specific repository.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read`

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

**Response (200):** Empty body on success.

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

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:delete`

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

List members of a group repository. Returns the configured remote URLs.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:read`

**Response (200):**

```json
{
  "type": "maven-group",
  "members": [
    "http://localhost:8080/maven-local",
    "http://localhost:8080/maven-central"
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

Change a user's password. Requires the old password for verification.

**Authentication:** JWT Bearer token required.
**Permission:** `api_user_permissions:change_password`

**Request Body:**

```json
{
  "old_pass": "currentPassword",
  "new_pass": "newSecurePassword"
}
```

**Response (200):** Empty body on success.

**Response (401):**

```json
{
  "error": "UNAUTHORIZED",
  "message": "Invalid old password",
  "status": 401
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
    { "name": "admin", "permissions": { "*": ["*"] } },
    { "name": "reader", "permissions": { "*": ["read", "download"] } }
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
    "maven-central": ["read", "download"],
    "npm-local": ["read", "download", "upload"]
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

**Request Body:**

```json
{
  "permissions": {
    "maven-central": ["read", "download"],
    "npm-local": ["read", "download", "upload"]
  }
}
```

**Response (201):** Empty body on success.

**curl example:**

```bash
curl -X PUT http://localhost:8086/api/v1/roles/developer \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"permissions": {"maven-central": ["read", "download"], "npm-local": ["read", "download", "upload"]}}'
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
**Permission:** `api_alias_permissions:read`

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
**Permission:** `api_alias_permissions:create`

**Request Body:**

```json
{
  "type": "fs",
  "path": "/var/pantera/data"
}
```

**Response (200):** Empty body on success.

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
**Permission:** `api_alias_permissions:delete`

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
**Permission:** `api_alias_permissions:read`

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
**Permission:** `api_alias_permissions:read`

**Request Body:**

```json
{
  "type": "fs",
  "path": "/var/pantera/data/maven-custom"
}
```

**Response (200):** Empty body on success.

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
**Permission:** `api_alias_permissions:delete`

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

The generated instructions are technology-aware: Maven produces `mvn` commands, npm produces `npm install`, Docker produces `docker pull`, PyPI produces `pip install`, Helm produces `helm` commands, NuGet produces `dotnet add package`, Go produces `go get`, and generic repositories produce `curl`/`wget` commands.

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
- `Content-Disposition: attachment; filename="<filename>"`
- `Content-Type: application/octet-stream`
- `Content-Length: <size>` (when available)

**curl example:**

```bash
curl -OJ "http://localhost:8086/api/v1/repositories/maven-local/artifact/download?path=com/example/lib/1.0/lib-1.0.jar" \
  -H "Authorization: Bearer eyJhbGciOi..."
```

---

### POST /api/v1/repositories/:name/artifact/download-token

Generate a short-lived (60 seconds), stateless HMAC-signed download token. This enables native browser downloads without requiring the JWT in the URL. The UI calls this first, then opens the `download-direct` URL in a new tab.

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

Download an artifact using an HMAC download token instead of JWT authentication. Tokens are valid for 60 seconds and are scoped to a specific repository and path.

**Authentication:** HMAC token in query parameter (no JWT required).

**Query Parameters:**

| Parameter | Type   | Required | Description                       |
|-----------|--------|----------|-----------------------------------|
| `token`   | string | Yes      | HMAC download token from `/download-token` |

**Response (200):** Binary file content with headers:
- `Content-Disposition: attachment; filename="<filename>"`
- `Content-Type: application/octet-stream`
- `Content-Length: <size>` (when available)

**Response (401):** Token expired, invalid signature, or malformed token.

**curl example:**

```bash
curl -OJ "http://localhost:8086/api/v1/repositories/maven-local/artifact/download-direct?token=bWF2ZW4tY2VudHJhbA..."
```

---

### DELETE /api/v1/repositories/:name/artifacts

Delete a specific artifact from a repository.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:delete`

**Request Body:**

```json
{
  "path": "com/example/lib/1.0/lib-1.0.jar"
}
```

**Response (204):** No content on success.

**curl example:**

```bash
curl -X DELETE http://localhost:8086/api/v1/repositories/maven-local/artifacts \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"path": "com/example/lib/1.0/lib-1.0.jar"}'
```

---

### DELETE /api/v1/repositories/:name/packages

Delete an entire package folder (directory and all contents) from a repository.

**Authentication:** JWT Bearer token required.
**Permission:** `api_repository_permissions:delete`

**Request Body:**

```json
{
  "path": "com/example/lib/1.0"
}
```

**Response (204):** No content on success.

**curl example:**

```bash
curl -X DELETE http://localhost:8086/api/v1/repositories/maven-local/packages \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"path": "com/example/lib/1.0"}'
```

---

## 9. Search

### GET /api/v1/search

Full-text search across all indexed artifacts. Results are filtered by the caller's `read` permission on each repository. Supports plain full-text search and structured field filters.

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
      "owner": "admin"
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1,
  "hasMore": false
}
```

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

Trigger a full reindex of all artifacts. The reindex runs asynchronously.

**Authentication:** JWT Bearer token required.
**Permission:** `api_search_permissions:write`

**Response (202):**

```json
{
  "status": "started",
  "message": "Full reindex initiated"
}
```

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/search/reindex \
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

Cooldown prevents recently-published upstream artifacts from being cached in proxy repositories for a configurable period, protecting against supply-chain attacks involving newly uploaded malicious packages.

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
      "reason": "TOO_YOUNG",
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
          "reason": "MALWARE",
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

Manually unblock a specific artifact version in a repository.

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

Unblock all currently blocked artifacts in a repository.

**Authentication:** JWT Bearer token required.
**Permission:** `api_cooldown_permissions:write`

**Response (204):** No content on success.

**curl example:**

```bash
curl -X POST http://localhost:8086/api/v1/repositories/maven-central/cooldown/unblock-all \
  -H "Authorization: Bearer eyJhbGciOi..."
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

### POST /api/v1/admin/revoke-user/:username

Immediately revoke all tokens (access, refresh, and API) for the specified user. The revocation is propagated to all cluster nodes via Valkey pub/sub (sub-second propagation when Valkey is available; DB polling fallback otherwise).

> Note: Access tokens (which are not DB-stored) are placed on an in-memory blocklist and will be rejected until they expire naturally. This makes the effective revocation window equal to the access token TTL (default: 1 hour).

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

Import an artifact into a repository. Supports idempotent uploads with checksum verification.

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
| `X-Pantera-Artifact-Name`    | Logical artifact name                            |
| `X-Pantera-Artifact-Version` | Artifact version string                          |
| `X-Pantera-Artifact-Size`    | Size in bytes (falls back to `Content-Length`)    |
| `X-Pantera-Artifact-Owner`   | Owner/publisher name                             |
| `X-Pantera-Artifact-Created` | Created timestamp (milliseconds since epoch)     |
| `X-Pantera-Artifact-Release` | Release timestamp (milliseconds since epoch)     |
| `X-Pantera-Checksum-Sha1`   | Expected SHA-1 checksum                          |
| `X-Pantera-Checksum-Sha256` | Expected SHA-256 checksum                        |
| `X-Pantera-Checksum-Md5`    | Expected MD5 checksum                            |
| `X-Pantera-Checksum-Mode`   | Checksum policy (`VERIFY`, `STORE`, `NONE`)      |
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
