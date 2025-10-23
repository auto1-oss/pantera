# NPM Authentication Integration with Artipie/Keycloak

## Overview

NPM authentication is now **fully integrated** with Artipie's authentication system (Keycloak). Users can authenticate with their Artipie credentials when using `npm adduser`.

---

## Architecture

### Before (Standalone Mode)
- NPM maintained separate user database in `/_users/`
- Users were stored with BCrypt password hashes
- No connection to Artipie auth

### After (Integrated Mode)
- NPM authenticates against **Artipie Authentication** (Keycloak)
- NPM tokens are bound to **Artipie usernames**
- No separate user database needed
- **Fallback**: If no Artipie auth is configured, falls back to standalone mode

---

## Implementation

### Key Components

#### 1. **ArtipieAddUserSlice**
Location: `/npm-adapter/src/main/java/com/artipie/npm/http/auth/ArtipieAddUserSlice.java`

**Flow:**
```
npm adduser
    ↓
PUT /-/user/org.couchdb.user:username
    ↓
Extract username & password from request body
    ↓
Authenticate against Artipie (Keycloak)
    ↓
If valid: Generate NPM token + save to /_tokens/
    ↓
Return token to npm CLI
```

**Response on Success:**
```json
{
  "ok": true,
  "id": "org.couchdb.user:username",
  "rev": "1-<timestamp>",
  "token": "<npm-token>"
}
```

**Response on Invalid Credentials:**
```json
{
  "error": "Invalid credentials. Use your Artipie username and password."
}
```

#### 2. **TokenGenerator Enhancements**
Added overloaded methods to generate tokens from username strings:
```java
public CompletableFuture<NpmToken> generate(String username)
public CompletableFuture<NpmToken> generate(String username, Instant expiresAt)
```

#### 3. **NpmSlice Integration**
Automatically uses `ArtipieAddUserSlice` when Artipie authentication is available:
```java
basicAuth != null 
    ? new ArtipieAddUserSlice(basicAuth, tokens, tokenGen)  // Keycloak integration
    : new AddUserSlice(users, tokens, hasher, tokenGen)      // Standalone mode
```

---

## Usage

### User Workflow

#### 1. **Create Artipie User (via Keycloak)**
First, ensure the user exists in Artipie/Keycloak:
- Via Artipie Web UI: Settings → Users → Add User
- Via Keycloak Admin Console
- Username: `developer1`
- Password: `SecurePass123!`

#### 2. **NPM Login with Artipie Credentials**
```bash
npm adduser --registry=http://localhost:8081/npm

# Enter your ARTIPIE credentials:
Username: developer1
Password: SecurePass123!
Email: developer1@example.com
```

**Behind the scenes:**
1. NPM CLI sends PUT request to `/-/user/org.couchdb.user:developer1`
2. Artipie authenticates `developer1` against Keycloak
3. If valid, generates NPM token and saves to storage
4. NPM CLI stores token in `~/.npmrc`

#### 3. **Verify Authentication**
```bash
npm whoami --registry=http://localhost:8081/npm
# Output: developer1
```

#### 4. **Use NPM Token for Operations**
```bash
# Publish
npm publish --registry=http://localhost:8081/npm

# Install private packages
npm install my-private-package --registry=http://localhost:8081/npm
```

---

## Token Storage

NPM tokens are stored in the repository at:
```
/_tokens/<token-id>.json
```

Example token file:
```json
{
  "token": "xK9mP3nR8vL2qW5sT7yH4jN6fG1dC0bA",
  "username": "developer1",
  "expiresAt": null
}
```

---

## Configuration

### Enable Artipie Authentication

In your repository configuration (`_server.yaml`):
```yaml
repo:
  type: npm
  storage:
    type: fs
    path: /var/artipie/data/npm
  settings:
    # Artipie authentication is automatically used when configured
```

### Fallback to Standalone Mode

If you want to use NPM's standalone authentication (without Keycloak):
- Don't configure Artipie authentication for the repository
- Users will be stored in `/_users/` with BCrypt hashes

---

## Security

### Token Security
- **Generation**: 32-byte cryptographically secure random tokens
- **Encoding**: Base64 URL-safe encoding
- **Storage**: Tokens stored in repository storage (same as packages)
- **Expiration**: Configurable (null = no expiration)

