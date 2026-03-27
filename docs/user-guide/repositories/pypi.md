# PyPI

> **Guide:** User Guide | **Section:** Repositories / PyPI

This page covers how to configure pip and twine to install Python packages from and upload packages to Pantera.

---

## Prerequisites

- Python 3.x with pip
- twine (for publishing): `pip install twine`
- A Pantera account with a JWT token (see [Getting Started](../getting-started.md))
- The Pantera hostname and port (default: `pantera-host:8080`)

---

## Configure pip

### pip.conf (global or per-user)

Create or edit `~/.pip/pip.conf` (Linux/macOS) or `%APPDATA%\pip\pip.ini` (Windows):

```ini
[global]
index-url = http://your-username:your-jwt-token@pantera-host:8080/pypi-proxy/simple
trusted-host = pantera-host
```

Replace:
- `your-username` with your Pantera username
- `your-jwt-token` with the JWT token from the API
- `pypi-proxy` with the name of your PyPI proxy repository

### Environment Variable Alternative

```bash
export PIP_INDEX_URL="http://your-username:your-jwt-token@pantera-host:8080/pypi-proxy/simple"
export PIP_TRUSTED_HOST="pantera-host"
```

### Per-Command Usage

```bash
pip install requests \
  --index-url http://your-username:your-jwt-token@pantera-host:8080/pypi-proxy/simple \
  --trusted-host pantera-host
```

---

## Install Packages

Once pip is configured, standard installation commands work as expected:

```bash
pip install requests
pip install -r requirements.txt
pip install my-internal-package==1.0.0
```

All package lookups are routed through Pantera, which caches packages from the configured upstream (typically `https://pypi.org/simple/`).

---

## Upload with twine

### Step 1: Configure ~/.pypirc

Create `~/.pypirc`:

```ini
[distutils]
index-servers =
    pantera

[pantera]
repository = http://pantera-host:8080/pypi-local
username = your-username
password = your-jwt-token
```

### Step 2: Build and Upload

```bash
# Build the distribution
python -m build

# Upload to Pantera
twine upload --repository pantera dist/*
```

### Command-Line Alternative (no .pypirc)

```bash
twine upload \
  --repository-url http://pantera-host:8080/pypi-local \
  -u your-username -p your-jwt-token \
  dist/*
```

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 Unauthorized` | Expired or invalid JWT token | Generate a new token and update pip.conf |
| `SSLError` or certificate errors | pip expects HTTPS by default | Add `trusted-host = pantera-host` to pip.conf or use `--trusted-host` flag |
| `Could not find a version that satisfies the requirement` | Package not cached in proxy, or wrong index URL | Verify the index-url includes `/simple` at the end |
| Upload fails with `403 Forbidden` | User lacks write permission on local repo | Contact admin for publish access |
| Upload fails with `400 Bad Request` | Uploading to a proxy repository | Upload only to a **local** PyPI repository |
| Package installs old version | pip caching locally | Run with `--no-cache-dir` flag |

---

<details>
<summary>Server-Side Repository Configuration (Admin Reference)</summary>

**Local repository:**

```yaml
# pypi-local.yaml
repo:
  type: pypi
  storage:
    type: fs
    path: /var/pantera/data
```

**Proxy repository:**

```yaml
# pypi-proxy.yaml
repo:
  type: pypi-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://pypi.org/simple/
```

</details>

---

## Related Pages

- [Getting Started](../getting-started.md) -- Obtaining JWT tokens
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
- [REST API Reference](../../rest-api-reference.md) -- Repository management endpoints
