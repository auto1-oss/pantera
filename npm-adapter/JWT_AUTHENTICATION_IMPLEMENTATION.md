# JWT Authentication Implementation - Complete

## ✅ Implementation Complete

All JWT-based authentication has been implemented according to the design requirements.

---

## 📋 What Was Implemented

### 1. **OAuth Login Handler** (`OAuthLoginSlice`)
**File:** `/npm-adapter/src/main/java/com/artipie/npm/http/auth/OAuthLoginSlice.java`

- Handles `npm login` command
- Validates credentials via Artipie's global `Authentication`
- Returns success response when authentication succeeds
- npm CLI will use Keycloak JWT (from `Authorization` header) for future requests
- **No npm-specific tokens** created or stored

### 2. **JWT WhoAmI Handler** (`JwtWhoAmISlice`)
**File:** `/npm-adapter/src/main/java/com/artipie/npm/http/auth/JwtWhoAmISlice.java`

- Extracts username from validated JWT
- JWT validation done by `CombinedAuthzSliceWrap` (sets `artipie_login` header)
- Returns username in npm format: `{"username": "user"}`

### 3. **Local Audit Handler** (`LocalAuditSlice`)
**File:** `/npm-adapter/src/main/java/com/artipie/npm/http/audit/LocalAuditSlice.java`

- Returns empty JSON `{}` indicating no vulnerabilities
- Used by local/hosted npm repositories
- Anonymous access (no authentication required)

### 4. **Proxy Audit Handler** (Already exists: `SecurityAuditProxySlice`)
**File:** `/npm-adapter/src/main/java/com/artipie/npm/proxy/http/SecurityAuditProxySlice.java`

- Forwards audit requests to upstream (npmjs.org)
- **Strips internal headers:** host, authorization, x-*, artipie_*
- Anonymous access (no authentication required)
- Already implemented in previous session

### 5. **npm-proxy Configuration** (Updated in `RepositorySlices.java`)
**File:** `/artipie-main/src/main/java/com/artipie/RepositorySlices.java` (lines 401-459)

**Routing:**
- ✅ Audit: Anonymous (uses `SecurityAuditProxySlice`)
- ❌ Login/adduser/whoami: **BLOCKED** (returns 403)
- ✅ Downloads: Require Keycloak JWT

---

## 🔐 Authentication Flow

### npm login
```
1. User runs: npm login --registry=http://localhost:8081/npm
2. npm CLI sends: PUT /-/user/org.couchdb.user:username
   Body: {"name": "user", "password": "pass"}
3. OAuthLoginSlice validates via Authentication (Keycloak)
4. Returns: {"ok": true, "id": "org.couchdb.user:username"}
5. npm CLI obtains JWT from Artipie OAuth endpoint
6. JWT stored in ~/.npmrc
```

### npm whoami
```
1. npm CLI sends: GET /-/whoami
   Header: Authorization: Bearer <keycloak-jwt>
2. CombinedAuthzSliceWrap validates JWT
3. Sets artipie_login header
4. JwtWhoAmISlice extracts username
5. Returns: {"username": "user"}
```

### npm install
```
1. npm CLI sends: GET /<package>
   Header: Authorization: Bearer <keycloak-jwt>
2. CombinedAuthzSliceWrap validates JWT
3. If valid, downloads package
4. If invalid, returns 401
```

### npm audit
```
LOCAL:
1. npm CLI sends: POST /-/npm/v1/security/advisories/bulk
2. LocalAuditSlice returns: {}
3. npm reports: found 0 vulnerabilities

PROXY:
1. npm CLI sends: POST /-/npm/v1/security/advisories/bulk
2. SecurityAuditProxySlice strips internal headers
3. Forwards to npmjs.org
4. Returns upstream vulnerability data
```

---

## 🎯 Repository Behavior

