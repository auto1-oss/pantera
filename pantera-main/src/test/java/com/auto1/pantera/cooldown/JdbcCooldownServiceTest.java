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
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.DbManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class JdbcCooldownServiceTest {

    @Container
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:15");

    private DataSource dataSource;

    private CooldownRepository repository;

    private JdbcCooldownService service;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        this.dataSource = new ArtifactDbFactory(this.settings(), "cooldowns").initialize();
        // Run Flyway migrations so the V121 artifact_cooldowns_history table
        // exists — archiveAndDelete targets it from expire/release.
        DbManager.migrate(this.dataSource);
        this.repository = new CooldownRepository(this.dataSource);
        this.executor = Executors.newSingleThreadExecutor();
        this.service = new JdbcCooldownService(
            CooldownSettings.defaults(),
            this.repository,
            this.executor
        );
        this.truncate();
    }

    @AfterEach
    void tearDown() {
        this.truncate();
        this.executor.shutdownNow();
    }

    @Test
    void allowsWhenNewerVersionThanCache() {
        this.insertArtifact("maven-proxy", "central", "com.test.pkg", "1.0.0");
        final CooldownRequest request =
            new CooldownRequest("maven-proxy", "central", "com.test.pkg", "2.0.0", "alice", Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CooldownResult result = this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(result.blocked(), Matchers.is(false));
        MatcherAssert.assertThat(result.block().isEmpty(), Matchers.is(true));
    }

    @Test
    void blocksFreshReleaseWithinWindow() {
        final CooldownRequest request =
            new CooldownRequest("npm-proxy", "npm", "left-pad", "1.1.0", "bob", Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.of(Instant.now().minus(Duration.ofHours(1))));
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CooldownResult result = this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(result.blocked(), Matchers.is(true));
        MatcherAssert.assertThat(result.block().get().reason(), Matchers.is(CooldownReason.FRESH_RELEASE));
        
        // Verify block exists in DB
        final List<CooldownBlock> blocks = this.service.activeBlocks(request.repoType(), request.repoName()).join();
        final List<CooldownBlock> leftPadBlocks = blocks.stream()
            .filter(b -> "left-pad".equals(b.artifact()))
            .collect(java.util.stream.Collectors.toList());
        MatcherAssert.assertThat("Block for left-pad should exist", leftPadBlocks, Matchers.hasSize(1));
    }

    @Test
    void allowsWhenReleaseDateIsUnknown() {
        final CooldownRequest request =
            new CooldownRequest("npm-proxy", "npm", "left-pad", "1.1.0", "bob", Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CooldownResult result = this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(result.blocked(), Matchers.is(false));
    }

    @Test
    void allowsAfterManualUnblock() {
        this.insertArtifact("maven-proxy", "central", "com.test.pkg", "1.0.0");
        final CooldownRequest request =
            new CooldownRequest("maven-proxy", "central", "com.test.pkg", "2.0.0", "alice", Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        this.service.evaluate(request, inspector).join();
        this.service.unblock("maven-proxy", "central", "com.test.pkg", "2.0.0", "alice").join();
        final CooldownResult result = this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(result.blocked(), Matchers.is(false));
    }

    @Test
    void unblockAllReleasesForUser() {
        final CooldownRequest request =
            new CooldownRequest("npm-proxy", "npm", "main", "1.0.0", "eve", Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.of(Instant.now()));
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        this.service.evaluate(request, inspector).join();
        this.service.unblockAll("npm-proxy", "npm", "eve").join();
        MatcherAssert.assertThat(
            "Record should be deleted after unblock",
            this.recordExists("npm", "main", "1.0.0"),
            Matchers.is(false)
        );
    }

    @Test
    void storesSystemAsBlocker() {
        final CooldownRequest request =
            new CooldownRequest("maven-proxy", "central", "com.test.blocked", "3.0.0", "carol", Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.of(Instant.now()));
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(
            this.blockedBy("central", "com.test.blocked", "3.0.0"),
            Matchers.is("system")
        );
    }

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

    private void insertArtifact(
        final String repoType,
        final String repoName,
        final String name,
        final String version
    ) {
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO artifacts(repo_type, repo_name, name, version, size, created_date, owner) VALUES (?,?,?,?,?,?,?)"
            )) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setString(3, name);
            stmt.setString(4, version);
            stmt.setLong(5, 1L);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.setString(7, "tester");
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }

    private boolean recordExists(final String repo, final String artifact, final String version) {
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM artifact_cooldowns WHERE repo_name = ? AND artifact = ? AND version = ?"
            )) {
            stmt.setString(1, repo);
            stmt.setString(2, artifact);
            stmt.setString(3, version);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }

    private String blockedBy(final String repo, final String artifact, final String version) {
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT blocked_by FROM artifact_cooldowns WHERE repo_name = ? AND artifact = ? AND version = ?"
            )) {
            stmt.setString(1, repo);
            stmt.setString(2, artifact);
            stmt.setString(3, version);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
            throw new IllegalStateException("Cooldown entry not found");
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }

    private long blockId(final String repo, final String artifact, final String version) {
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT id FROM artifact_cooldowns WHERE repo_name = ? AND artifact = ? AND version = ?"
            )) {
            stmt.setString(1, repo);
            stmt.setString(2, artifact);
            stmt.setString(3, version);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            throw new IllegalStateException("Cooldown entry not found");
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }


    @Test
    void perRepoDurationBlocksArtifactReleasedWithinWindow() {
        // Global: 72h; per-repo "my-pypi": P30D (30 days)
        final CooldownSettings settings = CooldownSettings.defaults();
        settings.setRepoNameOverride("my-pypi", true, Duration.ofDays(30));
        final JdbcCooldownService svc = new JdbcCooldownService(
            settings, this.repository, this.executor
        );
        // Artifact released 5 days ago — within the 30-day per-repo window, should be blocked
        final Instant releaseDate = Instant.now().minus(Duration.ofDays(5));
        final CooldownRequest request =
            new CooldownRequest("pypi-proxy", "my-pypi", "requests", "2.31.0", "alice", Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.of(releaseDate));
            }
            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CooldownResult result = svc.evaluate(request, inspector).join();
        MatcherAssert.assertThat(
            "Artifact released 5 days ago must be blocked under 30-day per-repo cooldown",
            result.blocked(), Matchers.is(true)
        );
    }

    @Test
    void perRepoDisabledOverridesGlobalEnabled() {
        // Global: enabled; per-repo "internal-npm": disabled
        final CooldownSettings settings = CooldownSettings.defaults();
        settings.setRepoNameOverride("internal-npm", false, Duration.ofHours(72));
        final JdbcCooldownService svc = new JdbcCooldownService(
            settings, this.repository, this.executor
        );
        // Artifact released just now — would be blocked globally but repo override disables cooldown
        final CooldownRequest request =
            new CooldownRequest("npm-proxy", "internal-npm", "lodash", "4.17.21", "bob", Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.of(Instant.now()));
            }
            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CooldownResult result = svc.evaluate(request, inspector).join();
        MatcherAssert.assertThat(
            "Artifact must be allowed when per-repo cooldown is disabled",
            result.blocked(), Matchers.is(false)
        );
    }

    @Test
    void perRepoDurationDoesNotAffectOtherRepos() {
        // Per-repo "guarded-repo": 30 days; "other-repo": no override (global 72h)
        final CooldownSettings settings = CooldownSettings.defaults();
        settings.setRepoNameOverride("guarded-repo", true, Duration.ofDays(30));
        final JdbcCooldownService svc = new JdbcCooldownService(
            settings, this.repository, this.executor
        );
        // Artifact released 5 days ago — blocked in "guarded-repo" (30d) but NOT in "other-repo" (72h)
        final Instant releaseDate = Instant.now().minus(Duration.ofDays(5));
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.of(releaseDate));
            }
            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CooldownResult guarded = svc.evaluate(
            new CooldownRequest("maven-proxy", "guarded-repo", "com.example.lib", "1.0.0", "user", Instant.now()),
            inspector
        ).join();
        final CooldownResult other = svc.evaluate(
            new CooldownRequest("maven-proxy", "other-repo", "com.example.lib", "1.0.0", "user", Instant.now()),
            inspector
        ).join();
        MatcherAssert.assertThat("guarded-repo blocks 5-day-old artifact", guarded.blocked(), Matchers.is(true));
        MatcherAssert.assertThat("other-repo allows 5-day-old artifact (only 72h global)", other.blocked(), Matchers.is(false));
    }

    @Test
    void expireArchivesWithSystemActorAndExpiredReason() {
        // Seed an ACTIVE block whose blocked_until is already in the past.
        final Instant pastBlockedAt = Instant.now().minus(Duration.ofHours(80));
        final Instant pastBlockedUntil = Instant.now().minus(Duration.ofHours(1));
        final DbBlockRecord inserted = this.repository.insertBlock(
            "maven-proxy", "central", "com.expire.me", "1.0.0",
            CooldownReason.FRESH_RELEASE,
            pastBlockedAt, pastBlockedUntil, "system",
            Optional.empty()
        );
        // Trigger the expire path: evaluate() -> checkExistingBlockWithTimestamp()
        // sees blocked_until < now and calls expire().
        final CooldownRequest request = new CooldownRequest(
            "maven-proxy", "central", "com.expire.me", "1.0.0", "alice", Instant.now()
        );
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(
            "Live row should be gone after expire",
            this.recordExists("central", "com.expire.me", "1.0.0"),
            Matchers.is(false)
        );
        final List<DbHistoryRecord> history = this.repository.findHistoryPaginated(
            Set.of("central"), null, null, null, "archived_at", false, 0, 50
        );
        MatcherAssert.assertThat(
            "History should contain the archived expire row", history, Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            history.get(0).archiveReason(), Matchers.is(ArchiveReason.EXPIRED)
        );
        MatcherAssert.assertThat(
            history.get(0).archivedBy(), Matchers.is("system")
        );
        MatcherAssert.assertThat(
            history.get(0).originalId(), Matchers.is(inserted.id())
        );
    }

    @Test
    void releaseArchivesWithProvidedActorAndManualUnblockReason() {
        final Instant now = Instant.now();
        final DbBlockRecord inserted = this.repository.insertBlock(
            "npm-proxy", "npm", "left-pad", "1.1.0",
            CooldownReason.FRESH_RELEASE,
            now, now.plus(Duration.ofHours(72)), "system",
            Optional.empty()
        );
        this.service.unblock("npm-proxy", "npm", "left-pad", "1.1.0", "alice").join();
        MatcherAssert.assertThat(
            "Live row should be gone after release",
            this.recordExists("npm", "left-pad", "1.1.0"),
            Matchers.is(false)
        );
        final List<DbHistoryRecord> history = this.repository.findHistoryPaginated(
            Set.of("npm"), null, null, null, "archived_at", false, 0, 50
        );
        MatcherAssert.assertThat(
            "History should contain the archived release row", history, Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            history.get(0).archiveReason(), Matchers.is(ArchiveReason.MANUAL_UNBLOCK)
        );
        MatcherAssert.assertThat(
            history.get(0).archivedBy(), Matchers.is("alice")
        );
        MatcherAssert.assertThat(
            history.get(0).originalId(), Matchers.is(inserted.id())
        );
    }

    @Test
    void unblockAllForRepoRoutesThroughArchive() {
        // Seed three active blocks in the target repo plus one unrelated row
        // that must be left alone.
        final Instant now = Instant.now();
        final Instant until = now.plus(Duration.ofHours(72));
        for (int i = 0; i < 3; i++) {
            this.repository.insertBlock(
                "npm-proxy", "npm", "pkg" + i, "1.0." + i,
                CooldownReason.FRESH_RELEASE,
                now, until, "system", Optional.empty()
            );
        }
        this.repository.insertBlock(
            "npm-proxy", "other-repo", "keep-me", "9.9.9",
            CooldownReason.FRESH_RELEASE,
            now, until, "system", Optional.empty()
        );
        this.service.unblockAll("npm-proxy", "npm", "alice").join();
        MatcherAssert.assertThat(
            "All target-repo live rows should be gone",
            this.recordExists("npm", "pkg0", "1.0.0"), Matchers.is(false)
        );
        MatcherAssert.assertThat(
            this.recordExists("npm", "pkg1", "1.0.1"), Matchers.is(false)
        );
        MatcherAssert.assertThat(
            this.recordExists("npm", "pkg2", "1.0.2"), Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "Unrelated repo's row must remain live",
            this.recordExists("other-repo", "keep-me", "9.9.9"),
            Matchers.is(true)
        );
        final List<DbHistoryRecord> history = this.repository.findHistoryPaginated(
            Set.of("npm", "other-repo"), null, null, null,
            "archived_at", false, 0, 50
        );
        MatcherAssert.assertThat(
            "Three archived rows in history (one per bulk-unblocked live row)",
            history, Matchers.hasSize(3)
        );
        MatcherAssert.assertThat(
            "all history rows tagged MANUAL_UNBLOCK",
            history.stream().allMatch(r -> r.archiveReason() == ArchiveReason.MANUAL_UNBLOCK),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "all history rows tagged with the actor passed in",
            history.stream().allMatch(r -> "alice".equals(r.archivedBy())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "history rows are for the target repo only",
            history.stream().allMatch(r -> "npm".equals(r.repoName())),
            Matchers.is(true)
        );
    }

    private void truncate() {
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "TRUNCATE TABLE artifact_cooldowns, artifact_cooldowns_history, artifacts RESTART IDENTITY"
            )) {
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }
}
