# Package Manager Client Behavior Analysis

**Date:** November 23, 2024  
**Purpose:** Detailed analysis of how official clients for each package manager handle metadata, caching, and version resolution

---

## 1. NPM Client Behavior

### Metadata Request Flow

**Endpoint:** `GET /{package}` (e.g., `GET /lodash`)

**Response Format:** JSON
```json
{
  "name": "lodash",
  "dist-tags": {
    "latest": "4.17.21"
  },
  "versions": {
    "4.17.21": {
      "name": "lodash",
      "version": "4.17.21",
      "dist": {
        "tarball": "https://registry.npmjs.org/lodash/-/lodash-4.17.21.tgz",
        "shasum": "679591c564c3bffaae8454cf0b3df370c3d6911c",
        "integrity": "sha512-v2kDEe57lecTulaDIuNTPy3Ry4gLGJ6Z1O3vE1krgXZNrsQ+LFTGHVxVjcXPs17LhbZVGedAJv8XZ1tvj5FvSg=="
      }
    },
    "4.17.20": { /* ... */ }
  },
  "time": {
    "4.17.21": "2021-02-20T16:06:50.000Z",
    "4.17.20": "2020-02-18T21:20:08.000Z"
  }
}
```

### Client Behavior

1. **Version Resolution:**
   - Client parses `versions` object to get available versions
   - Applies semver range matching (e.g., `^4.17.0` matches `4.17.21`)
   - Selects highest matching version
   - Writes selected version to `package-lock.json`

2. **Download:**
   - Reads `dist.tarball` URL from metadata
   - Downloads tarball
   - Verifies `dist.integrity` (SHA512 hash)
   - Extracts tarball
   - **Validates `package.json` inside tarball matches expected version**

3. **Caching:**
   - Metadata cached in `~/.npm/_cacache` for 5 minutes (default)
   - Respects `Cache-Control` headers
   - Tarballs cached indefinitely (content-addressed by integrity hash)

### Critical Requirements for Metadata Filtering

**Must Do:**
- ✅ Remove blocked versions from `versions` object
- ✅ Remove blocked versions from `time` object
- ✅ Update `dist-tags.latest` if latest version is blocked
- ✅ Preserve `dist.integrity` hashes for remaining versions
- ✅ Serve consistent `Cache-Control` headers

**Must Not Do:**
- ❌ Serve tarball with different version than metadata claims
- ❌ Modify integrity hashes
- ❌ Change version numbers inside tarballs

---

## 2. PyPI Client Behavior

### Metadata Request Flow

**Endpoint:** `GET /simple/{package}/` (e.g., `GET /simple/requests/`)

**Response Format:** HTML (Simple Repository API)
```html
<!DOCTYPE html>
<html>
  <body>
    <h1>Links for requests</h1>
    <a href="https://files.pythonhosted.org/packages/.../requests-2.28.0.tar.gz#sha256=abc123...">requests-2.28.0.tar.gz</a><br/>
    <a href="https://files.pythonhosted.org/packages/.../requests-2.27.1.tar.gz#sha256=def456...">requests-2.27.1.tar.gz</a><br/>
  </body>
</html>
```

**Alternative:** JSON API (PEP 691)
```json
{
  "meta": {"api-version": "1.0"},
  "name": "requests",
  "files": [
    {
      "filename": "requests-2.28.0.tar.gz",
      "url": "https://files.pythonhosted.org/packages/.../requests-2.28.0.tar.gz",
      "hashes": {"sha256": "abc123..."}
    }
  ]
}
```

### Client Behavior

1. **Version Resolution:**
   - Client parses HTML or JSON to get available files
   - Extracts version from filename (e.g., `requests-2.28.0.tar.gz` → `2.28.0`)
   - Applies PEP 440 version specifiers (e.g., `>=2.27,<3.0`)
   - Selects highest matching version

2. **Download:**
   - Downloads file from URL
   - **Verifies SHA256 hash from URL fragment or JSON**
   - Extracts archive
   - Reads `PKG-INFO` or `METADATA` to verify version

