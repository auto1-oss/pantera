# PyPI PEP 503/691 Full Compliance Design

**Date:** 2026-04-02
**Status:** Approved
**Author:** Ayd Asraf + Claude

---

## Problem Statement

Pantera's PyPI adapter has two compliance gaps that break modern tooling:

1. **No PEP 691 JSON Simple API** — `uv lock --exclude-newer` fails because `upload-time` is only available in the PEP 691 JSON format (PEP 700). Pantera only serves PEP 503 HTML. Clients like `uv` and modern `pip` prefer JSON but get HTML without upload timestamps.

2. **Hosted repos missing PEP 503 attributes** — `SliceIndex.java` and `IndexGenerator.java` generate bare `<a href="...#sha256=...">` links. Missing: `data-requires-python` (PEP 503), `data-yanked` (PEP 592), `data-dist-info-metadata` (PEP 658).

Proxy repos partially comply — `ProxySlice.java` preserves upstream HTML attributes during URL rewriting — but don't serve JSON format.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| JSON for proxy | Fetch JSON from upstream, rewrite URLs, cache | Upstream already has all fields including upload-time |
| Content negotiation | Accept header only | PEP 691 standard; no query param fallback |
| Hosted repo metadata | Sidecar JSON files in storage | No DB dependency, works for YAML-only deployments |
| Metadata extraction | From wheel/sdist at upload time | Reuse existing `MetadataFromArchive` |
| Yank support | Full API (yank + unyank endpoints) | Complete PEP 592 compliance |
| Existing packages | Graceful degradation, no backfill required | Attributes omitted when sidecar missing |

---

## 1. PEP 691 JSON for Proxy Repos

### Flow

1. Client requests `GET /{repo}/simple/{package}/` with `Accept: application/vnd.pypi.simple.v1+json`
2. Pantera checks Accept header — JSON requested → serve JSON; otherwise → HTML (default)
3. For proxy repos, Pantera fetches JSON from upstream PyPI with `Accept: application/vnd.pypi.simple.v1+json`
4. URLs in JSON rewritten from `files.pythonhosted.org` to Pantera's proxy URL
5. Both HTML and JSON cached separately (existing HTML cache key + new JSON cache key suffix)
6. Subsequent requests served from cache in requested format

### JSON Response Structure (PEP 691 v1.1 + PEP 700)

```json
{
  "meta": {"api-version": "1.1"},
  "name": "pydantic",
  "files": [
    {
      "filename": "pydantic-2.12.5.tar.gz",
      "url": "https://pantera.local/pypi-proxy/pydantic/pydantic-2.12.5.tar.gz#sha256=...",
      "hashes": {"sha256": "abc123..."},
      "requires-python": ">=3.8",
      "upload-time": "2024-12-15T10:30:00.000000Z",
      "yanked": false,
      "data-dist-info-metadata": {"sha256": "def456..."}
    }
  ]
}
```

### Files Modified

- `ProxySlice.java` — add JSON URL rewriting method, content negotiation logic in request handling
- `CachedPyProxySlice.java` — cache JSON responses with separate cache key
- `SliceIndex.java` — Accept header routing to choose HTML vs JSON code path

---

## 2. PEP 503 Full Compliance for Hosted Repos (HTML)

### Current State

```html
<a href="pydantic-2.12.5.tar.gz#sha256=abc123">pydantic-2.12.5.tar.gz</a>
```

### New State

```html
<a href="pydantic-2.12.5.tar.gz#sha256=abc123"
   data-requires-python="&gt;=3.8"
   data-yanked=""
   data-dist-info-metadata="sha256=def456">pydantic-2.12.5.tar.gz</a>
```

### Metadata Sources

| Attribute | Source |
|-----------|--------|
| `data-requires-python` | Extracted from wheel `METADATA` / sdist `PKG-INFO` at upload time |
| `data-yanked` | Set via yank API endpoint |
| `data-dist-info-metadata` | Hash of `.metadata` file if it exists alongside the artifact |
| `upload-time` | Timestamp when file was uploaded to Pantera (stored in sidecar, JSON-only) |

### Sidecar Metadata Files

Stored at `.pypi/metadata/{package}/{filename}.json`:

```json
{
  "requires-python": ">=3.8",
  "upload-time": "2026-04-01T10:30:00.000000Z",
  "yanked": false,
  "yanked-reason": null,
  "dist-info-metadata": {"sha256": "def456..."}
}
```

