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
- When a member cannot answer (for example, the upstream is down) and no other member has the package, the group returns `503` with `Retry-After` instead of `404`.

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

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 Unauthorized` | Expired token or missing auth | Update `auth.json` with a fresh JWT token |
| `The "http://..." file could not be downloaded (HTTP/1.1 404)` | Package not in local repo and not cached from upstream | Verify the group repository includes a proxy member |
| `curl error 60: SSL certificate problem` | HTTPS verification failure | Set `"secure-http": false` in composer.json (non-HTTPS) or install proper certs |
| Package found on Packagist but not resolving | Proxy not configured for packagist.org | Ask admin to verify the php-proxy remote URL |
| `Your requirements could not be resolved` | Dependency conflict, not a Pantera issue | Run `composer update --with-all-dependencies` to resolve conflicts |

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
