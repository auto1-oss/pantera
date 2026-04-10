"""
PEP 691 / PEP 700 tests for Pantera's PyPI adapter.

The core insight: a successful **exclusion** is the proof that PEP 700
upload-time works. We don't need packages to install — we need uv to
correctly REJECT packages based on their upload timestamp. If the JSON
response is malformed or upload-time is missing, uv can't enforce the
cutoff and the test fails.

Run:
    uv run python -m pytest tests/ -v
"""
import re
import subprocess
import tempfile
from pathlib import Path


PANTERA_GROUP = (
    "http://ayd:ayd@localhost:8081"
    "/test_prefix/api/pypi/pypi_group/simple/"
)


def _uv_lock(pyproject_content: str) -> subprocess.CompletedProcess:
    """Run `uv lock` in a temp directory with the given pyproject.toml."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        (tmp_path / "pyproject.toml").write_text(pyproject_content)
        return subprocess.run(
            ["uv", "lock"],
            capture_output=True,
            text=True,
            cwd=str(tmp_path),
            timeout=120,
        )


def _uv_lock_and_read(pyproject_content: str) -> tuple[subprocess.CompletedProcess, str]:
    """Run `uv lock` and return (result, lockfile_content)."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        (tmp_path / "pyproject.toml").write_text(pyproject_content)
        result = subprocess.run(
            ["uv", "lock"],
            capture_output=True,
            text=True,
            cwd=str(tmp_path),
            timeout=120,
        )
        lockfile = ""
        lock_path = tmp_path / "uv.lock"
        if lock_path.exists():
            lockfile = lock_path.read_text()
        return result, lockfile


# ==================================================================
# PEP 691 JSON structure (direct HTTP validation)
# ==================================================================

def _fetch_json_index(package: str) -> dict:
    """Fetch PEP 691 JSON index for a package from the Pantera group."""
    import requests as req
    resp = req.get(
        f"{PANTERA_GROUP}{package}/",
        headers={"Accept": "application/vnd.pypi.simple.v1+json"},
        timeout=10,
    )
    assert resp.status_code == 200, f"Expected 200 for {package}, got {resp.status_code}"
    ctype = resp.headers.get("Content-Type", "")
    assert "application/vnd.pypi.simple.v1+json" in ctype, (
        f"Content-Type must be PEP 691 JSON for {package}, got: {ctype}"
    )
    return resp.json()


def test_hosted_json_index_structure():
    """PEP 691 structure for a hosted package (hello)."""
    data = _fetch_json_index("hello")
    assert data["meta"]["api-version"] == "1.1"
    assert data["name"] == "hello"
    assert len(data["files"]) > 0
    for f in data["files"]:
        assert not f["url"].startswith("//"), f"protocol-relative URL: {f['url']}"
        assert "sha256" in f.get("hashes", {}), f"missing sha256: {f['filename']}"
        yanked = f.get("yanked")
        assert yanked is False or isinstance(yanked, str)


def test_proxied_json_index_structure():
    """PEP 691 structure for a proxied package (requests)."""
    data = _fetch_json_index("requests")
    assert data["meta"]["api-version"] in ("1.0", "1.1")
    assert data["name"] == "requests"
    assert len(data["files"]) > 0
    for f in data["files"]:
        assert "sha256" in f.get("hashes", {})


def test_proxied_json_has_upload_time():
    """
    The proxied JSON for 'requests' must include upload-time (PEP 700)
    on at least some files. Without this, exclude-newer silently does
    nothing through the proxy.
    """
    data = _fetch_json_index("requests")
    files_with_upload_time = [f for f in data["files"] if f.get("upload-time")]
    assert len(files_with_upload_time) > 0, (
        "Proxied JSON must preserve upstream upload-time (PEP 700). "
        "None of the 'requests' files have upload-time."
    )


# ==================================================================
# PEP 700 exclude-newer: PROXIED package
#
# A successful EXCLUSION proves the mechanism works end-to-end:
#   uv → Pantera proxy → upstream PyPI JSON → upload-time parsed → cutoff applied
# ==================================================================

