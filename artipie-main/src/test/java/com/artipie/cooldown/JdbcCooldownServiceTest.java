/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.artipie.db.ArtifactDbFactory;
import com.artipie.db.SharedPostgreSQLContainer;
import com.artipie.cooldown.CooldownReason;
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class JdbcCooldownServiceTest {

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
                this.executor);
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
        final CooldownRequest request = new CooldownRequest("maven-proxy", "central", "com.test.pkg", "2.0.0", "alice",
                Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact,
                    final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CooldownResult result = this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(result.blocked(), Matchers.is(false));
        MatcherAssert.assertThat(result.block().isEmpty(), Matchers.is(true));
    }

    @Test
    void blocksFreshReleaseWithinWindow() {
        final CooldownRequest request = new CooldownRequest("npm-proxy", "npm", "left-pad", "1.1.0", "bob",
                Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.of(Instant.now().minus(Duration.ofHours(1))));
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact,
                    final String version) {
                return CompletableFuture.completedFuture(List.of(new CooldownDependency("dependency", "1.0.0")));
            }
        };
        final CooldownResult result = this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(result.blocked(), Matchers.is(true));
        MatcherAssert.assertThat(result.block().get().reason(), Matchers.is(CooldownReason.FRESH_RELEASE));
        MatcherAssert.assertThat(result.block().get().dependencies(), Matchers.hasSize(1));
    }

    @Test
    void allowsWhenReleaseDateIsUnknown() {
        final CooldownRequest request = new CooldownRequest("npm-proxy", "npm", "left-pad", "1.1.0", "bob",
                Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact,
                    final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CooldownResult result = this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(result.blocked(), Matchers.is(false));
    }

    @Test
    void allowsAfterManualUnblock() {
        this.insertArtifact("maven-proxy", "central", "com.test.pkg", "1.0.0");
        final CooldownRequest request = new CooldownRequest("maven-proxy", "central", "com.test.pkg", "2.0.0", "alice",
                Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact,
                    final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        this.service.evaluate(request, inspector).join();
        this.service.unblock("maven-proxy", "central", "com.test.pkg", "2.0.0", "alice").join();
        final CooldownResult result = this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(result.blocked(), Matchers.is(false));
    }

    @Test
    void unblockAllReleasesDependencies() {
        final CooldownRequest request = new CooldownRequest("npm-proxy", "npm", "main", "1.0.0", "eve", Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.of(Instant.now()));
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact,
                    final String version) {
                return CompletableFuture.completedFuture(List.of(
                        new CooldownDependency("dep-a", "1.0.0"),
                        new CooldownDependency("dep-b", "2.0.0")));
            }
        };
        this.service.evaluate(request, inspector).join();
        this.service.unblockAll("npm-proxy", "npm", "eve").join();
        MatcherAssert.assertThat(this.status("npm", "main", "1.0.0"), Matchers.is("INACTIVE"));
        MatcherAssert.assertThat(this.status("npm", "dep-a", "1.0.0"), Matchers.is("INACTIVE"));
        MatcherAssert.assertThat(this.status("npm", "dep-b", "2.0.0"), Matchers.is("INACTIVE"));
    }

    @Test
    void storesSystemAsBlockerAndTracksRequester() {
        final CooldownRequest request = new CooldownRequest("maven-proxy", "central", "com.test.blocked", "3.0.0",
                "carol", Instant.now());
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.of(Instant.now()));
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact,
                    final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        this.service.evaluate(request, inspector).join();
        MatcherAssert.assertThat(
                this.blockedBy("central", "com.test.blocked", "3.0.0"),
                Matchers.is("system"));
        final long id = this.blockId("central", "com.test.blocked", "3.0.0");
        MatcherAssert.assertThat(
                this.requesters(id),
                Matchers.contains("carol"));
    }

    private YamlMapping settings() {
        PostgreSQLContainer<?> postgres = SharedPostgreSQLContainer.getInstance();
        return Yaml.createYamlMappingBuilder().add(
                "artifacts_database",
                Yaml.createYamlMappingBuilder()
                        .add(ArtifactDbFactory.YAML_HOST, postgres.getHost())
                        .add(ArtifactDbFactory.YAML_PORT, String.valueOf(postgres.getFirstMappedPort()))
                        .add(ArtifactDbFactory.YAML_DATABASE, postgres.getDatabaseName())
                        .add(ArtifactDbFactory.YAML_USER, postgres.getUsername())
                        .add(ArtifactDbFactory.YAML_PASSWORD, postgres.getPassword())
                        .build())
                .build();
    }

    private void insertArtifact(
            final String repoType,
            final String repoName,
            final String name,
            final String version) {
        try (Connection conn = this.dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO artifacts(repo_type, repo_name, name, version, size, created_date, owner) VALUES (?,?,?,?,?,?,?)")) {
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

    private String status(final String repo, final String artifact, final String version) {
        try (Connection conn = this.dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT status FROM artifact_cooldowns WHERE repo_name = ? AND artifact = ? AND version = ?")) {
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
                        "SELECT blocked_by FROM artifact_cooldowns WHERE repo_name = ? AND artifact = ? AND version = ?")) {
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
                        "SELECT id FROM artifact_cooldowns WHERE repo_name = ? AND artifact = ? AND version = ?")) {
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

    private List<String> requesters(final long blockId) {
        try (Connection conn = this.dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT requested_by FROM artifact_cooldown_attempts WHERE block_id = ? ORDER BY attempted_at")) {
            stmt.setLong(1, blockId);
            try (ResultSet rs = stmt.executeQuery()) {
                final List<String> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
                return result;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }

    private void truncate() {
        try (Connection conn = this.dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "TRUNCATE TABLE artifact_cooldown_attempts, artifact_cooldowns, artifacts RESTART IDENTITY")) {
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(err);
        }
    }
}