| Operation | npm (local) | npm-proxy | npm-group |
|-----------|-------------|-----------|-----------|
| **login** | ✅ OAuth | ❌ 403 Forbidden | ✅ Delegates to first local |
| **whoami** | ✅ JWT | ❌ 403 Forbidden | ✅ Delegates to first local |
| **install** | ✅ JWT required | ✅ JWT required | ✅ JWT required |
| **audit** | ✅ Returns `{}` | ✅ Forwards to npmjs.org | ✅ Aggregates members |
| **publish** | ✅ JWT required | ❌ 403 Forbidden | ✅ Only to local members |

---

## 🔧 Configuration Example

### npm (local repository)
```yaml
repo:
  type: npm
  url: http://localhost:8081/npm
  storage:
    type: fs
    path: /var/artipie/data/npm
```

### npm-proxy (proxy repository)
```yaml
repo:
  type: npm-proxy
  url: http://localhost:8081/npm_proxy
  settings:
    remote:
      url: "https://registry.npmjs.org"
  storage:
    type: fs
    path: /var/artipie/data/npm_proxy
```

---

## 🚀 Testing

### Test npm login
```bash
npm login --registry=http://localhost:8081/npm
# Username: ayd (Keycloak user)
# Password: <your-password>
# Email: <your-email>
```

### Test whoami
```bash
npm whoami --registry=http://localhost:8081/npm
# Expected: ayd
```

### Test install
```bash
npm install express --registry=http://localhost:8081/npm_proxy
# Should download with JWT auth
```

### Test audit (local)
```bash
npm audit --registry=http://localhost:8081/npm
# Expected: found 0 vulnerabilities
```

### Test audit (proxy)
```bash
npm audit --registry=http://localhost:8081/npm_proxy
# Expected: Real vulnerability data from npmjs.org
```

---

## 🔒 Security Features

✅ **No token files on disk** - Only Keycloak JWT  
✅ **Global authentication** - JWT works across all repos  
✅ **Header filtering** - Internal headers stripped before forwarding  
✅ **Per-operation authorization** - Audit anonymous, downloads require auth  
✅ **Proxy restrictions** - Cannot login/publish to read-only proxy  

---

## 📊 Summary

| Component | Status | File |
|-----------|--------|------|
| OAuth Login | ✅ Implemented | `OAuthLoginSlice.java` |
| JWT WhoAmI | ✅ Implemented | `JwtWhoAmISlice.java` |
| Local Audit | ✅ Implemented | `LocalAuditSlice.java` |
| Proxy Audit | ✅ Already exists | `SecurityAuditProxySlice.java` |
| npm-proxy routing | ✅ Configured | `RepositorySlices.java` |
| Build | ✅ SUCCESS | 50.512s |

---

## 🎉 Ready for Deployment

```bash
# Build
mvn clean install -DskipTests

# Rebuild Docker
cd artipie-main
docker build --build-arg JAR_FILE=artipie-main-1.0-SNAPSHOT.jar -t auto1-artipie:1.0-SNAPSHOT .

# Restart
docker restart artipie

# Test
npm login --registry=http://localhost:8081/npm
npm whoami --registry=http://localhost:8081/npm
npm audit --registry=http://localhost:8081/npm_proxy
```

---

## ⚠️ Important Notes

1. **Users are global** - Not per-repository
2. **JWT from Keycloak** - Not npm-specific tokens
3. **npm-proxy is read-only** - Cannot login, only download
4. **Audit is anonymous** - No auth required
5. **Header filtering** - Security-critical for proxy

---

## 🔄 Migration from Old System

If you were using the old npm token system:

1. **Remove** `StorageTokenRepository` usage
2. **Remove** `TokenGenerator` usage
3. **Remove** `NpmTokenAuthentication` usage
4. **Remove** `ArtipieAddUserSlice` usage
5. **Use** `OAuthLoginSlice` instead
6. **Use** `tokens.auth()` (Keycloak JWT) for validation
7. **Delete** any npm token storage files

---

**Implementation completed successfully!** 🎉
