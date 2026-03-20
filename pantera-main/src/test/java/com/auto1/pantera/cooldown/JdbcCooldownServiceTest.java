/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.cooldown.CooldownReason;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private String status(final String repo, final String artifact, final String version) {
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT status FROM artifact_cooldowns WHERE repo_name = ? AND artifact = ? AND version = ?"
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


    private void truncate() {
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "TRUNCATE TABLE artifact_cooldowns, artifacts RESTART IDENTITY"
            )) {
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }
}
