# NPM CLI Command Compatibility Matrix

Complete reference for NPM CLI commands across Artipie repository types.

---

## 📊 Quick Reference

| Command | Local | Proxy | Group | Notes |
|---------|-------|-------|-------|-------|
| **Authentication** |
| `npm adduser` | ✅ | ✅ | ✅ | Keycloak integration |
| `npm login` | ✅ | ✅ | ✅ | Alias for adduser |
| `npm whoami` | ✅ | ✅ | ✅ | Returns authenticated user |
| `npm logout` | ⚠️ | ⚠️ | ⚠️ | Client-side only (deletes ~/.npmrc token) |
| **Package Management** |
| `npm install <pkg>` | ✅ | ✅ | ✅ | Downloads from repo |
| `npm ci` | ✅ | ✅ | ✅ | Clean install from lock file |
| `npm update` | ✅ | ✅ | ✅ | Updates packages |
| `npm uninstall <pkg>` | ✅ | ✅ | ✅ | Local operation (client-side) |
| **Publishing** |
| `npm publish` | ✅ | ❌ | ❌ | Only to local repos |
| `npm unpublish` | ✅ | ❌ | ❌ | Only from local repos |
| `npm deprecate` | ✅ | ❌ | ❌ | Only on local repos |
| **Information** |
| `npm view <pkg>` | ✅ | ✅ | ✅ | Shows package metadata |
| `npm show <pkg>` | ✅ | ✅ | ✅ | Alias for view |
| `npm info <pkg>` | ✅ | ✅ | ✅ | Alias for view |
| `npm search <query>` | ✅ | ✅ | ✅ | Searches packages |
| `npm outdated` | ✅ | ✅ | ✅ | Shows outdated packages |
| **Security** |
| `npm audit` | ✅ | ✅ | ✅ | Security vulnerability scan |
| `npm audit fix` | ✅ | ✅ | ✅ | Auto-fix vulnerabilities |
| **Dist Tags** |
| `npm dist-tag add` | ✅ | ❌ | ❌ | Only on local repos |
| `npm dist-tag rm` | ✅ | ❌ | ❌ | Only on local repos |
| `npm dist-tag ls` | ✅ | ✅ | ✅ | Read-only operation |
| **Other** |
| `npm pack` | ✅ | ✅ | ✅ | Downloads and creates tarball |
| `npm ping` | ✅ | ✅ | ✅ | Checks registry connectivity |

---

## 📖 Detailed Command Reference

### 🔐 Authentication Commands

#### `npm adduser` / `npm login`
**Purpose:** Authenticate with the registry

**Local Repository:**
```bash
npm adduser --registry=http://localhost:8081/npm
# ✅ Authenticates with Artipie/Keycloak
# ✅ Stores token to /_tokens/
# ✅ Returns NPM token in ~/.npmrc
```

**Proxy Repository:**
```bash
npm adduser --registry=http://localhost:8081/npm_proxy
# ✅ Authenticates with Artipie/Keycloak (not upstream)
# ✅ Token stored locally in Artipie
# ⚠️ Does NOT authenticate with upstream registry
```

**Group Repository:**
```bash
npm adduser --registry=http://localhost:8081/npm_group
# ✅ Forwards to FIRST member (typically local repo)
# ✅ Authenticates with Artipie/Keycloak
# ✅ Token valid for all operations
```

---

#### `npm whoami`
**Purpose:** Display authenticated username

**Local Repository:**
```bash
npm whoami --registry=http://localhost:8081/npm
# ✅ Returns: ayd
```

**Proxy Repository:**
```bash
npm whoami --registry=http://localhost:8081/npm_proxy
# ✅ Returns: ayd (from local token, not upstream)
```

**Group Repository:**
```bash
npm whoami --registry=http://localhost:8081/npm_group
# ✅ Forwards to first member
# ✅ Returns: ayd
```

---

#### `npm logout`
**Purpose:** Remove authentication token

