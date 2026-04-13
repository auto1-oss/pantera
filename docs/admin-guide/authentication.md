# Authentication

> **Guide:** Admin Guide | **Section:** Authentication

Pantera supports multiple authentication providers that can be combined in a priority chain. This page covers the configuration and operation of each provider.

---

## ⚠ Default Admin Credentials (Fresh Install)

On a fresh install with an empty `users` table, Pantera bootstraps a single default user so you can log in and complete setup:

| Field | Value |
|---|---|
| **Username** | `admin` |
| **Password** | `admin` |
| **Role** | `admin` (all permissions) |
| **Must change password** | `true` |

**On first login the server refuses every other API call** and the UI redirects to a forced-password-change page until you pick a compliant password. The complexity rules (enforced server-side in `PasswordPolicy.java` and mirrored in the UI) are:

- At least **12 characters**
- At least one **uppercase** letter
- At least one **lowercase** letter
- At least one **digit**
- At least one **special character** (`!@#$%^&*()-_=+[]{};:,.<>?/|~` etc.)
- **Not** equal to the username
- **Not** in the well-known weak-password list (`password`, `admin`, `changeme`, ...)

⚠ **Change this password immediately in production.** The default is logged at WARN level during startup so operators see it. The bootstrap only runs when the `users` table is empty, so it will not overwrite an existing admin account.

---

## Protected Providers

Two provider types are **protected** and cannot be disabled or deleted via the UI or API:

- **`local`** — username/password authentication against the `users` table. Required for fallback access if SSO breaks.
- **`jwt-password`** — API token authentication. Required for the REST API and client-tool integrations.

The UI disables the toggle and delete buttons for these providers with an explanatory tooltip. The backend also refuses the operations with HTTP 400:

```
{"code": 400, "message": "Cannot disable the 'local' provider — it is required for fallback access."}
```

Both providers are auto-created on a fresh install if they don't already exist, so every Pantera instance always has a working fallback.

---

## Provider Evaluation Order

Pantera evaluates authentication providers in the order listed in `meta.credentials`. The first provider that recognizes the credentials authenticates the request. If no provider matches, the request is rejected with HTTP 401.

```yaml
meta:
  credentials:
    - type: keycloak       # Checked first
    - type: jwt-password   # Checked second
    - type: okta           # Checked third
    - type: env            # Checked fourth
    - type: pantera        # Checked last
```

Recommended ordering for production:

1. **SSO providers** (keycloak, okta) -- Handle interactive users first.
2. **jwt-password** -- Handle programmatic clients using JWT tokens as passwords.
3. **env** -- Bootstrap admin access.
4. **pantera** -- Native user database fallback.

---

## Environment Variables Provider (type: env)

The simplest provider. Reads a single admin credential from environment variables. Intended for bootstrap access and development.

```yaml
credentials:
  - type: env
```

| Environment Variable | Description |
|---------------------|-------------|
| `PANTERA_USER_NAME` | Admin username |
| `PANTERA_USER_PASS` | Admin password |

The env provider creates a virtual user with full admin permissions. It has no additional configuration keys.

---

## Pantera Native Users (type: pantera)

Native user management stored in YAML files under the policy storage path, or in the PostgreSQL database when configured.

```yaml
credentials:
  - type: pantera
```

Users are managed via:

