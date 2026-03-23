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

---

## Publish Packages

### Upload a Package Archive

```bash
curl -X PUT \
  -H "Authorization: Basic $(echo -n your-username:your-jwt-token | base64)" \
  --data-binary @my-package-1.0.0.zip \
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