3. **Hash Verification:**
   - `pip install --require-hashes` mode **requires** exact hash match
   - Hash mismatch causes immediate failure
   - No fallback or retry

4. **Caching:**
   - Metadata cached for 10 minutes (default)
   - Wheels/tarballs cached in `~/.cache/pip`
   - Cache keyed by URL + hash

### Critical Requirements for Metadata Filtering

**Must Do:**
- ✅ Remove `<a>` tags for blocked versions (HTML format)
- ✅ Remove entries from `files` array (JSON format)
- ✅ Preserve exact SHA256 hashes for remaining versions
- ✅ Ensure filename matches version in archive metadata

**Must Not Do:**
- ❌ Serve file with different hash than metadata claims
- ❌ Modify version in archive metadata
- ❌ Change filenames

---

## 3. Maven Client Behavior

### Metadata Request Flow

**Endpoint:** `GET /{group}/{artifact}/maven-metadata.xml`

**Response Format:** XML
```xml
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>org.springframework</groupId>
  <artifactId>spring-core</artifactId>
  <versioning>
    <latest>6.0.0</latest>
    <release>6.0.0</release>
    <versions>
      <version>6.0.0</version>
      <version>5.3.23</version>
      <version>5.3.22</version>
    </versions>
    <lastUpdated>20221116120000</lastUpdated>
  </versioning>
</metadata>
```

### Client Behavior

1. **Version Resolution:**
   - Client parses `<versions>` to get available versions
   - Applies version range (e.g., `[5.3,6.0)`)
   - Selects highest matching version
   - Writes to `pom.xml` or uses from dependency

2. **Download:**
   - Downloads JAR: `GET /{group}/{artifact}/{version}/{artifact}-{version}.jar`
   - Downloads POM: `GET /{group}/{artifact}/{version}/{artifact}-{version}.pom`
   - Downloads checksums: `.sha1`, `.md5` files
   - **Verifies checksums**

3. **Caching:**
   - Metadata cached in `~/.m2/repository`
   - Default update policy: `daily`
   - Can be configured: `always`, `never`, `interval:X`
   - Checksums verified on every download

### Critical Requirements for Metadata Filtering

**Must Do:**
- ✅ Remove `<version>` elements for blocked versions
- ✅ Update `<latest>` and `<release>` if blocked
- ✅ Update `<lastUpdated>` timestamp
- ✅ Preserve XML structure and encoding

**Must Not Do:**
- ❌ Serve JAR with different version than metadata claims
- ❌ Modify checksums
- ❌ Change version in POM inside JAR

---

## 4. Gradle Client Behavior

### Metadata Request Flow

**Same as Maven** - Gradle uses Maven repository format

**Additional:** Gradle Module Metadata (`.module` files)
```json
{
  "formatVersion": "1.1",
  "component": {
    "group": "org.springframework",
    "module": "spring-core",
    "version": "6.0.0"
  },
  "variants": [...]
}
```

### Client Behavior

1. **Version Resolution:**
   - Tries Gradle Module Metadata first (`.module` file)
   - Falls back to Maven metadata (`maven-metadata.xml`)
   - Applies version constraints (e.g., `5.3.+`)
   - Selects highest matching version

2. **Download:**
   - Same as Maven
   - Additionally verifies `.module` file if present

3. **Caching:**
   - Cached in `~/.gradle/caches`
   - Default: 24 hour cache for dynamic versions
   - Checksums verified

### Critical Requirements for Metadata Filtering

**Must Do:**
- ✅ Same as Maven
- ✅ Additionally filter `.module` files if present
- ✅ Keep Maven metadata and Gradle metadata consistent

---

## 5. Composer Client Behavior

### Metadata Request Flow

**Endpoint:** `GET /p2/{vendor}/{package}.json` (Packagist v2 API)

**Response Format:** JSON
```json
{
  "packages": {
    "vendor/package": {
      "2.0.0": {
        "name": "vendor/package",
        "version": "2.0.0",
        "dist": {
          "type": "zip",
          "url": "https://api.github.com/repos/vendor/package/zipball/abc123",
          "reference": "abc123",
          "shasum": "def456"
        },
        "time": "2023-01-15T10:00:00+00:00"
      },
      "1.9.0": { /* ... */ }
    }
  }
}
```