**All Repository Types:**
```bash
npm logout --registry=http://localhost:8081/npm
# ⚠️ Client-side only: Deletes token from ~/.npmrc
# ❌ Does NOT delete token from server (/_tokens/)
# Note: Token remains valid on server until manually deleted
```

---

### 📦 Package Management Commands

#### `npm install <package>`
**Purpose:** Install package and dependencies

**Local Repository:**
```bash
npm install express --registry=http://localhost:8081/npm
# ✅ Downloads from local storage
# ❌ Fails with 404 if package not in local repo
```

**Proxy Repository:**
```bash
npm install express --registry=http://localhost:8081/npm_proxy
# ✅ Checks local cache first
# ✅ Downloads from upstream (npmjs.org) if not cached
# ✅ Caches for future requests
# ⚠️ May be slower on first request (upstream fetch)
```

**Group Repository:**
```bash
npm install express --registry=http://localhost:8081/npm_group
# ✅ Tries first member (local)
# ✅ Falls back to second member (proxy) if 404
# ✅ Best of both worlds: fast local + fallback to proxy
```

**Example Group Configuration:**
```yaml
# _server.yaml
npm_group:
  type: npm-group
  members:
    - npm        # Try local first (published packages)
    - npm_proxy  # Then proxy (public packages)
```

---

#### `npm ci`
**Purpose:** Clean install from package-lock.json

**All Repository Types:**
```bash
npm ci --registry=http://localhost:8081/npm_group
# ✅ Same behavior as npm install
# ✅ Uses exact versions from lock file
# ✅ Deletes node_modules before install
```

---

#### `npm update`
**Purpose:** Update packages to latest versions

**All Repository Types:**
```bash
npm update express --registry=http://localhost:8081/npm_group
# ✅ Checks for newer versions
# ✅ Updates within semver ranges
# ✅ Works with local, proxy, and group
```

---

### 🚀 Publishing Commands

#### `npm publish`
**Purpose:** Publish package to registry

**Local Repository:**
```bash
npm publish --registry=http://localhost:8081/npm
# ✅ SUCCESS: Publishes to local repository
# ✅ Stores package tarball
# ✅ Updates metadata at /package-name
# ✅ Triggers artifact events
```

**Proxy Repository:**
```bash
npm publish --registry=http://localhost:8081/npm_proxy
# ❌ 405 Method Not Allowed
# Reason: Proxy repos are read-only aggregators
```

**Group Repository:**
```bash
npm publish --registry=http://localhost:8081/npm_group
# ❌ 405 Method Not Allowed
# Reason: Group repos are read-only aggregators
# Solution: Publish directly to local repo
```

**Best Practice:**
Use `publishConfig` in package.json:
```json
{
  "name": "@mycompany/my-package",
  "version": "1.0.0",
  "publishConfig": {
    "registry": "http://localhost:8081/npm"
  }
}
```
This way:
- `npm install` uses group (defaults from .npmrc)
- `npm publish` uses local (explicit in package.json)

---

#### `npm unpublish`
**Purpose:** Remove package from registry

**Local Repository:**
```bash
npm unpublish my-package@1.0.0 --registry=http://localhost:8081/npm
# ✅ SUCCESS: Removes specific version
npm unpublish my-package --force --registry=http://localhost:8081/npm
# ✅ SUCCESS: Removes entire package
```

**Proxy Repository:**
```bash
npm unpublish my-package --registry=http://localhost:8081/npm_proxy
# ❌ 405 Method Not Allowed
```

**Group Repository:**
```bash
npm unpublish my-package --registry=http://localhost:8081/npm_group
# ❌ 405 Method Not Allowed
```

---

#### `npm deprecate`
**Purpose:** Mark package version as deprecated

**Local Repository:**
```bash
npm deprecate my-package@1.0.0 "Use 2.0.0 instead" --registry=http://localhost:8081/npm
# ✅ SUCCESS: Adds deprecation message to metadata
```

