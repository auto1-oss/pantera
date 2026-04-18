# JWT Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Use ralph-loop for code review after each major task group. Create feature branch first (Task 0), raise PR at end (Task 21).

**Goal:** Replace HS256 shared-secret JWT auth with RS256 asymmetric keys, unified auth handler, access/refresh/API token architecture, and multi-node revocation blocklist.

**Architecture:** Auth0 java-jwt handles all crypto (signing, verification, algorithm safety). A unified `UnifiedJwtAuthHandler` replaces both `JwtTokenAuth` (port 80) and raw `JWTAuthHandler` (port 8086). Tokens are split into three types: short-lived `access` (1h, no DB), `refresh` (7d, DB-backed), and `api` (user-chosen TTL, DB-backed). Revocation uses Valkey pub/sub (reusing existing `CacheInvalidationPubSub`) with DB-polling fallback.

**Tech Stack:** Auth0 java-jwt 4.4.0, Vert.x (existing), Lettuce/Valkey (existing), PostgreSQL/Flyway (existing), Vue 3 + PrimeVue (existing)

**Spec:** `docs/superpowers/specs/2026-03-31-jwt-security-hardening-design.md`

---

## File Map

### New Files (Backend)

| File | Responsibility |
|------|---------------|
| `pantera-main/src/main/java/com/auto1/pantera/auth/UnifiedJwtAuthHandler.java` | Single auth handler for both ports. RS256 verification via Auth0, type routing, blocklist/DB check. |
| `pantera-main/src/main/java/com/auto1/pantera/auth/RsaKeyLoader.java` | Load RSA key pair from PEM files. |
| `pantera-main/src/main/java/com/auto1/pantera/auth/TokenType.java` | Enum: `ACCESS`, `REFRESH`, `API`. |
| `pantera-core/src/main/java/com/auto1/pantera/auth/RevocationBlocklist.java` | Interface for revocation blocklist. |
| `pantera-main/src/main/java/com/auto1/pantera/auth/ValkeyRevocationBlocklist.java` | Valkey pub/sub + SET implementation. |
| `pantera-main/src/main/java/com/auto1/pantera/auth/DbRevocationBlocklist.java` | DB polling fallback implementation. |
| `pantera-main/src/main/java/com/auto1/pantera/db/dao/AuthSettingsDao.java` | CRUD for `auth_settings` table. |
| `pantera-main/src/main/java/com/auto1/pantera/db/dao/RevocationDao.java` | CRUD for `revocation_blocklist` table. |
| `pantera-main/src/main/java/com/auto1/pantera/api/v1/AdminAuthHandler.java` | Admin endpoints: revoke-user, auth-settings. |
| `pantera-main/src/main/resources/db/migration/V105__add_token_type_column.sql` | Add `token_type` to `user_tokens`. |
| `pantera-main/src/main/resources/db/migration/V106__create_revocation_blocklist.sql` | Create `revocation_blocklist` table. |
| `pantera-main/src/main/resources/db/migration/V107__create_auth_settings.sql` | Create `auth_settings` table with defaults. |

### New Files (Tests)

| File | Responsibility |
|------|---------------|
| `pantera-main/src/test/java/com/auto1/pantera/auth/RsaKeyLoaderTest.java` | PEM loading, validation, error cases. |
| `pantera-main/src/test/java/com/auto1/pantera/auth/UnifiedJwtAuthHandlerTest.java` | Token verification, type routing, scope enforcement. |
| `pantera-main/src/test/java/com/auto1/pantera/auth/ValkeyRevocationBlocklistTest.java` | Valkey-backed blocklist. |
| `pantera-main/src/test/java/com/auto1/pantera/auth/DbRevocationBlocklistTest.java` | DB-backed blocklist. |
| `pantera-main/src/test/java/com/auto1/pantera/db/dao/AuthSettingsDaoTest.java` | Settings CRUD. |

### Modified Files (Backend)

| File | Change |
|------|--------|
| `pantera-main/pom.xml` | Add `com.auth0:java-jwt:4.4.0` dependency. |
| `pantera-main/src/main/java/com/auto1/pantera/settings/JwtSettings.java` | Replace `secret` with `privateKeyPath` + `publicKeyPath`. RS256 config. |
| `pantera-main/src/main/java/com/auto1/pantera/auth/JwtTokens.java` | Rewrite to use Auth0 java-jwt for signing. Add `type` claim. Generate access+refresh pairs. |
| `pantera-core/src/main/java/com/auto1/pantera/http/auth/Tokens.java` | Add `generateAccess()`, `generateRefresh()` methods. |
| `pantera-main/src/main/java/com/auto1/pantera/VertxMain.java:201-222` | Replace `JWTAuth.create(HS256)` with `RsaKeyLoader`. Wire `UnifiedJwtAuthHandler`. |
| `pantera-main/src/main/java/com/auto1/pantera/api/v1/AsyncApiVerticle.java:259-280` | Replace `JWTAuthHandler.create(this.jwt)` with `UnifiedJwtAuthHandler`. |
| `pantera-main/src/main/java/com/auto1/pantera/api/v1/AuthHandler.java:116-151,524-530,573-612,626-644` | Login/callback return dual tokens. Refresh accepts refresh token only. Generate validates limits. |
| `pantera-main/src/main/java/com/auto1/pantera/RepositorySlices.java` | Wire `UnifiedJwtAuthHandler` into `CombinedAuthzSlice`. |
| `pantera-main/src/main/java/com/auto1/pantera/db/dao/UserTokenDao.java` | Add `token_type` to `store()`, `isValidForUser()` with username check. |
| `pantera-main/src/main/java/com/auto1/pantera/api/AuthTokenRest.java` | Add `TYPE = "type"`, `JTI = "jti"` constants. |

### Modified Files (Frontend)

| File | Change |
|------|--------|
| `pantera-ui/src/types/index.ts` | Update `TokenResponse` to include `refresh_token` and `expires_in`. |
| `pantera-ui/src/api/client.ts` | Read `access_token`; send `refresh_token` on refresh; store both. |
| `pantera-ui/src/stores/auth.ts` | Dual token storage. Updated login/callback/logout. |
| `pantera-ui/src/api/auth.ts` | Add admin auth settings API. Update `refreshToken()` to send refresh_token. |
| `pantera-ui/src/views/profile/ProfileView.vue` | Dynamic expiry options from admin settings. |
| `pantera-ui/src/views/admin/SettingsView.vue` | New "Authentication" settings card. |

---

## Task 0: Create Feature Branch

- [ ] **Step 1: Create and switch to feature branch**

```bash
git checkout -b feat/jwt-security-hardening master
```

- [ ] **Step 2: Verify clean state**

Run: `git status`
Expected: On branch `feat/jwt-security-hardening`, nothing to commit, working tree clean.

---

## Task 1: Add Auth0 java-jwt Dependency

**Files:**
- Modify: `pantera-main/pom.xml`

- [ ] **Step 1: Add dependency to pom.xml**

In `pantera-main/pom.xml`, add inside the `<dependencies>` section:

```xml
    <!-- Auth0 java-jwt: battle-tested JWT library for RS256 signing/verification -->
    <dependency>
      <groupId>com.auth0</groupId>
      <artifactId>java-jwt</artifactId>
      <version>4.4.0</version>
    </dependency>
```

- [ ] **Step 2: Verify dependency resolves**

Run: `mvn dependency:resolve -pl pantera-main -q`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add pantera-main/pom.xml
git commit -m "build: add Auth0 java-jwt 4.4.0 dependency"
```

---

## Task 2: Database Migrations

**Files:**
- Create: `pantera-main/src/main/resources/db/migration/V105__add_token_type_column.sql`
- Create: `pantera-main/src/main/resources/db/migration/V106__create_revocation_blocklist.sql`
- Create: `pantera-main/src/main/resources/db/migration/V107__create_auth_settings.sql`

- [ ] **Step 1: Create V105 migration — add token_type column**

```sql
-- V105__add_token_type_column.sql
ALTER TABLE user_tokens ADD COLUMN IF NOT EXISTS token_type VARCHAR(10) NOT NULL DEFAULT 'api';

