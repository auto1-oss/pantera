# NPM Group Repository Authentication Support

## Overview

NPM group repositories now support **user authentication** (`npm adduser`) while maintaining read-only behavior for all other operations.

---

## What Changed

### Before
- ❌ `npm adduser --registry=http://localhost:8081/npm_group` → **405 Method Not Allowed**
- ❌ Group repos blocked ALL PUT requests
- ✅ Only GET/HEAD allowed

### After
- ✅ `npm adduser --registry=http://localhost:8081/npm_group` → **Works!**
- ✅ PUT allowed ONLY for NPM authentication endpoints
- ✅ All other PUT requests still blocked
- ✅ Group repos remain read-only for packages

---

## Supported NPM Group Operations

| Operation | Method | Path Pattern | Allowed? | Behavior |
|-----------|--------|--------------|----------|----------|
| **adduser** | PUT | `/-/user/org.couchdb.user:*` | ✅ Yes | Forwards to first member |
| **whoami** | GET | `/-/whoami` | ✅ Yes | Forwards to first member |
| **search** | GET | `/-/v1/search` | ✅ Yes | Aggregates from all members |
| **audit** | POST | `/-/npm/v1/security/*` | ✅ Yes | Aggregates from all members |
| **install** | GET | `/*` | ✅ Yes | Tries members in order |
| **publish** | PUT | `/package-name` | ❌ No | 405 Method Not Allowed |
| **unpublish** | DELETE | `/*` | ❌ No | 405 Method Not Allowed |

---

## Implementation Details

### GroupSlice Logic

```java
@Override
public CompletableFuture<Response> response(...) {
    final String method = line.method().value();
    final String path = line.uri().getPath();
    
    // 1. Allow read-only methods (all groups)
    if ("GET".equals(method) || "HEAD".equals(method)) {
        return tryMember(0, line, headers, body);
    }
    
    // 2. Allow POST for npm audit (all groups with npm members)
    if ("POST".equals(method) && path.contains("/-/npm/v1/security/")) {
        return tryMember(0, line, headers, body);
    }
    
    // 3. Allow PUT ONLY for npm auth endpoints and ONLY for npm-group
    if ("PUT".equals(method) && isNpmGroup() && isNpmAuthEndpoint(path)) {
        return tryMember(0, line, headers, body);  // Forward to first member
    }
    
    // 4. Block all other write operations
    return ResponseBuilder.methodNotAllowed().completedFuture();
}
```

### NPM Group Detection

```java
private boolean isNpmGroup() {
    return this.group != null && 
           (this.group.contains("npm") || this.group.equals("npm-group"));
}
```

Matches:
- ✅ `npm_group`
- ✅ `npm-group`
- ✅ `my-npm-proxy-group`
- ❌ `maven-group`
- ❌ `docker-group`

### Auth Endpoint Detection

```java
private boolean isNpmAuthEndpoint(final String path) {
    return path.contains("/-/user/org.couchdb.user:");
}
```

Matches:
- ✅ `/-/user/org.couchdb.user:developer1`
- ✅ `/npm_group/-/user/org.couchdb.user:ayd`
- ❌ `/npm_group/package-name` (regular PUT)

---

## Usage

### Scenario: Team with Local + Proxy NPM Repos

**Repository Configuration:**
```yaml
# _server.yaml
repo:
  npm:  # Local hosted repo
    type: npm
    storage: ...
    
  npm_proxy:  # Proxy to npmjs.org
    type: npm-proxy
    remotes:
      - url: https://registry.npmjs.org
    storage: ...
    
  npm_group:  # Group aggregator
    type: npm-group
    members:
      - npm         # Try local first
      - npm_proxy   # Then proxy
```

### Developer Workflow

#### 1. **Login via Group Repository**
```bash
# Authenticate using group URL
npm adduser --registry=http://localhost:8081/npm_group

# Enter Artipie/Keycloak credentials:
Username: developer1
Password: <keycloak-password>
Email: developer1@example.com
```

**What happens:**
1. PUT request sent to `npm_group/-/user/org.couchdb.user:developer1`
2. GroupSlice detects: NPM group + auth endpoint → Allow
3. Forwards to **first member** (`npm`)
4. `npm` authenticates against Artipie/Keycloak
5. Generates token, saves to `npm/_tokens/`
6. Returns token to developer

#### 2. **Verify Authentication**
```bash
npm whoami --registry=http://localhost:8081/npm_group
# Output: developer1
```

#### 3. **Publish to Local Repo**
```bash
# Publish goes directly to local repo (not group)
npm publish --registry=http://localhost:8081/npm
```

**Why not group?** Group repos are read-only aggregators. Publishing should target the specific local repo.

#### 4. **Install from Group**
```bash
# Install from group (tries local, then proxy)
npm install express --registry=http://localhost:8081/npm_group
```

