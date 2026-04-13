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

import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.http.auth.AuthUser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Optional;
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
 * Tests for {@link AuthFromDb} — in particular the
 * {@link AuthFromDb#isAuthoritative(String)} authority check introduced
 * in 2.1.0 to stop SSO fall-through from bypassing a local password.
 *
 * <p>Uses a real PostgreSQL via Testcontainers because the method
 * queries the users table directly.</p>
 */
@Testcontainers
class AuthFromDbTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    static HikariDataSource ds;

    AuthFromDb auth;

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
        this.auth = new AuthFromDb(ds);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM user_roles");
            conn.createStatement().execute("DELETE FROM users");
        }
    }

    private void insertUser(
        final String username, final String passwordHash,
        final String provider, final boolean enabled
    ) throws Exception {
        try (var conn = ds.getConnection();
            var ps = conn.prepareStatement(
                "INSERT INTO users (username, password_hash, auth_provider, enabled) "
                    + "VALUES (?, ?, ?, ?)"
            )) {
            ps.setString(1, username);
            if (passwordHash == null) {
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(2, passwordHash);
            }
            ps.setString(3, provider);
            ps.setBoolean(4, enabled);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------
    // isAuthoritative tests
    // ---------------------------------------------------------------

    @Test
    void isAuthoritativeTrueForLocalUserWithRealHash() throws Exception {
        insertUser("admin", BCrypt.hashpw("SecurePass!234", BCrypt.gensalt()),
            "local", true);
        assertTrue(
            this.auth.isAuthoritative("admin"),
            "local user with real bcrypt hash must be authoritative"
        );
    }

    @Test
    void isAuthoritativeFalseForLocalUserWithEmptyHash() throws Exception {
        // SSO-provisioned placeholder — has auth_provider='local' but no password
        insertUser("ayd", "", "local", true);
        assertFalse(
            this.auth.isAuthoritative("ayd"),
            "user without a real password must NOT be authoritative — "
                + "otherwise SSO fall-through is blocked for legitimate Keycloak users"
        );
    }

    @Test
    void isAuthoritativeFalseForLocalUserWithNullHash() throws Exception {
        insertUser("sso-user", null, "local", true);
        assertFalse(
            this.auth.isAuthoritative("sso-user"),
            "user with NULL password_hash must NOT be authoritative"
        );
    }

    @Test
    void isAuthoritativeFalseForDisabledUser() throws Exception {
        insertUser("disabled", BCrypt.hashpw("Secure!234567", BCrypt.gensalt()),
            "local", false);
        assertFalse(
            this.auth.isAuthoritative("disabled"),
            "disabled users must NOT be authoritative"
        );
    }

    @Test
    void isAuthoritativeFalseForNonLocalProvider() throws Exception {
        insertUser("okta-user",
            BCrypt.hashpw("Secure!234567", BCrypt.gensalt()),
            "okta", true);
        assertFalse(
            this.auth.isAuthoritative("okta-user"),
            "non-local provider users must NOT be authoritative via AuthFromDb"
        );
    }

    @Test
    void isAuthoritativeFalseForUnknownUser() {
        assertFalse(
            this.auth.isAuthoritative("no-such-user"),
            "unknown users must NOT be authoritative"
        );
    }

    @Test
    void isAuthoritativeFalseForNullOrEmpty() {
        assertFalse(this.auth.isAuthoritative(null));
        assertFalse(this.auth.isAuthoritative(""));
    }

    // ---------------------------------------------------------------
    // user(name, pass) bcrypt verification — sanity check
    // ---------------------------------------------------------------

    @Test
    void userAuthenticatesWithCorrectPassword() throws Exception {
        insertUser("alice", BCrypt.hashpw("Correct!Horse123", BCrypt.gensalt()),
            "local", true);
        final Optional<AuthUser> result = this.auth.user("alice", "Correct!Horse123");
        assertTrue(result.isPresent(), "correct password should succeed");
        assertEquals("alice", result.get().name());
    }

    @Test
    void userRejectsWrongPassword() throws Exception {
        insertUser("bob", BCrypt.hashpw("TheRealPass!1", BCrypt.gensalt()),
            "local", true);
        final Optional<AuthUser> result = this.auth.user("bob", "wrong-one");
        assertTrue(result.isEmpty(), "wrong password must fail");
    }

    @Test
    void userRejectsDisabledUser() throws Exception {
        insertUser("eve", BCrypt.hashpw("Correct!Horse123", BCrypt.gensalt()),
            "local", false);
        final Optional<AuthUser> result = this.auth.user("eve", "Correct!Horse123");
        assertTrue(result.isEmpty(), "disabled users must not authenticate");
    }

    @Test
    void userRejectsNonLocalProvider() throws Exception {
        // SSO-provisioned user with a password hash shouldn't be authenticated
        // by AuthFromDb — only the 'local' provider rows are eligible.
        insertUser("keycloak-user",
            BCrypt.hashpw("Correct!Horse123", BCrypt.gensalt()),
            "keycloak", true);
        final Optional<AuthUser> result =
            this.auth.user("keycloak-user", "Correct!Horse123");
        assertTrue(result.isEmpty(),
            "non-local provider rows must not authenticate via AuthFromDb");
    }
}
