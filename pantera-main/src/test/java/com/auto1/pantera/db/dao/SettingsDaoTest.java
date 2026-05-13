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

import java.util.Map;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import javax.sql.DataSource;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SettingsDaoTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();
    static HikariDataSource ds;
    SettingsDao dao;

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
        if (ds != null) { ds.close(); }
    }

    @BeforeEach
    void init() throws Exception {
        this.dao = new SettingsDao(ds);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM settings");
        }
    }

    @Test
    void putAndGetSetting() {
        final JsonObject val = Json.createObjectBuilder().add("timeout", 120).build();
        this.dao.put("http_client", val, "admin");
        final Optional<JsonObject> result = this.dao.get("http_client");
        assertTrue(result.isPresent());
        assertEquals(120, result.get().getInt("timeout"));
    }

    @Test
    void returnsEmptyForMissingKey() {
        assertTrue(this.dao.get("nonexistent").isEmpty());
    }

    @Test
    void updatesExistingKey() {
        this.dao.put("port", Json.createObjectBuilder().add("value", 8080).build(), "admin");
        this.dao.put("port", Json.createObjectBuilder().add("value", 9090).build(), "admin");
        assertEquals(9090, this.dao.get("port").get().getInt("value"));
    }

    @Test
    void listsAllSettings() {
        this.dao.put("key1", Json.createObjectBuilder().add("a", 1).build(), "admin");
        this.dao.put("key2", Json.createObjectBuilder().add("b", 2).build(), "admin");
        final Map<String, JsonObject> all = this.dao.listAll();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("key1"));
        assertTrue(all.containsKey("key2"));
    }

    @Test
    void deletesKey() {
        this.dao.put("temp", Json.createObjectBuilder().add("x", 1).build(), "admin");
        assertTrue(this.dao.get("temp").isPresent());
        this.dao.delete("temp");
        assertTrue(this.dao.get("temp").isEmpty());
    }

    @Test
    void putIfAbsentInsertsWhenKeyMissing() throws Exception {
        boolean inserted = dao.putIfAbsent("zeta",
            Json.createObjectBuilder().add("value", 1).build(), "test");
        org.junit.jupiter.api.Assertions.assertTrue(inserted);
        org.junit.jupiter.api.Assertions.assertEquals(1,
            dao.get("zeta").orElseThrow().getInt("value"));
    }

    @Test
    void putIfAbsentReturnsFalseAndPreservesValueWhenKeyExists() throws Exception {
        dao.put("zeta", Json.createObjectBuilder().add("value", 1).build(), "first");
        boolean inserted = dao.putIfAbsent("zeta",
            Json.createObjectBuilder().add("value", 99).build(), "second");
        org.junit.jupiter.api.Assertions.assertFalse(inserted);
        org.junit.jupiter.api.Assertions.assertEquals(1,
            dao.get("zeta").orElseThrow().getInt("value"));
    }
}
