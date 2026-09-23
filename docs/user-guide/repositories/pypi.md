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

pip sends the credentials in the index URL. Percent-encode a username containing `@` (e.g. `me%40example.com`). `trusted-host` is only needed for a plain-HTTP registry.

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

The upload URL is the repository root. The PyPI-style `/legacy/` suffix
(`http://pantera-host:8080/pypi-local/legacy/`) is accepted too; either way the
file is stored at `<package>/<version>/<file>` in the repository.

### Published files are immutable

As on PyPI, a published file cannot be replaced. Uploading a file whose name
already exists with **different** content fails with
`400 File already exists`; publish a new version instead. Re-uploading the
**identical** file succeeds and changes nothing, so a retried `twine upload`
is safe.

---

## Yank a Release

Yanking (PEP 592) hides a release from unpinned resolution without deleting
it: `pip install pkg` skips the yanked version, while an exact pin
(`pkg==1.2.0`) still installs it with a warning. Yank from the Pantera UI or
with the REST API (requires `write` on the repository):

```bash
curl -X POST http://pantera-host:8086/api/v1/pypi/pypi-local/my-package/1.2.0/yank \
  -H "Authorization: Bearer your-jwt-token" \
  -H "Content-Type: application/json" \
  -d '{"reason": "broken build"}'
```

`.../unyank` reverses it. The simple index reflects the change immediately. See
the [REST API Reference](../../rest-api-reference.md) for details.

---

## Search

`pip search --index http://your-username:your-token@pantera-host:8080/pypi-local/ <name>`
works against a **local** repository and needs only read permission. Group and
proxy repositories cannot search; they answer with an XML-RPC fault that pip
prints as an error. Use the Pantera UI search for those.

---

## uv

Add the index to `pyproject.toml` (`default = true` replaces PyPI; `publish-url` is the local repository for uploads):

```toml
[[tool.uv.index]]
name = "pantera"
url = "http://pantera-host:8080/pypi-group/simple/"
publish-url = "http://pantera-host:8080/pypi-local"
default = true
```

uv reads the credentials from environment variables named after the index:

```bash
export UV_INDEX_PANTERA_USERNAME='your-username'
export UV_INDEX_PANTERA_PASSWORD='your-api-token'
uv add requests
uv build
uv publish --index pantera --trusted-publishing never
```

---

## Poetry

```bash
poetry source add --priority=primary pantera http://pantera-host:8080/pypi-group/simple/
poetry config http-basic.pantera 'your-username' 'your-api-token'

# Uploads go to a local repository, configured separately
poetry config repositories.pantera-publish http://pantera-host:8080/pypi-local
poetry config http-basic.pantera-publish 'your-username' 'your-api-token'
poetry publish --build -r pantera-publish
```

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 Unauthorized` | Expired or invalid JWT token | Generate a new token and update pip.conf |
| `SSLError` or certificate errors | pip expects HTTPS by default | Add `trusted-host = pantera-host` to pip.conf or use `--trusted-host` flag |
| `Could not find a version that satisfies the requirement` | Package not cached in proxy, or wrong index URL | Verify the index-url includes `/simple` at the end |
| Upload fails with `403 Forbidden` | User lacks write permission on local repo | Contact admin for publish access |
| Upload fails with `404 Not Found` (proxy) or `405 Method Not Allowed` (group) | Uploading to a proxy or group repository | Upload only to a **local** PyPI repository |
| Upload fails with `400 File already exists` | A file with that name was already published with different content | Bump the version and upload again; published files are immutable |
| Upload fails with `400 Bad Request: Filename ... does not match the package metadata` | The archive's file name does not match the name/version inside it | Rebuild the distribution (`python -m build`) instead of renaming files |
| `403 Forbidden` when installing through a group | Read on the group alone is not enough; each member is authorized separately | Grant read on the group **and** on its member repositories |
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