def test_exclude_newer_pins_proxied_package_version():
    """
    requests 2.28.0 published 2022-06-29, 2.28.1 published 2022-08-29.
    With cutoff 2022-07-01, uv must resolve exactly 2.28.0.

    If the proxy doesn't forward upload-time, uv resolves latest and
    the version check fails.
    """
    result, lockfile = _uv_lock_and_read(
        '[project]\n'
        'name = "test-proxy-pin"\n'
        'version = "0.1.0"\n'
        'requires-python = ">=3.10"\n'
        'dependencies = ["requests>=2.28.0"]\n'
        '\n'
        '[build-system]\n'
        'requires = ["hatchling"]\n'
        'build-backend = "hatchling.build"\n'
        '\n'
        '[tool.uv]\n'
        f'index-url = "{PANTERA_GROUP}"\n'
        'exclude-newer = "2022-07-01T00:00:00Z"\n'
    )
    assert result.returncode == 0, (
        f"uv lock with exclude-newer 2022-07-01 failed:\n{result.stderr}"
    )
    match = re.search(
        r'name\s*=\s*"requests"\s*\n\s*version\s*=\s*"([^"]+)"',
        lockfile,
    )
    assert match, "requests not found in lockfile"
    assert match.group(1) == "2.28.0", (
        f"Expected requests==2.28.0 (published 2022-06-29, cutoff "
        f"2022-07-01), got {match.group(1)}. Proxy is not forwarding "
        f"upload-time."
    )


def test_exclude_newer_rejects_future_proxied_version():
    """
    Set cutoff to 2020-01-01 — before requests 2.28.0 existed.
    uv must fail or resolve an older version. This proves the
    cutoff actually filters, not just that it's ignored.
    """
    result, lockfile = _uv_lock_and_read(
        '[project]\n'
        'name = "test-proxy-reject"\n'
        'version = "0.1.0"\n'
        'requires-python = ">=3.10"\n'
        'dependencies = ["requests>=2.28.0"]\n'
        '\n'
        '[build-system]\n'
        'requires = ["hatchling"]\n'
        'build-backend = "hatchling.build"\n'
        '\n'
        '[tool.uv]\n'
        f'index-url = "{PANTERA_GROUP}"\n'
        'exclude-newer = "2020-01-01T00:00:00Z"\n'
    )
    # Must fail: requests>=2.28.0 didn't exist before 2020-01-01
    assert result.returncode != 0, (
        "uv lock should FAIL: requests>=2.28.0 was not published before "
        "2020-01-01. If it succeeded, exclude-newer is not working."
    )


# ==================================================================
# PEP 700 exclude-newer: HOSTED package
#
# hello was uploaded to Pantera ~today. Setting cutoff 30 days in
# the past must cause uv to reject it — proving Pantera writes
# upload-time on hosted uploads.
# ==================================================================

def test_exclude_newer_rejects_recent_hosted_package():
    """
    Set cutoff to 30 days before today. hello was uploaded today,
    so uv must reject it. A successful exclusion proves Pantera
    writes upload-time in the hosted JSON index.
    """
    from datetime import datetime, timedelta, timezone
    cutoff = (datetime.now(timezone.utc) - timedelta(days=30)).strftime(
        "%Y-%m-%dT00:00:00Z"
    )
    result = _uv_lock(
        '[project]\n'
        'name = "test-hosted-reject"\n'
        'version = "0.1.0"\n'
        'requires-python = ">=3.10"\n'
        'dependencies = ["hello>=0.2.0"]\n'
        '\n'
        '[build-system]\n'
        'requires = ["hatchling"]\n'
        'build-backend = "hatchling.build"\n'
        '\n'
        '[tool.uv]\n'
        f'index-url = "{PANTERA_GROUP}"\n'
        f'exclude-newer = "{cutoff}"\n'
    )
    assert result.returncode != 0, (
        f"uv lock should FAIL: hello was uploaded recently but cutoff "
        f"is {cutoff}. If it succeeded, upload-time is missing from "
        f"the hosted JSON."
    )
    stderr_lower = result.stderr.lower()
    assert "exclude-newer" in stderr_lower or "filtered" in stderr_lower, (
        f"uv should mention exclude-newer in the error:\n{result.stderr}"
    )


def test_exclude_newer_accepts_hosted_with_future_cutoff():
    """
    Set cutoff to tomorrow. hello was uploaded today, so it passes
    the cutoff and uv must resolve it successfully.
    """
    from datetime import datetime, timedelta, timezone
    cutoff = (datetime.now(timezone.utc) + timedelta(days=1)).strftime(
        "%Y-%m-%dT00:00:00Z"
    )
    result = _uv_lock(
        '[project]\n'
        'name = "test-hosted-accept"\n'
        'version = "0.1.0"\n'
        'requires-python = ">=3.10"\n'
        'dependencies = ["hello>=0.2.0"]\n'
        '\n'
        '[build-system]\n'
        'requires = ["hatchling"]\n'
        'build-backend = "hatchling.build"\n'
        '\n'
        '[tool.uv]\n'
        f'index-url = "{PANTERA_GROUP}"\n'
        f'exclude-newer = "{cutoff}"\n'
    )
    assert result.returncode == 0, (
        f"uv lock should succeed: hello was uploaded before cutoff "
        f"{cutoff}.\nstderr: {result.stderr}"
    )
