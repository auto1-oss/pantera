# Getting Started

> **Guide:** User Guide | **Section:** Getting Started

This page covers the essentials you need before interacting with Pantera as a package consumer or publisher: what Pantera is, which formats it supports, how repositories are organized, and how to obtain credentials.

---

> **v2.1 upgrade notice:** JWT tokens have changed. All previously issued tokens are invalid. Log in again to obtain a new token. For CI/CD pipelines and build tools, generate a new API token. See your administrator or the [Upgrade Procedures](../admin-guide/upgrade-procedures.md#jwt-migration-hs256-to-rs256) for details.

---

## What is Pantera

Pantera is a universal artifact registry that hosts, proxies, and groups package repositories across 16 formats in a single deployment. It serves as the central gateway for all artifact traffic in your organization -- whether you are pulling open-source dependencies, pushing internal builds, or searching for artifacts across teams.

---

## Supported Formats

| Format | Client Tools | Local | Proxy | Group |
|--------|-------------|:-----:|:-----:|:-----:|
| Maven / Gradle | `mvn`, `gradle` | yes | yes | yes |
| Docker (OCI) | `docker`, `podman` | yes | yes | yes |
| npm | `npm`, `yarn`, `pnpm` | yes | yes | yes |
| PyPI | `pip`, `twine` | yes | yes | -- |
| PHP / Composer | `composer` | yes | yes | yes |
| Go Modules | `go` | yes | yes | yes |
| Helm | `helm` | yes | -- | -- |
| Generic Files | `curl`, `wget` | yes | yes | yes |
| RubyGems | `gem`, `bundler` | yes | -- | yes |
| NuGet | `dotnet`, `nuget` | yes | -- | -- |
| Debian | `apt` | yes | -- | -- |
| RPM | `yum`, `dnf` | yes | -- | -- |
| Conda | `conda` | yes | -- | -- |
| Conan | `conan` | yes | -- | -- |
| Hex | `mix` | yes | -- | -- |

---

## Repository Modes

Pantera organizes packages into **repositories**. Each repository operates in one of three modes:

### Local

A local repository is the authoritative source for artifacts. You push packages directly to it, and clients pull from it. Use local repositories for your organization's internal builds.

**URL pattern:** `http://pantera-host:8080/<repo-name>/<path>`

### Proxy

A proxy repository caches artifacts from an upstream registry (such as Maven Central, npmjs.org, or Docker Hub). The first time a client requests a package, Pantera fetches it from upstream and caches it locally. Subsequent requests are served from cache.

**URL pattern:** `http://pantera-host:8080/<repo-name>/<path>`

### Group

A group repository aggregates multiple local and proxy repositories under a single URL. When a client requests a package, Pantera checks member repositories in order and returns the first match. This is the recommended way to configure your clients -- point them at a single group URL and let Pantera resolve from the right source.

**URL pattern:** `http://pantera-host:8080/<group-repo-name>/<path>`

**Resolution order example:**

```
maven-group
  |-- maven-local      (checked first -- your internal artifacts)
  |-- maven-central    (checked second -- open-source from Maven Central)
```

---

## Obtaining Access

### Step 1: Get an Access Token

Authenticate with your username and password to receive a JWT access token and refresh token:

```bash
curl -X POST http://pantera-host:8086/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"name": "your-username", "pass": "your-password"}'
```

Response:

```json
{
  "token": "eyJhbGciOiJSUzI1NiIs...",
  "refresh_token": "eyJhbGciOiJSUzI1NiIs...",
  "expires_in": 3600
}
```

The `token` field is your **access token** (valid for 1 hour by default). The `refresh_token` lets you obtain a new access token without re-entering your password.

If your organization uses Okta with MFA, include the `mfa_code` field:

```json
{
  "name": "user@company.com",
  "pass": "your-password",
  "mfa_code": "123456"
}
```

> **Note:** All tokens are now signed with RS256 (asymmetric keys). Access tokens are short-lived (1 hour). For tools that need persistent credentials, use a long-lived API token (see below).

### Refreshing Your Access Token

When your access token expires, exchange the refresh token for a new one:

```bash
curl -X POST http://pantera-host:8086/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refresh_token": "eyJhbGciOiJSUzI1NiIs..."}'
```

The Management UI handles token refresh automatically. If you are scripting against the API, store the refresh token and call this endpoint when you receive a `401`.

### Step 2: Use the Token as Your Password

In all client configurations (Maven `settings.xml`, `.npmrc`, `pip.conf`, Docker login, etc.), use:

- **Username:** your Pantera username (e.g., `your-username` or `user@company.com`)
- **Password:** the access token (or API token, for persistent configurations)

This works because Pantera supports **JWT-as-Password** authentication: your token is verified locally from the RS256 signature, making authentication fast with no external IdP call.

### SSO Login (Okta / Keycloak)

If your organization has configured SSO, you can also log in through the Management UI at `http://pantera-host:8090/login`. Click the SSO provider button (e.g., "Continue with okta") and complete authentication through your identity provider. After login, you can generate API tokens from your profile page.

---

## Generating Long-Lived API Tokens

Access tokens expire (default: 1 hour). For CI/CD pipelines and automated tools, generate a long-lived API token that does not expire on short intervals:

```bash
# First, get an access token (see Step 1 above)
ACCESS_TOKEN="eyJhbGciOi..."

# Then generate a long-lived token
curl -X POST http://pantera-host:8086/api/v1/auth/token/generate \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"label": "CI Pipeline Token", "expiry_days": 90}'
```

Response:

```json
{
  "token": "eyJhbGciOiJSUzI1NiIs...",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "label": "CI Pipeline Token",
  "expires_at": "2026-06-20T12:00:00Z",
  "permanent": false
}
```

Set `"expiry_days": 0` for a non-expiring token (if allowed by your administrator -- check with your Pantera admin if you are unsure).

### Managing Your Tokens

| Action | Command |
|--------|---------|
| List your tokens | `GET /api/v1/auth/tokens` |
| Revoke a token | `DELETE /api/v1/auth/tokens/<token-id>` |

You can also manage tokens from the Management UI under **Profile**.

See the [REST API Reference](../rest-api-reference.md) for full details on token management endpoints.

---

## Next Steps

Choose the guide for your package format:

- [Maven](repositories/maven.md)
- [npm](repositories/npm.md)
- [Docker](repositories/docker.md)
- [PyPI](repositories/pypi.md)
- [Composer (PHP)](repositories/composer.md)
- [Go Modules](repositories/go.md)
- [Helm](repositories/helm.md)
- [Generic Files](repositories/generic-files.md)
- [Other Formats](repositories/other-formats.md)

---

## Related Pages

- [User Guide Index](index.md)
- [REST API Reference](../rest-api-reference.md) -- Authentication endpoints
- [Management UI](ui-guide.md) -- Browser-based access
