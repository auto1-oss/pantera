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
package com.auto1.pantera.index;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link DbArtifactIndex}.
 * Uses Testcontainers PostgreSQL for integration testing.
 *
 * @since 1.20.13
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods", "PMD.TooManyStaticImports"})
@Testcontainers
class DbArtifactIndexTest {

    /**
     * PostgreSQL test container.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSQLTestConfig.createContainer();

    /**
     * DataSource for tests.
     */
    private DataSource dataSource;

    /**
     * Index under test.
     */
    private DbArtifactIndex index;

    @BeforeEach
    void setUp() throws Exception {
        this.dataSource = new ArtifactDbFactory(
            Yaml.createYamlMappingBuilder().add(
                "artifacts_database",
                Yaml.createYamlMappingBuilder()
                    .add(ArtifactDbFactory.YAML_HOST, POSTGRES.getHost())
                    .add(ArtifactDbFactory.YAML_PORT, String.valueOf(POSTGRES.getFirstMappedPort()))
                    .add(ArtifactDbFactory.YAML_DATABASE, POSTGRES.getDatabaseName())
                    .add(ArtifactDbFactory.YAML_USER, POSTGRES.getUsername())
                    .add(ArtifactDbFactory.YAML_PASSWORD, POSTGRES.getPassword())
                    // Limit pool size to 2 per test JVM to avoid exhausting PostgreSQL's
                    // max_connections (100) when multiple forks run concurrently.
                    .add(ArtifactDbFactory.YAML_POOL_MAX_SIZE, "2")
                    .add(ArtifactDbFactory.YAML_POOL_MIN_IDLE, "1")
                    .build()
            ).build(),
            "artifacts"
        ).initialize();
        // Clean up artifacts table before each test
        try (Connection conn = this.dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM artifacts");
        }
        this.index = new DbArtifactIndex(this.dataSource);
    }

    @AfterEach
    void tearDown() {
        if (this.index != null) {
            this.index.close();
        }
        if (this.dataSource instanceof HikariDataSource) {
            ((HikariDataSource) this.dataSource).close();
        }
    }

    @Test
    void indexAndLocate() throws Exception {
        final ArtifactDocument doc = new ArtifactDocument(
            "maven", "my-repo", "com/example/lib", "lib",
            "1.0.0", 1024L, Instant.now(), "admin"
        );
        this.index.index(doc).join();
        final List<String> repos = this.index.locate("com/example/lib").join();
        MatcherAssert.assertThat(repos, Matchers.contains("my-repo"));
    }

    @Test
    void indexAndSearch() throws Exception {
        this.index.index(new ArtifactDocument(
            "maven", "repo1", "com/example/alpha-lib", "alpha-lib",
            "1.0", 100L, Instant.now(), "user1"
        )).join();
        this.index.index(new ArtifactDocument(
            "maven", "repo2", "com/example/beta-lib", "beta-lib",
            "2.0", 200L, Instant.now(), "user2"
        )).join();
        final SearchResult result = this.index.search("lib", 10, 0).join();
        MatcherAssert.assertThat(
            "Should find both artifacts containing 'lib'",
            result.documents().size(),
            new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "Total hits should be 2",
            result.totalHits(),
            new IsEqual<>(2L)
        );
    }

    @Test
    void indexUpsert() throws Exception {
        final Instant now = Instant.now();
        this.index.index(new ArtifactDocument(
            "maven", "repo1", "com/example/lib", "lib",
            "1.0", 100L, now, "user1"
        )).join();
        // Upsert same doc with different size
        this.index.index(new ArtifactDocument(
            "maven", "repo1", "com/example/lib", "lib",
            "1.0", 999L, now, "user2"
        )).join();
        final SearchResult result = this.index.search("com/example/lib", 10, 0).join();
        MatcherAssert.assertThat(
            "Should have exactly 1 document after upsert",
            result.documents().size(),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "Size should be updated to 999",
            result.documents().get(0).size(),
            new IsEqual<>(999L)
        );
    }

