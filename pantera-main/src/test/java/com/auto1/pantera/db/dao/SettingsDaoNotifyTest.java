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
package com.auto1.pantera.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import javax.json.Json;
import javax.json.JsonObject;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for v2.2.0 settings hot-reload foundation:
 *  - {@link SettingsDao#getChangedSince(Instant)} polling-fallback query.
 *  - PostgreSQL {@code settings_changed} NOTIFY channel emitted by the
 *    V127 trigger on every settings INSERT/UPDATE/DELETE.
 */
@Testcontainers
final class SettingsDaoNotifyTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    static HikariDataSource ds;

    private SettingsDao dao;

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
        this.dao = new SettingsDao(ds);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM settings");
        }
    }

    @Test
    void getChangedSinceReturnsOnlyKeysUpdatedAfterTimestamp() throws Exception {
        this.dao.put(
            "alpha",
            Json.createObjectBuilder().add("value", 1).build(),
            "ayd"
        );
        // Derive cutoff from the row itself: getChangedSince uses strict
        // `updated_at > ?`, so passing alpha's exact timestamp guarantees
        // alpha is excluded without depending on wall-clock granularity.
        final Instant cutoff = readUpdatedAt("alpha");
        this.dao.put(
            "beta",
            Json.createObjectBuilder().add("value", 2).build(),
            "ayd"
        );
        final Map<String, JsonObject> changed = this.dao.getChangedSince(cutoff);
        assertEquals(1, changed.size(), "only one key should be returned");
        assertTrue(changed.containsKey("beta"), "result should contain 'beta'");
        assertFalse(changed.containsKey("alpha"), "result must not contain 'alpha'");
        assertEquals(2, changed.get("beta").getInt("value"));
    }

    private Instant readUpdatedAt(final String key) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT updated_at FROM settings WHERE key = ?")) {
            ps.setString(1, key);
            final ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "row for key '" + key + "' must exist");
            return rs.getTimestamp("updated_at").toInstant();
        }
    }

    @Test
    void putFiresPgNotify() throws Exception {
        try (Connection conn = ds.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("LISTEN settings_changed");
            }
            this.dao.put(
                "gamma",
                Json.createObjectBuilder().add("value", 9).build(),
                "ayd"
            );
            // Round-trip a no-op SELECT on the same connection so PgJDBC
            // collects pending NOTIFY frames before we read them.
            try (Statement poke = conn.createStatement()) {
                poke.execute("SELECT 1");
            }
            final PGNotification[] notifs =
                conn.unwrap(PGConnection.class).getNotifications();
            assertNotNull(notifs, "notifications array must not be null");
            assertTrue(notifs.length >= 1, "at least one notification expected");
            boolean found = false;
            for (final PGNotification n : notifs) {
                if ("settings_changed".equals(n.getName())
                    && "gamma".equals(n.getParameter())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "expected settings_changed/gamma notification");
        }
    }
}
