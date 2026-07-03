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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Startup-time detection of whether pg_cron is installed and the cooldown
 * cleanup job is scheduled.
 *
 * <p>Used by the application bootstrap (Task 9) to decide whether to start
 * the Vertx-based {@code CooldownCleanupFallback}. If pg_cron is active and
 * the scheduled cleanup job is present, the fallback is not started —
 * avoiding dual cleanup paths.
 *
 * <p>Fail-safe semantics: any SQL error while probing (extension absent,
 * permissions denied, cron schema missing) returns {@code false}, i.e.
 * "not scheduled" — the caller will then activate the Vertx fallback.
 * This is safer than optimistically assuming pg_cron works when we
 * cannot confirm it.
 *
 * @since 2.2.1
 */
public final class PgCronStatus {

    /**
     * Jobname used for the cooldown cleanup schedule in cron.job.
     */
    public static final String CLEANUP_JOB_NAME = "cleanup-expired-cooldowns";

    /**
     * Data source to probe.
     */
    private final DataSource dataSource;

    /**
     * Ctor.
     * @param dataSource JDBC data source pointing at the cooldown database
     */
    public PgCronStatus(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Returns {@code true} if pg_cron is installed AND the
     * {@code cleanup-expired-cooldowns} job is scheduled.
     * Returns {@code false} on any probe failure (fail-safe toward fallback).
     *
     * @return true when pg_cron handles cleanup, false otherwise
     */
    public boolean cleanupJobScheduled() {
        return this.extensionInstalled() && this.jobExists(CLEANUP_JOB_NAME);
    }

    /**
     * Probes pg_extension for an installed pg_cron entry.
     * @return true if installed, false otherwise (including on SQL error)
     */
    private boolean extensionInstalled() {
        try (Connection conn = this.dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM pg_extension WHERE extname = 'pg_cron'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (final SQLException err) {
            return false;
        }
    }

    /**
     * Probes cron.job for a scheduled job with the given name.
     * @param jobName scheduled job name (cron.job.jobname)
     * @return true if found, false otherwise (including on SQL error)
     */
    private boolean jobExists(final String jobName) {
        try (Connection conn = this.dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM cron.job WHERE jobname = ?")) {
            ps.setString(1, jobName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (final SQLException err) {
            return false;
        }
    }
}