- Created at upload time by extracting from the package archive
- `upload-time` = `Instant.now()` at upload (ISO 8601, UTC, PEP 700 format)
- `yanked` / `yanked-reason` = updated by yank API
- Existing packages without sidecar files: attributes gracefully omitted (backward compatible)

### Files Modified

- `SliceIndex.java` — read sidecar metadata, include attributes in HTML anchor tags
- `IndexGenerator.java` — same enrichment for persistent index generation
- Upload path (`WheelSlice.java`, `PySlice.java`) — create sidecar on upload

---

## 3. PEP 691 JSON for Hosted Repos

Same content negotiation as proxy repos. When client requests JSON:

1. `SliceIndex.java` checks Accept header
2. JSON requested → iterate stored artifacts, read sidecar metadata, build PEP 691 JSON
3. HTML requested → enriched HTML path from Section 2

### JSON Structure

```json
{
  "meta": {"api-version": "1.1"},
  "name": "my-internal-lib",
  "files": [
    {
      "filename": "my_internal_lib-1.0.0-py3-none-any.whl",
      "url": "/pypi-hosted/my-internal-lib/1.0.0/my_internal_lib-1.0.0-py3-none-any.whl",
      "hashes": {"sha256": "abc123..."},
      "requires-python": ">=3.10",
      "upload-time": "2026-03-15T14:22:00.000000Z",
      "yanked": false,
      "data-dist-info-metadata": {"sha256": "def456..."}
    }
  ]
}
```

Missing sidecar metadata → fields omitted (all optional per PEP 691).

No new files — extension of `SliceIndex.java` with JSON rendering path.

---

## 4. Yank API for Hosted Repos

### New Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/pypi/{repo}/{package}/{version}/yank` | POST | Yank a specific version |
| `/api/v1/pypi/{repo}/{package}/{version}/unyank` | POST | Unyank a specific version |

### Request Body (yank)

```json
{
  "reason": "Security vulnerability CVE-2026-XXXX"
}
```

`reason` is optional per PEP 592.

### Behavior

1. Find all artifact files for `{package}/{version}` in the repo storage
2. Update each file's sidecar metadata: `"yanked": true, "yanked-reason": "..."`
3. Next index request picks up the change (both HTML and JSON)

**Unyank:** Same flow, sets `"yanked": false, "yanked-reason": null`.

**Authorization:** Requires write permission on the repository (same as upload/delete).

### Files

- New: `pantera-main/src/main/java/com/auto1/pantera/api/v1/PypiHandler.java` — yank/unyank endpoints
- Register in `AsyncApiVerticle.java`

---

## 5. Yank/Unyank UI

### Repository Browser Integration

In the existing artifact browser (`ArtifactView` / repository detail page), when viewing a PyPI hosted repo:

- Each package version row shows a **"Yanked"** tag (PrimeVue `Tag`, severity `danger`) if the version is yanked
- A **"Yank"** button (icon: `pi pi-ban`, severity `danger`, text style) appears on each version row for users with write permission
- Clicking "Yank" opens a confirmation dialog:
  - Title: "Yank {package} {version}"
  - Optional textarea: "Reason (optional)" — pre-filled with empty string
  - Cancel / Yank buttons
- After yanking, the row updates to show the "Yanked" tag and the button changes to "Unyank" (icon: `pi pi-undo`)
- "Unyank" has a simpler confirmation: "Unyank {package} {version}? This will make it available to installers again."

### API Functions

New in `pantera-ui/src/api/pypi.ts`:

```typescript
export async function yankVersion(repo: string, pkg: string, version: string, reason?: string): Promise<void>
export async function unyankVersion(repo: string, pkg: string, version: string): Promise<void>
```

### Files Modified

- New: `pantera-ui/src/api/pypi.ts` — yank/unyank API functions
- Modified: artifact browser view — yank/unyank buttons, yanked tag, confirmation dialog

---

## 6. Upload Path Changes (Backend)

### Current Flow

Package uploaded → stored → index regenerated with bare links.

### New Flow

1. Package uploaded (`.whl` or `.tar.gz`)
2. **Extract metadata** using existing `MetadataFromArchive`:
   - `Requires-Python` from `METADATA` (wheel) or `PKG-INFO` (sdist)