    @Test
    void removeByRepoAndName() throws Exception {
        this.index.index(new ArtifactDocument(
            "maven", "repo1", "com/example/lib", "lib",
            "1.0", 100L, Instant.now(), "user1"
        )).join();
        this.index.remove("repo1", "com/example/lib").join();
        final List<String> repos = this.index.locate("com/example/lib").join();
        MatcherAssert.assertThat(
            "Locate should return empty after removal",
            repos,
            Matchers.empty()
        );
    }

    @Test
    void locateReturnsMultipleRepos() throws Exception {
        this.index.index(new ArtifactDocument(
            "maven", "repo-a", "shared/artifact", "artifact",
            "1.0", 100L, Instant.now(), "user1"
        )).join();
        this.index.index(new ArtifactDocument(
            "maven", "repo-b", "shared/artifact", "artifact",
            "1.0", 200L, Instant.now(), "user2"
        )).join();
        final List<String> repos = this.index.locate("shared/artifact").join();
        MatcherAssert.assertThat(
            "Should find artifact in both repos",
            repos,
            Matchers.containsInAnyOrder("repo-a", "repo-b")
        );
    }

    @Test
    void searchWithPagination() throws Exception {
        for (int idx = 0; idx < 10; idx++) {
            this.index.index(new ArtifactDocument(
                "maven", "repo1", "com/example/item-" + idx, "item-" + idx,
                "1.0", idx * 10L, Instant.now(), "user1"
            )).join();
        }
        final SearchResult page1 = this.index.search("item", 3, 0).join();
        MatcherAssert.assertThat(
            "First page should have 3 results",
            page1.documents().size(),
            new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "Total hits should be 10",
            page1.totalHits(),
            new IsEqual<>(10L)
        );
        final SearchResult page2 = this.index.search("item", 3, 3).join();
        MatcherAssert.assertThat(
            "Second page should have 3 results",
            page2.documents().size(),
            new IsEqual<>(3)
        );
    }

    @Test
    void getStatsReturnsCount() throws Exception {
        for (int idx = 0; idx < 5; idx++) {
            this.index.index(new ArtifactDocument(
                "maven", "repo1", "artifact-" + idx, "artifact-" + idx,
                "1.0", 100L, Instant.now(), "user1"
            )).join();
        }
        final Map<String, Object> stats = this.index.getStats().join();
        MatcherAssert.assertThat(
            "Document count should be 5",
            stats.get("documents"),
            new IsEqual<>(5L)
        );
        MatcherAssert.assertThat(
            "Should be warmed up",
            stats.get("warmedUp"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Type should be postgresql",
            stats.get("type"),
            new IsEqual<>("postgresql")
        );
    }

    @Test
    void isAlwaysWarmedUp() {
        MatcherAssert.assertThat(
            "DbArtifactIndex should always be warmed up",
            this.index.isWarmedUp(),
            new IsEqual<>(true)
        );
    }

    @Test
    void locateByPathPrefix() throws Exception {
        // Insert rows with path_prefix directly (DbConsumer sets this, not DbArtifactIndex.index)
        try (Connection conn = this.dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO artifacts (repo_type, repo_name, name, version, size, created_date, owner, path_prefix) "
                     + "VALUES (?,?,?,?,?,?,?,?)"
             )) {
            stmt.setString(1, "maven");
            stmt.setString(2, "maven-central");
            stmt.setString(3, "com.google.guava:guava");
            stmt.setString(4, "31.1");
            stmt.setLong(5, 1000L);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.setString(7, "proxy");
            stmt.setString(8, "com/google/guava/guava/31.1");
            stmt.executeUpdate();
        }
        // Locate with a full artifact path — should match via path_prefix
        final List<String> repos = this.index.locate(
            "com/google/guava/guava/31.1/guava-31.1.jar"
        ).join();
        MatcherAssert.assertThat(
            "Should find repo via path_prefix match",
            repos,
            Matchers.contains("maven-central")
        );
        // Locate with a path that doesn't match any prefix
        final List<String> empty = this.index.locate("org/apache/commons/commons-lang3/3.12/commons-lang3-3.12.jar").join();
        MatcherAssert.assertThat(
            "Should return empty for non-matching path",
            empty,
            Matchers.empty()
        );
    }

