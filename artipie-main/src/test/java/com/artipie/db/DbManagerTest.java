/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.db;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DbManager}.
 * @since 1.0
 */
@Testcontainers
class DbManagerTest {

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
    }

    @AfterAll
    static void teardown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void runsMigrationsAndCreatesSettingsTables() throws Exception {
        DbManager.migrate(ds);
        try (Connection conn = ds.getConnection()) {
            assertTrue(tableExists(conn, "repositories"));
            assertTrue(tableExists(conn, "users"));
            assertTrue(tableExists(conn, "roles"));
            assertTrue(tableExists(conn, "user_roles"));
            assertTrue(tableExists(conn, "storage_aliases"));
            assertTrue(tableExists(conn, "settings"));
            assertTrue(tableExists(conn, "auth_providers"));
            assertTrue(tableExists(conn, "audit_log"));
        }
    }

    @Test
    void migrationsAreIdempotent() throws Exception {
        DbManager.migrate(ds);
        DbManager.migrate(ds);
        try (Connection conn = ds.getConnection()) {
            assertTrue(tableExists(conn, "repositories"));
        }
    }

    private static boolean tableExists(Connection conn, String table) throws Exception {
        try (ResultSet rs = conn.getMetaData().getTables(null, "public", table, null)) {
            return rs.next();
        }
    }
}
