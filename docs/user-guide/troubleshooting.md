# Troubleshooting

> **Guide:** User Guide | **Section:** Troubleshooting

This page covers common client-side issues and their resolutions. For server-side troubleshooting, see the administrator documentation.

---

## Authentication Errors

### 401 Unauthorized

**Symptom:** All requests return `401 Unauthorized`.

**Common causes and fixes:**

| Cause | Fix |
|-------|-----|
| JWT token has expired | Generate a new token: `POST /api/v1/auth/token` |
| Wrong username in client config | Verify the username matches your Pantera account |
| Token from a different Pantera instance | Ensure the token was generated from the same server |
| Missing authentication header | Verify your client config includes credentials (see format-specific guides) |

**Quick test:**

```bash
# Verify your token is valid
curl -H "Authorization: Bearer $TOKEN" \
  http://pantera-host:8086/api/v1/auth/me
```

If this returns your user info, the token is valid. If it returns 401, generate a new token.

### 403 Forbidden

**Symptom:** Authenticated but the operation is denied.

**Fix:** Your user account lacks the required permission for this operation. Contact your administrator to grant the appropriate role:

| Operation | Required Permission |
|-----------|-------------------|
| Pull/download artifacts | `read` on the repository |
| Push/upload artifacts | `write` on the repository |
| Delete artifacts | `delete` on the repository |

---

## Package Not Found

### Symptom: 404 on Proxy Repository

**Possible causes:**

| Cause | How to Verify | Fix |
|-------|---------------|-----|
| Package is in cooldown | Check the Cooldown panel in the UI or `GET /api/v1/cooldown/blocked?search=<name>` | Wait for cooldown to expire or request an admin unblock (see [Cooldown](cooldown.md)) |
| Package is in the negative cache | The proxy tried upstream before and got a 404 | Wait for the negative cache TTL to expire (default: 24h), or ask admin to clear it |
| Wrong repository URL | Verify the URL matches the repository name exactly | Check your client config against the repository list |
| Upstream registry is down | Check the upstream registry status page | Wait for upstream to recover; cached artifacts remain available |

### Symptom: Package Exists on Public Registry but Not Through Pantera

1. **Check if the proxy is configured.** Your group repository must include a proxy member that points to the correct upstream.
2. **Check for typos.** Repository names and package paths are case-sensitive.
3. **Try a direct proxy URL.** Instead of the group, try the proxy repository directly to isolate the issue:
   ```bash
   # Instead of the group
   npm install lodash --registry http://pantera-host:8080/npm-proxy
   ```

---

## Slow Downloads

**Possible causes:**

| Cause | How to Verify | Fix |
|-------|---------------|-----|
| First-time fetch from upstream | Downloads are slow only once per artifact | This is expected; subsequent fetches are fast from cache |
| Large artifact | Check the artifact size | Large artifacts (Docker images, ML models) take time on first fetch |
| Network latency to upstream | Test direct upstream speed | Ask admin to check `http_client` timeout settings |
| S3 storage without disk cache | Ask admin | Enable S3 disk cache for frequently accessed repositories |

---

## Token Expiry

### Default Expiry

Session tokens expire after 24 hours by default. Long-lived API tokens expire based on the `expiry_days` value set at generation time.

### Symptoms of Expired Tokens

- Builds that worked yesterday now fail with `401 Unauthorized`
- Docker: `unauthorized: authentication required` after successful login
- Maven: `Not authorized` during dependency resolution

### How to Fix

1. **For interactive use:** Log in again via the API or UI to get a fresh session token.
2. **For CI/CD pipelines:** Generate a long-lived API token with `POST /api/v1/auth/token/generate`:
   ```bash
   curl -X POST http://pantera-host:8086/api/v1/auth/token/generate \
     -H "Authorization: Bearer $SESSION_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"label": "CI Token", "expiry_days": 365}'
   ```
3. **For permanent tokens:** Set `"expiry_days": 0` (if allowed by your admin configuration).

### Automating Token Refresh

For CI pipelines, add a token refresh step before artifact operations:

```bash
# Refresh token at the start of the pipeline
export PANTERA_TOKEN=$(curl -s -X POST http://pantera-host:8086/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$CI_USER\",\"pass\":\"$CI_PASS\"}" | jq -r .token)
```

---

## Client Configuration Issues by Format

### Maven

| Issue | Fix |
|-------|-----|
| `<server><id>` does not match `<repository><id>` | Both must use the same id value |
| `Return code is: 405` on deploy | Deploy to a local repo, not proxy/group |
| SNAPSHOT not updating | Run with `-U` flag: `mvn install -U` |
| Certificate errors | Add `<insecure>true</insecure>` under `<server>` or configure TLS |

### npm

| Issue | Fix |
|-------|-----|
| Publishing goes to npmjs.org | Add `publishConfig.registry` in `package.json` |
| Scoped packages not found | Add `@scope:registry=http://pantera-host:8080/npm-group` to `.npmrc` |
| `UNABLE_TO_GET_ISSUER_CERT_LOCALLY` | Set `strict-ssl=false` in `.npmrc` (non-HTTPS) |
| `ERR! code E409` on publish | Package version already exists; bump the version |

### Docker

| Issue | Fix |
|-------|-----|
| `http: server gave HTTP response to HTTPS client` | Add to `insecure-registries` in `daemon.json` |
| `manifest unknown` for official images | Include `library/` prefix: `docker pull pantera-host:8080/proxy/library/ubuntu:latest` |
| Push hangs or times out | Increase Nginx `proxy_read_timeout` and `client_max_body_size 0` |

### PyPI / pip

| Issue | Fix |
|-------|-----|
| `SSLError` | Add `trusted-host = pantera-host` to pip.conf |
| `No matching distribution found` | Ensure index-url ends with `/simple` |
| Old version installed | Run with `--no-cache-dir` |

### Composer

| Issue | Fix |
|-------|-----|
| `curl error 60: SSL certificate problem` | Set `"secure-http": false` in composer.json |
| Auth prompt on every run | Create `~/.composer/auth.json` with credentials |

### Go

| Issue | Fix |
|-------|-----|
| `proxyconnect tcp: tls: first record does not look like a TLS handshake` | Set `GOINSECURE=pantera-host:8080` |
| `verifying module: checksum mismatch` | Set `GONOSUMCHECK=*` |

### Helm

| Issue | Fix |
|-------|-----|
| `not a valid chart repository` | Verify the URL and ensure the Helm repo has `index.yaml` |
| Charts not found after push | Run `helm repo update` |

---

## Getting Help

If the issue is not covered here:

1. **Check your client configuration** against the format-specific guides in this User Guide.
2. **Verify connectivity** with a curl request: `curl http://pantera-host:8080/.health`
3. **Check the Management UI** (Cooldown panel, Repository browser) for clues.
4. **Contact your Pantera administrator** with:
   - The exact error message
   - The client tool and version (e.g., `npm 10.2.4`, `Maven 3.9.6`)
   - The repository name and artifact path
   - The timestamp of the failure

---

## Related Pages

- [User Guide Index](index.md)
- [Getting Started](getting-started.md) -- Setup and authentication
- [Cooldown](cooldown.md) -- When artifacts are blocked
- [REST API Reference](../rest-api-reference.md) -- Endpoint details
