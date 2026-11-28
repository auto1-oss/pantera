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
package com.auto1.pantera.auth.failedlogins;

import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link DbFailedLoginStore} against a real PostgreSQL with the project's
 * migrations: the same contract as the in-memory store, including failures
 * counted from many connections at once.
 */
@Testcontainers
final class DbFailedLoginStoreIT extends FailedLoginStoreContract {

    /**
     * Database.
     */
    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    /**
     * Connection pool.
     */
    private static HikariDataSource source;

    @BeforeAll
    static void migrate() {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(DbFailedLoginStoreIT.PG.getJdbcUrl());
        cfg.setUsername(DbFailedLoginStoreIT.PG.getUsername());
        cfg.setPassword(DbFailedLoginStoreIT.PG.getPassword());
        cfg.setMaximumPoolSize(8);
        DbFailedLoginStoreIT.source = new HikariDataSource(cfg);
        DbManager.migrate(DbFailedLoginStoreIT.source);
    }

    @AfterAll
    static void close() {
        if (DbFailedLoginStoreIT.source != null) {
            DbFailedLoginStoreIT.source.close();
        }
    }

    @Override
    FailedLoginStore store() {
        try (Connection conn = DbFailedLoginStoreIT.source.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM login_failures");
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
        return new DbFailedLoginStore(DbFailedLoginStoreIT.source);
    }
}
