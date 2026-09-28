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

A proxy repository does not serve the root project index (`/simple/` with no project name). It answers `404` with the header `X-Pantera-Reason: not_implemented` and does not contact the upstream. pip and uv never request the root index; they always resolve `/simple/<project>/`.

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
already exists with **different** content is rejected with the HTTP status
`400 File already exists`; twine prints it as
`HTTPError: 400 Bad Request from <upload-url>` with `File already exists` on
the next line. Publish a new version instead. Re-uploading the **identical**
file succeeds and changes nothing, so re-running a partly failed
`twine upload dist/*` is safe as it is.

Do not add `--skip-existing`: twine supports that flag only for PyPI and TestPyPI and
refuses it for any other repository URL before uploading anything
(`UnsupportedConfiguration: The configured repository ... does not have support
for the following features: --skip-existing`).

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

## Yanking a Release (PEP 592)

Hosted PyPI repositories support yanking a version so pip/uv skip it during
dependency resolution, without deleting the distribution files:

```bash
# Yank a version (optionally with a reason)
curl -X POST http://pantera-host:8080/api/v1/pypi/<repo>/<package>/<version>/yank \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"reason": "broken build"}'

# Reverse it
curl -X POST http://pantera-host:8080/api/v1/pypi/<repo>/<package>/<version>/unyank \
  -H "Authorization: Bearer <token>"
```

The served `/simple/<package>/` index is regenerated immediately, so the
change is visible on the next request — no re-upload needed. Behavior
matches PEP 592: `pip install <package>` (no version pin) skips a yanked
version, while `pip install <package>==<version>` still installs it and
prints a yank warning.

For **proxy** repositories, an upstream yank is now preserved end-to-end:
the served `/simple/<package>/` HTML carries `data-yanked` and the JSON
carries `"yanked"` exactly as the upstream index does, so pip's yank
warning fires for proxied packages too, not just hosted ones.

---

## Distribution Metadata (PEP 658 / PEP 700 / PEP 714)

Hosted uploads (`twine upload`) automatically extract the distribution's
core metadata (`METADATA`/`PKG-INFO`) and serve it at
`GET <file>.metadata` — `pip install --require-hashes` and metadata-only
resolvers can read this without downloading the wheel body. The simple
index advertises it via the PEP 714 `core-metadata` key (JSON) /
`data-core-metadata` attribute (HTML), with the legacy
`dist-info-metadata` / `data-dist-info-metadata` forms retained for
older clients.

The PEP 691 JSON detail page (`Accept: application/vnd.pypi.simple.v1+json`)
also carries the PEP 700 fields: a top-level `versions` array (every
distinct version present, PEP 440 ordered) and a `size` on every file
entry, matching the stored artifact's byte size.

Content negotiation honors RFC 9110 `Accept` q-values and the
`application/vnd.pypi.simple.latest+json` / `…latest+html` aliases, not
just a bare substring match — a client sending
`Accept: application/vnd.pypi.simple.v1+json;q=0.1, text/html;q=1.0`
correctly gets HTML.

---

## Legacy JSON API

Local (hosted) repositories serve the legacy package-level JSON API at
`GET /pypi/<package>/json` for tools (poetry, pip-tools) that still
resolve through it — synthesized from the same persisted files and
`.metadata`/sidecar data the Simple index projects, with
repository-relative download URLs. The version-specific form
(`/pypi/<package>/<version>/json`) is not served locally.

For **proxy** repositories, the version-specific
`/pypi/<package>/<version>/json` endpoint is now cooldown-filtered:
requesting a cooldown-blocked version returns `404` instead of leaking
its metadata through an unfiltered upstream passthrough.

For **proxy** repositories, `/pypi/<package>/json` and
`/pypi/<package>/<version>/json` are now cached (TTL, single-flighted per
package) instead of being fetched from pypi.org on every request — a
pypi.org blip no longer breaks `poetry`/`pip-tools` resolution for a
package Pantera has already cached; the last-known document is served
stale until upstream recovers.

---

## HEAD Requests

Proxy and group repositories now answer `HEAD` for both artifact and
simple-index paths with the same status and headers as the equivalent
`GET` (including `Content-Length`), body omitted — matching hosted
repositories, which already supported HEAD. A missing artifact returns
`404`, never `405`. uv and other resolvers that probe with HEAD before
deciding to download now behave consistently across all three repo modes.

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