**Proxy/Group Repositories:**
```bash
npm deprecate my-package@1.0.0 "msg" --registry=http://localhost:8081/npm_proxy
# ❌ 405 Method Not Allowed
```

---

### 📋 Information Commands

#### `npm view <package>`
**Purpose:** Display package metadata

**Local Repository:**
```bash
npm view express --registry=http://localhost:8081/npm
# ✅ Shows metadata from local storage
# ❌ 404 if package not in local repo
```

**Proxy Repository:**
```bash
npm view express --registry=http://localhost:8081/npm_proxy
# ✅ Fetches from upstream if not cached
# ✅ Caches metadata
# ✅ Returns complete package information
```

**Group Repository:**
```bash
npm view express --registry=http://localhost:8081/npm_group
# ✅ Tries first member (local)
# ✅ Falls back to second member (proxy)
# ✅ Returns first match found
```

---

#### `npm search <query>`
**Purpose:** Search for packages

**Local Repository:**
```bash
npm search express --registry=http://localhost:8081/npm
# ✅ Searches local packages only
# ✅ Fast (in-memory index)
# ⚠️ Limited to published packages in this repo
```

**Proxy Repository:**
```bash
npm search express --registry=http://localhost:8081/npm_proxy
# ✅ Searches local cache first
# ✅ Falls back to upstream search
# ✅ Merges and deduplicates results
```

**Group Repository:**
```bash
npm search express --registry=http://localhost:8081/npm_group
# ✅ Searches ALL members in parallel
# ✅ Aggregates results from local + proxy
# ✅ Deduplicates by package name
# 🚀 Best option: comprehensive results
```

**Endpoint:** `GET /-/v1/search?text={query}&size=20&from=0`

---

#### `npm outdated`
**Purpose:** Check for outdated packages

**All Repository Types:**
```bash
npm outdated --registry=http://localhost:8081/npm_group
# ✅ Checks installed vs. available versions
# ✅ Works with all repo types
# ✅ Group repos provide most comprehensive results
```

---

### 🔒 Security Commands

#### `npm audit`
**Purpose:** Scan for security vulnerabilities

**Local Repository:**
```bash
npm audit --registry=http://localhost:8081/npm
# ✅ Returns: {} (no vulnerabilities)
# ⚠️ Local repos don't have vulnerability database
# Endpoint: POST /-/npm/v1/security/audits/quick
```

**Proxy Repository:**
```bash
npm audit --registry=http://localhost:8081/npm_proxy
# ✅ Forwards to upstream (npmjs.org)
# ✅ Returns real vulnerability data
# ✅ Caches results for performance
```

**Group Repository:**
```bash
npm audit --registry=http://localhost:8081/npm_group
# ✅ Queries ALL members in parallel
# ✅ Aggregates vulnerability data
# ✅ Deduplicates by vulnerability ID
# 🚀 Most comprehensive: checks local + upstream
```

**Example Response (Proxy/Group):**
```json
{
  "actions": [],
  "advisories": {
    "1179": {
      "id": 1179,
      "title": "Prototype Pollution",
      "severity": "high",
      "module_name": "lodash"
    }
  },
  "metadata": {
    "vulnerabilities": {
      "high": 1
    }
  }
}
```

---

#### `npm audit fix`
**Purpose:** Automatically fix vulnerabilities

**All Repository Types:**
```bash
npm audit fix --registry=http://localhost:8081/npm_group
# ✅ Gets vulnerability data from registry
# ✅ Updates package-lock.json
# ✅ Installs fixed versions
# Note: Fix operation is client-side, uses registry for version info
```

---

### 🏷️ Dist Tag Commands

#### `npm dist-tag add <pkg>@<version> <tag>`
**Purpose:** Add distribution tag to package version