COMMENT ON COLUMN user_tokens.token_type IS 'Token type: api or refresh';
```

- [ ] **Step 2: Create V106 migration — revocation blocklist table**

```sql
-- V106__create_revocation_blocklist.sql
CREATE TABLE IF NOT EXISTS revocation_blocklist (
    id          BIGSERIAL PRIMARY KEY,
    entry_type  VARCHAR(10) NOT NULL,
    entry_value VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_revocation_expires
    ON revocation_blocklist (expires_at);

CREATE INDEX IF NOT EXISTS idx_revocation_lookup
    ON revocation_blocklist (entry_type, entry_value)
    WHERE expires_at > NOW();

COMMENT ON TABLE revocation_blocklist IS 'Access token revocation entries for DB-polling fallback mode';
COMMENT ON COLUMN revocation_blocklist.entry_type IS 'jti or username';
COMMENT ON COLUMN revocation_blocklist.entry_value IS 'Token UUID or username string';
```

- [ ] **Step 3: Create V107 migration — auth settings table**

```sql
-- V107__create_auth_settings.sql
CREATE TABLE IF NOT EXISTS auth_settings (
    key         VARCHAR(100) PRIMARY KEY,
    value       VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO auth_settings (key, value) VALUES
    ('access_token_ttl_seconds', '3600'),
    ('refresh_token_ttl_seconds', '604800'),
    ('api_token_max_ttl_seconds', '7776000'),
    ('api_token_allow_permanent', 'true')
ON CONFLICT (key) DO NOTHING;

COMMENT ON TABLE auth_settings IS 'UI-configurable authentication policy settings';
```

- [ ] **Step 4: Verify migrations compile**

Run: `mvn compile -pl pantera-main -q`
Expected: No errors (migrations are just SQL resources)

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/resources/db/migration/V105__add_token_type_column.sql \
       pantera-main/src/main/resources/db/migration/V106__create_revocation_blocklist.sql \
       pantera-main/src/main/resources/db/migration/V107__create_auth_settings.sql
git commit -m "feat: add DB migrations for token_type, revocation_blocklist, auth_settings"
```

---

## Task 3: RSA Key Loader

**Files:**
- Create: `pantera-main/src/main/java/com/auto1/pantera/auth/RsaKeyLoader.java`
- Create: `pantera-main/src/test/java/com/auto1/pantera/auth/RsaKeyLoaderTest.java`

- [ ] **Step 1: Write the failing test**

Create `pantera-main/src/test/java/com/auto1/pantera/auth/RsaKeyLoaderTest.java`:

```java
package com.auto1.pantera.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsaKeyLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsValidKeyPair() throws Exception {
        final KeyPair kp = generateKeyPair();
        final Path privPath = writePem(tempDir, "private.pem", "RSA PRIVATE KEY",
            kp.getPrivate().getEncoded());
        final Path pubPath = writePem(tempDir, "public.pem", "PUBLIC KEY",
            kp.getPublic().getEncoded());
        final RsaKeyLoader loader = new RsaKeyLoader(privPath.toString(), pubPath.toString());
        MatcherAssert.assertThat(loader.privateKey(), new IsNot<>(new IsNull<>()));
        MatcherAssert.assertThat(loader.publicKey(), new IsNot<>(new IsNull<>()));
        MatcherAssert.assertThat(
            loader.publicKey().getModulus(),
            new IsEqual<>(((RSAPublicKey) kp.getPublic()).getModulus())
        );
    }

    @Test
    void throwsOnMissingPrivateKey() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> new RsaKeyLoader("/nonexistent/private.pem", "/nonexistent/public.pem")
        );
    }

    @Test
    void throwsOnInvalidPem() throws Exception {
        final Path bad = tempDir.resolve("bad.pem");
        Files.writeString(bad, "not a pem file");
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> new RsaKeyLoader(bad.toString(), bad.toString())
        );
    }

    private static KeyPair generateKeyPair() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static Path writePem(final Path dir, final String name,
        final String type, final byte[] encoded) throws IOException {
        final Path file = dir.resolve(name);
        final String pem = "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
            + "\n-----END " + type + "-----\n";
        Files.writeString(file, pem);
        return file;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl pantera-main -Dtest="RsaKeyLoaderTest" -q`
Expected: FAIL — `RsaKeyLoader` class does not exist

- [ ] **Step 3: Implement RsaKeyLoader**

Create `pantera-main/src/main/java/com/auto1/pantera/auth/RsaKeyLoader.java`:

```java
package com.auto1.pantera.auth;

import com.auto1.pantera.http.log.EcsLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads RSA key pair from PEM files for RS256 JWT signing.
 * Fails fast with actionable error messages on misconfiguration.
 */
public final class RsaKeyLoader {

    private final RSAPrivateKey privKey;
    private final RSAPublicKey pubKey;

    /**
     * Load RSA key pair from PEM file paths.
     * @param privateKeyPath Path to PKCS#8 PEM private key
     * @param publicKeyPath Path to X.509 PEM public key
     * @throws IllegalStateException if files are missing or invalid
     */
    public RsaKeyLoader(final String privateKeyPath, final String publicKeyPath) {
        final Path privPath = Path.of(privateKeyPath);
        final Path pubPath = Path.of(publicKeyPath);
        if (!Files.isReadable(privPath)) {
            throw new IllegalStateException(
                "JWT private key not found at " + privateKeyPath
                + ". Generate with: openssl genrsa -out private.pem 2048"
            );
        }
        if (!Files.isReadable(pubPath)) {
            throw new IllegalStateException(
                "JWT public key not found at " + publicKeyPath
                + ". Generate with: openssl rsa -in private.pem -pubout -out public.pem"
            );
        }
        try {
            this.privKey = loadPrivateKey(privPath);
            this.pubKey = loadPublicKey(pubPath);
        } catch (final IllegalStateException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to load RSA key pair: " + ex.getMessage(), ex);
        }
        EcsLogger.info("com.auto1.pantera.auth")
            .message("RS256 key pair loaded successfully")
            .eventCategory("configuration")
            .eventAction("key_load")
            .eventOutcome("success")
            .log();
    }

    public RSAPrivateKey privateKey() {
        return this.privKey;
    }

    public RSAPublicKey publicKey() {
        return this.pubKey;
    }

    private static RSAPrivateKey loadPrivateKey(final Path path) throws Exception {
        final String pem = Files.readString(path);
        final String base64 = pem
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        final byte[] decoded = Base64.getDecoder().decode(base64);
        final KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private static RSAPublicKey loadPublicKey(final Path path) throws Exception {
        final String pem = Files.readString(path);
        final String base64 = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        final byte[] decoded = Base64.getDecoder().decode(base64);
        final KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(decoded));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl pantera-main -Dtest="RsaKeyLoaderTest" -q`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/auth/RsaKeyLoader.java \
       pantera-main/src/test/java/com/auto1/pantera/auth/RsaKeyLoaderTest.java
git commit -m "feat: add RsaKeyLoader for RS256 PEM key loading"
```

---

## Task 4: TokenType Enum and AuthTokenRest Constants

**Files:**
- Create: `pantera-main/src/main/java/com/auto1/pantera/auth/TokenType.java`
- Modify: `pantera-main/src/main/java/com/auto1/pantera/api/AuthTokenRest.java`

- [ ] **Step 1: Create TokenType enum**

Create `pantera-main/src/main/java/com/auto1/pantera/auth/TokenType.java`:

```java
package com.auto1.pantera.auth;

/**
 * JWT token types. Stored as the "type" claim in every token.
 */
public enum TokenType {
    ACCESS("access"),
    REFRESH("refresh"),
    API("api");

    private final String value;

    TokenType(final String value) {
        this.value = value;
    }

    public String value() {
        return this.value;
    }

    /**
     * Parse from claim string. Returns null if unknown.
     */
    public static TokenType fromClaim(final String claim) {
        if (claim == null) {
            return null;
        }
        for (final TokenType type : values()) {
            if (type.value.equals(claim)) {
                return type;
            }
        }
        return null;
    }
}
```

- [ ] **Step 2: Add constants to AuthTokenRest**

In `pantera-main/src/main/java/com/auto1/pantera/api/AuthTokenRest.java`, add after the existing `CONTEXT` constant:

```java
    /**
     * Token type claim name.
     */
    public static final String TYPE = "type";

    /**
     * JWT ID claim name.
     */
    public static final String JTI = "jti";
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl pantera-main -q`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/auth/TokenType.java \
       pantera-main/src/main/java/com/auto1/pantera/api/AuthTokenRest.java
git commit -m "feat: add TokenType enum and JWT claim constants"
```

---

## Task 5: AuthSettingsDao

**Files:**
- Create: `pantera-main/src/main/java/com/auto1/pantera/db/dao/AuthSettingsDao.java`
- Create: `pantera-main/src/test/java/com/auto1/pantera/db/dao/AuthSettingsDaoTest.java`

- [ ] **Step 1: Write the failing test**

Create `pantera-main/src/test/java/com/auto1/pantera/db/dao/AuthSettingsDaoTest.java`:

```java
package com.auto1.pantera.db.dao;

import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers
class AuthSettingsDaoTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private AuthSettingsDao dao;

    @BeforeEach
    void setUp() throws Exception {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(2);
        final DataSource ds = new HikariDataSource(cfg);
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS auth_settings ("
                + "key VARCHAR(100) PRIMARY KEY,"
                + "value VARCHAR(255) NOT NULL,"
                + "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())"
            );
            stmt.execute(
                "INSERT INTO auth_settings (key, value) VALUES "
                + "('access_token_ttl_seconds', '3600'),"
                + "('api_token_allow_permanent', 'true') "
                + "ON CONFLICT (key) DO NOTHING"
            );
        }
        this.dao = new AuthSettingsDao(ds);
    }

    @Test
    void getsExistingValue() {
        MatcherAssert.assertThat(
            this.dao.get("access_token_ttl_seconds"),
            new IsEqual<>(Optional.of("3600"))
        );
    }

    @Test
    void returnsEmptyForMissing() {
        MatcherAssert.assertThat(
            this.dao.get("nonexistent_key"),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void getsIntWithDefault() {
        MatcherAssert.assertThat(
            this.dao.getInt("access_token_ttl_seconds", 999),
            new IsEqual<>(3600)
        );
        MatcherAssert.assertThat(
            this.dao.getInt("missing_key", 999),
            new IsEqual<>(999)
        );
    }

    @Test
    void getsBoolWithDefault() {
        MatcherAssert.assertThat(
            this.dao.getBool("api_token_allow_permanent", false),
            new IsEqual<>(true)
        );
    }

    @Test
    void putsAndGets() {
        this.dao.put("test_key", "test_value");
        MatcherAssert.assertThat(
            this.dao.get("test_key"),
            new IsEqual<>(Optional.of("test_value"))
        );
    }

    @Test
    void putUpdatesExisting() {
        this.dao.put("access_token_ttl_seconds", "7200");
        MatcherAssert.assertThat(
            this.dao.get("access_token_ttl_seconds"),
            new IsEqual<>(Optional.of("7200"))
        );
    }

    @Test
    void getsAll() {
        final Map<String, String> all = this.dao.getAll();
        MatcherAssert.assertThat(all.containsKey("access_token_ttl_seconds"), new IsEqual<>(true));
        MatcherAssert.assertThat(all.get("access_token_ttl_seconds"), new IsEqual<>("3600"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl pantera-main -Dtest="AuthSettingsDaoTest" -q`
Expected: FAIL — `AuthSettingsDao` class does not exist

- [ ] **Step 3: Implement AuthSettingsDao**

Create `pantera-main/src/main/java/com/auto1/pantera/db/dao/AuthSettingsDao.java`:

```java
package com.auto1.pantera.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * DAO for auth_settings table. Provides UI-configurable token policy.
 */
public final class AuthSettingsDao {

    private final DataSource source;

    public AuthSettingsDao(final DataSource source) {
        this.source = source;
    }

    public Optional<String> get(final String key) {
        final String sql = "SELECT value FROM auth_settings WHERE key = ?";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            final ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(rs.getString("value"));
            }
            return Optional.empty();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to get auth setting: " + key, ex);
        }
    }

    public int getInt(final String key, final int defaultValue) {
        return this.get(key).map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (final NumberFormatException ex) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    public boolean getBool(final String key, final boolean defaultValue) {
        return this.get(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    public void put(final String key, final String value) {
        final String sql =
            "INSERT INTO auth_settings (key, value, updated_at) VALUES (?, ?, NOW()) "
            + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to put auth setting: " + key, ex);
        }
    }

    public Map<String, String> getAll() {
        final String sql = "SELECT key, value FROM auth_settings ORDER BY key";
        final Map<String, String> result = new LinkedHashMap<>();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.put(rs.getString("key"), rs.getString("value"));
            }
            return result;
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to get all auth settings", ex);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl pantera-main -Dtest="AuthSettingsDaoTest" -q`
Expected: All 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/db/dao/AuthSettingsDao.java \
       pantera-main/src/test/java/com/auto1/pantera/db/dao/AuthSettingsDaoTest.java
git commit -m "feat: add AuthSettingsDao for UI-configurable token policy"
```

---

## Task 6: Update UserTokenDao — Token Type + Username Validation

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/db/dao/UserTokenDao.java`
- Modify: existing tests or add new test cases

- [ ] **Step 1: Add token_type parameter to store()**

In `UserTokenDao.java`, modify the `store()` method (lines 53-74) to accept and persist `tokenType`:

```java
    public void store(final UUID id, final String username, final String label,
        final String tokenValue, final Instant expiresAt, final String tokenType) {
        final String sql = String.join(" ",
            "INSERT INTO user_tokens (id, username, label, token_hash, expires_at, token_type)",
            "VALUES (?, ?, ?, ?, ?, ?)"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, username);
            ps.setString(3, label);
            ps.setString(4, sha256(tokenValue));
            if (expiresAt != null) {
                ps.setTimestamp(5, Timestamp.from(expiresAt));
            } else {
                ps.setNull(5, java.sql.Types.TIMESTAMP);
            }
            ps.setString(6, tokenType);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to store token", ex);
        }
    }
```

Keep the old `store()` signature as a backward-compatible overload that defaults to `"api"`:

```java
    public void store(final UUID id, final String username, final String label,
        final String tokenValue, final Instant expiresAt) {
        this.store(id, username, label, tokenValue, expiresAt, "api");
    }
```

- [ ] **Step 2: Add isValidForUser() method**

Add below the existing `isValid()` method:

```java
    /**
     * Check if a token ID is valid for a specific user (exists, not revoked, owned by user).
     * Closes the JTI-theft-across-users vulnerability.
     * @param id Token UUID (jti)
     * @param username Expected token owner
     * @return True if valid and owned by this user
     */
    public boolean isValidForUser(final UUID id, final String username) {
        final String sql =
            "SELECT 1 FROM user_tokens WHERE id = ? AND username = ? AND revoked = FALSE";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, username);
            return ps.executeQuery().next();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to check token validity for user", ex);
        }
    }
```

- [ ] **Step 3: Add revokeAllForUser() method**

```java
    /**
     * Revoke all tokens for a user (admin action).
     * @param username Username
     * @return Number of tokens revoked
     */
    public int revokeAllForUser(final String username) {
        final String sql =
            "UPDATE user_tokens SET revoked = TRUE WHERE username = ? AND revoked = FALSE";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to revoke all tokens for user", ex);
        }
    }
```

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -pl pantera-main -q`
Expected: No errors

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/db/dao/UserTokenDao.java
git commit -m "feat: add token_type, isValidForUser, revokeAllForUser to UserTokenDao"
```

---

## Task 7: RevocationBlocklist Interface + DB Implementation

**Files:**
- Create: `pantera-core/src/main/java/com/auto1/pantera/auth/RevocationBlocklist.java`
- Create: `pantera-main/src/main/java/com/auto1/pantera/db/dao/RevocationDao.java`
- Create: `pantera-main/src/main/java/com/auto1/pantera/auth/DbRevocationBlocklist.java`
- Create: `pantera-main/src/test/java/com/auto1/pantera/auth/DbRevocationBlocklistTest.java`

- [ ] **Step 1: Create RevocationBlocklist interface**

Create `pantera-core/src/main/java/com/auto1/pantera/auth/RevocationBlocklist.java`:

```java
package com.auto1.pantera.auth;

/**
 * Interface for token revocation blocklist.
 * Used by UnifiedJwtAuthHandler to reject access tokens immediately.
 */
public interface RevocationBlocklist {

    /**
     * Check if a JTI is blocklisted.
     * @param jti Token UUID string
     * @return true if revoked
     */
    boolean isRevokedJti(String jti);

    /**
     * Check if a username is blocklisted.
     * @param username Username string
     * @return true if revoked
     */
    boolean isRevokedUser(String username);

    /**
     * Add a JTI to the blocklist with a TTL.
     * @param jti Token UUID string
     * @param ttlSeconds Seconds until entry expires
     */
    void revokeJti(String jti, int ttlSeconds);

    /**
     * Add a username to the blocklist with a TTL.
     * @param username Username string
     * @param ttlSeconds Seconds until entry expires
     */
    void revokeUser(String username, int ttlSeconds);
}
```

- [ ] **Step 2: Create RevocationDao**

Create `pantera-main/src/main/java/com/auto1/pantera/db/dao/RevocationDao.java`:

```java
package com.auto1.pantera.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * DAO for revocation_blocklist table (DB-polling fallback mode).
 */
public final class RevocationDao {

    private final DataSource source;

    public RevocationDao(final DataSource source) {
        this.source = source;
    }

    /**
     * Insert a revocation entry.
     */
    public void insert(final String entryType, final String entryValue, final int ttlSeconds) {
        final String sql =
            "INSERT INTO revocation_blocklist (entry_type, entry_value, expires_at) "
            + "VALUES (?, ?, ?)";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entryType);
            ps.setString(2, entryValue);
            ps.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(ttlSeconds)));
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to insert revocation entry", ex);
        }
    }

    /**
     * Check if an entry exists and is not expired.
     */
    public boolean isRevoked(final String entryType, final String entryValue) {
        final String sql =
            "SELECT 1 FROM revocation_blocklist "
            + "WHERE entry_type = ? AND entry_value = ? AND expires_at > NOW()";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entryType);
            ps.setString(2, entryValue);
            return ps.executeQuery().next();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to check revocation", ex);
        }
    }

    /**
     * Poll for entries created since a given timestamp.
     */
    public List<RevocationEntry> pollSince(final Instant since) {
        final String sql =
            "SELECT entry_type, entry_value, expires_at FROM revocation_blocklist "
            + "WHERE created_at > ? AND expires_at > NOW()";
        final List<RevocationEntry> result = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(since));
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new RevocationEntry(
                    rs.getString("entry_type"),
                    rs.getString("entry_value"),
                    rs.getTimestamp("expires_at").toInstant()
                ));
            }
            return result;
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to poll revocations", ex);
        }
    }

    public record RevocationEntry(String entryType, String entryValue, Instant expiresAt) {}
}
```

- [ ] **Step 3: Write failing test for DbRevocationBlocklist**

Create `pantera-main/src/test/java/com/auto1/pantera/auth/DbRevocationBlocklistTest.java`:

```java
package com.auto1.pantera.auth;

import com.auto1.pantera.db.dao.RevocationDao;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers
class DbRevocationBlocklistTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private DbRevocationBlocklist blocklist;

    @BeforeEach
    void setUp() throws Exception {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(2);
        final var ds = new HikariDataSource(cfg);
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS revocation_blocklist ("
                + "id BIGSERIAL PRIMARY KEY,"
                + "entry_type VARCHAR(10) NOT NULL,"
                + "entry_value VARCHAR(255) NOT NULL,"
                + "expires_at TIMESTAMPTZ NOT NULL,"
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())"
            );
        }
        this.blocklist = new DbRevocationBlocklist(new RevocationDao(ds));
    }

    @Test
    void jtiNotRevokedByDefault() {
        MatcherAssert.assertThat(
            this.blocklist.isRevokedJti("some-jti"),
            new IsEqual<>(false)
        );
    }

    @Test
    void revokesAndChecksJti() {
        this.blocklist.revokeJti("test-jti", 3600);
        MatcherAssert.assertThat(
            this.blocklist.isRevokedJti("test-jti"),
            new IsEqual<>(true)
        );
    }

    @Test
    void revokesAndChecksUser() {
        this.blocklist.revokeUser("alice@auto1.local", 3600);
        MatcherAssert.assertThat(
            this.blocklist.isRevokedUser("alice@auto1.local"),
            new IsEqual<>(true)
        );
    }

    @Test
    void unrevokedUserNotBlocked() {
        MatcherAssert.assertThat(
            this.blocklist.isRevokedUser("bob@auto1.local"),
            new IsEqual<>(false)
        );
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn test -pl pantera-main -Dtest="DbRevocationBlocklistTest" -q`
Expected: FAIL — `DbRevocationBlocklist` class does not exist

- [ ] **Step 5: Implement DbRevocationBlocklist**

Create `pantera-main/src/main/java/com/auto1/pantera/auth/DbRevocationBlocklist.java`:

```java
package com.auto1.pantera.auth;

import com.auto1.pantera.db.dao.RevocationDao;
import com.auto1.pantera.http.log.EcsLogger;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB-polling revocation blocklist for deployments without Valkey.
 * Polls the revocation_blocklist table every 5 seconds and caches entries in memory.
 * O(1) lookups via ConcurrentHashMap.
 */
public final class DbRevocationBlocklist implements RevocationBlocklist {

    private final RevocationDao dao;
    private final Map<String, Instant> jtiCache;
    private final Map<String, Instant> userCache;
    private volatile Instant lastPoll;

    public DbRevocationBlocklist(final RevocationDao dao) {
        this.dao = dao;
        this.jtiCache = new ConcurrentHashMap<>();
        this.userCache = new ConcurrentHashMap<>();
        this.lastPoll = Instant.now();
    }

    @Override
    public boolean isRevokedJti(final String jti) {
        this.pollIfStale();
        final Instant exp = this.jtiCache.get(jti);
        if (exp == null) {
            return false;
        }
        if (Instant.now().isAfter(exp)) {
            this.jtiCache.remove(jti);
            return false;
        }
        return true;
    }

    @Override
    public boolean isRevokedUser(final String username) {
        this.pollIfStale();
        final Instant exp = this.userCache.get(username);
        if (exp == null) {
            return false;
        }
        if (Instant.now().isAfter(exp)) {
            this.userCache.remove(username);
            return false;
        }
        return true;
    }

    @Override
    public void revokeJti(final String jti, final int ttlSeconds) {
        this.dao.insert("jti", jti, ttlSeconds);
        this.jtiCache.put(jti, Instant.now().plusSeconds(ttlSeconds));
    }

    @Override
    public void revokeUser(final String username, final int ttlSeconds) {
        this.dao.insert("username", username, ttlSeconds);
        this.userCache.put(username, Instant.now().plusSeconds(ttlSeconds));
    }

    /**
     * Poll DB for new entries if more than 5 seconds since last poll.
     */
    private void pollIfStale() {
        final Instant now = Instant.now();
        if (now.minusSeconds(5).isBefore(this.lastPoll)) {
            return;
        }
        try {
            for (final RevocationDao.RevocationEntry entry : this.dao.pollSince(this.lastPoll)) {
                if ("jti".equals(entry.entryType())) {
                    this.jtiCache.put(entry.entryValue(), entry.expiresAt());
                } else if ("username".equals(entry.entryType())) {
                    this.userCache.put(entry.entryValue(), entry.expiresAt());
                }
            }
            this.lastPoll = now;
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("Failed to poll revocation blocklist")
                .error(ex)
                .log();
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -pl pantera-main -Dtest="DbRevocationBlocklistTest" -q`
Expected: All 4 tests PASS

- [ ] **Step 7: Commit**

```bash
git add pantera-core/src/main/java/com/auto1/pantera/auth/RevocationBlocklist.java \
       pantera-main/src/main/java/com/auto1/pantera/db/dao/RevocationDao.java \
       pantera-main/src/main/java/com/auto1/pantera/auth/DbRevocationBlocklist.java \
       pantera-main/src/test/java/com/auto1/pantera/auth/DbRevocationBlocklistTest.java
git commit -m "feat: add RevocationBlocklist interface + DB polling implementation"
```

---

## Task 8: ValkeyRevocationBlocklist

**Files:**
- Create: `pantera-main/src/main/java/com/auto1/pantera/auth/ValkeyRevocationBlocklist.java`
- Create: `pantera-main/src/test/java/com/auto1/pantera/auth/ValkeyRevocationBlocklistTest.java`

- [ ] **Step 1: Write the failing test**

Create `pantera-main/src/test/java/com/auto1/pantera/auth/ValkeyRevocationBlocklistTest.java`:

```java
package com.auto1.pantera.auth;

import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.cache.ValkeyConnection;
import java.time.Duration;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ValkeyRevocationBlocklistTest {

    @Container
    static final GenericContainer<?> VALKEY =
        new GenericContainer<>("valkey/valkey:8.1.4")
            .withExposedPorts(6379);

    private ValkeyConnection valkey;
    private CacheInvalidationPubSub pubSub;
    private ValkeyRevocationBlocklist blocklist;

    @BeforeEach
    void setUp() {
        this.valkey = new ValkeyConnection(
            VALKEY.getHost(), VALKEY.getFirstMappedPort(), Duration.ofMillis(500), 2
        );
        this.pubSub = new CacheInvalidationPubSub(this.valkey);
        this.blocklist = new ValkeyRevocationBlocklist(this.valkey, this.pubSub, 3600);
    }

    @AfterEach
    void tearDown() {
        this.pubSub.close();
        this.valkey.close();
    }

    @Test
    void jtiNotRevokedByDefault() {
        MatcherAssert.assertThat(
            this.blocklist.isRevokedJti("some-jti"),
            new IsEqual<>(false)
        );
    }

    @Test
    void revokesAndChecksJti() {
        this.blocklist.revokeJti("test-jti", 3600);
        MatcherAssert.assertThat(
            this.blocklist.isRevokedJti("test-jti"),
            new IsEqual<>(true)
        );
    }

    @Test
    void revokesAndChecksUser() {
        this.blocklist.revokeUser("alice@auto1.local", 3600);
        MatcherAssert.assertThat(
            this.blocklist.isRevokedUser("alice@auto1.local"),
            new IsEqual<>(true)
        );
    }

    @Test
    void unrevokedUserNotBlocked() {
        MatcherAssert.assertThat(
            this.blocklist.isRevokedUser("bob@auto1.local"),
            new IsEqual<>(false)
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl pantera-main -Dtest="ValkeyRevocationBlocklistTest" -q`
Expected: FAIL — `ValkeyRevocationBlocklist` class does not exist

- [ ] **Step 3: Implement ValkeyRevocationBlocklist**

Create `pantera-main/src/main/java/com/auto1/pantera/auth/ValkeyRevocationBlocklist.java`:

```java
package com.auto1.pantera.auth;

import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.http.log.EcsLogger;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Valkey-backed revocation blocklist with pub/sub for instant multi-node propagation.
 * Uses existing CacheInvalidationPubSub infrastructure.
 * Also stores entries in Valkey SETs with TTL for restarting-node catch-up.
 */
public final class ValkeyRevocationBlocklist implements RevocationBlocklist {

    private static final String CACHE_TYPE = "revocation";
    private static final String JTI_PREFIX = "jti:";
    private static final String USER_PREFIX = "user:";
    private static final String VALKEY_JTI_SET = "pantera:revoked:jti";
    private static final String VALKEY_USER_SET = "pantera:revoked:user";

    private final ValkeyConnection valkey;
    private final CacheInvalidationPubSub pubSub;
    private final Map<String, Instant> jtiCache;
    private final Map<String, Instant> userCache;
    private final int defaultTtl;

    public ValkeyRevocationBlocklist(
        final ValkeyConnection valkey,
        final CacheInvalidationPubSub pubSub,
        final int defaultTtlSeconds
    ) {
        this.valkey = valkey;
        this.pubSub = pubSub;
        this.jtiCache = new ConcurrentHashMap<>();
        this.userCache = new ConcurrentHashMap<>();
        this.defaultTtl = defaultTtlSeconds;
        this.pubSub.register(CACHE_TYPE, new RevocationCacheHandler());
    }

    @Override
    public boolean isRevokedJti(final String jti) {
        final Instant exp = this.jtiCache.get(jti);
        if (exp != null && Instant.now().isBefore(exp)) {
            return true;
        }
        if (exp != null) {
            this.jtiCache.remove(jti);
        }
        return false;
    }

    @Override
    public boolean isRevokedUser(final String username) {
        final Instant exp = this.userCache.get(username);
        if (exp != null && Instant.now().isBefore(exp)) {
            return true;
        }
        if (exp != null) {
            this.userCache.remove(username);
        }
        return false;
    }

    @Override
    public void revokeJti(final String jti, final int ttlSeconds) {
        this.jtiCache.put(jti, Instant.now().plusSeconds(ttlSeconds));
        this.pubSub.publish(CACHE_TYPE, JTI_PREFIX + jti);
        try {
            this.valkey.async().setex(
                VALKEY_JTI_SET + ":" + jti, ttlSeconds, "1".getBytes()
            );
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("Failed to store JTI revocation in Valkey")
                .error(ex)
                .log();
        }
    }

    @Override
    public void revokeUser(final String username, final int ttlSeconds) {
        this.userCache.put(username, Instant.now().plusSeconds(ttlSeconds));
        this.pubSub.publish(CACHE_TYPE, USER_PREFIX + username);
        try {
            this.valkey.async().setex(
                VALKEY_USER_SET + ":" + username, ttlSeconds, "1".getBytes()
            );
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("Failed to store user revocation in Valkey")
                .error(ex)
                .log();
        }
    }

    /**
     * Handler invoked by CacheInvalidationPubSub when remote nodes publish revocations.
     */
    private final class RevocationCacheHandler implements Cleanable<String> {
        @Override
        public void invalidate(final String key) {
            if (key.startsWith(JTI_PREFIX)) {
                final String jti = key.substring(JTI_PREFIX.length());
                ValkeyRevocationBlocklist.this.jtiCache.put(
                    jti, Instant.now().plusSeconds(ValkeyRevocationBlocklist.this.defaultTtl)
                );
            } else if (key.startsWith(USER_PREFIX)) {
                final String user = key.substring(USER_PREFIX.length());
                ValkeyRevocationBlocklist.this.userCache.put(
                    user, Instant.now().plusSeconds(ValkeyRevocationBlocklist.this.defaultTtl)
                );
            }
        }

        @Override
        public void invalidateAll() {
            ValkeyRevocationBlocklist.this.jtiCache.clear();
            ValkeyRevocationBlocklist.this.userCache.clear();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl pantera-main -Dtest="ValkeyRevocationBlocklistTest" -q`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/auth/ValkeyRevocationBlocklist.java \
       pantera-main/src/test/java/com/auto1/pantera/auth/ValkeyRevocationBlocklistTest.java
git commit -m "feat: add ValkeyRevocationBlocklist with pub/sub propagation"
```

---

## Task 9: Update JwtSettings for RS256

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/settings/JwtSettings.java`

- [ ] **Step 1: Rewrite JwtSettings**

Replace the contents of `JwtSettings.java` to support RS256 key paths instead of HS256 secret:

```java
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.Optional;

/**
 * JWT token settings. Supports RS256 asymmetric key configuration.
 */
public final class JwtSettings {

    public static final int DEFAULT_EXPIRY_SECONDS = 86400;

    private final boolean expires;
    private final int expirySeconds;
    private final String privateKeyPath;
    private final String publicKeyPath;

    /**
     * Ctor with defaults (for tests / YAML-only mode without DB).
     */
    public JwtSettings() {
        this(false, DEFAULT_EXPIRY_SECONDS, null, null);
    }

    public JwtSettings(final boolean expires, final int expirySeconds,
        final String privateKeyPath, final String publicKeyPath) {
        this.expires = expires;
        this.expirySeconds = expirySeconds;
        this.privateKeyPath = privateKeyPath;
        this.publicKeyPath = publicKeyPath;
    }

    public boolean expires() {
        return this.expires;
    }

    public int expirySeconds() {
        return this.expirySeconds;
    }

    public Optional<String> privateKeyPath() {
        return Optional.ofNullable(this.privateKeyPath);
    }

    public Optional<String> publicKeyPath() {
        return Optional.ofNullable(this.publicKeyPath);
    }

    public Optional<Integer> optionalExpiry() {
        if (this.expires) {
            return Optional.of(this.expirySeconds);
        }
        return Optional.empty();
    }

    public static JwtSettings fromYaml(final YamlMapping meta) {
        if (meta == null) {
            return new JwtSettings();
        }
        final YamlMapping jwt = meta.yamlMapping("jwt");
        if (jwt == null) {
            return new JwtSettings();
        }
        // Fail fast if old HS256 secret config is present
        if (jwt.string("secret") != null) {
            throw new IllegalStateException(
                "HS256 secret configuration is no longer supported. "
                + "Migrate to RS256. Generate keys with: "
                + "openssl genrsa -out private.pem 2048 && "
                + "openssl rsa -in private.pem -pubout -out public.pem"
            );
        }
        final String expiresStr = jwt.string("expires");
        final boolean expires = expiresStr != null && Boolean.parseBoolean(expiresStr);
        int expirySeconds = DEFAULT_EXPIRY_SECONDS;
        final String expiryStr = jwt.string("expiry-seconds");
        if (expiryStr != null) {
            try {
                expirySeconds = Integer.parseInt(expiryStr.trim());
                if (expirySeconds <= 0) {
                    expirySeconds = DEFAULT_EXPIRY_SECONDS;
                }
            } catch (final NumberFormatException ex) {
                EcsLogger.warn("com.auto1.pantera.settings")
                    .message("Invalid JWT expiry-seconds value, using default")
                    .error(ex)
                    .log();
            }
        }
        final String privPath = resolveEnv(jwt.string("private-key-path"));
        final String pubPath = resolveEnv(jwt.string("public-key-path"));
        return new JwtSettings(expires, expirySeconds, privPath, pubPath);
    }

    private static String resolveEnv(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            final String envName = trimmed.substring(2, trimmed.length() - 1);
            final String envVal = System.getenv(envName);
            return envVal != null ? envVal : trimmed;
        }
        return trimmed;
    }
}
```

- [ ] **Step 2: Fix compilation errors in callers that used `secret()`**

The old `secret()` method is removed. Any caller that used `jwtSettings.secret()` (like `VertxMain.java:204`) will need updating in a later task. For now, verify this file compiles alone:

Run: `mvn compile -pl pantera-main -q 2>&1 | head -20`
Expected: Compilation errors in VertxMain.java referencing `secret()` — this is expected, will be fixed in Task 12.

- [ ] **Step 3: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/settings/JwtSettings.java
git commit -m "feat: rewrite JwtSettings for RS256 key paths, remove HS256 secret"
```

---

## Task 10: Rewrite JwtTokens with Auth0 java-jwt

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/auth/JwtTokens.java`
- Modify: `pantera-core/src/main/java/com/auto1/pantera/http/auth/Tokens.java`

- [ ] **Step 1: Update Tokens interface**

Add methods to `pantera-core/src/main/java/com/auto1/pantera/http/auth/Tokens.java`:

```java
package com.auto1.pantera.http.auth;

/**
 * Authentication tokens: generate token and provide authentication mechanism.
 */
public interface Tokens {

    TokenAuthentication auth();

    String generate(AuthUser user);

    default String generate(AuthUser user, boolean permanent) {
        return generate(user);
    }

    /**
     * Generate an access + refresh token pair for login/callback.
     * @param user Authenticated user
     * @return Token pair (access token, refresh token)
     */
    default TokenPair generatePair(AuthUser user) {
        throw new UnsupportedOperationException("Token pair generation not supported");
    }

    /**
     * Token pair containing both access and refresh tokens.
     */
    record TokenPair(String accessToken, String refreshToken, int expiresIn) {}
}
```

- [ ] **Step 2: Rewrite JwtTokens**

Replace `pantera-main/src/main/java/com/auto1/pantera/auth/JwtTokens.java`:

```java
package com.auto1.pantera.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;

/**
 * JWT token management using Auth0 java-jwt with RS256.
 */
public final class JwtTokens implements Tokens {

    private final Algorithm algorithm;
    private final RSAPublicKey publicKey;
    private final UserTokenDao tokenDao;
    private final AuthSettingsDao settingsDao;
    private final RevocationBlocklist blocklist;
    private final int defaultAccessTtl;
    private final int defaultRefreshTtl;

    /**
     * Full constructor for production use.
     */
    public JwtTokens(
        final RSAPrivateKey privateKey,
        final RSAPublicKey publicKey,
        final UserTokenDao tokenDao,
        final AuthSettingsDao settingsDao,
        final RevocationBlocklist blocklist
    ) {
        this.algorithm = Algorithm.RSA256(publicKey, privateKey);
        this.publicKey = publicKey;
        this.tokenDao = tokenDao;
        this.settingsDao = settingsDao;
        this.blocklist = blocklist;
        this.defaultAccessTtl = settingsDao != null
            ? settingsDao.getInt("access_token_ttl_seconds", 3600) : 3600;
        this.defaultRefreshTtl = settingsDao != null
            ? settingsDao.getInt("refresh_token_ttl_seconds", 604800) : 604800;
    }

    @Override
    public TokenAuthentication auth() {
        return new UnifiedJwtAuthHandler(
            this.publicKey, this.tokenDao, this.blocklist
        );
    }

    @Override
    public String generate(final AuthUser user) {
        return this.generateAccess(user);
    }

    @Override
    public String generate(final AuthUser user, final boolean permanent) {
        return permanent ? this.generateApiToken(user, 0, UUID.randomUUID(), "API Token")
            : this.generateAccess(user);
    }

    @Override
    public TokenPair generatePair(final AuthUser user) {
        final String access = this.generateAccess(user);
        final String refresh = this.generateRefresh(user);
        final int ttl = this.settingsDao != null
            ? this.settingsDao.getInt("access_token_ttl_seconds", 3600)
            : this.defaultAccessTtl;
        return new TokenPair(access, refresh, ttl);
    }

    /**
     * Generate an API token with specific expiry and label.
     * @param user User
     * @param expirySeconds 0 = permanent
     * @param jti Token UUID
     * @param label Human-readable label
     * @return Signed JWT string
     */
    public String generateApiToken(
        final AuthUser user, final int expirySeconds,
        final UUID jti, final String label
    ) {
        final var builder = JWT.create()
            .withSubject(user.name())
            .withClaim(AuthTokenRest.CONTEXT, user.authContext())
            .withClaim(AuthTokenRest.TYPE, TokenType.API.value())
            .withJWTId(jti.toString())
            .withIssuedAt(Instant.now());
        final Instant expiresAt;
        if (expirySeconds > 0) {
            expiresAt = Instant.now().plusSeconds(expirySeconds);
            builder.withExpiresAt(expiresAt);
        } else {
            expiresAt = null;
        }
        final String token = builder.sign(this.algorithm);
        if (this.tokenDao != null) {
            this.tokenDao.store(jti, user.name(), label, token, expiresAt, "api");
        }
        return token;
    }

    private String generateAccess(final AuthUser user) {
        final int ttl = this.settingsDao != null
            ? this.settingsDao.getInt("access_token_ttl_seconds", 3600)
            : this.defaultAccessTtl;
        return JWT.create()
            .withSubject(user.name())
            .withClaim(AuthTokenRest.CONTEXT, user.authContext())
            .withClaim(AuthTokenRest.TYPE, TokenType.ACCESS.value())
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(ttl))
            .sign(this.algorithm);
    }

    private String generateRefresh(final AuthUser user) {
        final int ttl = this.settingsDao != null
            ? this.settingsDao.getInt("refresh_token_ttl_seconds", 604800)
            : this.defaultRefreshTtl;
        final UUID jti = UUID.randomUUID();
        final Instant expiresAt = Instant.now().plusSeconds(ttl);
        final String token = JWT.create()
            .withSubject(user.name())
            .withClaim(AuthTokenRest.CONTEXT, user.authContext())
            .withClaim(AuthTokenRest.TYPE, TokenType.REFRESH.value())
            .withJWTId(jti.toString())
            .withIssuedAt(Instant.now())
            .withExpiresAt(expiresAt)
            .sign(this.algorithm);
        if (this.tokenDao != null) {
            this.tokenDao.store(jti, user.name(), "Refresh Token", token, expiresAt, "refresh");
        }
        return token;
    }
}
```

- [ ] **Step 3: Verify compilation (may have downstream errors — expected)**

Run: `mvn compile -pl pantera-main -q 2>&1 | head -30`
Expected: Some compilation errors in callers — will be fixed in subsequent tasks.

- [ ] **Step 4: Commit**

```bash
git add pantera-core/src/main/java/com/auto1/pantera/http/auth/Tokens.java \
       pantera-main/src/main/java/com/auto1/pantera/auth/JwtTokens.java
git commit -m "feat: rewrite JwtTokens with Auth0 java-jwt RS256 signing"
```

---

## Task 11: UnifiedJwtAuthHandler

**Files:**
- Create: `pantera-main/src/main/java/com/auto1/pantera/auth/UnifiedJwtAuthHandler.java`
- Create: `pantera-main/src/test/java/com/auto1/pantera/auth/UnifiedJwtAuthHandlerTest.java`

- [ ] **Step 1: Write the failing test**

Create `pantera-main/src/test/java/com/auto1/pantera/auth/UnifiedJwtAuthHandlerTest.java`:

```java
package com.auto1.pantera.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.http.auth.AuthUser;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class UnifiedJwtAuthHandlerTest {

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private Algorithm algorithm;
    private UnifiedJwtAuthHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        this.privateKey = (RSAPrivateKey) kp.getPrivate();
        this.publicKey = (RSAPublicKey) kp.getPublic();
        this.algorithm = Algorithm.RSA256(this.publicKey, this.privateKey);
        // No DB, no blocklist — signature-only mode
        this.handler = new UnifiedJwtAuthHandler(this.publicKey, null, null);
    }

    @Test
    void validAccessTokenReturnsUser() {
        final String token = JWT.create()
            .withSubject("alice@auto1.local")
            .withClaim(AuthTokenRest.CONTEXT, "okta")
            .withClaim(AuthTokenRest.TYPE, TokenType.ACCESS.value())
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
        final Optional<AuthUser> result =
            this.handler.user(token).toCompletableFuture().join();
        MatcherAssert.assertThat(result.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(result.get().name(), new IsEqual<>("alice@auto1.local"));
    }

    @Test
    void tokenWithoutTypeClaimIsRejected() {
        final String token = JWT.create()
            .withSubject("alice@auto1.local")
            .withClaim(AuthTokenRest.CONTEXT, "okta")
            .withJWTId(UUID.randomUUID().toString())
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
        final Optional<AuthUser> result =
            this.handler.user(token).toCompletableFuture().join();
        MatcherAssert.assertThat(result.isEmpty(), new IsEqual<>(true));
    }

    @Test
    void tokenWithoutJtiIsRejected() {
        final String token = JWT.create()
            .withSubject("alice@auto1.local")
            .withClaim(AuthTokenRest.CONTEXT, "okta")
            .withClaim(AuthTokenRest.TYPE, TokenType.ACCESS.value())
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
        final Optional<AuthUser> result =
            this.handler.user(token).toCompletableFuture().join();
        MatcherAssert.assertThat(result.isEmpty(), new IsEqual<>(true));
    }

    @Test
    void expiredTokenIsRejected() {
        final String token = JWT.create()
            .withSubject("alice@auto1.local")
            .withClaim(AuthTokenRest.CONTEXT, "okta")
            .withClaim(AuthTokenRest.TYPE, TokenType.ACCESS.value())
            .withJWTId(UUID.randomUUID().toString())
            .withExpiresAt(Instant.now().minusSeconds(60))
            .sign(this.algorithm);
        final Optional<AuthUser> result =
            this.handler.user(token).toCompletableFuture().join();
        MatcherAssert.assertThat(result.isEmpty(), new IsEqual<>(true));
    }

    @Test
    void wrongSignatureIsRejected() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair otherKp = gen.generateKeyPair();
        final Algorithm otherAlg = Algorithm.RSA256(
            (RSAPublicKey) otherKp.getPublic(),
            (RSAPrivateKey) otherKp.getPrivate()
        );
        final String token = JWT.create()
            .withSubject("alice@auto1.local")
            .withClaim(AuthTokenRest.CONTEXT, "okta")
            .withClaim(AuthTokenRest.TYPE, TokenType.ACCESS.value())
            .withJWTId(UUID.randomUUID().toString())
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(otherAlg);
        final Optional<AuthUser> result =
            this.handler.user(token).toCompletableFuture().join();
        MatcherAssert.assertThat(result.isEmpty(), new IsEqual<>(true));
    }

    @Test
    void invalidTokenStringIsRejected() {
        final Optional<AuthUser> result =
            this.handler.user("not.a.valid.token").toCompletableFuture().join();
        MatcherAssert.assertThat(result.isEmpty(), new IsEqual<>(true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl pantera-main -Dtest="UnifiedJwtAuthHandlerTest" -q`
Expected: FAIL — `UnifiedJwtAuthHandler` class does not exist

- [ ] **Step 3: Implement UnifiedJwtAuthHandler**

Create `pantera-main/src/main/java/com/auto1/pantera/auth/UnifiedJwtAuthHandler.java`:

```java
package com.auto1.pantera.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.log.EcsLogger;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;

/**
 * Unified JWT auth handler for both port 80 (artifact proxy) and port 8086 (management API).
 * Uses Auth0 java-jwt for RS256 verification.
 * Routes validation by token type: access → blocklist, refresh/api → DB check with username.
 */
public final class UnifiedJwtAuthHandler implements TokenAuthentication {

    private final JWTVerifier verifier;
    private final UserTokenDao tokenDao;
    private final RevocationBlocklist blocklist;

    /**
     * @param publicKey RSA public key for signature verification
     * @param tokenDao DAO for refresh/api token DB validation (null = no DB check)
     * @param blocklist Revocation blocklist for access tokens (null = no blocklist)
     */
    public UnifiedJwtAuthHandler(
        final RSAPublicKey publicKey,
        final UserTokenDao tokenDao,
        final RevocationBlocklist blocklist
    ) {
        this.verifier = JWT.require(Algorithm.RSA256(publicKey))
            .withClaimPresence(AuthTokenRest.JTI)
            .withClaimPresence(AuthTokenRest.TYPE)
            .withClaimPresence(AuthTokenRest.SUB)
            .withClaimPresence(AuthTokenRest.CONTEXT)
            .build();
        this.tokenDao = tokenDao;
        this.blocklist = blocklist;
    }

    @Override
    public CompletionStage<Optional<AuthUser>> user(final String token) {
        return CompletableFuture.supplyAsync(
            () -> this.validate(token),
            ForkJoinPool.commonPool()
        );
    }

    /**
     * Get the token type from a raw token string. Used for scope enforcement.
     * @param token Raw JWT string
     * @return TokenType or null if invalid
     */
    public TokenType tokenType(final String token) {
        try {
            final DecodedJWT decoded = this.verifier.verify(token);
            return TokenType.fromClaim(decoded.getClaim(AuthTokenRest.TYPE).asString());
        } catch (final JWTVerificationException ex) {
            return null;
        }
    }

    private Optional<AuthUser> validate(final String token) {
        final DecodedJWT decoded;
        try {
            decoded = this.verifier.verify(token);
        } catch (final JWTVerificationException ex) {
            return Optional.empty();
        }
        final String sub = decoded.getSubject();
        final String context = decoded.getClaim(AuthTokenRest.CONTEXT).asString();
        final String jti = decoded.getId();
        final TokenType type = TokenType.fromClaim(
            decoded.getClaim(AuthTokenRest.TYPE).asString()
        );
        if (sub == null || context == null || jti == null || type == null) {
            return Optional.empty();
        }
        switch (type) {
            case ACCESS:
                if (this.blocklist != null) {
                    if (this.blocklist.isRevokedJti(jti)
                        || this.blocklist.isRevokedUser(sub)) {
                        EcsLogger.warn("com.auto1.pantera.auth")
                            .message("Access token rejected: blocklisted")
                            .eventCategory("authentication")
                            .eventAction("token_validate")
                            .eventOutcome("failure")
                            .field("user.name", sub)
                            .log();
                        return Optional.empty();
                    }
                }
                break;
            case REFRESH:
            case API:
                if (this.tokenDao != null) {
                    try {
                        if (!this.tokenDao.isValidForUser(
                            UUID.fromString(jti), sub)) {
                            EcsLogger.warn("com.auto1.pantera.auth")
                                .message("Token rejected: JTI not found or wrong user")
                                .eventCategory("authentication")
                                .eventAction("token_validate")
                                .eventOutcome("failure")
                                .field("user.name", sub)
                                .log();
                            return Optional.empty();
                        }
                    } catch (final IllegalArgumentException ex) {
                        return Optional.empty();
                    }
                }
                break;
            default:
                return Optional.empty();
        }
        return Optional.of(new AuthUser(sub, context));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl pantera-main -Dtest="UnifiedJwtAuthHandlerTest" -q`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/auth/UnifiedJwtAuthHandler.java \
       pantera-main/src/test/java/com/auto1/pantera/auth/UnifiedJwtAuthHandlerTest.java
git commit -m "feat: add UnifiedJwtAuthHandler with Auth0 RS256, type routing, blocklist"
```

---

## Task 12: Wire Everything in VertxMain + AsyncApiVerticle

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/VertxMain.java`
- Modify: `pantera-main/src/main/java/com/auto1/pantera/api/v1/AsyncApiVerticle.java`

- [ ] **Step 1: Update VertxMain — replace HS256 JWTAuth with RsaKeyLoader**

In `VertxMain.java`, replace lines 201-206 (the `JWTAuth.create(HS256)` block) with:

```java
        final com.auto1.pantera.settings.JwtSettings jwtSettings = settings.jwtSettings();
        final com.auto1.pantera.auth.RsaKeyLoader rsaKeys;
        if (jwtSettings.privateKeyPath().isPresent() && jwtSettings.publicKeyPath().isPresent()) {
            rsaKeys = new com.auto1.pantera.auth.RsaKeyLoader(
                jwtSettings.privateKeyPath().get(),
                jwtSettings.publicKeyPath().get()
            );
        } else {
            throw new IllegalStateException(
                "JWT RS256 key paths are required. Configure meta.jwt.private-key-path "
                + "and meta.jwt.public-key-path in pantera.yaml"
            );
        }
```

Replace lines 217-222 (where JwtTokens is created with `JWTAuth`) with:

```java
        final com.auto1.pantera.db.dao.UserTokenDao userTokenDao = sharedDs
            .map(com.auto1.pantera.db.dao.UserTokenDao::new)
            .orElse(null);
        final com.auto1.pantera.db.dao.AuthSettingsDao authSettingsDao = sharedDs
            .map(com.auto1.pantera.db.dao.AuthSettingsDao::new)
            .orElse(null);
        // Set up revocation blocklist: Valkey if available, else DB polling
        final com.auto1.pantera.auth.RevocationBlocklist blocklist;
        if (valkeyConnection != null) {
            final int accessTtl = authSettingsDao != null
                ? authSettingsDao.getInt("access_token_ttl_seconds", 3600) : 3600;
            blocklist = new com.auto1.pantera.auth.ValkeyRevocationBlocklist(
                valkeyConnection, cachePubSub, accessTtl
            );
        } else if (sharedDs.isPresent()) {
            blocklist = new com.auto1.pantera.auth.DbRevocationBlocklist(
                new com.auto1.pantera.db.dao.RevocationDao(sharedDs.get())
            );
        } else {
            blocklist = null;
        }
        final RepositorySlices slices = new RepositorySlices(
            settings, repos,
            new com.auto1.pantera.auth.JwtTokens(
                rsaKeys.privateKey(), rsaKeys.publicKey(),
                userTokenDao, authSettingsDao, blocklist
            )
        );
```

Also pass `rsaKeys`, `authSettingsDao`, and `blocklist` into the `AsyncApiVerticle` constructor (or pass them via a shared context object — follow the existing pattern for how `jwt` is passed today).

- [ ] **Step 2: Update AsyncApiVerticle — replace JWTAuthHandler with UnifiedJwtAuthHandler**

In `AsyncApiVerticle.java`, replace lines 258-280 (the auth handler setup):

Replace the JwtTokens creation at line 260:
```java
        final JwtTokens tokens = new JwtTokens(
            this.rsaKeys.privateKey(), this.rsaKeys.publicKey(),
            this.dataSource != null ? new UserTokenDao(this.dataSource) : null,
            this.dataSource != null ? new AuthSettingsDao(this.dataSource) : null,
            this.blocklist
        );
```

Replace the JWT filter at lines 269-278:
```java
        // Unified JWT auth for all /api/v1/* routes
        final UnifiedJwtAuthHandler jwtHandler = (UnifiedJwtAuthHandler) tokens.auth();
        router.route("/api/v1/*").handler(ctx -> {
            if (ctx.request().path().contains("/artifact/download-direct")) {
                ctx.next();
                return;
            }
            final String authHeader = ctx.request().getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Bearer token required");
                return;
            }
            final String rawToken = authHeader.substring(7);
            jwtHandler.user(rawToken).toCompletionStage().toCompletableFuture()
                .thenAccept(userOpt -> {
                    if (userOpt.isPresent()) {
                        // Store auth info in routing context for downstream handlers
                        ctx.put("auth_user", userOpt.get());
                        ctx.put("raw_token", rawToken);
                        ctx.put("token_type", jwtHandler.tokenType(rawToken));
                        ctx.next();
                    } else {
                        ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Invalid or expired token");
                    }
                })
                .exceptionally(err -> {
                    ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Authentication failed");
                    return null;
                });
        });
```

Note: The existing code uses `ctx.user().principal()` to read claims. The new code stores `auth_user` in the routing context. AuthHandler endpoints need to read from `ctx.get("auth_user")` instead of `ctx.user().principal()`. This migration is handled in Task 13.

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl pantera-main -q`
Expected: Some errors in AuthHandler (still reads `ctx.user().principal()`) — fixed in next task.

- [ ] **Step 4: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/VertxMain.java \
       pantera-main/src/main/java/com/auto1/pantera/api/v1/AsyncApiVerticle.java
git commit -m "feat: wire RS256 keys, UnifiedJwtAuthHandler, revocation blocklist"
```

---

## Task 13: Update AuthHandler Endpoints

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/api/v1/AuthHandler.java`

This is the largest single change. The key modifications:

- [ ] **Step 1: Update login endpoint to return dual tokens**

Replace `tokenEndpoint()` (lines 116-151). Change the success response from:
```java
ctx.response().end(new JsonObject().put("token", token).encode());
```
to:
```java
final Tokens.TokenPair pair = this.tokens.generatePair(user.get());
ctx.response()
    .setStatusCode(200)
    .putHeader("Content-Type", "application/json")
    .end(new JsonObject()
        .put("token", pair.accessToken())
        .put("refresh_token", pair.refreshToken())
        .put("expires_in", pair.expiresIn())
        .encode());
```

- [ ] **Step 2: Update callback endpoint to return dual tokens**

In `callbackEndpoint()`, replace lines 524-530 (where the JWT is generated and returned). Change from returning a single token to returning a pair using the same pattern as Step 1.

The return type inside the `executeBlocking` changes from `String` to `Tokens.TokenPair`. The success handler sends both tokens.

- [ ] **Step 3: Update refresh endpoint to use refresh tokens**

Replace `refreshEndpoint()` (lines 626-644) to:
1. Read `raw_token` from context (set by `UnifiedJwtAuthHandler`)
2. Verify `token_type` is `REFRESH` — reject if not
3. Generate a new token pair (access + rotated refresh)
4. Revoke the old refresh token in DB
5. Return both new tokens

- [ ] **Step 4: Update generateTokenEndpoint for admin limits**

In `generateTokenEndpoint()` (lines 573-612):
1. Read `auth_user` from context instead of `ctx.user().principal()`
2. Verify `token_type` is `ACCESS` — reject API/refresh tokens
3. Validate `expiry_days` against `auth_settings`:
   - If `expiry_days > 0`: check `<= api_token_max_ttl_seconds / 86400`
   - If `expiry_days == 0`: check `api_token_allow_permanent == true`
4. Use `JwtTokens.generateApiToken()` instead of the old `generate()` calls

- [ ] **Step 5: Update all other endpoints to read from routing context**

Replace all occurrences of:
```java
ctx.user().principal().getString(AuthTokenRest.SUB)
```
with:
```java
((AuthUser) ctx.get("auth_user")).name()
```

And:
```java
ctx.user().principal().getString(AuthTokenRest.CONTEXT)
```
with:
```java
((AuthUser) ctx.get("auth_user")).authContext()
```

This affects: `meEndpoint()`, `listTokensEndpoint()`, `revokeTokenEndpoint()`.

- [ ] **Step 6: Verify compilation**

Run: `mvn compile -pl pantera-main -q`
Expected: Clean compilation

- [ ] **Step 7: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/api/v1/AuthHandler.java
git commit -m "feat: update auth endpoints for dual tokens, refresh rotation, admin limits"
```

---

## Task 14: AdminAuthHandler — Revoke User + Auth Settings API

**Files:**
- Create: `pantera-main/src/main/java/com/auto1/pantera/api/v1/AdminAuthHandler.java`

- [ ] **Step 1: Create AdminAuthHandler**

```java
package com.auto1.pantera.api.v1;

import com.auto1.pantera.auth.RevocationBlocklist;
import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;

/**
 * Admin-only auth management endpoints.
 */
public final class AdminAuthHandler {

    private final AuthSettingsDao settingsDao;
    private final UserTokenDao tokenDao;
    private final RevocationBlocklist blocklist;

    public AdminAuthHandler(
        final AuthSettingsDao settingsDao,
        final UserTokenDao tokenDao,
        final RevocationBlocklist blocklist
    ) {
        this.settingsDao = settingsDao;
        this.tokenDao = tokenDao;
        this.blocklist = blocklist;
    }

    public void register(final Router router) {
        router.get("/api/v1/admin/auth-settings").handler(this::getSettings);
        router.put("/api/v1/admin/auth-settings").handler(this::updateSettings);
        router.post("/api/v1/admin/revoke-user/:username").handler(this::revokeUser);
    }

    private void getSettings(final RoutingContext ctx) {
        if (this.settingsDao == null) {
            ApiResponse.sendError(ctx, 501, "NOT_IMPLEMENTED", "Database not configured");
            return;
        }
        ctx.vertx().<JsonObject>executeBlocking(
            () -> {
                final Map<String, String> all = this.settingsDao.getAll();
                final JsonObject result = new JsonObject();
                all.forEach(result::put);
                return result;
            }, false
        ).onSuccess(
            result -> ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(result.encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    private void updateSettings(final RoutingContext ctx) {
        if (this.settingsDao == null) {
            ApiResponse.sendError(ctx, 501, "NOT_IMPLEMENTED", "Database not configured");
            return;
        }
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null || body.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Request body required");
            return;
        }
        // Validate known keys
        final int accessTtl = body.getInteger("access_token_ttl_seconds", -1);
        if (accessTtl != -1 && accessTtl < 60) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "access_token_ttl_seconds must be >= 60");
            return;
        }
        ctx.vertx().<Void>executeBlocking(
            () -> {
                for (final String key : body.fieldNames()) {
                    this.settingsDao.put(key, body.getValue(key).toString());
                }
                return null;
            }, false
        ).onSuccess(
            v -> ctx.response().setStatusCode(204).end()
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    private void revokeUser(final RoutingContext ctx) {
        final String username = ctx.pathParam("username");
        if (username == null || username.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Username required");
            return;
        }
        ctx.vertx().<Integer>executeBlocking(
            () -> {
                // Revoke all tokens in DB
                final int count = this.tokenDao != null
                    ? this.tokenDao.revokeAllForUser(username) : 0;
                // Add to revocation blocklist for immediate access token rejection
                if (this.blocklist != null) {
                    this.blocklist.revokeUser(username, 7200);
                }
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("Admin revoked all tokens for user: " + username)
                    .eventCategory("authentication")
                    .eventAction("admin_revoke_user")
                    .eventOutcome("success")
                    .field("user.name", username)
                    .field("tokens_revoked", count)
                    .log();
                return count;
            }, false
        ).onSuccess(
            count -> ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("username", username)
                    .put("tokens_revoked", count)
                    .encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }
}
```

- [ ] **Step 2: Register in AsyncApiVerticle**

Add after the existing handler registrations (around line 314):

```java
        if (this.dataSource != null) {
            new AdminAuthHandler(
                new AuthSettingsDao(this.dataSource),
                new UserTokenDao(this.dataSource),
                this.blocklist
            ).register(router);
        }
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl pantera-main -q`
Expected: Clean compilation

- [ ] **Step 4: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/api/v1/AdminAuthHandler.java \
       pantera-main/src/main/java/com/auto1/pantera/api/v1/AsyncApiVerticle.java
git commit -m "feat: add admin auth endpoints for settings and user revocation"
```

---

## Task 15: Frontend — Dual Token Storage + Interceptor

**Files:**
- Modify: `pantera-ui/src/types/index.ts`
- Modify: `pantera-ui/src/api/client.ts`
- Modify: `pantera-ui/src/stores/auth.ts`
- Modify: `pantera-ui/src/api/auth.ts`

- [ ] **Step 1: Update TokenResponse type**

In `pantera-ui/src/types/index.ts`, replace:
```typescript
export interface TokenResponse {
  token: string
}
```
with:
```typescript
export interface TokenResponse {
  token: string
  refresh_token?: string
  expires_in?: number
}
```

- [ ] **Step 2: Update API client interceptor**

Replace the contents of `pantera-ui/src/api/client.ts`:

```typescript
import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'

let apiClient: AxiosInstance | null = null
let isRefreshing = false
let pendingRefreshQueue: Array<(newToken: string) => void> = []

const AUTH_BYPASS_URLS = ['/auth/token', '/auth/providers', '/auth/callback', '/auth/refresh']

function flushPendingQueue(newToken: string) {
  pendingRefreshQueue.forEach((resolve) => resolve(newToken))
  pendingRefreshQueue = []
}

function redirectToLogin() {
  localStorage.removeItem('access_token')
  localStorage.removeItem('refresh_token')
  // Clean up legacy key
  localStorage.removeItem('jwt')
  window.location.href = '/login'
}

export function initApiClient(baseUrl: string): AxiosInstance {
  apiClient = axios.create({
    baseURL: baseUrl,
    timeout: 10_000,
    headers: { 'Content-Type': 'application/json' },
  })
  apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('access_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })
  apiClient.interceptors.response.use(
    (response) => response,
    async (error) => {
      if (error.response?.status !== 401) {
        return Promise.reject(error)
      }
      const url: string = error.config?.url ?? ''
      if (AUTH_BYPASS_URLS.some((bypass) => url.includes(bypass))) {
        redirectToLogin()
        return Promise.reject(error)
      }
      if (isRefreshing) {
        return new Promise<unknown>((resolve) => {
          pendingRefreshQueue.push((newToken: string) => {
            error.config.headers.Authorization = `Bearer ${newToken}`
            resolve(apiClient!.request(error.config))
          })
        })
      }
      isRefreshing = true
      try {
        const refreshToken = localStorage.getItem('refresh_token')
        if (!refreshToken) {
          redirectToLogin()
          return Promise.reject(error)
        }
        const resp = await apiClient!.post<{ token: string; refresh_token?: string; expires_in?: number }>(
          '/auth/refresh',
          {},
          { headers: { Authorization: `Bearer ${refreshToken}` } }
        )
        const newAccessToken = resp.data.token
        localStorage.setItem('access_token', newAccessToken)
        if (resp.data.refresh_token) {
          localStorage.setItem('refresh_token', resp.data.refresh_token)
        }
        flushPendingQueue(newAccessToken)
        error.config.headers.Authorization = `Bearer ${newAccessToken}`
        return apiClient!.request(error.config)
      } catch {
        pendingRefreshQueue = []
        redirectToLogin()
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    },
  )
  return apiClient
}

export function getApiClient(): AxiosInstance {
  if (!apiClient) {
    throw new Error('API client not initialized. Call initApiClient() first.')
  }
  return apiClient
}
```

- [ ] **Step 3: Update auth store**

Replace the contents of `pantera-ui/src/stores/auth.ts`:

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { UserInfo, AuthProvider } from '@/types'
import * as authApi from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  // Migrate from legacy 'jwt' key
  const legacyToken = localStorage.getItem('jwt')
  if (legacyToken) {
    localStorage.removeItem('jwt')
    // Don't migrate — old HS256 tokens are invalid after RS256 cutover
  }

  const token = ref<string | null>(localStorage.getItem('access_token'))
  const refreshToken = ref<string | null>(localStorage.getItem('refresh_token'))
  const user = ref<UserInfo | null>(null)
  const providers = ref<AuthProvider[]>([])
  const loading = ref(false)

  const isAuthenticated = computed(() => !!token.value)
  const isAdmin = computed(() => {
    if (!user.value) return false
    return hasAction('api_user_permissions', 'write')
      || hasAction('api_role_permissions', 'write')
  })
  const username = computed(() => user.value?.name ?? '')

  async function login(uname: string, password: string) {
    loading.value = true
    try {
      const resp = await authApi.login(uname, password)
      token.value = resp.token
      localStorage.setItem('access_token', resp.token)
      if (resp.refresh_token) {
        refreshToken.value = resp.refresh_token
        localStorage.setItem('refresh_token', resp.refresh_token)
      }
      await fetchUser()
    } finally {
      loading.value = false
    }
  }

  async function fetchUser() {
    if (!token.value) return
    try {
      user.value = await authApi.getMe()
    } catch {
      logout()
    }
  }

  async function fetchProviders() {
    try {
      const resp = await authApi.getProviders()
      providers.value = resp.providers
    } catch {
      providers.value = []
    }
  }

  async function ssoRedirect(providerName: string) {
    const callbackUrl = window.location.origin + '/auth/callback'
    const resp = await authApi.getProviderRedirect(providerName, callbackUrl)
    sessionStorage.setItem('sso_state', resp.state)
    sessionStorage.setItem('sso_provider', providerName)
    sessionStorage.setItem('sso_callback_url', callbackUrl)
    window.location.href = resp.url
  }

  async function handleOAuthCallback(code: string, state: string) {
    const savedState = sessionStorage.getItem('sso_state')
    const provider = sessionStorage.getItem('sso_provider')
    const callbackUrl = sessionStorage.getItem('sso_callback_url')
    sessionStorage.removeItem('sso_state')
    sessionStorage.removeItem('sso_provider')
    sessionStorage.removeItem('sso_callback_url')
    if (!savedState || savedState !== state) {
      throw new Error('Invalid OAuth state — possible CSRF attack')
    }
    if (!provider || !callbackUrl) {
      throw new Error('Missing SSO session data')
    }
    loading.value = true
    try {
      const resp = await authApi.exchangeOAuthCode(code, provider, callbackUrl)
      token.value = resp.token
      localStorage.setItem('access_token', resp.token)
      if (resp.refresh_token) {
        refreshToken.value = resp.refresh_token
        localStorage.setItem('refresh_token', resp.refresh_token)
      }
      await fetchUser()
    } finally {
      loading.value = false
    }
  }

  function logout() {
    token.value = null
    refreshToken.value = null
    user.value = null
    localStorage.removeItem('access_token')
    localStorage.removeItem('refresh_token')
    localStorage.removeItem('jwt')
  }

  function hasAction(key: string, action: string): boolean {
    const perms = user.value?.permissions ?? {}
    const val = perms[key]
    if (!Array.isArray(val)) return false
    if (val.includes('*')) return true
    if (action === 'write') {
      return val.some(a => a !== 'read')
    }
    return val.includes(action)
  }

  return {
    token, refreshToken, user, providers, loading,
    isAuthenticated, isAdmin, username,
    login, logout, fetchUser, fetchProviders,
    ssoRedirect, handleOAuthCallback,
    hasAction,
  }
})
```

- [ ] **Step 4: Add admin API functions to auth.ts**

Add to the end of `pantera-ui/src/api/auth.ts`:

```typescript
// --- Admin Auth Settings ---

export async function getAuthSettings(): Promise<Record<string, string>> {
  const { data } = await getApiClient().get<Record<string, string>>('/admin/auth-settings')
  return data
}

export async function updateAuthSettings(settings: Record<string, string>): Promise<void> {
  await getApiClient().put('/admin/auth-settings', settings)
}

export async function revokeAllUserTokens(username: string): Promise<{ tokens_revoked: number }> {
  const { data } = await getApiClient().post<{ tokens_revoked: number }>(
    `/admin/revoke-user/${username}`
  )
  return data
}
```

- [ ] **Step 5: Verify frontend builds**

Run: `cd pantera-ui && npm run build`
Expected: Clean build

- [ ] **Step 6: Commit**

```bash
git add pantera-ui/src/types/index.ts \
       pantera-ui/src/api/client.ts \
       pantera-ui/src/stores/auth.ts \
       pantera-ui/src/api/auth.ts
git commit -m "feat(ui): dual token storage, refresh token interceptor, admin auth API"
```

---

## Task 16: Frontend — Auth Settings UI + Dynamic Token Options

**Files:**
- Modify: `pantera-ui/src/views/admin/SettingsView.vue`
- Modify: `pantera-ui/src/views/profile/ProfileView.vue`

- [ ] **Step 1: Add Authentication Settings card to SettingsView**

In `pantera-ui/src/views/admin/SettingsView.vue`, add imports and state for auth settings in the `<script setup>` section:

```typescript
import { getAuthSettings, updateAuthSettings } from '@/api/auth'

// Auth settings
const authAccessTtl = ref(3600)
const authRefreshTtl = ref(604800)
const authApiMaxTtl = ref(7776000)
const authAllowPermanent = ref(true)
```

In `onMounted`, add:
```typescript
    getAuthSettings().then(s => {
      authAccessTtl.value = parseInt(s.access_token_ttl_seconds ?? '3600')
      authRefreshTtl.value = parseInt(s.refresh_token_ttl_seconds ?? '604800')
      authApiMaxTtl.value = parseInt(s.api_token_max_ttl_seconds ?? '7776000')
      authAllowPermanent.value = s.api_token_allow_permanent === 'true'
    }).catch(() => {})
```

Add save function:
```typescript
async function saveAuthSettings() {
  saving.value = 'auth'
  try {
    await updateAuthSettings({
      access_token_ttl_seconds: String(authAccessTtl.value),
      refresh_token_ttl_seconds: String(authRefreshTtl.value),
      api_token_max_ttl_seconds: String(authApiMaxTtl.value),
      api_token_allow_permanent: String(authAllowPermanent.value),
    })
    notify.success('Authentication settings saved')
  } catch {
    notify.error('Failed to save authentication settings')
  } finally {
    saving.value = null
  }
}
```

In the `<template>`, add a new Card after the JWT card:

```html
      <!-- Authentication Policy -->
      <Card class="shadow-sm">
        <template #title>Authentication Policy</template>
        <template #content>
          <div class="space-y-4">
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="text-sm text-gray-500 block mb-1">Access Token TTL (seconds)</label>
                <InputNumber v-model="authAccessTtl" :min="60" :max="86400" class="w-full" />
                <span class="text-xs text-gray-400">Default: 3600 (1 hour)</span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">Refresh Token TTL (seconds)</label>
                <InputNumber v-model="authRefreshTtl" :min="3600" :max="2592000" class="w-full" />
                <span class="text-xs text-gray-400">Default: 604800 (7 days)</span>
              </div>
              <div>
                <label class="text-sm text-gray-500 block mb-1">API Token Max TTL (seconds)</label>
                <InputNumber v-model="authApiMaxTtl" :min="86400" :max="31536000" class="w-full" />
                <span class="text-xs text-gray-400">Default: 7776000 (90 days)</span>
              </div>
            </div>
            <div class="flex items-center gap-3">
              <InputSwitch v-model="authAllowPermanent" />
              <span class="text-sm">Allow permanent API tokens (no expiry)</span>
            </div>
            <Button
              label="Save Auth Settings"
              icon="pi pi-save"
              size="small"
              :loading="saving === 'auth'"
              @click="saveAuthSettings"
            />
          </div>
        </template>
      </Card>
```

- [ ] **Step 2: Update ProfileView for dynamic expiry options**

In `pantera-ui/src/views/profile/ProfileView.vue`, update the expiry options to be loaded from admin settings:

```typescript
import { getAuthSettings } from '@/api/auth'

// Replace static expiryOptions with dynamic ones
const expiryOptions = ref([
  { label: '30 days', value: 30 },
  { label: '90 days', value: 90 },
])

onMounted(async () => {
  loadTokens()
  try {
    const settings = await getAuthSettings()
    const maxTtlDays = Math.floor(parseInt(settings.api_token_max_ttl_seconds ?? '7776000') / 86400)
    const allowPermanent = settings.api_token_allow_permanent === 'true'
    const opts = []
    if (maxTtlDays >= 30) opts.push({ label: '30 days', value: 30 })
    if (maxTtlDays >= 90) opts.push({ label: '90 days', value: 90 })
    if (maxTtlDays >= 365) opts.push({ label: '1 year', value: 365 })
    if (allowPermanent) opts.push({ label: 'Permanent (no expiry)', value: 0 })
    if (opts.length > 0) expiryOptions.value = opts
  } catch {
    // Fallback to defaults if settings unavailable
  }
})
```

- [ ] **Step 3: Verify frontend builds**

Run: `cd pantera-ui && npm run build`
Expected: Clean build

- [ ] **Step 4: Commit**

```bash
git add pantera-ui/src/views/admin/SettingsView.vue \
       pantera-ui/src/views/profile/ProfileView.vue
git commit -m "feat(ui): auth settings admin panel, dynamic API token expiry options"
```

---

## Task 17: Update Existing Tests + Full Test Suite

**Files:**
- Modify: `pantera-main/src/test/java/com/auto1/pantera/auth/JwtTokenAuthTest.java`

- [ ] **Step 1: Update JwtTokenAuthTest for RS256**

The old test uses HS256 with Vert.x JWTAuth. Since `JwtTokenAuth` is being replaced by `UnifiedJwtAuthHandler`, either:
- **Option A:** Delete `JwtTokenAuthTest.java` (the class is deprecated/removed)
- **Option B:** Update it to test backward-compat if the class is kept

Recommended: Delete the file since `UnifiedJwtAuthHandlerTest` covers all scenarios.

```bash
git rm pantera-main/src/test/java/com/auto1/pantera/auth/JwtTokenAuthTest.java
```

- [ ] **Step 2: Run full test suite**

Run: `mvn test -pl pantera-main -Dexclude="**/LocateHitRateTest.java" -q`
Expected: All tests pass. If there are compilation/test failures, fix them.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: update test suite for RS256 migration, remove obsolete HS256 tests"
```

---

## Task 18: Update Docker Compose + Example Config

**Files:**
- Modify: `pantera-main/docker-compose/docker-compose.yaml`
- Modify: example YAML config files

- [ ] **Step 1: Update docker-compose for RS256 keys**

Add key generation to the Pantera service and mount key files. Add environment variables:

```yaml
    environment:
      JWT_PRIVATE_KEY_PATH: /etc/pantera/jwt-private.pem
      JWT_PUBLIC_KEY_PATH: /etc/pantera/jwt-public.pem
```

- [ ] **Step 2: Update example YAML config**

In `pantera-main/src/main/resources/swagger-ui/yaml/` or the example config, update the JWT section:

```yaml
meta:
  jwt:
    algorithm: RS256
    private-key-path: ${JWT_PRIVATE_KEY_PATH}
    public-key-path: ${JWT_PUBLIC_KEY_PATH}
```

- [ ] **Step 3: Commit**

```bash
git add pantera-main/docker-compose/docker-compose.yaml
git commit -m "chore: update docker-compose and example config for RS256"
```

---

## Task 19: Final Integration Test + Cleanup

- [ ] **Step 1: Full compilation check**

Run: `mvn compile -q`
Expected: Clean compilation across all modules

- [ ] **Step 2: Full test suite**

Run: `mvn test -pl pantera-main -Dexclude="**/LocateHitRateTest.java"`
Expected: All tests pass

- [ ] **Step 3: Frontend build check**

Run: `cd pantera-ui && npm run build`
Expected: Clean build

- [ ] **Step 4: Remove dead code**

If `JwtTokenAuth.java` is no longer referenced anywhere:
```bash
git rm pantera-main/src/main/java/com/auto1/pantera/auth/JwtTokenAuth.java
```

Remove any unused Vert.x JWT imports from files that no longer need them.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "chore: cleanup dead code, verify full build"
```

---

## Task 20: Documentation Update

**Files:**
- Modify: `docs/admin-guide/` — admin-facing docs
- Modify: `docs/user-guide/` — user-facing docs
- Modify: `docs/developer-guide.md` or `docs/developer-guide/` — developer-facing docs
- Modify: `docs/configuration-reference.md` — config reference
- Modify: `docs/rest-api-reference.md` — API reference

- [ ] **Step 1: Update Admin Guide**

Add/update a security section in the admin guide covering:
- **RS256 Key Management:** How to generate RSA key pair (`openssl genrsa ...`), where to place files, env var configuration (`JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH`)
- **Migration from HS256:** Step-by-step migration procedure (generate keys, set env vars, deploy, announce token invalidation)
- **Auth Settings (UI):** How to configure access token TTL, refresh token TTL, API token max TTL, permanent token toggle — via the admin settings page
- **User Revocation:** How to immediately revoke all tokens for a user via admin UI ("Revoke All Tokens" button) and what it does (blocklists username across all nodes, revokes DB tokens)
- **Revocation Blocklist:** Explain the two modes (Valkey pub/sub for multi-node, DB polling for single-node), how instant revocation works, and the propagation delay in DB-only mode (max 5 seconds)
- **Token Types:** Explain access (1h, auto-refreshed), refresh (7d, session lifetime), API (user-chosen, permanent option) tokens and how they interact

- [ ] **Step 2: Update User Guide**

Add/update sections covering:
- **Login & Sessions:** Explain that sessions auto-refresh silently. Users will be logged out after 7 days of inactivity (configurable by admin). No action needed from users.
- **API Tokens:** Updated token generation flow — users can choose expiry (30d, 90d, 1yr, permanent depending on admin config). Explain that `expiry_days: 0` means permanent (valid until revoked). Tokens are shown once and cannot be retrieved later.
- **Token Migration:** After the RS256 upgrade, all existing tokens are invalidated. Users must re-login and regenerate API tokens. CI/CD pipelines need updated tokens.
- **Security:** Tokens are now signed with asymmetric cryptography (RS256). Even if someone obtains the public key, they cannot forge tokens.

- [ ] **Step 3: Update Developer Guide**

Add/update sections covering:
- **Auth Architecture Overview:** Describe the three token types (access/refresh/API), `UnifiedJwtAuthHandler`, `RevocationBlocklist` interface, and how Auth0 java-jwt is used
- **Adding New Protected Endpoints:** How to read the authenticated user from `ctx.get("auth_user")` in Vert.x handlers. How to enforce token type restrictions (e.g., API tokens rejected on management API).
- **Token Generation:** How `JwtTokens.generatePair()` works for login, `generateApiToken()` for API tokens
- **Revocation Blocklist:** How to add entries (`revokeJti()`, `revokeUser()`), how Valkey vs DB implementations work, how to test with Testcontainers
- **Testing Auth:** How to create test tokens with Auth0 java-jwt in unit tests (generate RSA key pair in `@BeforeEach`, sign tokens with `Algorithm.RSA256()`)
- **Configuration Reference Update:** New YAML fields (`meta.jwt.private-key-path`, `meta.jwt.public-key-path`), removal of `meta.jwt.secret`

- [ ] **Step 4: Update REST API Reference**

Document the changed/new endpoints:
- `POST /api/v1/auth/token` — now returns `{token, refresh_token, expires_in}`
- `POST /api/v1/auth/callback` — now returns `{token, refresh_token, expires_in}`
- `POST /api/v1/auth/refresh` — accepts refresh token in Authorization header, returns new token pair
- `POST /api/v1/auth/token/generate` — `expiry_days: 0` for permanent, validated against admin limits
- `GET /api/v1/admin/auth-settings` — new admin endpoint, returns key-value settings
- `PUT /api/v1/admin/auth-settings` — new admin endpoint, updates settings
- `POST /api/v1/admin/revoke-user/:username` — new admin endpoint, revokes all user tokens

- [ ] **Step 5: Update Configuration Reference**

Update `docs/configuration-reference.md` with:
- New JWT config block (RS256 key paths, removal of secret)
- `auth_settings` table defaults and meanings
- Environment variables: `JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH`

- [ ] **Step 6: Commit**

```bash
git add docs/
git commit -m "docs: update admin, user, developer, and API docs for JWT security hardening"
```

---

## Task 21: Raise Pull Request

- [ ] **Step 1: Push feature branch**

```bash
git push -u origin feat/jwt-security-hardening
```

- [ ] **Step 2: Create pull request**

```bash
gh pr create --title "feat: JWT security hardening — RS256, unified auth, revocation blocklist" --body "$(cat <<'EOF'
## Summary

- **Replaces HS256 shared-secret JWT with RS256 asymmetric signing** using Auth0 java-jwt library. The old secret was publicly visible in the OSS repo.
- **Unified auth handler** (`UnifiedJwtAuthHandler`) replaces both `JwtTokenAuth` (port 80) and raw Vert.x `JWTAuthHandler` (port 8086). Single code path, single set of security guarantees.
- **Access + Refresh + API token architecture**: access tokens (1h, no DB hit), refresh tokens (7d, DB-backed), API tokens (user-chosen TTL, permanent option).
- **Multi-node revocation blocklist**: Valkey pub/sub for instant propagation (reuses existing `CacheInvalidationPubSub`), DB polling fallback for single-node deployments.
- **JTI + username validation** closes the token ownership vulnerability (forged token with stolen JTI).
- **Admin UI settings** for token TTLs and permanent token policy.
- **User revocation** endpoint for immediate token invalidation across all nodes.

## Breaking Changes

- All existing HS256 tokens are invalidated (hard cutover)
- `meta.jwt.secret` config replaced with `meta.jwt.private-key-path` + `meta.jwt.public-key-path`
- Login/callback endpoints now return `{token, refresh_token, expires_in}` instead of `{token}`
- API tokens on management API (port 8086) are rejected — use access tokens instead

## Security Fixes

1. HS256 shared secret → RS256 asymmetric keys (cannot forge tokens even with public key)
2. Management API (port 8086) now validates JTI + token ownership
3. `SELECT 1 FROM user_tokens WHERE id = ? AND username = ? AND revoked = FALSE` (was missing `username`)
4. Token type claim prevents cross-use (refresh token can't be used as access token, etc.)

## Test plan

- [ ] Unit tests pass: `mvn test -pl pantera-main`
- [ ] Frontend builds: `cd pantera-ui && npm run build`
- [ ] Generate RSA key pair and verify startup with RS256 config
- [ ] Login returns dual tokens (access + refresh)
- [ ] Access token expires after TTL, silent refresh works
- [ ] API token generation respects admin limits
- [ ] Permanent API token (`expiry_days: 0`) works when allowed
- [ ] Token revocation propagates across nodes (Valkey pub/sub)
- [ ] Admin user revocation immediately blocks all user access
- [ ] Old HS256 tokens are rejected with clear 401
- [ ] Forged token with wrong RS256 key → 401
- [ ] Token with valid JTI but wrong username → 401

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Verify PR was created**

Run: `gh pr view --web`
Expected: PR opens in browser