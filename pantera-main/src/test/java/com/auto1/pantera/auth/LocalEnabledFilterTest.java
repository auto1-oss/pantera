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
import com.auto1.pantera.http.auth.Authentication;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link LocalEnabledFilter} — the wrapper that
 * rejects successful authentications whose local user row is disabled.
 *
 * <p>Guards the "disabled user can still pull artifacts via basic auth"
 * bug: before this fix, a user disabled in Pantera but still valid at
 * an upstream SSO provider (Keycloak direct grant, Okta ROPC, ...)
 * could continue to authenticate via HTTP basic auth on the
 * Authentication chain, because only {@link AuthFromDb} checked the
 * local enabled flag — SSO providers did not.</p>
 */
@Testcontainers
class LocalEnabledFilterTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    static HikariDataSource ds;

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
        }
    }

    private void insertUser(final String name, final boolean enabled) throws Exception {
        try (var conn = ds.getConnection();
            var ps = conn.prepareStatement(
                "INSERT INTO users (username, password_hash, auth_provider, enabled) "
                    + "VALUES (?, 'x', 'keycloak', ?)"
            )) {
            ps.setString(1, name);
            ps.setBoolean(2, enabled);
            ps.executeUpdate();
        }
    }

    /** A fake SSO provider that always authenticates any credentials. */
    private static final class AlwaysOk implements Authentication {
        final AtomicInteger calls = new AtomicInteger();
        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            this.calls.incrementAndGet();
            return Optional.of(new AuthUser(name, "keycloak"));
        }
    }

    /** A fake provider that always rejects. */
    private static final class AlwaysFail implements Authentication {
        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------
    // Core semantics: disabled users are rejected, enabled are not
    // -----------------------------------------------------------

    @Test
    void rejectsEnabledSsoUserWhoIsDisabledLocally() throws Exception {
        insertUser("ayd", false);
        final AlwaysOk inner = new AlwaysOk();
        final LocalEnabledFilter filter = new LocalEnabledFilter(inner, ds);

        final Optional<AuthUser> result = filter.user("ayd", "any-keycloak-password");

        assertTrue(result.isEmpty(),
            "user disabled in Pantera must be rejected even if the inner "
                + "SSO provider authenticates them");
        assertEquals(1, inner.calls.get(),
            "filter must still delegate to the inner provider; the rejection "
                + "happens AFTER the inner's successful authentication");
    }

    @Test
    void passesThroughEnabledUser() throws Exception {
        insertUser("alice", true);
        final AlwaysOk inner = new AlwaysOk();
        final LocalEnabledFilter filter = new LocalEnabledFilter(inner, ds);

        final Optional<AuthUser> result = filter.user("alice", "any-password");

        assertTrue(result.isPresent(), "enabled user must pass through");
        assertEquals("alice", result.get().name());
    }

    @Test
    void passesThroughUnknownUserForJitProvisioning() {
        // No row inserted. Simulates first-time SSO login where the
        // OAuth callback will create the row after authentication.
        final AlwaysOk inner = new AlwaysOk();
        final LocalEnabledFilter filter = new LocalEnabledFilter(inner, ds);

        final Optional<AuthUser> result = filter.user("first-time-sso-user", "pwd");

        assertTrue(result.isPresent(),
            "first-time SSO users have no local row yet; the filter must "
                + "let them through so the OAuth callback can provision them");
    }

    @Test
    void propagatesFailureFromInner() throws Exception {
        insertUser("alice", true);
        final LocalEnabledFilter filter = new LocalEnabledFilter(new AlwaysFail(), ds);

        final Optional<AuthUser> result = filter.user("alice", "wrong");

        assertTrue(result.isEmpty(),
            "inner failure propagates; the filter doesn't reverse a rejection");
    }

    // -----------------------------------------------------------
    // canHandle / isAuthoritative must delegate to inner
    // -----------------------------------------------------------

    @Test
    void canHandleDelegatesToInner() {
        final Authentication inner = new Authentication() {
            @Override
            public Optional<AuthUser> user(final String name, final String pass) {
                return Optional.empty();
            }
            @Override
            public boolean canHandle(final String username) {
                return "ayd".equals(username);
            }
        };
        final LocalEnabledFilter filter = new LocalEnabledFilter(inner, ds);

        assertTrue(filter.canHandle("ayd"));
        assertFalse(filter.canHandle("other"));
    }

    @Test
    void isAuthoritativeDelegatesToInner() {
        final Authentication inner = new Authentication() {
            @Override
            public Optional<AuthUser> user(final String name, final String pass) {
                return Optional.empty();
            }
            @Override
            public boolean isAuthoritative(final String username) {
                return "admin".equals(username);
            }
        };
        final LocalEnabledFilter filter = new LocalEnabledFilter(inner, ds);

        assertTrue(filter.isAuthoritative("admin"));
        assertFalse(filter.isAuthoritative("other"));
    }

    // -----------------------------------------------------------
    // Enable/disable cycle: the check is stateless, flips instantly
    // -----------------------------------------------------------

    @Test
    void disablingUserBlocksSubsequentLogins() throws Exception {
        insertUser("ayd", true);
        final LocalEnabledFilter filter = new LocalEnabledFilter(new AlwaysOk(), ds);

        assertTrue(filter.user("ayd", "pwd").isPresent(),
            "enabled user can log in");

        // Admin disables the user
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute(
                "UPDATE users SET enabled = false WHERE username = 'ayd'"
            );
        }

        assertTrue(filter.user("ayd", "pwd").isEmpty(),
            "just-disabled user must be rejected on the next auth attempt");
    }

    @Test
    void reenablingRestoresAccess() throws Exception {
        insertUser("ayd", false);
        final LocalEnabledFilter filter = new LocalEnabledFilter(new AlwaysOk(), ds);

        assertTrue(filter.user("ayd", "pwd").isEmpty());

        try (var conn = ds.getConnection()) {
            conn.createStatement().execute(
                "UPDATE users SET enabled = true WHERE username = 'ayd'"
            );
        }

        assertTrue(filter.user("ayd", "pwd").isPresent(),
            "re-enabled user regains access");
    }
}
