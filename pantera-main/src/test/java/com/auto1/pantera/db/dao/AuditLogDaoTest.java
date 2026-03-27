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
import java.sql.ResultSet;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class AuditLogDaoTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();
    static HikariDataSource ds;
    AuditLogDao dao;

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
    void init() {
        this.dao = new AuditLogDao(ds);
    }

    @Test
    void logsCreateAction() throws Exception {
        final JsonObject val = Json.createObjectBuilder()
            .add("type", "maven-proxy").build();
        this.dao.log("admin", "CREATE", "repository", "maven-central", null, val);
        try (Connection conn = ds.getConnection()) {
            final ResultSet rs = conn.createStatement()
                .executeQuery("SELECT * FROM audit_log WHERE resource_name = 'maven-central'");
            assertTrue(rs.next());
            assertEquals("admin", rs.getString("actor"));
            assertEquals("CREATE", rs.getString("action"));
            assertEquals("repository", rs.getString("resource_type"));
        }
    }

    @Test
    void logsUpdateWithOldAndNewValues() throws Exception {
        final JsonObject old = Json.createObjectBuilder().add("enabled", true).build();
        final JsonObject nw = Json.createObjectBuilder().add("enabled", false).build();
        this.dao.log("admin", "UPDATE", "user", "john", old, nw);
        try (Connection conn = ds.getConnection()) {
            final ResultSet rs = conn.createStatement()
                .executeQuery("SELECT * FROM audit_log WHERE resource_name = 'john'");
            assertTrue(rs.next());
            assertEquals("UPDATE", rs.getString("action"));
        }
    }
}