3. **Compute dist-info-metadata hash** if `.metadata` sidecar exists
4. **Write sidecar file** to `.pypi/metadata/{package}/{filename}.json`:
   ```json
   {
     "requires-python": ">=3.8",
     "upload-time": "2026-04-01T10:30:00.000000Z",
     "yanked": false,
     "yanked-reason": null,
     "dist-info-metadata": null
   }
   ```
5. Store artifact as before
6. Index regeneration reads sidecar → rich HTML/JSON

### Existing Packages (One-Time Migration)

A one-time migration CLI command backfills sidecar metadata for all existing packages in hosted PyPI repos:

**Command:** `java -jar pantera-backfill.jar pypi-metadata --storage <path> --repos <repo1,repo2,...>`

**Behavior:**
1. Scans each hosted PyPI repo's storage for `.whl` and `.tar.gz` files
2. For each file without a sidecar at `.pypi/metadata/{package}/{filename}.json`:
   - Extracts `Requires-Python` from wheel `METADATA` / sdist `PKG-INFO` using `MetadataFromArchive`
   - Sets `upload-time` to the file's last-modified timestamp from storage (best available approximation)
   - Sets `yanked: false`, `dist-info-metadata: null`
   - Writes the sidecar JSON file
3. Reports: `Processed N files, created M sidecars, skipped K (already exists)`
4. Supports `--dry-run` flag to preview without writing

**Implementation:** New command in `pantera-backfill` module (alongside existing `BatchInserter`). Reuses `MetadataFromArchive` from pypi-adapter.

Without the migration, existing packages still work — attributes are gracefully omitted from index responses. The migration is recommended for full PEP compliance on existing hosted repos.

### Files Modified

- `WheelSlice.java` — create sidecar after successful upload
- `PySlice.java` — same for sdist uploads
- Reuse `MetadataFromArchive` (already exists, parses `Requires-Python`)

---

## 7. Content Negotiation

### Accept Header Routing

| Accept Header | Response |
|---------------|----------|
| `application/vnd.pypi.simple.v1+json` | PEP 691 JSON with `Content-Type: application/vnd.pypi.simple.v1+json` |
| `application/vnd.pypi.simple.v1+html` | PEP 503 HTML with `Content-Type: application/vnd.pypi.simple.v1+html` |
| `text/html` | PEP 503 HTML with `Content-Type: text/html` |
| `*/*` or missing | PEP 503 HTML (backward compatible default) |
| Malformed | PEP 503 HTML |

### Implementation

Content negotiation logic added to `SliceIndex` (hosted) and `ProxySlice` (proxy) at the request entry point. Single method `negotiateFormat(Request)` returns `HTML` or `JSON` enum.

---

## 8. Testing Strategy

### Unit Tests

- `SliceIndexTest` — HTML output includes all PEP 503 attributes when sidecar exists; attributes omitted when sidecar missing
- `SliceIndexJsonTest` — PEP 691 JSON output structure, all fields present, correct `api-version: "1.1"`
- `ProxySliceJsonTest` — JSON URL rewriting preserves all fields including `upload-time`
- `ContentNegotiationTest` — Accept header routing to HTML vs JSON

### Integration Tests

- Upload `.whl` → verify sidecar created with correct `requires-python` and `upload-time`
- Upload `.tar.gz` → verify sidecar created from `PKG-INFO`
- Yank/unyank via API → verify sidecar updated, HTML includes `data-yanked`, JSON includes `"yanked": true`
- Proxy fetch JSON → verify cached and served with rewritten URLs
- `uv lock --exclude-newer` against Pantera proxy → resolves without warnings (end-to-end)
- Yank via UI → verify "Yanked" tag appears, unyank removes it
- Yank via API → verify HTML `data-yanked` and JSON `"yanked": true`

### Edge Cases

- Package without `Requires-Python` in metadata → attribute omitted (not empty string)
- Existing packages with no sidecar → graceful degradation, no errors
- Client sends `Accept: */*` → serve HTML (backward compatible default)
- Client sends `Accept: application/vnd.pypi.simple.v1+json` → serve JSON
- Malformed Accept header → serve HTML
- Upstream PyPI doesn't support JSON (possible for mirrors) → fall back to HTML-only caching
- Yanked package with no reason → `data-yanked=""` in HTML, `"yanked": true` with no `"yanked-reason"` in JSON
