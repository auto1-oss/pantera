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

import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import java.time.Duration;
import java.time.Instant;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * SNAPSHOT classifier knob precedence ladder. Asserts that the SNAPSHOT
 * timestamp regex routes only timestamped versions through the SNAPSHOT
 * tiers, and that the four-tier precedence (per-repo SNAPSHOT → per-repo →
 * global SNAPSHOT → per-type → global) resolves as documented.
 *
 * @since 2.2.0
 */
class JdbcCooldownServiceSnapshotEffectiveTest {

    private static final String TIMESTAMP_VERSION = "1.0-20260519.090000-1";
    private static final String RELEASE_VERSION = "1.0.0";

    /**
     * Placeholder DataSource — CooldownRepository's constructor requires
     * non-null but the effective-* paths under test never reach JDBC.
     */
    private static final javax.sql.DataSource NULL_DS = new javax.sql.DataSource() {
        @Override public java.sql.Connection getConnection() { return null; }
        @Override public java.sql.Connection getConnection(final String user, final String pass) { return null; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(final java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(final int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(final Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(final Class<?> iface) { return false; }
    };

    private static CooldownRequest request(final String version, final String repoName) {
        return new CooldownRequest(
            "maven-proxy", repoName, "com.example.lib",
            version, "tester", Instant.now()
        );
    }

    @Test
    void snapshotPolicyMinAgeBeatsGlobalForTimestampedVersion() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        settings.setSnapshotPolicy(
            CooldownSettings.SnapshotPolicy.of(null, Duration.ofDays(30))
        );
        final JdbcCooldownService svc = new JdbcCooldownService(
            settings, new CooldownRepository(NULL_DS)
        );
        MatcherAssert.assertThat(
            svc.effectiveDuration(request(TIMESTAMP_VERSION, "central")),
            new IsEqual<>(Duration.ofDays(30))
        );
    }

    @Test
    void snapshotPolicyIgnoredForReleaseVersion() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        settings.setSnapshotPolicy(
            CooldownSettings.SnapshotPolicy.of(null, Duration.ofDays(30))
        );
        final JdbcCooldownService svc = new JdbcCooldownService(
            settings, new CooldownRepository(NULL_DS)
        );
        MatcherAssert.assertThat(
            svc.effectiveDuration(request(RELEASE_VERSION, "central")),
            new IsEqual<>(Duration.ofDays(7))
        );
    }

    @Test
    void repoNameSnapshotOverrideBeatsGlobalSnapshotPolicy() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        settings.setSnapshotPolicy(
            CooldownSettings.SnapshotPolicy.of(null, Duration.ofDays(30))
        );
        settings.setRepoNameSnapshotOverride(
            "internal-mvn",
            CooldownSettings.SnapshotPolicy.of(null, Duration.ofDays(45))
        );
        final JdbcCooldownService svc = new JdbcCooldownService(
            settings, new CooldownRepository(NULL_DS)
        );
        MatcherAssert.assertThat(
            svc.effectiveDuration(request(TIMESTAMP_VERSION, "internal-mvn")),
            new IsEqual<>(Duration.ofDays(45))
        );
    }

    @Test
    void snapshotPolicyEnabledFalseDisablesCooldownForTimestampedVersion() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        settings.setSnapshotPolicy(
            CooldownSettings.SnapshotPolicy.of(false, null)
        );
        final JdbcCooldownService svc = new JdbcCooldownService(
            settings, new CooldownRepository(NULL_DS)
        );
        MatcherAssert.assertThat(
            svc.effectiveEnabled(request(TIMESTAMP_VERSION, "central")),
            new IsEqual<>(false)
        );
    }

    @Test
    void snapshotPolicyEnabledFalseLeavesReleaseVersionEnabled() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        settings.setSnapshotPolicy(
            CooldownSettings.SnapshotPolicy.of(false, null)
        );
        final JdbcCooldownService svc = new JdbcCooldownService(
            settings, new CooldownRepository(NULL_DS)
        );
        MatcherAssert.assertThat(
            svc.effectiveEnabled(request(RELEASE_VERSION, "central")),
            new IsEqual<>(true)
        );
    }
}