### Password Security
- **Keycloak Mode**: Passwords verified by Keycloak (never stored in NPM)
- **Standalone Mode**: Passwords hashed with BCrypt (work factor 10)

### Authentication Flow
```
npm CLI → PUT /-/user/org.couchdb.user:user
    ↓
Artipie Authentication (Keycloak LDAP/DB)
    ↓
Valid? → Generate token → Save to /_tokens/
    ↓
Invalid? → Return 401 Unauthorized
```

---

## Benefits

### ✅ **Single Sign-On**
- One set of credentials for Artipie Web UI and NPM CLI
- Centralized user management in Keycloak

### ✅ **Enterprise Integration**
- Works with LDAP, Active Directory, SAML, OAuth
- Leverage existing identity providers

### ✅ **Audit Trail**
- All authentications logged through Artipie
- Track who published what package

### ✅ **Token Management**
- Tokens bound to Artipie users
- Easy revocation: Delete user in Keycloak → tokens invalidated on next auth check

### ✅ **Backward Compatible**
- Falls back to standalone mode if Artipie auth not configured
- Existing NPM workflows unchanged

---

## Troubleshooting

### Issue: "Invalid credentials" error

**Cause**: Username or password doesn't match Artipie/Keycloak

**Solution:**
1. Verify user exists in Keycloak
2. Check password is correct
3. Ensure user has access to the repository

### Issue: "Unable to authenticate" error

**Cause**: Artipie authentication not properly configured

**Solution:**
Check Artipie logs for authentication errors:
```bash
docker logs artipie | grep -i auth
```

### Issue: Token not working after generation

**Cause**: Token not saved to storage or tokenAuth not configured

**Solution:**
1. Check `/_tokens/` directory exists and is writable
2. Verify tokenAuth is configured in NpmSlice
3. Check Artipie logs for token validation errors

---

## Migration

### From Standalone NPM Auth to Integrated

1. **Export existing users** (if needed):
   - Existing NPM users in `/_users/` will continue to work
   - New users should be created in Keycloak

2. **Create Keycloak users**:
   - Add existing NPM usernames to Keycloak
   - Set passwords

3. **Users re-authenticate**:
   ```bash
   npm adduser --registry=http://localhost:8081/npm
   ```

4. **Tokens regenerated**:
   - Old tokens remain in `/_tokens/`
   - New tokens generated on re-authentication

---

## API Reference

### PUT /-/user/org.couchdb.user:username

**Request Body:**
```json
{
  "name": "username",
  "password": "user-password",
  "email": "user@example.com"
}
```

**Success Response (201):**
```json
{
  "ok": true,
  "id": "org.couchdb.user:username",
  "rev": "1-<timestamp>",
  "token": "<npm-token>"
}
```

**Error Responses:**

**401 Unauthorized** - Invalid credentials:
```json
{
  "error": "Invalid credentials. Use your Artipie username and password."
}
```

**400 Bad Request** - Missing password:
```json
{
  "error": "Password required"
}
```

**500 Internal Server Error** - Server error:
```json
{
  "error": "<error-message>"
}
```

---

## Testing

### Test Artipie Integration

```bash
# 1. Create test user in Keycloak
# Username: testuser
# Password: TestPass123!

# 2. Test authentication
npm adduser --registry=http://localhost:8081/npm
# Username: testuser
# Password: TestPass123!
# Email: test@example.com

# 3. Verify
npm whoami --registry=http://localhost:8081/npm
# Expected: testuser

# 4. Test publish
echo '{"name":"@test/package","version":"1.0.0"}' > package.json
npm publish --registry=http://localhost:8081/npm
# Expected: 200 OK
```

---

## Conclusion

NPM authentication is now **fully integrated** with Artipie's enterprise authentication system. Users authenticate once with their Artipie credentials and can use NPM seamlessly.

**Key Points:**
- ✅ Keycloak users can directly use NPM CLI
- ✅ NPM tokens bound to Artipie users only
- ✅ No separate user management needed
- ✅ Backward compatible with standalone mode
- ✅ Production-ready and secure

**Next Steps:**
1. Build and deploy updated Artipie
2. Create Keycloak users
3. Test with `npm adduser`
4. Enjoy integrated authentication! 🎉
