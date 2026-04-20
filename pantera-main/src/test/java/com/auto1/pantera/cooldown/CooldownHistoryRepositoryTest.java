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
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.DbManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for the v2.2.0 cooldown repository additions: {@code archiveAndDelete},
 * SQL-pushed permission + filter queries, batched archive/purge, and
 * history read methods.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class CooldownHistoryRepositoryTest {

    @Container
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:15");

    private DataSource dataSource;

    private CooldownRepository repository;

    @BeforeAll
    void initDb() {
        this.dataSource = new ArtifactDbFactory(this.settings(), "cooldowns").initialize();
        // Run Flyway migrations so the V121 artifact_cooldowns_history table
        // (and indexes/helper functions) exist. ArtifactDbFactory.initialize()
        // creates only the imperative subset via createStructure(); the rest is
        // delegated to Flyway in production via DbManager.migrate().
        DbManager.migrate(this.dataSource);
        this.repository = new CooldownRepository(this.dataSource);
    }

    @BeforeEach
    void setUp() {
        this.truncate();
    }

    @AfterEach
    void tearDown() {
        this.truncate();
    }

    @Test
    void archiveAndDeleteMovesRowToHistory() {
        final long id = this.insertLive(
            "maven-proxy", "central", "com.example.lib", "1.0.0",
            CooldownReason.FRESH_RELEASE, "system",
            Instant.now().minus(Duration.ofHours(2)),
            Instant.now().plus(Duration.ofHours(70))
        );
        this.repository.archiveAndDelete(id, ArchiveReason.MANUAL_UNBLOCK, "alice");
        MatcherAssert.assertThat(
            "Live row should be deleted after archiveAndDelete",
            this.liveRowExists(id), Matchers.is(false)
        );
        final List<DbHistoryRecord> history = this.repository.findHistoryPaginated(
            Set.of("central"), null, null, null, "archived_at", false, 0, 50
        );
        MatcherAssert.assertThat(
            "History should contain one row", history, Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            history.get(0).archiveReason(), Matchers.is(ArchiveReason.MANUAL_UNBLOCK)
        );
        MatcherAssert.assertThat(
            history.get(0).archivedBy(), Matchers.is("alice")
        );
        MatcherAssert.assertThat(
            history.get(0).originalId(), Matchers.is(id)
        );
    }

    @Test
    void archiveAndDeleteIsIdempotentOnMissingId() {
        // Missing id must be treated as a concurrent-race no-op: no throw,
        // no history row inserted. This matches the idempotent semantics of
        // the prior plain-DELETE path and prevents 500s when the periodic
        // archiveExpiredBatch wins the race against expire()/release().
        Assertions.assertDoesNotThrow(
            () -> this.repository.archiveAndDelete(99999L, ArchiveReason.EXPIRED, "system")
        );
        MatcherAssert.assertThat(
            "No history row should be inserted when target id is missing",
            this.historyRowCount(), Matchers.is(0L)
        );
    }

    @Test
    void archiveAndDeleteIsIdempotentAcrossConcurrentCalls() {
        final long id = this.insertLive(
            "maven-proxy", "central", "com.example.lib", "1.0.0",
            CooldownReason.FRESH_RELEASE, "system",
            Instant.now().minus(Duration.ofHours(2)),
            Instant.now().plus(Duration.ofHours(70))
        );
        // First call archives + deletes the live row.
        this.repository.archiveAndDelete(id, ArchiveReason.EXPIRED, "system");
        // Second call on the same id (simulating the race where e.g. the
        // expiry cron already archived the row) must NOT throw and must NOT
        // add a second history row.
        Assertions.assertDoesNotThrow(
            () -> this.repository.archiveAndDelete(id, ArchiveReason.EXPIRED, "system")
        );
        MatcherAssert.assertThat(
            "second archiveAndDelete on same id must be a no-op",
            this.repository.countHistory(Set.of("central"), null, null, null),
            Matchers.is(1L)
        );
    }

    @Test
    void findActivePaginatedFiltersByAccessibleRepos() {
        this.seedLive("maven-proxy", "repo-a", "pkg1", "1.0.0");
        this.seedLive("maven-proxy", "repo-b", "pkg2", "2.0.0");
        this.seedLive("maven-proxy", "repo-c", "pkg3", "3.0.0");
        final List<DbBlockRecord> rows = this.repository.findActivePaginated(
            Set.of("repo-a", "repo-b"), null, null, null, null, false, 0, 50
        );
        MatcherAssert.assertThat(rows, Matchers.hasSize(2));
        MatcherAssert.assertThat(
            rows.stream().map(DbBlockRecord::repoName).toList(),
            Matchers.containsInAnyOrder("repo-a", "repo-b")
        );
    }

    @Test
    void findActivePaginatedFiltersByRepoType() {
        this.seedLive("maven-proxy", "repo-a", "pkg1", "1.0.0");
        this.seedLive("npm-proxy", "repo-a", "pkg2", "2.0.0");
        this.seedLive("pypi-proxy", "repo-a", "pkg3", "3.0.0");
        final List<DbBlockRecord> rows = this.repository.findActivePaginated(
            Set.of("repo-a"), null, "npm-proxy", null, null, false, 0, 50
        );
        MatcherAssert.assertThat(rows, Matchers.hasSize(1));
        MatcherAssert.assertThat(rows.get(0).repoType(), Matchers.is("npm-proxy"));
        MatcherAssert.assertThat(rows.get(0).artifact(), Matchers.is("pkg2"));
    }

    @Test
    void findActivePaginatedHonorsSearch() {
        this.seedLive("maven-proxy", "repo-a", "needle-lib", "1.0.0");
        this.seedLive("maven-proxy", "repo-a", "haystack-lib", "2.0.0");
        this.seedLive("maven-proxy", "repo-a", "other-pkg", "3.0.0");
        final List<DbBlockRecord> rows = this.repository.findActivePaginated(
            Set.of("repo-a"), null, null, "needle", null, false, 0, 50
        );
        MatcherAssert.assertThat(rows, Matchers.hasSize(1));
        MatcherAssert.assertThat(rows.get(0).artifact(), Matchers.is("needle-lib"));
    }

    @Test
    void countActiveBlocksReflectsTotalIndependentOfPageSize() {
        final int total = 12;
        for (int i = 0; i < 8; i++) {
            this.seedLive("maven-proxy", "repo-a", "pkg" + i, "1.0.0");
        }
        for (int i = 0; i < 4; i++) {
            this.seedLive("npm-proxy", "repo-b", "npkg" + i, "2.0.0");
        }
        final Set<String> repos = Set.of("repo-a", "repo-b");
        final int pageSize = 5;
        final long count = this.repository.countActiveBlocks(repos, null, null, null);
        final List<DbBlockRecord> page = this.repository.findActivePaginated(
            repos, null, null, null, "blocked_at", true, 0, pageSize
        );
        MatcherAssert.assertThat(
            "count reflects full total", count, Matchers.is((long) total)
        );
        MatcherAssert.assertThat(
            "page slice is limited", page, Matchers.hasSize(pageSize)
        );
        MatcherAssert.assertThat(
            "count must not trivially equal page.size()",
            count, Matchers.not((long) page.size())
        );
    }

    @Test
    void countActiveBlocksReturnsZeroForEmptyAccessibleRepos() {
        this.seedLive("maven-proxy", "repo-a", "pkg1", "1.0.0");
        MatcherAssert.assertThat(
            this.repository.countActiveBlocks(Set.of(), null, null, null),
            Matchers.is(0L)
        );
        MatcherAssert.assertThat(
            this.repository.findActivePaginated(
                Set.of(), null, null, null, null, false, 0, 50
            ),
            Matchers.is(List.of())
        );
    }

    @Test
    void archiveExpiredBatchMovesOnlyExpired() {
        final long nowMs = Instant.now().toEpochMilli();
        // Two expired
        this.insertLiveRaw("maven-proxy", "repo-a", "exp1", "1.0.0",
            nowMs - 10_000L, nowMs - 1L);
        this.insertLiveRaw("maven-proxy", "repo-a", "exp2", "1.0.0",
            nowMs - 10_000L, nowMs - 1L);
        // Two not expired
        this.insertLiveRaw("maven-proxy", "repo-a", "live1", "1.0.0",
            nowMs - 10_000L, nowMs + 3_600_000L);
        this.insertLiveRaw("maven-proxy", "repo-a", "live2", "1.0.0",
            nowMs - 10_000L, nowMs + 3_600_000L);

        final int moved = this.repository.archiveExpiredBatch(100);
        MatcherAssert.assertThat(moved, Matchers.is(2));
        MatcherAssert.assertThat(this.liveRowCount(), Matchers.is(2L));
        MatcherAssert.assertThat(this.historyRowCount(), Matchers.is(2L));
        final List<DbHistoryRecord> hist = this.repository.findHistoryPaginated(
            Set.of("repo-a"), null, null, null, "artifact", true, 0, 100
        );
        MatcherAssert.assertThat(
            hist.stream().map(DbHistoryRecord::artifact).toList(),
            Matchers.containsInAnyOrder("exp1", "exp2")
        );
        MatcherAssert.assertThat(
            hist.stream().allMatch(r -> r.archiveReason() == ArchiveReason.EXPIRED),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            hist.stream().allMatch(r -> "system".equals(r.archivedBy())),
            Matchers.is(true)
        );
    }

    @Test
    void archiveExpiredBatchRespectsLimit() {
        final long nowMs = Instant.now().toEpochMilli();
        for (int i = 0; i < 50; i++) {
            this.insertLiveRaw("maven-proxy", "repo-a", "pkg" + i, "1.0.0",
                nowMs - 10_000L, nowMs - (50L - i));
        }
        final int moved = this.repository.archiveExpiredBatch(10);
        MatcherAssert.assertThat(moved, Matchers.is(10));
        MatcherAssert.assertThat(this.liveRowCount(), Matchers.is(40L));
        MatcherAssert.assertThat(this.historyRowCount(), Matchers.is(10L));
    }

    @Test
    void purgeHistoryOlderThanRespectsCutoffAndLimit() {
        final long nowMs = Instant.now().toEpochMilli();
        // Seed 30 history rows with archived_at ranging 1..30 days ago.
        for (int day = 1; day <= 30; day++) {
            this.insertHistoryRaw(
                "maven-proxy", "repo-a", "pkg" + day, "1.0.0",
                nowMs - Duration.ofDays(day).toMillis(),
                ArchiveReason.EXPIRED
            );
        }
        // Purge rows older than 10 days, limited to 15 rows.
        final long cutoff = nowMs - Duration.ofDays(10).toMillis();
        final int deleted = this.repository.purgeHistoryOlderThan(cutoff, 15);
        // 20 rows are eligible (days 11..30), but limit is 15, so 15 deleted.
        MatcherAssert.assertThat(deleted, Matchers.is(15));
        MatcherAssert.assertThat(this.historyRowCount(), Matchers.is(15L));
    }

    @Test
    void findHistoryPaginatedAppliesAccessibleRepos() {
        final long nowMs = Instant.now().toEpochMilli();
        this.insertHistoryRaw("maven-proxy", "repo-a", "pkg1", "1.0.0",
            nowMs - 100_000L, ArchiveReason.EXPIRED);
        this.insertHistoryRaw("maven-proxy", "repo-b", "pkg2", "2.0.0",
            nowMs - 100_000L, ArchiveReason.EXPIRED);
        this.insertHistoryRaw("maven-proxy", "repo-c", "pkg3", "3.0.0",
            nowMs - 100_000L, ArchiveReason.EXPIRED);
        final List<DbHistoryRecord> rows = this.repository.findHistoryPaginated(
            Set.of("repo-a", "repo-b"), null, null, null, null, false, 0, 50
        );
        MatcherAssert.assertThat(rows, Matchers.hasSize(2));
        MatcherAssert.assertThat(
            rows.stream().map(DbHistoryRecord::repoName).toList(),
            Matchers.containsInAnyOrder("repo-a", "repo-b")
        );
    }

    @Test
    void countHistoryReflectsTotalIndependentOfPageSize() {
        final long nowMs = Instant.now().toEpochMilli();
        final int total = 12;
        for (int i = 0; i < 8; i++) {
            this.insertHistoryRaw("maven-proxy", "repo-a", "pkg" + i, "1.0.0",
                nowMs - i * 1000L, ArchiveReason.EXPIRED);
        }
        for (int i = 0; i < 4; i++) {
            this.insertHistoryRaw("npm-proxy", "repo-b", "npkg" + i, "2.0.0",
                nowMs - i * 1000L, ArchiveReason.MANUAL_UNBLOCK);
        }
        final Set<String> repos = Set.of("repo-a", "repo-b");
        final int pageSize = 5;
        final long count = this.repository.countHistory(repos, null, null, null);
        final List<DbHistoryRecord> page = this.repository.findHistoryPaginated(
            repos, null, null, null, "archived_at", true, 0, pageSize
        );
        MatcherAssert.assertThat(
            "count reflects full total", count, Matchers.is((long) total)
        );
        MatcherAssert.assertThat(
            "page slice is limited", page, Matchers.hasSize(pageSize)
        );
        MatcherAssert.assertThat(
            "count must not trivially equal page.size()",
            count, Matchers.not((long) page.size())
        );
    }

    @Test
    void archiveAndDeleteByRepoArchivesAllActiveForThatRepo() {
        for (int i = 0; i < 5; i++) {
            this.seedLive("npm-proxy", "repo-a", "pkg" + i, "1.0." + i);
        }
        this.seedLive("npm-proxy", "repo-b", "untouched", "1.0.0");
        final int moved = this.repository.archiveAndDeleteByRepo(
            "npm-proxy", "repo-a", ArchiveReason.MANUAL_UNBLOCK, "alice");
        MatcherAssert.assertThat(
            "5 rows for repo-a should have been archived", moved, Matchers.is(5)
        );
        MatcherAssert.assertThat(
            "Only repo-b's row should remain live",
            this.liveRowCount(), Matchers.is(1L)
        );
        final List<DbHistoryRecord> history = this.repository.findHistoryPaginated(
            Set.of("repo-a", "repo-b"), null, null, null, "archived_at", false, 0, 50
        );
        MatcherAssert.assertThat(history, Matchers.hasSize(5));
        MatcherAssert.assertThat(
            "all history rows are for repo-a",
            history.stream().allMatch(r -> "repo-a".equals(r.repoName())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "all history rows tagged MANUAL_UNBLOCK",
            history.stream().allMatch(r -> r.archiveReason() == ArchiveReason.MANUAL_UNBLOCK),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "all history rows tagged with actor 'alice'",
            history.stream().allMatch(r -> "alice".equals(r.archivedBy())),
            Matchers.is(true)
        );
    }

    @Test
    void archiveAndDeleteByRepoTypeArchivesAcrossRepos() {
        this.seedLive("maven-proxy", "repo-a", "pkg1", "1.0.0");
        this.seedLive("maven-proxy", "repo-b", "pkg2", "2.0.0");
        this.seedLive("npm-proxy", "repo-c", "pkg3", "3.0.0");
        final int moved = this.repository.archiveAndDeleteByRepoType(
            "maven-proxy", ArchiveReason.MANUAL_UNBLOCK, "alice");
        MatcherAssert.assertThat(moved, Matchers.is(2));
        MatcherAssert.assertThat(
            "Only the npm-proxy row should remain live",
            this.liveRowCount(), Matchers.is(1L)
        );
        final List<DbHistoryRecord> history = this.repository.findHistoryPaginated(
            Set.of("repo-a", "repo-b", "repo-c"), null, null, null,
            "archived_at", false, 0, 50
        );
        MatcherAssert.assertThat(history, Matchers.hasSize(2));
        MatcherAssert.assertThat(
            history.stream().map(DbHistoryRecord::repoName).toList(),
            Matchers.containsInAnyOrder("repo-a", "repo-b")
        );
        MatcherAssert.assertThat(
            history.stream().allMatch(r -> "maven-proxy".equals(r.repoType())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            history.stream().allMatch(r -> r.archiveReason() == ArchiveReason.MANUAL_UNBLOCK),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            history.stream().allMatch(r -> "alice".equals(r.archivedBy())),
            Matchers.is(true)
        );
    }

    @Test
    void archiveAndDeleteAllArchivesEverything() {
        this.seedLive("maven-proxy", "repo-a", "pkg1", "1.0.0");
        this.seedLive("npm-proxy", "repo-b", "pkg2", "2.0.0");
        this.seedLive("pypi-proxy", "repo-c", "pkg3", "3.0.0");
        final int moved = this.repository.archiveAndDeleteAll(
            ArchiveReason.MANUAL_UNBLOCK, "alice");
        MatcherAssert.assertThat(moved, Matchers.is(3));
        MatcherAssert.assertThat(this.liveRowCount(), Matchers.is(0L));
        final List<DbHistoryRecord> history = this.repository.findHistoryPaginated(
            Set.of("repo-a", "repo-b", "repo-c"), null, null, null,
            "archived_at", false, 0, 50
        );
        MatcherAssert.assertThat(history, Matchers.hasSize(3));
        MatcherAssert.assertThat(
            history.stream().allMatch(r -> r.archiveReason() == ArchiveReason.MANUAL_UNBLOCK),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            history.stream().allMatch(r -> "alice".equals(r.archivedBy())),
            Matchers.is(true)
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private YamlMapping settings() {
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

    private void seedLive(
        final String repoType, final String repoName,
        final String artifact, final String version
    ) {
        this.insertLive(
            repoType, repoName, artifact, version,
            CooldownReason.FRESH_RELEASE, "system",
            Instant.now(),
            Instant.now().plus(Duration.ofHours(72))
        );
    }

    private long insertLive(
        final String repoType, final String repoName,
        final String artifact, final String version,
        final CooldownReason reason, final String blockedBy,
        final Instant blockedAt, final Instant blockedUntil
    ) {
        final DbBlockRecord rec = this.repository.insertBlock(
            repoType, repoName, artifact, version,
            reason, blockedAt, blockedUntil, blockedBy,
            Optional.empty()
        );
        return rec.id();
    }

    /**
     * Insert a row directly via SQL so we can control blocked_at /
     * blocked_until precisely (e.g. to create already-expired rows).
     */
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
        final long archivedAtMillis, final ArchiveReason reason
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
            stmt.setString(12, reason.name());
            stmt.setString(13, "system");
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }

    private boolean liveRowExists(final long id) {
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM artifact_cooldowns WHERE id = ?"
            )) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }

    private long liveRowCount() {
        return this.scalarCount("SELECT COUNT(*) FROM artifact_cooldowns");
    }

    private long historyRowCount() {
        return this.scalarCount("SELECT COUNT(*) FROM artifact_cooldowns_history");
    }

    private long scalarCount(final String sql) {
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql);
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
