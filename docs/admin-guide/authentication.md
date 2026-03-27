# Authentication

> **Guide:** Admin Guide | **Section:** Authentication

Pantera supports multiple authentication providers that can be combined in a priority chain. This page covers the configuration and operation of each provider.

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

No additional configuration keys are required. The JWT is validated locally using the shared secret from `meta.jwt.secret`, making this the highest-performance auth method -- no database queries or external IdP calls are needed.

### How It Works

1. A user (or CI pipeline) obtains a JWT token via `POST /api/v1/auth/token` or `POST /api/v1/auth/token/generate`.
2. The token is used as the password in the client's Basic Auth configuration.
3. Pantera validates the JWT signature and expiry locally.

### Client Configuration Examples

**Maven (settings.xml):**

```xml
<server>
  <id>pantera</id>
  <username>user@example.com</username>
  <password>eyJhbGciOiJIUzI1NiIs...</password>
</server>
```

**npm (.npmrc):**

```ini
//pantera-host:8080/:_authToken=eyJhbGciOiJIUzI1NiIs...
```

**Docker:**

```bash
docker login pantera-host:8080 -u user@example.com -p eyJhbGciOiJIUzI1NiIs...
```

**pip (pip.conf):**

```ini
[global]
index-url = http://user:eyJhbGciOiJIUzI1NiIs...@pantera-host:8080/pypi-proxy/simple
```

---

## JWT Token Configuration

Control token generation and expiry behavior in `pantera.yml`:

```yaml
meta:
  jwt:
    expires: true           # true = tokens expire; false = permanent tokens
    expiry-seconds: 86400   # Token lifetime in seconds (default: 24 hours)
    secret: ${JWT_SECRET}   # Signing key (HMAC-SHA256)
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `expires` | boolean | `true` | Whether tokens have an expiry time |
| `expiry-seconds` | int | `86400` | Session token lifetime (24 hours) |
| `secret` | string | -- | HMAC-SHA256 signing key (required) |

### Generating API Tokens

Session tokens (from `POST /api/v1/auth/token`) use the configured `expiry-seconds`. For long-lived tokens, use the token generation endpoint:

```bash
curl -X POST http://pantera-host:8086/api/v1/auth/token/generate \
  -H "Authorization: Bearer $SESSION_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"label":"CI Pipeline Token","expiry_days":90}'
```

Set `expiry_days` to `0` for a non-expiring token (requires `expires: false` in JWT config or uses the custom token generation path).

### Token Management

| Action | API Endpoint |
|--------|-------------|
| Generate token | `POST /api/v1/auth/token/generate` |
| List tokens | `GET /api/v1/auth/tokens` |
| Revoke token | `DELETE /api/v1/auth/tokens/:tokenId` |

See the [REST API Reference](../rest-api-reference.md#3-api-token-management) for full details.

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
- [Configuration Reference](../configuration-reference.md#12-metacredentials) -- Complete credentials key reference
- [REST API Reference](../rest-api-reference.md#1-authentication) -- Auth API endpoints