**Local Repository:**
```bash
npm dist-tag add my-package@1.0.0 beta --registry=http://localhost:8081/npm
# ✅ SUCCESS: Adds tag to package metadata
# Endpoint: PUT /-/package/my-package/dist-tags/beta
```

**Proxy/Group Repositories:**
```bash
npm dist-tag add my-package@1.0.0 beta --registry=http://localhost:8081/npm_proxy
# ❌ 405 Method Not Allowed
```

---

#### `npm dist-tag rm <pkg> <tag>`
**Purpose:** Remove distribution tag

**Local Repository:**
```bash
npm dist-tag rm my-package beta --registry=http://localhost:8081/npm
# ✅ SUCCESS: Removes tag
# Endpoint: DELETE /-/package/my-package/dist-tags/beta
```

**Proxy/Group Repositories:**
```bash
npm dist-tag rm my-package beta --registry=http://localhost:8081/npm_proxy
# ❌ 405 Method Not Allowed
```

---

#### `npm dist-tag ls <pkg>`
**Purpose:** List distribution tags

**All Repository Types:**
```bash
npm dist-tag ls my-package --registry=http://localhost:8081/npm_group
# ✅ SUCCESS: Lists all tags
# ✅ Works on local, proxy, and group
# Endpoint: GET /-/package/my-package/dist-tags
```

**Example Output:**
```
latest: 1.0.0
beta: 0.9.0
next: 2.0.0-rc.1
```

---

### 🔧 Other Commands

#### `npm pack <package>`
**Purpose:** Create tarball from package

**All Repository Types:**
```bash
npm pack express --registry=http://localhost:8081/npm_group
# ✅ Downloads package metadata
# ✅ Downloads tarball
# ✅ Creates local .tgz file
# ✅ Works with all repo types
```

---

#### `npm ping`
**Purpose:** Test registry connectivity

**All Repository Types:**
```bash
npm ping --registry=http://localhost:8081/npm
# ✅ Returns: Ping success
# Endpoint: GET /-/ping or GET /npm
```

---

## 🎯 Recommended Setup

### Configuration for Development Team

**1. Global NPM Configuration (~/.npmrc):**
```ini
# Use group registry for all operations
registry=http://localhost:8081/npm_group

# Authentication token (set by npm adduser)
//localhost:8081/npm_group/:_authToken=<your-token>
```

**2. Project Configuration (package.json):**
```json
{
  "name": "@mycompany/my-package",
  "version": "1.0.0",
  "publishConfig": {
    "registry": "http://localhost:8081/npm"
  }
}
```

**3. Artipie Repository Configuration (_server.yaml):**
```yaml
repo:
  # Local hosted repository (for internal packages)
  npm:
    type: npm
    storage:
      type: fs
      path: /var/artipie/data/npm

  # Proxy to npmjs.org (for public packages)
  npm_proxy:
    type: npm-proxy
    remotes:
      - url: https://registry.npmjs.org
    storage:
      type: fs
      path: /var/artipie/data/npm_proxy

  # Group aggregator (primary for developers)
  npm_group:
    type: npm-group
    members:
      - npm        # Try internal packages first
      - npm_proxy  # Fall back to public packages
```

---

## 📊 Command Flow Examples

### Example 1: Install Public Package (express)

**Using Group Registry:**
```
npm install express --registry=http://localhost:8081/npm_group
    ↓
Group: Try npm (local)
    ↓
Local: 404 Not Found
    ↓
Group: Try npm_proxy
    ↓
Proxy: Check cache → Not found
    ↓
Proxy: Fetch from npmjs.org → Success
    ↓
Proxy: Cache locally
    ↓
Return to client: express@4.18.2
```

### Example 2: Install Internal Package (@mycompany/auth)

**Using Group Registry:**
```
npm install @mycompany/auth --registry=http://localhost:8081/npm_group
    ↓
Group: Try npm (local)
    ↓
Local: Found! Return metadata
    ↓
Client: Download tarball from npm (local)
    ↓
Success: Fast response (no upstream call)
```

