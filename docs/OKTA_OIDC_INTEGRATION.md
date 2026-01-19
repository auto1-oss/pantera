# Okta OIDC Integration with Artipie

This document explains how to integrate Artipie with Okta using OpenID Connect (OIDC), including:

- How the Okta integration works internally.
- How to create and configure the Okta OIDC application.
- How to configure Artipie to use Okta (alongside other providers such as Keycloak).
- How automatic user provisioning works.
- How group-to-role mapping is configured.
- How MFA is supported (both OTP codes and push).

The goal is to preserve the existing `POST /api/auth/token` UX:

- Clients continue to send username/password (and optional `mfa_code`) to Artipie.
- Artipie authenticates the user against Okta, provisions the user into the YAML policy store, and returns an Artipie JWT.

---

## 1. High-Level Architecture

### 1.1 Request Flow

1. Client calls:
   - `POST /api/auth/token`
   - Body (JSON):
     - `name` – username
     - `pass` – password
     - `mfa_code` – optional MFA code for Okta (e.g. TOTP), may be omitted for push MFA.

2. `AuthTokenRest`:
   - Stores `mfa_code` in a thread-local `OktaAuthContext`.
   - Calls the global `Authentication` SPI: `Authentication.user(name, pass)`.

3. `Authentication` is usually a `Joined` stack of providers (e.g. Artipie, Keycloak, Okta, etc.).
   - Each provider is tried in order until one returns an `AuthUser`.

4. For Okta, `AuthFromOkta`:
   - Reads `mfa_code` from `OktaAuthContext`.
   - Uses `OktaOidcClient` to perform the Okta Authentication API + OIDC Authorization Code flow.
   - On success, passes the Okta username and groups to `OktaUserProvisioning`.
   - Returns `new AuthUser(oktaUsername, "okta")`.

5. `AuthTokenRest` then issues an Artipie JWT token for the user, as with other providers.

### 1.2 Key Components

All Okta integration code lives under `artipie-main/src/main/java/com/artipie/auth`:

- `AuthFromOkta` — implementation of `Authentication` backed by Okta OIDC.
- `AuthFromOktaFactory` — `@ArtipieAuthFactory("okta")`, creates `AuthFromOkta` from YAML config.
- `OktaOidcClient` — HTTP client for Okta Authentication API and OIDC Authorization Code flow.
- `OktaAuthContext` — thread-local holder for `mfa_code` passed from the REST layer.
- `OktaUserProvisioning` — just-in-time (JIT) user provisioning into the YAML policy storage.

Env-variable substitution for Okta config is handled in `AuthFromOktaFactory` similarly to the Artipie database and Keycloak factories.

---

## 2. Creating the Okta OIDC Application

> Note: Oktas UI and naming may evolve. The steps below describe the general flow for an **OIDC Web Application**.

### 2.1 Create OIDC Web App

1. Log in to the **Okta Admin Console**.
2. Navigate to **Applications  Applications**.
3. Click **Create App Integration** (or **Create App**).
4. Choose:
   - **Sign-in method**: `OIDC - OpenID Connect`.
   - **Application type**: `Web Application`.
5. Click **Next**.

### 2.2 Application Settings

Configure:

