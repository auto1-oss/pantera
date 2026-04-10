# pantera-pypi-uv-test

Tests Pantera's PEP 691 JSON Simple API via `uv` — the fast Python
package resolver that requires valid JSON index responses with PEP 700
`upload-time` metadata for its `--exclude-newer` feature.

## What this tests

1. **PEP 691 JSON Simple API** — `uv` sends `Accept: application/vnd.pypi.simple.v1+json` and expects valid JSON back. If Pantera serves HTML with a JSON content-type (the bug fixed in v2.1.0), `uv` fails with a parse error.

2. **PEP 700 upload-time** — `exclude-newer` in `pyproject.toml` filters packages by their upload timestamp. This only works if the JSON response includes the `upload-time` field per PEP 700. If missing, `uv` ignores the constraint (silent correctness bug).

3. **Relative URLs in JSON** — `uv` resolves file download URLs from the JSON `url` field relative to the index page URL. If Pantera emits protocol-relative URLs (`//pkg/...`), `uv` treats the package name as a hostname (the second bug fixed in v2.1.0).

4. **Group routing** — `hello` is a hosted package (uploaded via twine), while `requests` and `pydantic` are proxied from upstream PyPI. Both are served through the same `pypi_group` index. The resolver must find all three in a single index.

## Prerequisites

```bash
# Install uv (if not already)
curl -LsSf https://astral.sh/uv/install.sh | sh

# Upload the hello package to the hosted repo
cd ../python
python -m build
twine upload --repository-url http://localhost:8081/test_prefix/api/pypi/pypi \
    -u ayd -p ayd dist/*
```

## Running

```bash
cd pantera-main/docker-compose/pantera/artifacts/python-uv

# Lock dependencies — this is the real test. uv sends JSON requests
# to the Pantera index, parses the PEP 691 response, evaluates
# exclude-newer against upload-time, and resolves the dependency graph.
uv lock

# Verify the lockfile was created and contains all expected packages
uv lock --check

# Install into a venv and run the smoke test
uv sync
uv run python -m pytest tests/ -v
```

## Expected output

```
Resolved N packages in X.XXs
```

If PEP 691 is broken, you'll see one of:
- `error: Failed to parse response from ...` — HTML served as JSON
- `error: Failed to fetch ...` — protocol-relative URL resolved as hostname
- Missing packages in lockfile — `exclude-newer` silently filtered everything because `upload-time` was absent

## Adjusting exclude-newer

The `exclude-newer` timestamp in `pyproject.toml` is set to ~30 days
before the test date. Adjust it to your deployment timeline:

```toml
[tool.uv]
exclude-newer = "2026-03-10T00:00:00Z"  # packages published before this date
```

The date must be **after** the `hello` package's upload timestamp
(check `.pypi/metadata/hello/*.json` for the exact `upload-time`),
otherwise uv will correctly filter it out — which is actually the
proof that PEP 700 is working! If `uv lock` fails with *"hello was
filtered by exclude-newer"*, that means upload-time parsing works;
just bump the date past the upload.

Set it far enough back that `requests` and `pydantic` still resolve
(they've been on PyPI for years), but recent enough that `hello`
(uploaded today) is included.