### Example 3: Publish Internal Package

**Direct to Local Registry:**
```
npm publish --registry=http://localhost:8081/npm
(OR use publishConfig in package.json)
    ↓
Local: Validate authentication
    ↓
Local: Store tarball to /_attachments/
    ↓
Local: Update metadata at /@mycompany/auth
    ↓
Success: Package published
    ↓
Available immediately via npm_group
```

### Example 4: Security Audit

**Using Group Registry:**
```
npm audit --registry=http://localhost:8081/npm_group
    ↓
Group: Query npm (local) in parallel
Group: Query npm_proxy in parallel
    ↓
Local: Return {} (no vulnerability DB)
Proxy: Forward to npmjs.org → Get vulnerabilities
    ↓
Group: Merge results (proxy wins)
    ↓
Return to client: Comprehensive vulnerability report
```

---

## ⚠️ Common Gotchas

### ❌ **Trying to publish to group/proxy:**
```bash
npm publish --registry=http://localhost:8081/npm_group
# Error: 405 Method Not Allowed
```
**Solution:** Always publish to local repo directly

### ❌ **Authentication token not working:**
```bash
npm whoami --registry=http://localhost:8081/npm
# Error: 401 Unauthorized
```
**Solution:** Run `npm adduser` first to get valid token

### ❌ **Package not found in local, but exists in proxy:**
```bash
npm install express --registry=http://localhost:8081/npm
# Error: 404 Not Found
```
**Solution:** Use group registry or proxy registry instead

### ❌ **Slow installs on first request:**
```bash
npm install lodash --registry=http://localhost:8081/npm_proxy
# Takes 5-10 seconds on first request
```
**Reason:** Fetching from upstream npmjs.org
**Solution:** Subsequent requests are cached and fast

---

## 📈 Performance Comparison

| Operation | Local | Proxy (Cached) | Proxy (Uncached) | Group |
|-----------|-------|----------------|------------------|-------|
| **install** (internal pkg) | 🟢 50ms | ❌ N/A | ❌ N/A | 🟢 50ms |
| **install** (public pkg) | ❌ 404 | 🟢 100ms | 🟡 2-5s | 🟢 100ms |
| **publish** | 🟢 200ms | ❌ 405 | ❌ 405 | ❌ 405 |
| **search** | 🟡 Limited | 🟢 Comprehensive | 🟡 2-5s | 🟢 Best |
| **audit** | 🟡 Empty | 🟢 Real data | 🟡 2-5s | 🟢 Best |
| **whoami** | 🟢 50ms | 🟢 50ms | 🟢 50ms | 🟢 50ms |

**Legend:**
- 🟢 Fast/Best
- 🟡 Acceptable
- ❌ Not Supported/Slow

---

## 🎓 Summary

### **Use Local (`/npm`) when:**
- ✅ Publishing internal packages
- ✅ Managing dist-tags
- ✅ Unpublishing packages
- ✅ You only need internal packages

### **Use Proxy (`/npm_proxy`) when:**
- ✅ You only need public packages
- ✅ You want upstream vulnerability data
- ✅ You want to cache npmjs.org packages

### **Use Group (`/npm_group`) when:** 🌟 **RECOMMENDED**
- ✅ You need both internal AND public packages
- ✅ You want comprehensive search results
- ✅ You want aggregated security audits
- ✅ You want to simplify developer configuration
- ✅ **This is the best default for most teams**

---

## 🚀 Quick Start for Developers

```bash
# 1. Configure npm to use group registry (one-time setup)
npm config set registry http://localhost:8081/npm_group

# 2. Authenticate
npm adduser
# Enter your Artipie/Keycloak credentials

# 3. Verify
npm whoami
# Should return your username

# 4. Install packages (works for both internal and public)
npm install

# 5. Publish (will use publishConfig from package.json)
npm publish
```

**That's it!** Everything just works. ✅