### Client Behavior

1. **Version Resolution:**
   - Client parses `packages` object
   - Applies version constraints (e.g., `^2.0`)
   - Selects highest matching version
   - Writes to `composer.lock` with exact version and hash

2. **Download:**
   - Downloads ZIP from `dist.url`
   - Verifies `dist.shasum` (SHA1 hash)
   - Extracts ZIP
   - **Validates `composer.json` inside ZIP matches expected version**

3. **Caching:**
   - Metadata cached for 6 hours (default)
   - ZIPs cached in `~/.composer/cache`
   - Lock file ensures reproducible installs

### Critical Requirements for Metadata Filtering

**Must Do:**
- ✅ Remove blocked versions from `packages` object
- ✅ Preserve `dist.shasum` for remaining versions
- ✅ Preserve `time` field for remaining versions

**Must Not Do:**
- ❌ Serve ZIP with different version than metadata claims
- ❌ Modify shasum hashes
- ❌ Change version in `composer.json` inside ZIP

---

## 6. Go Client Behavior

### Metadata Request Flow

**Endpoint:** `GET /{module}/@v/list` (e.g., `GET /github.com/google/uuid/@v/list`)

**Response Format:** Plain text (one version per line)
```
v1.3.0
v1.2.0
v1.1.1
```

**Version Info:** `GET /{module}/@v/{version}.info`
```json
{
  "Version": "v1.3.0",
  "Time": "2022-08-16T20:17:57Z"
}
```

### Client Behavior

1. **Version Resolution:**
   - Client fetches `list` to get available versions
   - Applies version constraints from `go.mod` (e.g., `>=v1.2.0`)
   - Selects highest matching version
   - Writes to `go.mod` and `go.sum`

2. **Download:**
   - Downloads `.mod` file: `GET /{module}/@v/{version}.mod`
   - Downloads `.zip` file: `GET /{module}/@v/{version}.zip`
   - Downloads `.info` file: `GET /{module}/@v/{version}.info`
   - **Verifies hash in `go.sum`**

3. **Hash Verification:**
   - `go.sum` contains cryptographic hash of `.mod` and `.zip`
   - Hash mismatch causes build failure
   - No fallback or retry

4. **Caching:**
   - Cached in `$GOPATH/pkg/mod/cache`
   - Immutable once cached
   - Checksums verified on every build

### Critical Requirements for Metadata Filtering

**Must Do:**
- ✅ Remove blocked versions from `list` response
- ✅ Return 404 for `.info`, `.mod`, `.zip` of blocked versions
- ✅ Preserve exact hashes for remaining versions

**Must Not Do:**
- ❌ Serve `.zip` with different version than `list` claims
- ❌ Modify hashes in `go.sum`
- ❌ Change version in `.mod` file

---

## Summary: Common Patterns

### All Package Managers Follow This Flow

```
1. Fetch Metadata → 2. Parse Versions → 3. Select Version → 4. Download → 5. Verify Hash → 6. Install
```

### Critical Insight

**Metadata filtering MUST happen at step 1**, not step 4.

If we filter at step 4 (download), steps 2-3 have already happened with wrong information, causing:
- Version mismatch errors
- Hash verification failures
- Corrupted lock files
- Build failures

### Metadata Filtering Requirements (All Package Managers)

| Requirement | NPM | PyPI | Maven | Gradle | Composer | Go |
|------------|-----|------|-------|--------|----------|-----|
| Remove blocked versions from metadata | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Preserve hashes for remaining versions | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Update "latest" tag if blocked | ✅ | N/A | ✅ | ✅ | N/A | N/A |
| Serve consistent Cache-Control headers | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Return 404 for blocked version downloads | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

---

## Next Steps

1. Design metadata parsing strategy for each format (JSON/XML/HTML/Text)
2. Design metadata filtering algorithm
3. Design metadata rewriting strategy
4. Design cache invalidation on block/unblock events
5. Implement and test with real clients

