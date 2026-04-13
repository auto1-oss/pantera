/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.auth;

import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.db.dao.UserDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.settings.cache.CachedUsers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test that would have caught the "old password
 * still works after change" bug in production.
 *
 * <p>The scenario: a user logs in successfully, their credentials get
 * cached in {@link CachedUsers}. They then change their password. The
 * next login attempt with the OLD password must fail — the cache must
 * not serve the previous success. Before the 2.1.0 fix, calling
 * {@code ucache.invalidate(username)} was a silent no-op (the cache is
 * keyed by {@code SHA-256(username:password)}) so the L1 entry stayed
 * live for the full TTL (5 minutes).</p>
 *
 * <p>This test exercises the full stack: real PostgreSQL via
 * Testcontainers, real {@link AuthFromDb} on top of it, real
 * {@link CachedUsers} wrapping it, real {@link UserDao#alterPassword}
 * doing the bcrypt update, and the same {@code invalidate} call path
 * that {@code UserHandler.alterPassword} uses in production.</p>
 */
@Testcontainers
class PasswordChangeCacheFlushIT {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    static HikariDataSource ds;

    UserDao userDao;
    AuthFromDb dbAuth;
    CachedUsers cached;

    @BeforeAll
    static void setup() {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(2);
        ds = new HikariDataSource(cfg);
        DbManager.migrate(ds);
    }

    @AfterAll
    static void teardown() {
        if (ds != null) {
            ds.close();
        }
    }

    @BeforeEach
    void init() throws Exception {
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM user_roles");
            conn.createStatement().execute("DELETE FROM users");
            conn.createStatement().execute("DELETE FROM roles");
        }
        this.userDao = new UserDao(ds);
        this.dbAuth = new AuthFromDb(ds);
        this.cached = new CachedUsers(this.dbAuth);
    }

    private void createLocalUser(final String name, final String pass)
        throws Exception {
        try (var conn = ds.getConnection();
            var ps = conn.prepareStatement(
                "INSERT INTO users (username, password_hash, auth_provider, enabled) "
                    + "VALUES (?, ?, 'local', true)"
            )) {
            ps.setString(1, name);
            ps.setString(2, BCrypt.hashpw(pass, BCrypt.gensalt()));
            ps.executeUpdate();
        }
    }

    @Test
    void oldPasswordRejectedAfterChange() throws Exception {
        createLocalUser("admin", "OldPassword!1");

        // 1. Log in with the old password — populates the cache.
        final Optional<AuthUser> firstLogin =
            this.cached.user("admin", "OldPassword!1");
        assertTrue(firstLogin.isPresent(),
            "initial login with correct password should succeed");

        // 2. Verify subsequent login is cache-served (no DB hit).
        //    We can't observe this directly, but we can verify it's still
        //    present in the cache at this point.
        assertTrue(this.cached.user("admin", "OldPassword!1").isPresent(),
            "cached login still works");

        // 3. Change the password via UserDao — this is what UserHandler
        //    calls in production.
        final JsonObject passInfo = Json.createObjectBuilder()
            .add("new_pass", "NewCompliant!Pass234")
            .build();
        this.userDao.alterPassword("admin", passInfo);

        // 4. Invalidate the auth cache using the same call path that
        //    UserHandler.alterPassword uses: the Cleanable interface.
        //    This is where the bug was — calling invalidate(username)
        //    used to be a no-op.
        final Cleanable<String> asCleanable = this.cached;
        asCleanable.invalidate("admin");

        // 5. ATTEMPT TO LOG IN WITH THE OLD PASSWORD.
        //    Before the fix this would return a cached success.
        //    After the fix the cache is flushed and the DB hash is
        //    new, so AuthFromDb rejects it.
        final Optional<AuthUser> oldPasswordAttempt =
            this.cached.user("admin", "OldPassword!1");
        assertTrue(
            oldPasswordAttempt.isEmpty(),
            "OLD password MUST be rejected after a password change. "
                + "If this assertion fails, the cache invalidation is "
                + "broken again — this is the exact bug we fixed."
        );

        // 6. The new password works.
        final Optional<AuthUser> newPasswordLogin =
            this.cached.user("admin", "NewCompliant!Pass234");
        assertTrue(newPasswordLogin.isPresent(),
            "new password should authenticate");
    }

    @Test
    void invalidateByUsernameDirectlyAlsoWorks() throws Exception {
        createLocalUser("alice", "Secure!Pass123");

        assertTrue(this.cached.user("alice", "Secure!Pass123").isPresent());

        // Change via DAO
        this.userDao.alterPassword("alice", Json.createObjectBuilder()
            .add("new_pass", "AnotherCompliant!42")
            .build());

        // Flush directly via the typed API
        this.cached.invalidateByUsername("alice");

        assertTrue(
            this.cached.user("alice", "Secure!Pass123").isEmpty(),
            "direct invalidateByUsername call must also flush the cache"
        );
        assertTrue(
            this.cached.user("alice", "AnotherCompliant!42").isPresent()
        );
    }

    @Test
    void mustChangePasswordClearedAfterComplaintChange() throws Exception {
        // Simulate the bootstrap admin: must_change_password = true
        try (var conn = ds.getConnection();
            var ps = conn.prepareStatement(
                "INSERT INTO users (username, password_hash, auth_provider, "
                    + "enabled, must_change_password) "
                    + "VALUES (?, ?, 'local', true, true)"
            )) {
            ps.setString(1, "admin");
            ps.setString(2, BCrypt.hashpw("admin", BCrypt.gensalt()));
            ps.executeUpdate();
        }
        // The force-change flow sends the policy-compliant password
        this.userDao.alterPassword("admin", Json.createObjectBuilder()
            .add("new_pass", "Compliant!Pass999")
            .build());
        // must_change_password flag should now be cleared
        try (var conn = ds.getConnection();
            var ps = conn.prepareStatement(
                "SELECT must_change_password FROM users WHERE username = 'admin'"
            );
            var rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertFalse(rs.getBoolean(1),
                "must_change_password must be cleared after a successful change"
            );
        }
    }

    @Test
    void weakPasswordRejectedByPolicy() throws Exception {
        createLocalUser("bob", "InitialPass!1");
        // Weak password should be rejected BEFORE the DB is touched
        final JsonObject weak = Json.createObjectBuilder()
            .add("new_pass", "short")
            .build();
        assertThrows(
            IllegalArgumentException.class,
            () -> this.userDao.alterPassword("bob", weak),
            "PasswordPolicy must reject weak passwords"
        );
        // Original password should still work — the change never happened
        assertTrue(
            this.cached.user("bob", "InitialPass!1").isPresent(),
            "original password should still authenticate after rejected change"
        );
    }
}
