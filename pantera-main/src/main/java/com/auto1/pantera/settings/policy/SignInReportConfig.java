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
package com.auto1.pantera.settings.policy;

/**
 * Policy of the sign-in failures report.
 *
 * @param enabled Whether sign-in failures are noted and stored
 * @param retentionDays Days after its last event that a line is kept
 * @since 2.2.15
 */
public record SignInReportConfig(boolean enabled, int retentionDays) {

    /**
     * Shortest retention, in days.
     */
    public static final int MIN_RETENTION = 1;

    /**
     * Longest retention, in days.
     */
    public static final int MAX_RETENTION = 3650;

    /**
     * Ctor.
     */
    public SignInReportConfig {
        if (retentionDays < SignInReportConfig.MIN_RETENTION
            || retentionDays > SignInReportConfig.MAX_RETENTION) {
            throw new IllegalArgumentException(
                String.format(
                    "sign_in_report_retention_days must be between %d and %d",
                    SignInReportConfig.MIN_RETENTION, SignInReportConfig.MAX_RETENTION
                )
            );
        }
    }

    /**
     * The defaults: the report is on, lines are kept 90 days.
     *
     * @return Policy
     */
    public static SignInReportConfig defaults() {
        return new SignInReportConfig(true, 90);
    }
}
