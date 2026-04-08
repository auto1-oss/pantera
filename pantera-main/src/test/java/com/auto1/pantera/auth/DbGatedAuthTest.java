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
import com.auto1.pantera.db.dao.AuthProviderDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.json.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DbGatedAuth} and its shared
 * {@link DbGatedAuth.EnabledTypesCache}. Verifies that UI-driven
 * enable/disable/delete of auth providers takes effect at runtime
 * without requiring a server restart — the missing piece behind the
 * "disabled Keycloak but login still works" bug.
 */
@Testcontainers
class DbGatedAuthTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    static HikariDataSource ds;
    AuthProviderDao dao;
    DbGatedAuth.EnabledTypesCache cache;

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
        this.dao = new AuthProviderDao(ds);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM auth_providers");
        }
        this.cache = new DbGatedAuth.EnabledTypesCache(ds);
        this.cache.refresh();
    }

    /** Fake inner provider that records call counts. */
    private static final class FakeInner implements Authentication {
        final AtomicInteger userCalls = new AtomicInteger();
        final AtomicInteger canHandleCalls = new AtomicInteger();
        boolean authoritative;

        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            this.userCalls.incrementAndGet();
            return Optional.of(new AuthUser(name, "fake"));
        }

        @Override
        public boolean canHandle(final String username) {
            this.canHandleCalls.incrementAndGet();
            return true;
        }

        @Override
        public boolean isAuthoritative(final String username) {
            return this.authoritative;
        }
    }

    // ---------------------------------------------------------------
    // DbGatedAuth behavior
    // ---------------------------------------------------------------

    @Test
    void delegatesWhenProviderEnabled() {
        this.dao.put("okta", 10, Json.createObjectBuilder().build());
        this.cache.refresh();
        final FakeInner inner = new FakeInner();
        final DbGatedAuth gated = new DbGatedAuth(inner, "okta", this.cache);

        final Optional<AuthUser> result = gated.user("u", "p");

        assertTrue(result.isPresent(), "enabled provider should authenticate");
        assertEquals(1, inner.userCalls.get());
        assertTrue(gated.canHandle("u"), "enabled → canHandle delegates to inner");
        assertEquals(1, inner.canHandleCalls.get());
    }

    @Test
    void shortCircuitsWhenProviderDisabled() {
        this.dao.put("keycloak", 10, Json.createObjectBuilder().build());
        this.dao.disable(
            (int) this.dao.list().get(0).getInt("id")
        );
        this.cache.refresh();
        final FakeInner inner = new FakeInner();
        final DbGatedAuth gated = new DbGatedAuth(inner, "keycloak", this.cache);

        assertTrue(gated.user("u", "p").isEmpty(),
            "disabled provider must not authenticate");
        assertFalse(gated.canHandle("u"),
            "disabled provider must not handle any user");
        assertFalse(gated.isAuthoritative("u"),
            "disabled provider cannot be authoritative");
        assertEquals(0, inner.userCalls.get(),
            "inner provider must not be consulted at all");
    }

    @Test
    void shortCircuitsAfterDelete() {
        this.dao.put("keycloak", 10, Json.createObjectBuilder().build());
        final int id = this.dao.list().get(0).getInt("id");
        this.cache.refresh();
        final FakeInner inner = new FakeInner();
        final DbGatedAuth gated = new DbGatedAuth(inner, "keycloak", this.cache);

        // Works before delete
        assertTrue(gated.user("u", "p").isPresent());

        // Delete and force refresh
        this.dao.delete(id);
        this.cache.refresh();

        assertTrue(gated.user("u", "p").isEmpty(),
            "deleted provider must not authenticate");
        assertFalse(gated.canHandle("u"));
    }

    @Test
    void isAuthoritativeDelegatesWhenEnabled() {
        this.dao.put("okta", 10, Json.createObjectBuilder().build());
        this.cache.refresh();
        final FakeInner inner = new FakeInner();
        inner.authoritative = true;
        final DbGatedAuth gated = new DbGatedAuth(inner, "okta", this.cache);

        assertTrue(gated.isAuthoritative("u"),
            "enabled provider delegates isAuthoritative to inner");
    }

    // ---------------------------------------------------------------
    // EnabledTypesCache behavior
    // ---------------------------------------------------------------

    @Test
    void cacheReflectsEnabledTypesList() {
        this.dao.put("okta", 10, Json.createObjectBuilder().build());
        this.dao.put("keycloak", 20, Json.createObjectBuilder().build());
        this.cache.refresh();

        assertTrue(this.cache.isEnabled("okta"));
        assertTrue(this.cache.isEnabled("keycloak"));
        assertFalse(this.cache.isEnabled("missing"));
    }

    @Test
    void cacheRefreshCapturesDisable() {
        this.dao.put("keycloak", 10, Json.createObjectBuilder().build());
        this.cache.refresh();
        assertTrue(this.cache.isEnabled("keycloak"));

        this.dao.disable(this.dao.list().get(0).getInt("id"));
        this.cache.refresh();
        assertFalse(this.cache.isEnabled("keycloak"));
    }

    @Test
    void cacheRefreshCapturesReEnable() {
        this.dao.put("keycloak", 10, Json.createObjectBuilder().build());
        final int id = this.dao.list().get(0).getInt("id");
        this.dao.disable(id);
        this.cache.refresh();
        assertFalse(this.cache.isEnabled("keycloak"));

        this.dao.enable(id);
        this.cache.refresh();
        assertTrue(this.cache.isEnabled("keycloak"));
    }

    @Test
    void cacheHandlesEmptyProviderTable() {
        this.cache.refresh();
        assertFalse(this.cache.isEnabled("anything"));
    }
}