    @Test
    void pathPrefixesDecomposition() {
        MatcherAssert.assertThat(
            "Multi-segment path",
            DbArtifactIndex.pathPrefixes("com/google/guava/guava/31.1/guava-31.1.jar"),
            Matchers.contains(
                "com", "com/google", "com/google/guava",
                "com/google/guava/guava", "com/google/guava/guava/31.1"
            )
        );
        MatcherAssert.assertThat(
            "Single-segment path",
            DbArtifactIndex.pathPrefixes("artifact.jar"),
            Matchers.contains("artifact.jar")
        );
        MatcherAssert.assertThat(
            "Leading slash stripped",
            DbArtifactIndex.pathPrefixes("/com/example/lib"),
            Matchers.contains("com", "com/example")
        );
    }

    @Test
    void locateByNameFindsRepos() throws Exception {
        // Index artifacts with known names (as adapters store them)
        this.index.index(new ArtifactDocument(
            "maven", "maven-central", "com.google.guava.guava", "guava",
            "31.1.3-jre", 2_000_000L, Instant.now(), "proxy"
        )).join();
        this.index.index(new ArtifactDocument(
            "maven", "maven-releases", "com.google.guava.guava", "guava",
            "31.1.3-jre", 2_000_000L, Instant.now(), "admin"
        )).join();
        this.index.index(new ArtifactDocument(
            "maven", "maven-central", "org.slf4j.slf4j-api", "slf4j-api",
            "2.0.9", 50_000L, Instant.now(), "proxy"
        )).join();
        // locateByName should find both repos for guava
        final List<String> guavaRepos = this.index.locateByName("com.google.guava.guava").join();
        MatcherAssert.assertThat(
            "Should find guava in both repos",
            guavaRepos,
            Matchers.containsInAnyOrder("maven-central", "maven-releases")
        );
        // locateByName should find only maven-central for slf4j
        final List<String> slf4jRepos = this.index.locateByName("org.slf4j.slf4j-api").join();
        MatcherAssert.assertThat(
            "Should find slf4j in maven-central only",
            slf4jRepos,
            Matchers.contains("maven-central")
        );
        // locateByName with non-existent name
        final List<String> missing = this.index.locateByName("com.nonexistent.lib").join();
        MatcherAssert.assertThat(
            "Should return empty for missing artifact",
            missing,
            Matchers.empty()
        );
    }

    @Test
    void locateByNameUsesExistingIndex() throws Exception {
        // This test verifies the name-based locate works for all adapter name formats
        // Maven: dotted notation
        this.index.index(new ArtifactDocument(
            "maven", "repo1", "com.google.guava.guava", "guava",
            "31.1", 100L, Instant.now(), "user"
        )).join();
        // npm: package name with scope
        this.index.index(new ArtifactDocument(
            "npm", "repo2", "@babel/core", "core",
            "7.23.0", 200L, Instant.now(), "user"
        )).join();
        // Docker: image name
        this.index.index(new ArtifactDocument(
            "docker", "repo3", "library/nginx", "nginx",
            "sha256:abc", 300L, Instant.now(), "user"
        )).join();
        // PyPI: normalized name
        this.index.index(new ArtifactDocument(
            "pypi", "repo4", "numpy", "numpy",
            "1.24.0", 400L, Instant.now(), "user"
        )).join();
        MatcherAssert.assertThat(
            "Maven name lookup",
            this.index.locateByName("com.google.guava.guava").join(),
            Matchers.contains("repo1")
        );
        MatcherAssert.assertThat(
            "npm scoped name lookup",
            this.index.locateByName("@babel/core").join(),
            Matchers.contains("repo2")
        );
        MatcherAssert.assertThat(
            "Docker image name lookup",
            this.index.locateByName("library/nginx").join(),
            Matchers.contains("repo3")
        );
        MatcherAssert.assertThat(
            "PyPI name lookup",
            this.index.locateByName("numpy").join(),
            Matchers.contains("repo4")
        );
    }

