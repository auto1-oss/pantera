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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for {@link PgCronStatus}, which detects whether pg_cron is installed
 * AND the cooldown cleanup job is scheduled.
 *
 * <p>We only cover the negative path here: plain Postgres without pg_cron.
 * The positive case (pg_cron installed, job scheduled) requires a custom
 * Docker image with the shared-library preloaded and is not worth the
 * complexity — the fail-safe-to-false contract is what matters for
 * downstream callers deciding whether to start the Vertx fallback.
 *
 * <p>The other fail-safe cases ("cron schema missing" / "SQL error mid-probe")
 * are covered by inspection: both probe methods wrap in
 * {@code try (...) { ... } catch (SQLException) { return false; }}, so any
 * failure on the JDBC path collapses to {@code false}. Stubbing JDBC to
 * throw at precise points adds test code without adding confidence over
 * reading the class.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class PgCronStatusTest {

    @Container
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES =
        PostgreSQLTestConfig.createContainer();

    private static HikariDataSource dataSource;

    @BeforeAll
    static void initDataSource() {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(cfg);
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void returnsFalseWhenPgCronNotInstalled() {
        // Stock postgres:15-alpine has no pg_cron extension installed and
        // no cron schema. The extensionInstalled() probe returns false;
        // we should never reach the jobExists() probe, but even if we did
        // it too would fail-safe to false (cron.job does not exist).
        final PgCronStatus status = new PgCronStatus(dataSource);
        MatcherAssert.assertThat(
            "cleanupJobScheduled must be false on a stock Postgres without pg_cron",
            status.cleanupJobScheduled(), Matchers.is(false)
        );
    }
}