- **App integration name**: e.g. `Artipie`.
- **Sign-in redirect URIs**:
  - Must match the `redirect-uri` configured in Artipie.
  - Example: `https://artipie.local/okta/callback` (can be any valid https URL; Artipie doesn't listen on it but Okta requires a redirect URI).
- **Sign-out redirect URIs**: optional, not used by this integration.
- **Assignments**: choose which Okta users/groups can access Artipie.

Save the app, then note down:

- **Client ID**
- **Client Secret**

These values will be referenced in Artipie configuration.

### 2.3 Authorization Server and Issuer

Okta uses an authorization server for OIDC tokens. You can use the default or a custom server.

1. Navigate to **Security  API  Authorization Servers**.
2. Choose the server you want (e.g. `default`) or create a new one.
3. The **Issuer URI** will look like:
   - `https://dev-XXXXX.okta.com/oauth2/default`

This URI must be configured as the Okta `issuer` in Artipie.

### 2.4 Groups Claim for Role Mapping

Artipie maps Okta **groups** to Artipie **roles**. These groups must be present as a claim in the ID token.

1. In your authorization server, go to **Claims**.
2. Add or edit a claim for groups, e.g.:
   - **Name**: `groups`
   - **Include in token type**: at least `ID Token`.
   - **Value type**: `Groups`.
   - **Filter**: based on `Groups assigned to the application` or by regex.

Artipie assumes a claim named `groups` by default but this is configurable via `groups-claim`.

---

## 3. Artipie Configuration for Okta

### 3.1 Basic Okta Credentials Block

Add an Okta credentials item under `meta.credentials` in the Artipie config (typically `artipie.yml`).

```yaml
meta:
  credentials:
    - type: okta
      issuer: ${OKTA_ISSUER}
      client-id: ${OKTA_CLIENT_ID}
      client-secret: ${OKTA_CLIENT_SECRET}
      redirect-uri: ${OKTA_REDIRECT_URI}
      scope: "openid profile groups"
      groups-claim: "groups"
      group-roles:
        "okta-artipie-admins": "admin"
        "okta-artipie-readers": "readers"
```

#### Fields

- **issuer**
  - Okta OIDC issuer URI, e.g. `https://dev-XXXXX.okta.com/oauth2/default`.
  - Required.
- **client-id**, **client-secret**
  - Taken from the Okta application configuration.
  - Required.
- **redirect-uri**
  - Any valid HTTPS URI registered as a sign-in redirect in Okta.
  - Artipie does not expose an endpoint on it, but Okta requires one; it is used only to complete the OIDC flow server-side.
 - **scope**
  - Default applied if not set: `"openid profile groups"`.
  - Must include whatever scopes are required for your groups claim to be present.
 - **groups-claim**
  - Name of the claim in the ID token that contains groups, default `groups`.
  - Change this if your Okta authorization server exposes group info under a different claim name.

### 3.2 Environment Variable Substitution

`AuthFromOktaFactory` supports simple environment-variable substitution in string values, using the same pattern as the database and Keycloak factories:

- Any `${VAR_NAME}` substring is replaced with `System.getenv("VAR_NAME")` if present.
- If the environment variable is missing, the placeholder is left as-is.

Example:

```yaml
meta:
  credentials:
    - type: okta
      issuer: ${OKTA_ISSUER}
      client-id: ${OKTA_CLIENT_ID}
      client-secret: ${OKTA_CLIENT_SECRET}
      redirect-uri: ${OKTA_REDIRECT_URI}
      scope: "openid profile groups"
      groups-claim: "groups"
      group-roles:
        "okta-artipie-admins": "${ARTIPIE_ADMIN_ROLE}"
```

Environment:

```bash
export OKTA_ISSUER="https://dev-XXXXX.okta.com/oauth2/default"
export OKTA_CLIENT_ID="..."
export OKTA_CLIENT_SECRET="..."
export OKTA_REDIRECT_URI="https://artipie.local/okta/callback"
export ARTIPIE_ADMIN_ROLE="admin"
```

### 3.3 Advanced Okta URL Overrides

In most setups you do **not** need to configure these fields; they are derived from `issuer`. They exist to support unusual Okta deployments or proxies.

Supported optional keys under the Okta credentials block:

- `authn-url` — custom URL for the Okta Authentication API `/api/v1/authn`.
- `authorize-url` — custom URL for the OIDC authorize endpoint `/v1/authorize`.
- `token-url` — custom URL for the OIDC token endpoint `/v1/token`.

If omitted, `OktaOidcClient` derives them as:

- `authn-url` = `<scheme>://<host>[:port]/api/v1/authn` (from the issuer's host).
- `authorize-url` = `<issuer>/v1/authorize` (with trailing slash normalized).
- `token-url` = `<issuer>/v1/token`.

You generally only set these if you have a non-standard reverse proxy path in front of Okta.

---

## 4. Coexistence with Other Authentication Providers

Okta is just one entry in the `meta.credentials` list. You can still use Artipie built-in users, environment-based auth, GitHub, Keycloak, etc. All configured providers are combined via `Authentication.Joined`.

Example with Artipie local users, Keycloak, and Okta together:

```yaml
meta:
  storage:
    type: fs
    path: /var/artipie/config

  credentials:
    - type: artipie
      storage:
        type: fs
        path: /var/artipie/security
    - type: keycloak
      url: ${KEYCLOAK_URL}
      realm: ${KEYCLOAK_REALM}
      client-id: ${KEYCLOAK_CLIENT_ID}
      client-password: ${KEYCLOAK_CLIENT_SECRET}
    - type: okta
      issuer: ${OKTA_ISSUER}
      client-id: ${OKTA_CLIENT_ID}
      client-secret: ${OKTA_CLIENT_SECRET}
      redirect-uri: ${OKTA_REDIRECT_URI}
      scope: "openid profile groups"
      groups-claim: "groups"
      group-roles:
        "okta-artipie-admins": "admin"
        "okta-artipie-readers": "readers"

  policy:
    type: artipie
    storage:
      type: fs
      path: /var/artipie/security
```

- Providers are tried in order.
- Okta provisioning uses the same `policy.storage` as your existing Artipie YAML policy.

---

## 5. MFA Behavior (OTP and Push)

Okta MFA is handled via `OktaOidcClient` using Okta's Authentication API and factor verification.

### 5.1 Requesting a Token with MFA

The `/api/auth/token` endpoint accepts an optional `mfa_code` field in the JSON body.

#### OTP / Code-Based MFA

If the user has a TOTP or other code-based factor (Okta factor types `token:*`):

```bash
curl -X POST http://artipie-host:8086/api/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"name":"alice","pass":"password","mfa_code":"123456"}'
```

- `AuthTokenRest` stores `mfa_code` in `OktaAuthContext`.
- `AuthFromOkta` passes it to `OktaOidcClient.authenticate`.
- `OktaOidcClient`:
  - Calls `/api/v1/authn` with username/password.
  - If Okta responds with `MFA_REQUIRED` or `MFA_CHALLENGE`, looks for a factor with `factorType` starting with `token:` and calls its `verify.href` with `passCode = mfa_code`.
  - On `status = SUCCESS`, proceeds with the OIDC Authorization Code flow.

#### Push / Out-of-Band MFA (No Code)

If the user has a push factor (e.g. Okta Verify Push) and **no** `mfa_code` is provided:

```bash
curl -X POST http://artipie-host:8086/api/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"name":"alice","pass":"password"}'
```

- `mfa_code` is absent, so `OktaOidcClient` looks for a factor with `factorType = "push"`.
- It calls the factor's `verify.href` with the `stateToken` to trigger a push challenge.
- It then polls the same verify endpoint for a limited time (about 30 seconds, 1s interval) while the factor status is `MFA_REQUIRED` or `MFA_CHALLENGE`.
  - If the user approves the push in the Okta Verify app and Okta returns `status = SUCCESS`, Artipie proceeds with the OIDC flow.
  - Otherwise, Artipie fails authentication and `/api/auth/token` returns `401`.

### 5.2 Behavior Without MFA

If Okta returns `status = SUCCESS` directly from `/api/v1/authn` (no MFA required or already satisfied), `OktaOidcClient` uses the returned `sessionToken` directly to perform the Authorization Code flow.

---

## 6. Automatic User Provisioning and Role Mapping

Automatic provisioning is implemented by `OktaUserProvisioning` and is invoked by `AuthFromOkta` whenever Okta authentication succeeds.

### 6.1 Where Users Are Stored

Okta provisioning writes users into the same storage used by Artipie's YAML policy (`policy.storage`). This storage typically contains:

- `users/<username>.yml` or `users/<username>.yaml` files.

For each Okta-authenticated user:

1. If `users/<username>.yaml` exists, it is used.
2. Else if `users/<username>.yml` exists, it is used.
3. Otherwise, a new file `users/<username>.yml` is created.

### 6.2 What Provisioning Writes

`OktaUserProvisioning` performs the following steps:

1. Reads the existing YAML mapping (if any).
2. Copies all keys **except** `roles` and `enabled` into a new mapping.
3. Sets `enabled: true`.
4. Collects existing roles from the `roles` sequence (if present).
5. For each Okta group in the user's group list:
   - Looks up a mapped role in the `group-roles` map from the Okta config.
   - If a role is configured and non-empty, adds it to the roles set.
6. If the final roles set is non-empty, writes it as a `roles:` YAML sequence.
7. Saves the result back to the same `users/<username>.(yml|yaml)` file.

This means:

- Existing non-role metadata in the user file is preserved.
- Existing roles are merged with roles derived from Okta groups.
- The user is always marked `enabled: true` after a successful Okta login.

### 6.3 Example: First Login

Assume a user `alice` logs in for the first time and Okta reports she is in groups:

- `okta-artipie-admins`
- `okta-artipie-readers`

With the following config:

```yaml
group-roles:
  "okta-artipie-admins": "admin"
  "okta-artipie-readers": "readers"
```

If no existing user file is present, Artipie creates:

```yaml
# users/alice.yml
enabled: "true"
roles:
  - admin
  - readers
```

### 6.4 Example: Merging with Existing User

If a file already exists:

```yaml
# users/alice.yml (before)
email: alice@example.com
display_name: Alice
roles:
  - legacy-reader
```

After Okta login with the same groups as above, the file becomes:

```yaml
# users/alice.yml (after Okta provisioning)
email: alice@example.com
display_name: Alice
enabled: "true"
roles:
  - legacy-reader
  - admin
  - readers
```

Notes:

- Non-role fields (`email`, `display_name`) are preserved.
- `enabled` is set to `true`.
- Existing roles are retained and merged with Okta-derived roles.

### 6.5 Groups Without Mapping

If a user belongs to a group that is **not** present in `group-roles`, that group is ignored for role mapping (no implicit role is created).

Best practice:

- Define explicit `group-roles` for all Okta groups that should control Artipie permissions.
- Use Okta group naming conventions (e.g. `okta-artipie-<role>`) and map them centrally in the config.

---

## 7. Token Endpoint Usage Summary

The Okta integration is designed to keep the `/api/auth/token` UX as simple as possible.

### 7.1 Without MFA

```bash
curl -X POST http://artipie-host:8086/api/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"name":"alice","pass":"password"}'
```

If Okta is configured and finds the user without requiring MFA, Artipie returns:

```json
{"token":"<artipie-jwt>"}
```

### 7.2 With OTP MFA

```bash
curl -X POST http://artipie-host:8086/api/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"name":"alice","pass":"password","mfa_code":"123456"}'
```

Used when Okta requires a code-based factor.

### 7.3 With Push MFA

```bash
curl -X POST http://artipie-host:8086/api/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"name":"alice","pass":"password"}'
```

Used when Okta policy requires a push factor. The user approves the login in the Okta Verify app within the polling window.

---

## 8. Troubleshooting

### 8.1 `/api/auth/token` Returns 401 for Okta Users

Possible causes:

- Wrong password or invalid MFA code.
- Okta application or authorization server not assigned to the user.
- Misconfigured `issuer`, `client-id`, or `client-secret`.
- Groups claim not present in the ID token (only affects role mapping, not basic authentication).

Steps:

- Check Okta System Logs for the user and integration.
- Enable additional logging (Artipie logs with `eventCategory="authentication"`).

### 8.2 No Roles Assigned After Okta Login

Symptoms:

- User can obtain a token but has no or limited permissions.

Checks:

- Confirm groups are present in the ID token under the expected `groups-claim`.
- Confirm `group-roles` is configured and the Okta group names **exactly match** the keys.
- Inspect the generated `users/<username>.yml` user file to see what roles were written.

### 8.3 Environment Variable Substitution Not Working

- Ensure variables are exported in the environment where Artipie runs.
- Check for typos in `${VAR_NAME}` placeholders.
- Remember: if an environment variable is missing, the placeholder is left unchanged.

---

## 9. JWT Token Expiry Configuration

By default, Artipie JWT tokens are **permanent** (no expiry). You can configure tokens to expire after a specified duration.

### 9.1 Configuration

Add a `jwt` section under `meta` in `artipie.yml`:

```yaml
meta:
  jwt:
    # Set to true for tokens that expire, false for permanent tokens
    expires: true
    # Token lifetime in seconds (default: 86400 = 24 hours)
    expiry-seconds: 86400
    # Secret key for signing tokens (use env var in production)
    secret: ${JWT_SECRET}
```

#### Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `expires` | boolean | `false` | Whether tokens should expire |
| `expiry-seconds` | integer | `86400` | Token lifetime in seconds (24 hours) |
| `secret` | string | `"some secret"` | HMAC secret for signing tokens |

### 9.2 Examples

**Permanent tokens (default behavior):**

```yaml
meta:
  jwt:
    expires: false
```

**Tokens that expire in 1 hour:**

```yaml
meta:
  jwt:
    expires: true
    expiry-seconds: 3600
```

**Tokens that expire in 7 days with secure secret:**

```yaml
meta:
  jwt:
    expires: true
    expiry-seconds: 604800
    secret: ${JWT_SECRET}
```

### 9.3 Security Recommendations

- **Always use a strong secret** in production. Use environment variables to avoid hardcoding secrets.
- **Enable expiry** for better security. Shorter expiry times reduce the window of exposure if a token is compromised.
- **Typical expiry values:**
  - 1 hour (3600s) for high-security environments
  - 24 hours (86400s) for standard use
  - 7 days (604800s) for convenience in development

---

## 10. JWT-as-Password Authentication (High Performance Mode)

### 10.1 The Problem

When using Okta with MFA, each authentication request triggers:
1. Okta Authentication API call (~200-500ms)
2. MFA verification (up to 30 seconds for push notifications)
3. OIDC token exchange (~100-200ms)

This is unacceptable for repository operations where clients may make hundreds of requests per build.

### 10.2 The Solution: JWT-as-Password

Artipie supports using JWT tokens as passwords in Basic Authentication. The workflow is:

1. **One-time Token Generation (with MFA):**
   ```bash
   curl -X POST http://artipie:8081/api/v1/oauth/token \
     -H "Content-Type: application/json" \
     -d '{"name": "user@example.com", "pass": "password123"}'
   # Approve MFA push notification on your phone
   # Response: {"token": "eyJhbGciOiJIUzI1NiIs..."}
   ```

2. **Use JWT as Password (no MFA, local validation):**
   ```xml
   <!-- Maven settings.xml -->
   <server>
     <id>artipie</id>
     <username>user@example.com</username>
     <password>eyJhbGciOiJIUzI1NiIs...</password>
   </server>
   ```

3. **All subsequent requests are validated locally** (~1ms) without any Okta calls.

### 10.3 Configuration

Enable `jwt-password` authentication in `artipie.yml`:

```yaml
meta:
  jwt:
    secret: ${JWT_SECRET}
    expires: true
    expiry-seconds: 86400  # 24 hours
  
  credentials:
    # First: JWT-as-password (LOCAL validation, no IdP calls)
    - type: jwt-password
    
    # Second: File-based users (LOCAL)
    - type: file
      path: _credentials.yaml
    
    # Last: Okta (only used for initial token generation)
    - type: okta
      issuer: ${OKTA_ISSUER}
      client-id: ${OKTA_CLIENT_ID}
      client-secret: ${OKTA_CLIENT_SECRET}
      redirect-uri: ${OKTA_REDIRECT_URI}
      scope: "openid email profile groups"
```

### 10.4 Authentication Chain Order

The order of `credentials` entries matters:

| Order | Type | Validation | Use Case |
|-------|------|------------|----------|
| 1st | `jwt-password` | Local (1ms) | Repository requests with JWT token as password |
| 2nd | `file` | Local | File-based users (backup/service accounts) |
| 3rd | `okta` | Remote (with MFA) | Initial token generation only |

When a request comes in:
1. `jwt-password` checks if the password looks like a JWT (`eyJ...`) and validates locally
2. If not a JWT, falls through to `file` auth
3. If file auth fails, falls through to `okta` (triggers MFA)

### 10.5 Client Configuration Examples

#### Maven (settings.xml)

```xml
<settings>
  <servers>
    <server>
      <id>artipie-releases</id>
      <username>user@example.com</username>
      <password>eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...</password>
    </server>
  </servers>
</settings>
```

#### Gradle (gradle.properties)

```properties
artipieUsername=user@example.com
artipiePassword=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### npm (.npmrc)

```
//artipie:8081/npm/my-npm/:_auth=dXNlckBleGFtcGxlLmNvbTpleUpoYkdjaU9pSklVekkxTmlJc0luUjVjQ0k2SWtwWFZDSjku...
```

Note: npm requires Base64 encoding of `username:password`.

#### Docker

```bash
# Login with JWT as password
echo "eyJhbGciOiJIUzI1NiIs..." | docker login artipie:8081 -u user@example.com --password-stdin
```

#### pip (pip.conf)

```ini
[global]
index-url = http://user%40example.com:eyJhbGciOiJIUzI1NiIs...@artipie:8081/pypi/my-pypi/simple/
```

### 10.6 Security Considerations

| Concern | Mitigation |
|---------|------------|
| Token theft | Configure short expiry (`expiry-seconds`), rotate regularly |
| Username spoofing | JWT `sub` claim must match provided username |
| Replay attacks | `exp` claim prevents indefinite reuse |
| Secret exposure | Use env vars for `secret`, never commit to git |

### 10.7 Performance Comparison

| Auth Method | Latency | MFA Required |
|-------------|---------|--------------|
| Okta (username/password) | 500ms - 30s | Yes, every request |
| JWT-as-password | ~1ms | No (done once at token generation) |
| File-based | ~1ms | No |

### 10.8 Token Refresh

When tokens expire, users need to generate a new one:

```bash
# Generate new token (triggers MFA)
TOKEN=$(curl -s -X POST http://artipie:8081/api/v1/oauth/token \
  -H "Content-Type: application/json" \
  -d '{"name": "user@example.com", "pass": "password123"}' | jq -r .token)

# Update your settings with new token
echo "New token: $TOKEN"
```

Consider automating this with a CI/CD secret rotation job.

---

## 11. Design Notes

- The integration deliberately avoids Resource Owner Password Credentials (ROPC) and instead uses the Okta Authentication API and OIDC Authorization Code flow with `sessionToken`.
- MFA is supported in both code-based and push form without changing the external API contract of `/api/auth/token` beyond the optional `mfa_code` field.
- Existing authentication providers (Keycloak, Artipie local users, etc.) continue to function and can be used in parallel with Okta.
- **JWT-as-password** follows the same pattern used by JFrog Artifactory (Access Tokens), Sonatype Nexus (User Tokens), and GitHub/GitLab (Personal Access Tokens).
- The JWT token generated by `/api/v1/oauth/token` serves as **proof of completed MFA authentication**, eliminating the need for MFA on subsequent requests.