    @Test
    void locateByNameHitRateWithMixedData() throws Exception {
        // Simulate realistic data: index many artifacts, then verify
        // that locateByName finds them all (100% hit rate for indexed data)
        final String[] mavenNames = {
            "com.google.guava.guava",
            "org.apache.commons.commons-lang3",
            "org.slf4j.slf4j-api",
            "junit.junit",
            "io.netty.netty-all",
            "com.fasterxml.jackson.core.jackson-databind",
            "org.projectlombok.lombok",
            "org.springframework.spring-core",
            "org.apache.maven.plugins.maven-compiler-plugin",
            "org.apache.maven.plugins.maven-surefire-plugin",
        };
        for (final String name : mavenNames) {
            this.index.index(new ArtifactDocument(
                "maven", "maven-central", name, name.substring(name.lastIndexOf('.') + 1),
                "1.0.0", 100L, Instant.now(), "proxy"
            )).join();
        }
        int hits = 0;
        for (final String name : mavenNames) {
            final List<String> repos = this.index.locateByName(name).join();
            if (!repos.isEmpty()) {
                hits++;
            }
        }
        MatcherAssert.assertThat(
            String.format("locateByName hit rate: %d/%d", hits, mavenNames.length),
            hits,
            new IsEqual<>(mavenNames.length)
        );
    }

    @Test
    void toSortFieldMapsValidValues() {
        MatcherAssert.assertThat(
            DbArtifactIndex.toSortField("name"),
            new IsEqual<>(DbArtifactIndex.SortField.NAME)
        );
        MatcherAssert.assertThat(
            DbArtifactIndex.toSortField("version"),
            new IsEqual<>(DbArtifactIndex.SortField.VERSION)
        );
        MatcherAssert.assertThat(
            DbArtifactIndex.toSortField("created_at"),
            new IsEqual<>(DbArtifactIndex.SortField.DATE)
        );
        MatcherAssert.assertThat(
            DbArtifactIndex.toSortField("relevance"),
            new IsEqual<>(DbArtifactIndex.SortField.RELEVANCE)
        );
    }

    @Test
    void toSortFieldDefaultsForUnknown() {
        MatcherAssert.assertThat(
            "Unknown string maps to RELEVANCE",
            DbArtifactIndex.toSortField("invalid"),
            new IsEqual<>(DbArtifactIndex.SortField.RELEVANCE)
        );
        MatcherAssert.assertThat(
            "Null maps to RELEVANCE",
            DbArtifactIndex.toSortField(null),
            new IsEqual<>(DbArtifactIndex.SortField.RELEVANCE)
        );
    }

