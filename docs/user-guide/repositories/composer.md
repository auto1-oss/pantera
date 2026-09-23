# Composer (PHP)

> **Guide:** User Guide | **Section:** Repositories / Composer

This page covers how to configure PHP Composer to install dependencies from and publish packages to Pantera.

---

## Prerequisites

- PHP 8.x with Composer 2.x
- A Pantera account with a JWT token (see [Getting Started](../getting-started.md))
- The Pantera hostname and port (default: `pantera-host:8080`)

---

## Configure composer.json

### Using a Group Repository (Recommended)

Add Pantera as a Composer repository in your project's `composer.json`:

```json
{
  "repositories": [
    {
      "type": "composer",
      "url": "http://pantera-host:8080/php-group"
    }
  ],
  "config": {
    "secure-http": false
  }
}
```

Set `secure-http` to `false` only if your Pantera instance does not use HTTPS.

### Using a Proxy Repository Directly

You can also point Composer at a `php-proxy` repository (for example `http://pantera-host:8080/php-proxy`) when you only need upstream packages. The proxy answers `packages.json` itself and sends the per-package lookups back to its own `/p2/` endpoint. Use a group when you also need packages from a local repository.

Dev-branch dists (`dev-*`, `*-dev`) downloaded through a proxy are tied to the commit named in the metadata. The dist URL ends in `?ref=<commit>`, so after the branch moves, `composer update` downloads the new commit rather than a cached copy of the old one.

### Configure Authentication

Create or edit `~/.composer/auth.json`:

```json
{
  "http-basic": {
    "pantera-host:8080": {
      "username": "your-username",
      "password": "your-jwt-token"
    }
  }
}
```

Or set it via the command line:

```bash
composer config --global http-basic.pantera-host:8080 your-username your-jwt-token
```

---

## Install Dependencies

Once configured, standard Composer commands work as expected:

```bash
composer install
composer update
composer require vendor/package
```

Pantera resolves packages through the group repository, checking your local repository first and then falling through to the proxied upstream (Packagist).

How a group resolves package metadata:

- Local (hosted) members are always asked before proxy members, whatever the member order in the group.
- A package that exists in a local member belongs to that member. The group never asks a proxy member about it, including its `dev-*` branches, so Packagist cannot add versions to a private package and private package names are not sent upstream.
- A `403` from a member is returned as `403`. A group reader also needs read permission on the member repositories.
- When a member cannot answer (for example, the upstream is down) and no other member has the package, the group returns `503` with `Retry-After` instead of `404`. When the upstream circuit breaker is open, the proxy's `502` and the group's `503` both carry `X-Pantera-Circuit-Open: true` and the breaker's `Retry-After`.

---

## Publish Packages

### Upload a Package Archive

Set `"version"` in the package's `composer.json` (without it the upload becomes `dev-master`) and exclude `vendor/` from the archive:

```json
{
  "version": "1.0.0",
  "archive": {
    "exclude": ["/vendor", "/dist"]
  }
}
```

Build and upload the archive (prints `201` once the package is indexed):

```bash
composer archive --format=zip --dir=dist --file=my-package-1.0.0
curl -sS -w '%{http_code}\n' -u 'your-username:your-api-token' \
  --upload-file dist/my-package-1.0.0.zip \
  http://pantera-host:8080/php-local/my-package-1.0.0.zip
```

The local Composer repository indexes uploaded archives and makes them available for `composer require`.

### Version Resolution

Pantera takes the package version from the first of these that is present:

1. `"version"` in the archive's `composer.json`.
2. A `major.minor.patch` version in the file name, such as `my-package-1.0.0.zip`.
3. `dev-master`. An archive with no version in `composer.json` or in its file name is published as `dev-master`, and the upload does not warn about it.

### Re-uploading a Version

Published releases are immutable:

| Upload | Result |
|--------|--------|
| A release that is not published yet | `201`, published |
| The same release again with the same content | `201`, nothing changes |
| The same release again with different content | `409 Conflict`, the published archive is kept |
| A dev branch (`dev-*` or `*-dev`) | `201`, replaces the previous upload of that branch |

To ship a change, publish a new version. The same rules apply to JSON package registrations (`PUT /` with a package JSON body).

Every uploaded archive is listed with `dist.shasum`, the SHA-1 of the archive as Pantera stores it (Pantera writes the resolved version into its `composer.json`, so it differs from the SHA-1 of the file you uploaded). Composer records it in `composer.lock` and checks every download against it. After a dev branch is re-uploaded, `composer install` from an older lock file fails the checksum check; run `composer update <package>` to lock the new upload. Releases uploaded before Pantera 2.2.9 keep their entry without `dist.shasum`, and Composer skips the check for them.

An archive that cannot be read, has no `composer.json`, or has a `composer.json` that is not valid JSON is rejected with `400 Bad Request`.

### Delete a Package Archive

Composer has no delete command. Delete an archive from the Pantera UI or with the REST API ([`DELETE /api/v1/repositories/:name/artifacts`](../../rest-api-reference.md)), using its storage path:

```bash
curl -X DELETE http://pantera-host:8086/api/v1/repositories/php-local/artifacts \
  -H "Authorization: Bearer your-jwt-token" \
  -H "Content-Type: application/json" \
  -d '{"path": "artifacts/vendor/my-package/1.0.0/vendor-my-package-1.0.0.zip"}'
```

Uploaded archives are stored as `artifacts/<vendor>/<package>/<version>/<vendor>-<package>-<version>.<zip|tar.gz>`. Consumers that locked the deleted version fail to install it. To ship a fix, publish a new version.

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 Unauthorized` | Expired token or missing auth | Update `auth.json` with a fresh JWT token |
| `The "http://..." file could not be downloaded (HTTP/1.1 404)` | Package not in local repo and not cached from upstream | Verify the group repository includes a proxy member |
| `curl error 60: SSL certificate problem` | HTTPS verification failure | Set `"secure-http": false` in composer.json (non-HTTPS) or install proper certs |
| Package found on Packagist but not resolving | Proxy not configured for packagist.org | Ask admin to verify the php-proxy remote URL |
| `Your requirements could not be resolved` | Dependency conflict, not a Pantera issue | Run `composer update --with-all-dependencies` to resolve conflicts |
| `409 Conflict` on upload | That release is already published with different content | Publish a new version |
| `400 Bad Request` on upload | The archive is unreadable or its `composer.json` is missing or invalid | Rebuild the archive with `composer archive` |
| `503 Service Unavailable` with `Retry-After` from a group, or `502` from a proxy | The upstream could not be reached or sent invalid metadata | Retry later. The package is not reported as missing during an upstream outage |
| `403 Forbidden` from a group | Your account cannot read one of the group's member repositories | Ask an admin for read access on the member repositories |

---

<details>
<summary>Server-Side Repository Configuration (Admin Reference)</summary>

**Local repository:**

```yaml
# php-local.yaml
repo:
  type: php
  storage:
    type: fs
    path: /var/pantera/data
  url: http://pantera-host:8080/php-local
```

**Proxy repository:**

```yaml
# php-proxy.yaml
repo:
  type: php-proxy
  url: http://pantera-host:8080/php-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://repo.packagist.org
```

**Group repository:**

```yaml
# php-group.yaml
repo:
  type: php-group
  members:
    - php-local
    - php-proxy
  url: http://pantera-host:8080/php-group
```

</details>

---

## Related Pages

- [Getting Started](../getting-started.md) -- Obtaining JWT tokens
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