**Behavior:**
1. Tries `npm` (local) first
2. If 404, tries `npm_proxy` (upstream)
3. Returns first match

---

## Benefits

### ✅ **Simplified Configuration**
Developers only need to configure ONE registry URL:
```bash
npm config set registry http://localhost:8081/npm_group
```

All operations work:
- ✅ `npm adduser` → Authenticates
- ✅ `npm install` → Pulls from local or proxy
- ✅ `npm whoami` → Verifies identity
- ✅ `npm search` → Searches all members
- ✅ `npm audit` → Audits all members

### ✅ **Single Source of Truth**
- One URL for all developers
- No need to remember separate URLs for auth vs. install

### ✅ **Consistent Experience**
- Same workflow as npmjs.org
- No special handling for group repos

### ✅ **Security Maintained**
- Write operations still blocked (can't publish to group)
- Only auth endpoints allowed
- Other repo types (maven, docker) unaffected

---

## Security Implications

### What's Protected

#### ✅ **Package Publishing Still Blocked**
```bash
# This still fails with 405
npm publish --registry=http://localhost:8081/npm_group
# Error: 405 Method Not Allowed
```

You **must** publish directly to the local repo:
```bash
npm publish --registry=http://localhost:8081/npm  # ✅ Works
```

#### ✅ **Delete Operations Blocked**
```bash
# This fails with 405
npm unpublish my-package --registry=http://localhost:8081/npm_group
# Error: 405 Method Not Allowed
```

#### ✅ **Only NPM Groups Affected**
```bash
# Maven group still read-only (no PUT allowed)
mvn deploy -DrepositoryUrl=http://localhost:8081/maven_group
# Error: 405 Method Not Allowed
```

### What Changed

#### ✅ **Authentication Enabled**
```bash
# Now works on npm-group
npm adduser --registry=http://localhost:8081/npm_group
# Success: Authenticated as developer1
```

**Security check:**
- Still authenticates against Artipie/Keycloak
- Credentials verified before token generation
- No bypass of authentication system

---

## Comparison: Direct Repo vs Group Repo

| Operation | Direct Repo (`/npm`) | Group Repo (`/npm_group`) |
|-----------|----------------------|---------------------------|
| **adduser** | ✅ Authenticates | ✅ Forwards to first member → authenticates |
| **whoami** | ✅ Returns username | ✅ Forwards to first member → returns username |
| **publish** | ✅ Publishes package | ❌ 405 Method Not Allowed |
| **install** | ✅ From local only | ✅ From local OR proxy |
| **search** | ✅ Local packages | ✅ All members (aggregated) |
| **audit** | ✅ Local packages | ✅ All members (aggregated) |
| **unpublish** | ✅ Deletes package | ❌ 405 Method Not Allowed |

---

## Recommendation

### For Developers:
```bash
# Set group as default registry
npm config set registry http://localhost:8081/npm_group

# Login once
npm adduser

# Install packages (works with local + proxy)
npm install

# Search all repos
npm search my-package

# Audit all repos
npm audit
```

### For Publishing:
```bash
# Use explicit URL for publishing (can't use group)
npm publish --registry=http://localhost:8081/npm
```

**Or** set `publishConfig` in `package.json`:
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
- `npm install` uses group (local + proxy)
- `npm publish` uses direct local repo

---

## Testing

### Test Authentication via Group

```bash
# 1. Ensure user exists in Keycloak
# Username: testuser
# Password: TestPass123!

# 2. Authenticate via group
npm adduser --registry=http://localhost:8081/npm_group
# Username: testuser
# Password: TestPass123!
# Email: test@example.com

# 3. Verify
npm whoami --registry=http://localhost:8081/npm_group
# Expected: testuser

# 4. Try to publish to group (should fail)
npm publish --registry=http://localhost:8081/npm_group
# Expected: 405 Method Not Allowed

# 5. Publish to direct repo (should work)
npm publish --registry=http://localhost:8081/npm
# Expected: 200 OK
```

---

## Implementation Files

**Modified:**
- `/artipie-main/src/main/java/com/artipie/group/GroupSlice.java`
  - Added `isNpmGroup()` method
  - Added `isNpmAuthEndpoint()` method
  - Updated `response()` to allow PUT for npm auth

**Unchanged:**
- NPM adapter authentication code
- Token generation
- User authentication flow

---

## Conclusion

NPM group repositories now support **seamless authentication** while maintaining security:

✅ **Enabled:** `npm adduser`, `npm whoami` via group URL  
✅ **Protected:** Publishing, deleting still require direct repo access  
✅ **Secure:** Authentication still via Artipie/Keycloak  
✅ **Isolated:** Only NPM groups affected, other repo types unchanged  

**Developers can now use a single URL for all NPM operations!** 🎉