    @Test
    void searchWithOffsetSkipsFacets() throws Exception {
        for (int idx = 0; idx < 5; idx++) {
            this.index.index(new ArtifactDocument(
                "maven", "repo1", "facet-item-" + idx, "facet-item-" + idx,
                "1.0." + idx, 100L, Instant.now(), "user1"
            )).join();
        }
        // Page 0 should have non-empty facets
        final SearchResult page0 = this.index.search(
            "facet", 3, 0, null, null, "relevance", true
        ).join();
        MatcherAssert.assertThat(
            "First page should have type counts",
            page0.typeCounts().isEmpty(),
            new IsEqual<>(false)
        );
        // Page 1 (offset=3) should have empty facet maps
        final SearchResult page1 = this.index.search(
            "facet", 3, 3, null, null, "relevance", true
        ).join();
        MatcherAssert.assertThat(
            "Non-first page typeCounts must be empty",
            page1.typeCounts().isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Non-first page repoCounts must be empty",
            page1.repoCounts().isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void typeCountsMergeSuffixes() throws Exception {
        // Insert docs with base, -proxy, and -group variants of maven
        this.index.index(new ArtifactDocument(
            "maven", "maven-hosted", "com.example.core", "core",
            "1.0", 100L, Instant.now(), "admin"
        )).join();
        this.index.index(new ArtifactDocument(
            "maven-proxy", "maven-central", "com.example.core", "core",
            "1.0", 100L, Instant.now(), "proxy"
        )).join();
        this.index.index(new ArtifactDocument(
            "maven-group", "maven-all", "com.example.core", "core",
            "1.0", 100L, Instant.now(), "admin"
        )).join();
        final SearchResult result = this.index.search(
            "core", 10, 0, null, null, "relevance", true
        ).join();
        MatcherAssert.assertThat(
            "Should have results",
            result.totalHits() > 0,
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Type counts should have exactly one 'maven' entry (merged suffixes)",
            result.typeCounts().containsKey("maven"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Merged maven count should be 3 (base + proxy + group)",
            result.typeCounts().get("maven"),
            new IsEqual<>(3L)
        );
        MatcherAssert.assertThat(
            "Type counts map should not contain 'maven-proxy'",
            result.typeCounts().containsKey("maven-proxy"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Type counts map should not contain 'maven-group'",
            result.typeCounts().containsKey("maven-group"),
            new IsEqual<>(false)
        );
    }

    @Test
    void indexBatchMultipleDocs() throws Exception {
        final List<ArtifactDocument> docs = new ArrayList<>();
        for (int idx = 0; idx < 5; idx++) {
            docs.add(new ArtifactDocument(
                "npm", "npm-repo", "pkg-" + idx, "pkg-" + idx,
                "2.0." + idx, 50L * idx, Instant.now(), "dev"
            ));
        }
        this.index.indexBatch(docs).join();
        final SearchResult result = this.index.search("pkg", 10, 0).join();
        MatcherAssert.assertThat(
            "All batch-indexed docs should be searchable",
            result.documents().size(),
            new IsEqual<>(5)
        );
        MatcherAssert.assertThat(
            "Total hits from batch should be 5",
            result.totalHits(),
            new IsEqual<>(5L)
        );
    }

    /**
     * Fix 3: when offset is beyond all results the page is empty, but
     * totalHits must still reflect the real document count via fallbackCount().
     * Without Fix 3, totalHits would be 0 on a deep page because COUNT(*) OVER()
     * returns no rows when the result set is empty.
     */
    @Test
    void searchWithDeepOffsetReturnsCorrectTotal() throws Exception {
        for (int idx = 0; idx < 3; idx++) {
            this.index.index(new ArtifactDocument(
                "pypi", "pypi-repo", "deep-item-" + idx, "deep-item-" + idx,
                "1.0." + idx, 100L, Instant.now(), "user"
            )).join();
        }
        // offset=100 is well beyond all 3 results — page must be empty
        final SearchResult result = this.index.search(
            "deep-item", 10, 100, null, null, "relevance", true
        ).join();
        MatcherAssert.assertThat(
            "Items must be empty on a deep page",
            result.documents().isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Total hits must equal 3 (not 0) via fallbackCount",
            result.totalHits(),
            new IsEqual<>(3L)
        );
    }

    /**
     * Fix 5: when allowedRepos is provided the SQL adds AND repo_name = ANY(?)
     * so only results from permitted repos are returned.
     */
    @Test
    void searchWithAllowedReposFiltersResults() throws Exception {
        this.index.index(new ArtifactDocument(
            "pypi", "pypi-proxy", "requests", "requests",
            "2.31.0", 500L, Instant.now(), "user"
        )).join();
        this.index.index(new ArtifactDocument(
            "maven", "maven-proxy", "requests", "requests",
            "1.0.0", 200L, Instant.now(), "user"
        )).join();
        // Search without filter — should return both
        final SearchResult all = this.index.search(
            "requests", 10, 0, null, null, "relevance", true, null
        ).join();
        MatcherAssert.assertThat(
            "Unfiltered search should find both repos",
            all.totalHits(),
            new IsEqual<>(2L)
        );
        // Search with allowedRepos restricted to pypi-proxy — should exclude maven-proxy
        final SearchResult filtered = this.index.search(
            "requests", 10, 0, null, null, "relevance", true, List.of("pypi-proxy")
        ).join();
        MatcherAssert.assertThat(
            "allowedRepos filter must return only 1 result",
            filtered.documents().size(),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "Allowed result must be from pypi-proxy",
            filtered.documents().get(0).repoName(),
            new IsEqual<>("pypi-proxy")
        );
        MatcherAssert.assertThat(
            "Total hits with allowedRepos must be 1",
            filtered.totalHits(),
            new IsEqual<>(1L)
        );
    }
}
