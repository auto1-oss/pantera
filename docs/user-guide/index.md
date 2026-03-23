# Pantera User Guide

> **Guide:** User Guide | **Section:** Index

Welcome to the Pantera Artifact Registry User Guide. This guide covers everything you need to know as a consumer or publisher of packages through Pantera.

---

## Table of Contents

### Getting Started

- [Getting Started](getting-started.md) -- What Pantera is, supported formats, repository modes, obtaining access, generating API tokens.

### Repository Guides

Step-by-step instructions for configuring your client and working with each package format:

- [Maven](repositories/maven.md) -- Pull dependencies, deploy artifacts, configure `settings.xml`.
- [npm](repositories/npm.md) -- Install packages, publish packages, configure `.npmrc`.
- [Docker](repositories/docker.md) -- Pull images, push images, configure Docker daemon.
- [PyPI](repositories/pypi.md) -- Install packages with pip, upload with twine, configure `pip.conf`.
- [Composer (PHP)](repositories/composer.md) -- Install dependencies, publish packages, configure `composer.json`.
- [Go Modules](repositories/go.md) -- Fetch modules, configure `GOPROXY`.
- [Helm](repositories/helm.md) -- Add repositories, search charts, install and push charts.
- [Generic Files](repositories/generic-files.md) -- Upload and download arbitrary files via curl.
- [Other Formats](repositories/other-formats.md) -- RubyGems, NuGet, Debian, RPM, Conda, Conan, Hex.

### Features

- [Search and Browse](search-and-browse.md) -- Full-text search, artifact locate, browsing via UI and HTTP.
- [Cooldown](cooldown.md) -- What gets blocked and why, checking status, requesting unblock.
- [Import and Migration](import-and-migration.md) -- Bulk import API, backfill CLI, migrating from other registries.

### Interfaces

- [Management UI](ui-guide.md) -- Login, dashboard, repository browser, search, cooldown panel, profile management.

### Support

- [Troubleshooting](troubleshooting.md) -- Common client-side issues and how to resolve them.

---

## Quick Reference: Ports and URLs

| Service | Default Port | URL Pattern |
|---------|-------------|-------------|
| Repository traffic | 8080 | `http://pantera-host:8080/<repo-name>/<path>` |
| REST API | 8086 | `http://pantera-host:8086/api/v1/...` |
| Management UI | 8090 | `http://pantera-host:8090/` |
| Prometheus metrics | 8087 | `http://pantera-host:8087/metrics/vertx` |

## Quick Reference: Authentication

All package manager clients authenticate using **HTTP Basic Auth** where the password is a JWT token. The workflow is:

1. Obtain a JWT token via the API: `POST /api/v1/auth/token`
2. Use your username and the JWT token as credentials in your client configuration.

See [Getting Started](getting-started.md) for detailed instructions.

---

## Related Pages

- [REST API Reference](../rest-api-reference.md) -- Complete endpoint documentation.
- [Configuration Reference](../configuration-reference.md) -- Server-side configuration options.
- [Developer Guide](../developer-guide.md) -- Architecture and contributor information.
