# npm

> **Guide:** User Guide | **Section:** Repositories / npm

This page covers how to configure npm (and compatible clients like yarn and pnpm) to install packages from and publish packages to Pantera.

---

## Prerequisites

- Node.js with npm, yarn, or pnpm
- A Pantera account with a JWT token (see [Getting Started](../getting-started.md))
- The Pantera hostname and port (default: `pantera-host:8080`)

---

## Configure Your Client

### .npmrc (per-project or global)

Create or edit `.npmrc` in your project root or `~/.npmrc` for global configuration:

```ini
registry=http://pantera-host:8080/npm-group
//pantera-host:8080/:_authToken=your-jwt-token-here
```

Replace:
- `npm-group` with the name of your group repository
- `your-jwt-token-here` with the JWT token obtained from the API

### Alternative: Basic Auth

If you prefer basic authentication:

```ini
registry=http://pantera-host:8080/npm-group
//pantera-host:8080/:_auth=BASE64_ENCODED
```

Where `BASE64_ENCODED` is the base64 encoding of `username:jwt-token`:

```bash
echo -n "your-username:your-jwt-token" | base64
```

### yarn

yarn v1 uses the same `.npmrc` format. For yarn v2+, edit `.yarnrc.yml`:

```yaml
npmRegistryServer: "http://pantera-host:8080/npm-group"
npmAuthToken: "your-jwt-token-here"
```

### pnpm

pnpm reads `.npmrc` natively. No additional configuration is needed.

---

## Install Packages

Once `.npmrc` is configured, standard npm commands work as expected:

```bash
npm install lodash
npm install @myorg/my-internal-package
npm ci
```

All requests are routed through the group repository, which resolves from your local repository first and then from proxied upstream registries (npmjs.org).

---

## Publish Packages

### Step 1: Set the Publish Registry

In your `package.json`, add a `publishConfig` to target the local repository:

```json
{
  "name": "@myorg/my-package",
  "version": "1.0.0",
  "publishConfig": {
    "registry": "http://pantera-host:8080/npm-local"
  }
}
```

### Step 2: Publish

```bash
npm publish
```

Or specify the registry on the command line:

```bash
npm publish --registry http://pantera-host:8080/npm-local
```

---

## Using Group Repositories

A typical npm group combines:

1. A **local** repository for your organization's private packages
2. A **proxy** repository that caches packages from npmjs.org

Point your `.npmrc` registry at the group, and Pantera handles resolution order automatically.

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 Unauthorized` | Expired or invalid JWT token | Generate a new token and update `.npmrc` |
| `UNABLE_TO_GET_ISSUER_CERT_LOCALLY` | HTTPS certificate issue | Use `http://` or set `strict-ssl=false` in `.npmrc` |
| `npm ERR! 404 Not Found` | Package not cached in proxy yet, or wrong registry URL | Verify the registry URL in `.npmrc`; check if the proxy has upstream configured |
| `npm ERR! code E403` | User lacks write permission | Contact admin for publish access to the local repository |
| Publish goes to npmjs.org instead of Pantera | Missing `publishConfig` in `package.json` | Add `publishConfig.registry` or use `--registry` flag |
| `ETARGET` no matching version | Package exists upstream but is in cooldown | Check with admin; see [Cooldown](../cooldown.md) |
| Scoped packages not resolving | Scope registry not configured | Add `@myorg:registry=http://pantera-host:8080/npm-group` to `.npmrc` |

---

<details>
<summary>Server-Side Repository Configuration (Admin Reference)</summary>

**Local repository:**

```yaml
# npm-local.yaml
repo:
  type: npm
  url: "http://pantera-host:8080/npm-local"
  storage:
    type: fs
    path: /var/pantera/data
```

**Proxy repository:**

```yaml
# npm-proxy.yaml
repo:
  type: npm-proxy
  url: http://pantera-host:8080/npm-proxy
  remotes:
    - url: "https://registry.npmjs.org"
  storage:
    type: fs
    path: /var/pantera/data
```

**Group repository:**

```yaml
# npm-group.yaml
repo:
  type: npm-group
  members:
    - npm-local
    - npm-proxy
```

</details>

---

## Related Pages

- [Getting Started](../getting-started.md) -- Obtaining JWT tokens
- [Cooldown](../cooldown.md) -- When packages are blocked from upstream
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
