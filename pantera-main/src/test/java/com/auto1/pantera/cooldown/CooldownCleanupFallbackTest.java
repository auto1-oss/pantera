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

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.DbManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for {@link CooldownCleanupFallback}. The fallback runs the same
 * archive + purge SQL that pg_cron would run; we drive
 * {@code runCleanupOnce()} / {@code maybePurgeHistory()} directly against
 * a Testcontainers Postgres (no Vertx timers) to keep the test fast and
 * deterministic.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class CooldownCleanupFallbackTest {

    @Container
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:15");

    private DataSource dataSource;
    private CooldownRepository repository;
    private CooldownSettings settings;
    private CooldownCleanupFallback fallback;

    @BeforeAll
    void initDb() {
        this.dataSource = new ArtifactDbFactory(this.settingsYaml(), "cooldowns").initialize();
        DbManager.migrate(this.dataSource);
        this.repository = new CooldownRepository(this.dataSource);
    }

    @BeforeEach
    void setUp() {
        this.truncate();
        this.settings = CooldownSettings.defaults();
        this.fallback = new CooldownCleanupFallback(this.repository, this.settings);
    }

    @AfterEach
    void tearDown() {
        this.truncate();
    }

    @Test
    void runCleanupOnceArchivesExpiredBlocks() {
        final long nowMs = Instant.now().toEpochMilli();
        // Five expired rows.
        for (int i = 0; i < 5; i++) {
            this.insertLiveRaw(
                "maven-proxy", "repo-a", "expired-" + i, "1.0.0",
                nowMs - Duration.ofHours(3).toMillis(),
                nowMs - Duration.ofMinutes(5).toMillis()
            );
        }
        // Two future (not yet expired) rows.
        for (int i = 0; i < 2; i++) {
            this.insertLiveRaw(
                "maven-proxy", "repo-a", "future-" + i, "1.0.0",
                nowMs - Duration.ofMinutes(5).toMillis(),
                nowMs + Duration.ofHours(72).toMillis()
            );
        }

        this.fallback.runCleanupOnce();

        final Set<String> repos = Set.of("repo-a");
        MatcherAssert.assertThat(
            "live table keeps only the two future rows",
            this.repository.countActiveBlocks(repos, null, null, null),
            Matchers.is(2L)
        );
        MatcherAssert.assertThat(
            "all five expired rows landed in history",
            this.repository.countHistory(repos, null, null, null),
            Matchers.is(5L)
        );
        // Cross-check archive_reason + archived_by on the history rows.
        for (final DbHistoryRecord h : this.repository.findHistoryPaginated(
                repos, null, null, null, "artifact", true, 0, 50)) {
            MatcherAssert.assertThat(
                "archive_reason=EXPIRED for cron-style archive",
                h.archiveReason(), Matchers.is(ArchiveReason.EXPIRED)
            );
            MatcherAssert.assertThat(
                "archived_by=system for cron-style archive",
                h.archivedBy(), Matchers.is("system")
            );
        }
    }

    @Test
    void maybePurgeHistoryDeletesOldRowsOnly() {
        final long nowMs = Instant.now().toEpochMilli();
        final int retention = this.settings.historyRetentionDays();
        // Rows older than retention (should be purged).
        for (int i = 0; i < 4; i++) {
            this.insertHistoryRaw(
                "maven-proxy", "repo-a", "ancient-" + i, "1.0.0",
                nowMs - Duration.ofDays(retention + 10L + i).toMillis()
            );
        }
        // Rows well inside retention (should survive).
        for (int i = 0; i < 3; i++) {
            this.insertHistoryRaw(
                "maven-proxy", "repo-a", "recent-" + i, "1.0.0",
                nowMs - Duration.ofDays(5L + i).toMillis()
            );
        }

        this.fallback.maybePurgeHistory();

        MatcherAssert.assertThat(
            "only rows inside retention survive",
            this.historyRowCount(), Matchers.is(3L)
        );
    }

    @Test
    void maybePurgeHistoryRespectsTwentyFourHourGate() {
        final long nowMs = Instant.now().toEpochMilli();
        final int retention = this.settings.historyRetentionDays();
        // Seed one row clearly older than retention for the first call to
        // delete. After that, seed another old row; the second call must
        // leave it alone because the 24h gate has not elapsed.
        this.insertHistoryRaw(
            "maven-proxy", "repo-a", "first-run", "1.0.0",
            nowMs - Duration.ofDays(retention + 30L).toMillis()
        );

        this.fallback.maybePurgeHistory();
        MatcherAssert.assertThat(
            "first run purged the old row", this.historyRowCount(), Matchers.is(0L)
        );

        // Seed a second old row. maybePurgeHistory must short-circuit due to
        // the 24h gate — the row survives.
        this.insertHistoryRaw(
            "maven-proxy", "repo-a", "second-run", "1.0.0",
            nowMs - Duration.ofDays(retention + 30L).toMillis()
        );

        this.fallback.maybePurgeHistory();
        MatcherAssert.assertThat(
            "second run inside 24h gate is a no-op",
            this.historyRowCount(), Matchers.is(1L)
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private YamlMapping settingsYaml() {
        return Yaml.createYamlMappingBuilder().add(
            "artifacts_database",
            Yaml.createYamlMappingBuilder()
                .add(ArtifactDbFactory.YAML_HOST, POSTGRES.getHost())
                .add(ArtifactDbFactory.YAML_PORT, String.valueOf(POSTGRES.getFirstMappedPort()))
                .add(ArtifactDbFactory.YAML_DATABASE, POSTGRES.getDatabaseName())
                .add(ArtifactDbFactory.YAML_USER, POSTGRES.getUsername())
                .add(ArtifactDbFactory.YAML_PASSWORD, POSTGRES.getPassword())
                .build()
        ).build();
    }

    private void insertLiveRaw(
        final String repoType, final String repoName,
        final String artifact, final String version,
        final long blockedAtMillis, final long blockedUntilMillis
    ) {
        final String sql =
            "INSERT INTO artifact_cooldowns(repo_type, repo_name, artifact, version, "
                + "reason, status, blocked_by, blocked_at, blocked_until) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = this.dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setString(3, artifact);
            stmt.setString(4, version);
            stmt.setString(5, CooldownReason.FRESH_RELEASE.name());
            stmt.setString(6, "ACTIVE");
            stmt.setString(7, "system");
            stmt.setLong(8, blockedAtMillis);
            stmt.setLong(9, blockedUntilMillis);
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }

    private void insertHistoryRaw(
        final String repoType, final String repoName,
        final String artifact, final String version,
        final long archivedAtMillis
    ) {
        final String sql =
            "INSERT INTO artifact_cooldowns_history("
                + "original_id, repo_type, repo_name, artifact, version, "
                + "reason, blocked_by, blocked_at, blocked_until, "
                + "installed_by, archived_at, archive_reason, archived_by"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = this.dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, 1L);
            stmt.setString(2, repoType);
            stmt.setString(3, repoName);
            stmt.setString(4, artifact);
            stmt.setString(5, version);
            stmt.setString(6, CooldownReason.FRESH_RELEASE.name());
            stmt.setString(7, "system");
            stmt.setLong(8, archivedAtMillis - 1000L);
            stmt.setLong(9, archivedAtMillis);
            stmt.setNull(10, java.sql.Types.VARCHAR);
            stmt.setLong(11, archivedAtMillis);
            stmt.setString(12, ArchiveReason.EXPIRED.name());
            stmt.setString(13, "system");
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }

    private long historyRowCount() {
        try (Connection conn = this.dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM artifact_cooldowns_history"
             );
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }

    private void truncate() {
        try (Connection conn = this.dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "TRUNCATE TABLE artifact_cooldowns, artifact_cooldowns_history RESTART IDENTITY"
             )) {
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }
}
