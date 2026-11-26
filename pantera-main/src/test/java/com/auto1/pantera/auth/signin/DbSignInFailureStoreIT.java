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
package com.auto1.pantera.auth.signin;

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
 * {@link DbSignInFailureStore} against PostgreSQL with the real schema.
 *
 * @since 2.2.15
 */
@Testcontainers
final class DbSignInFailureStoreIT extends SignInFailureStoreContract {

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
        cfg.setJdbcUrl(DbSignInFailureStoreIT.PG.getJdbcUrl());
        cfg.setUsername(DbSignInFailureStoreIT.PG.getUsername());
        cfg.setPassword(DbSignInFailureStoreIT.PG.getPassword());
        cfg.setMaximumPoolSize(4);
        DbSignInFailureStoreIT.source = new HikariDataSource(cfg);
        DbManager.migrate(DbSignInFailureStoreIT.source);
    }

    @AfterAll
    static void close() {
        if (DbSignInFailureStoreIT.source != null) {
            DbSignInFailureStoreIT.source.close();
        }
    }

    @Override
    SignInFailureStore store() {
        try (Connection conn = DbSignInFailureStoreIT.source.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE sign_in_failures");
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
        return new DbSignInFailureStore(DbSignInFailureStoreIT.source);
    }
}
