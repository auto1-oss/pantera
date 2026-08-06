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

### Corepack

Corepack (bundled with Node.js) manages the package manager itself (`pnpm`,
`yarn`) rather than your project's dependencies, and it resolves package
manager releases through its own registry setting instead of `.npmrc`:

```bash
export COREPACK_NPM_REGISTRY=http://pantera-host:8080/npm-group
```

Corepack fetches `GET <registry>/<pkg>/<version>` directly (e.g. `/pnpm/9.1.0`
or `/pnpm/latest`) and expects a full version manifest with a `dist.tarball`
field -- the same single-version endpoint covered in
[Fetching a Single Version](#fetching-a-single-version), served correctly by
local, proxy, and group repositories alike. Point `COREPACK_NPM_REGISTRY` at
any of the three.

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

## Dist-Tags & Custom Channels

Every published version gets the `latest` dist-tag by default. To publish to a custom channel (e.g. a beta/next release line) instead:

```bash
npm publish --tag beta
```

Manage tags directly:

```bash
npm dist-tag ls @myorg/my-package
npm dist-tag add @myorg/my-package@1.2.0-beta.1 beta
npm dist-tag rm @myorg/my-package beta
```

Installing from a tag works the same as anywhere else:

```bash
npm install @myorg/my-package@beta
```

Dist-tags are persisted durably per package on local repositories, so `dist-tag ls`/`add`/`rm`, `--tag` publishes, and installing by tag all reflect the same state. `npm deprecate` and `npm unpublish <pkg>@<version>` (single-version) are also effective against local repositories: a deprecated version is marked in the packument, and an unpublished version genuinely stops being served.

On proxy repositories, `npm dist-tag ls` and `npm search` are forwarded upstream (read-through, not persisted locally).

---

## Fetching a Single Version

Local, proxy, and group repositories alike serve a specific version's (or
dist-tag's, including `latest`) manifest directly, without downloading the
whole packument:

```bash
npm view @myorg/my-package@1.2.0 version
curl http://pantera-host:8080/npm-local/@myorg/my-package/1.2.0
curl http://pantera-host:8080/npm-local/@myorg/my-package/latest
```

The returned manifest's `dist.tarball` is always rooted at the repository
address you requested -- a proxy or group never hands back another
repository's URL, so the response is usable as-is by strict clients such as
corepack.

`HEAD` requests are also supported on packument and tarball URLs (returns headers only, e.g. `Content-Length`, no body) — useful for existence checks without downloading.

---

## Search

`npm search` (and `GET /-/v1/search`) works against local repositories, indexing every published package, and is forwarded to the upstream registry on proxy repositories:

```bash
npm search my-package --registry http://pantera-host:8080/npm-local
```

---

## Audit

`npm audit` and `npm ping` work against Pantera repositories. Local repositories have no vulnerability advisory database of their own, so `npm audit` against a local repository always reports a clean, honest zero-vulnerability result (not a lie — there genuinely is nothing to report locally). Proxy and group repositories forward the audit query upstream so real advisories from the upstream registry are reported.

### Package Signing & `npm audit signatures`

Local repositories sign every published version with the registry's own key (ECDSA P-256, generated automatically on first publish and reused thereafter), the same way the public npm registry signs packages. The public key is served at `GET /-/npm/v1/keys`, so `npm audit signatures` can verify a locally-published package's `dist.signatures` entry against it:

```bash
npm audit signatures
```

If you published with `npm publish --provenance`, the provenance/attestation bundle is stored and served back at `GET /-/npm/v1/attestations/<pkg>@<version>` — the bundle itself is verified client-side by the npm CLI/Sigstore tooling, exactly as it would be against any other registry.

---

## Tokens & Profile

On local (non-JWT-only) repositories, `npm token` manages registry-scoped tokens:

```bash
npm token list
npm token create
npm token revoke <id>
```

`npm profile get` returns the authenticated user's identity. On JWT-authenticated repositories, token management is not available through the npm CLI (JWT tokens are managed through the Pantera UI/API instead) — the endpoint answers with an honest "not available" status rather than silently returning an empty token list.

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

**Local repository:** `url:` is required here (used for `.npmrc` auth
responses and full-packument tarball rewriting).

```yaml
# npm-local.yaml
repo:
  type: npm
  url: "http://pantera-host:8080/npm-local"
  storage:
    type: fs
    path: /var/pantera/data
```

**Proxy repository:** `url:` is optional here -- omit it and Pantera derives
the client-facing base from each request instead (see
[configuration-reference.md §2.3](../../configuration-reference.md#23-proxy-repository)).
Shown explicitly below for a deployment behind a fixed public hostname.

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
