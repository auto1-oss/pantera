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
package com.auto1.pantera.settings.runtime;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import javax.json.Json;
import javax.json.JsonObject;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.db.dao.SettingsDao;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link SettingsBootstrap} — the idempotent first-boot seeder
 * that writes spec defaults into the {@code settings} table for any keys
 * that are not already present.
 */
@Testcontainers
final class SettingsBootstrapTest {

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
        cfg.setMaximumPoolSize(3);
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
    void seedsAllMissingKeysWithDefaults() {
        new SettingsBootstrap(this.dao).seedIfMissing();
        final Map<String, JsonObject> rows = this.dao.listAll();
        assertThat(
            "all catalog keys must be seeded on a fresh DB",
            rows.size(),
            equalTo(SettingsKey.allKeys().size())
        );
        for (String key : SettingsKey.allKeys()) {
            assertThat("missing key after seed: " + key, rows, hasKey(key));
        }
    }

    @Test
    void doesNotOverwriteExistingValues() {
        this.dao.put(
            "http_client.protocol",
            Json.createObjectBuilder().add("value", "h1").build(),
            "test"
        );
        new SettingsBootstrap(this.dao).seedIfMissing();
        final JsonObject row = this.dao.get("http_client.protocol").orElseThrow();
        assertThat(
            "existing value must be preserved (not overwritten with default h2)",
            row.getString("value"),
            is(equalTo("h1"))
        );
    }
}