- **YAML files** -- Stored in `users/` under the policy storage path. See [Authorization](authorization.md) for the file format.
- **REST API** -- Create, update, and delete users programmatically. See the [REST API Reference](../rest-api-reference.md#5-user-management).
- **Management UI** -- User management interface at port 8090.

---

## Keycloak OIDC (type: keycloak)

Enterprise SSO via Keycloak. Users are provisioned automatically on first login.

```yaml
credentials:
  - type: keycloak
    url: "http://keycloak:8080"
    realm: pantera
    client-id: pantera
    client-password: ${KEYCLOAK_CLIENT_SECRET}
    user-domains:
      - "local"
```

### Configuration Keys

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `url` | string | Yes | -- | Keycloak server base URL |
| `realm` | string | Yes | -- | Keycloak realm name |
| `client-id` | string | Yes | -- | OIDC client identifier |
| `client-password` | string | Yes | -- | OIDC client secret (supports `${ENV_VAR}`) |
| `user-domains` | list | No | -- | Accepted email domain suffixes for user matching |

### Keycloak Setup Steps

1. Create a realm named `pantera` in the Keycloak admin console.
2. Create a client with ID `pantera`, set the access type to "confidential".
3. Set the client secret and copy it to `KEYCLOAK_CLIENT_SECRET`.
4. Configure valid redirect URIs to include the Pantera UI callback URL.
5. Create users or connect to your LDAP/AD directory.

### User Domain Matching

The `user-domains` list controls which Keycloak users are accepted. When set, the user's email domain must match one of the listed suffixes. For example, with `user-domains: ["local"]`, only emails ending in `@local` are accepted.

---

## Okta OIDC with MFA (type: okta)

Okta integration with group-to-role mapping and multi-factor authentication support.

```yaml
credentials:
  - type: okta
    issuer: ${OKTA_ISSUER}
    client-id: ${OKTA_CLIENT_ID}
    client-secret: ${OKTA_CLIENT_SECRET}
    redirect-uri: ${OKTA_REDIRECT_URI}
    scope: "openid email profile groups"
    groups-claim: "groups"
    group-roles:
      - pantera_readers: "reader"
      - pantera_admins: "admin"
    user-domains:
      - "@auto1.local"
```

### Configuration Keys

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `issuer` | string | Yes | -- | Okta issuer URL (e.g., `https://your-org.okta.com`) |
| `client-id` | string | Yes | -- | OIDC client identifier |
| `client-secret` | string | Yes | -- | OIDC client secret |
| `redirect-uri` | string | Yes | -- | OAuth2 redirect URI |
| `scope` | string | No | `openid email profile groups` | Space-separated OIDC scopes |
| `groups-claim` | string | No | `groups` | JWT claim containing group membership |
| `group-roles` | list of maps | No | -- | Maps Okta groups to Pantera roles |
| `user-domains` | list | No | -- | Accepted email domain suffixes |
| `authn-url` | string | No | auto | Override authentication endpoint URL |
| `authorize-url` | string | No | auto | Override authorization endpoint URL |
| `token-url` | string | No | auto | Override token endpoint URL |

### Group-to-Role Mapping

The `group-roles` list maps Okta group names to Pantera role names. When a user authenticates via Okta, their group memberships are read from the `groups` claim in the id_token. Each matching group assigns the corresponding Pantera role.

```yaml
group-roles:
  - pantera_readers: "reader"      # Okta group -> Pantera role
  - pantera_developers: "deployer"
  - pantera_admins: "admin"
```

### MFA Authentication

When Okta MFA is enabled, pass the MFA code in the login request:

```bash
curl -X POST http://pantera-host:8086/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"name":"user@auto1.local","pass":"password","mfa_code":"123456"}'
```

The `mfa_code` field is only required when the Okta organization enforces MFA. If MFA is not configured, omit the field.

### Okta Setup Steps

1. Create an OIDC application in the Okta admin console (Web Application type).
2. Set the sign-in redirect URI to your Pantera UI callback URL.
3. Enable the `groups` scope and add a `groups` claim to the id_token.
4. Create Okta groups (e.g., `pantera_readers`, `pantera_admins`) and assign users.
5. Copy the client ID, client secret, and issuer URL to your Pantera environment.

---

## JWT-as-Password (type: jwt-password)

Allows clients to use a JWT token (obtained via the API) as the password in HTTP Basic Authentication. This is the recommended authentication mode for all non-interactive clients (Maven, npm, pip, Docker, Helm, Go, etc.).

```yaml
credentials:
  - type: jwt-password
```

No additional configuration keys are required. The JWT signature is verified locally using the RS256 public key, making this the highest-performance auth method -- no database queries or external IdP calls are needed for access tokens.

### How It Works

1. A user (or CI pipeline) obtains an access token via `POST /api/v1/auth/token` or a long-lived API token via `POST /api/v1/auth/token/generate`.
2. The token is used as the password in the client's Basic Auth configuration.
3. Pantera verifies the RS256 JWT signature and expiry locally (no DB hit for access tokens).

### Client Configuration Examples

**Maven (settings.xml):**

```xml
<server>
  <id>pantera</id>
  <username>user@example.com</username>
  <password>eyJhbGciOiJSUzI1NiIs...</password>
</server>
```

**npm (.npmrc):**

```ini
//pantera-host:8080/:_authToken=eyJhbGciOiJSUzI1NiIs...
```

**Docker:**

```bash
docker login pantera-host:8080 -u user@example.com -p eyJhbGciOiJSUzI1NiIs...
```

**pip (pip.conf):**

```ini
[global]
index-url = http://user:eyJhbGciOiJSUzI1NiIs...@pantera-host:8080/pypi-proxy/simple
```

---

## JWT Token Configuration

Pantera uses RS256 asymmetric signing. The private key signs tokens; the public key verifies them. Both keys are loaded from PEM files at startup.

> **Breaking change (v2.1.0+):** The `meta.jwt.secret` (HS256) field is no longer supported. Pantera will refuse to start if `secret` is present. Generate an RSA key pair and update your configuration.

### Key Pair Generation

```bash
# Generate a 2048-bit RSA private key
openssl genrsa -out /etc/pantera/jwt-private.pem 2048

# Derive the public key
openssl rsa -in /etc/pantera/jwt-private.pem -pubout -out /etc/pantera/jwt-public.pem

# Restrict permissions (private key must not be world-readable)
chmod 600 /etc/pantera/jwt-private.pem
chmod 644 /etc/pantera/jwt-public.pem
```

### pantera.yml Configuration

```yaml
meta:
  jwt:
    private-key-path: ${JWT_PRIVATE_KEY_PATH}   # Path to PEM private key file
    public-key-path: ${JWT_PUBLIC_KEY_PATH}      # Path to PEM public key file
    access-token-expiry-seconds: 3600            # Access token TTL (default: 1 hour)
    refresh-token-expiry-seconds: 604800         # Refresh token TTL (default: 7 days)
```

| Key | Type | Required | Default | Description |
|-----|------|----------|---------|-------------|
| `private-key-path` | string | Yes | -- | Path to RSA private key PEM file. Supports `${ENV_VAR}`. |
| `public-key-path` | string | Yes | -- | Path to RSA public key PEM file. Supports `${ENV_VAR}`. |
| `access-token-expiry-seconds` | int | No | `3600` | Access token lifetime (1 hour default) |
| `refresh-token-expiry-seconds` | int | No | `604800` | Refresh token lifetime (7 days default) |

### Token Architecture

Pantera issues three types of tokens:

| Token Type | TTL | Stored in DB | Use Case |
|-----------|-----|-------------|----------|
| **access** | 1 hour (configurable) | No | All API and proxy requests |
| **refresh** | 7 days (configurable) | Yes | Obtain new access tokens |
| **api** | User-chosen (or permanent) | Yes | CI/CD pipelines, build tools |

Access tokens are verified entirely from the JWT signature -- no DB query. Refresh and API tokens are also checked against the `user_tokens` table.

### Login Response Format

`POST /api/v1/auth/token` now returns both an access token and a refresh token:

```json
{
  "token": "eyJhbGciOiJSUzI1NiIs...",
  "refresh_token": "eyJhbGciOiJSUzI1NiIs...",
  "expires_in": 3600
}
```

### Token Refresh

Exchange a refresh token for a new access token:

```bash
curl -X POST http://pantera-host:8086/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refresh_token": "eyJhbGciOiJSUzI1NiIs..."}'
```

### Generating API Tokens

For CI/CD and automated tools, generate a long-lived API token:

```bash
curl -X POST http://pantera-host:8086/api/v1/auth/token/generate \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"label":"CI Pipeline Token","expiry_days":90}'
```

Set `expiry_days` to `0` for a non-expiring token (requires the admin to allow permanent tokens in the auth settings).

### Token Management

| Action | API Endpoint |
|--------|-------------|
| Login | `POST /api/v1/auth/token` |
| Refresh access token | `POST /api/v1/auth/refresh` |
| Generate API token | `POST /api/v1/auth/token/generate` |
| List tokens | `GET /api/v1/auth/tokens` |
| Revoke token | `DELETE /api/v1/auth/tokens/:tokenId` |

See the [REST API Reference](../rest-api-reference.md#3-api-token-management) for full details.

---

## Auth Settings (Admin)

Administrators can configure token TTLs and permanent token policy without a server restart. Changes take effect immediately and are stored in the database.

### View Auth Settings

```bash
curl http://pantera-host:8086/api/v1/admin/auth-settings \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Response:

```json
{
  "access_token_expiry_seconds": 3600,
  "refresh_token_expiry_seconds": 604800,
  "api_token_max_expiry_days": 90,
  "allow_permanent_tokens": false
}
```

### Update Auth Settings

```bash
curl -X PUT http://pantera-host:8086/api/v1/admin/auth-settings \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "access_token_expiry_seconds": 1800,
    "refresh_token_expiry_seconds": 86400,
    "api_token_max_expiry_days": 30,
    "allow_permanent_tokens": false
  }'
```

### User Revocation

Immediately invalidate all tokens for a user (access, refresh, and API tokens):

```bash
curl -X POST http://pantera-host:8086/api/v1/admin/revoke-user/jdoe \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

This publishes a revocation event via Valkey pub/sub, propagating to all cluster nodes within milliseconds. Nodes without Valkey fall back to polling the database every 30 seconds.

The revocation blocklist is maintained in the `user_tokens` table and an in-memory cache. Access tokens (which are not stored in DB) are invalidated via the blocklist until they expire naturally.

---

## Authentication Cache

Pantera caches authentication decisions to reduce load on external IdPs and the database:

```yaml
meta:
  caches:
    auth:
      ttl: 5m
      maxSize: 1000
      valkey:
        enabled: true
        l1MaxSize: 1000
        l1Ttl: 5m
        l2MaxSize: 100000
        l2Ttl: 5m
```

The auth cache stores successful authentication results. After a password change or user disable, the cache entry will expire naturally after the TTL, or you can invalidate it by restarting the node or clearing the Valkey L2 cache.

---

## Related Pages

- [Authorization](authorization.md) -- RBAC roles and permissions
- [Configuration](configuration.md) -- Main pantera.yml structure
- [Configuration Reference](../configuration-reference.md#14-metajwt) -- JWT configuration key reference
- [REST API Reference](../rest-api-reference.md#1-authentication) -- Auth API endpoints (including new refresh and admin endpoints)
- [Upgrade Procedures](upgrade-procedures.md) -- Migration steps from HS256 to RS256
