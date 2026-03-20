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
package com.auto1.pantera.db;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs Flyway database migrations.
 * Called once from VertxMain.start() before verticle deployment.
 * @since 1.0
 */
public final class DbManager {

    private static final Logger LOG = LoggerFactory.getLogger(DbManager.class);

    private DbManager() {
    }

    /**
     * Run all pending Flyway migrations.
     * baselineVersion("99") ensures Flyway skips versions 1-99.
     * The existing artifact tables (artifacts, artifact_cooldowns, import_sessions)
     * are still managed by ArtifactDbFactory.createStructure() with IF NOT EXISTS.
     * Only V100+ migrations (settings tables) are managed by Flyway.
     * This will be consolidated in a future phase.
     * @param datasource HikariCP DataSource
     */
    public static void migrate(final DataSource datasource) {
        LOG.info("Running Flyway database migrations...");
        final Flyway flyway = Flyway.configure()
            .dataSource(datasource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("99")
            .load();
        final var result = flyway.migrate();
        LOG.info(
            "Flyway migration complete: {} migrations applied",
            result.migrationsExecuted
        );
    }
}
