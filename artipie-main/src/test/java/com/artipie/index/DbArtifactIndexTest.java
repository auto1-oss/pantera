/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.index;

import com.amihaiemil.eoyaml.Yaml;
import com.artipie.db.ArtifactDbFactory;
import com.artipie.db.PostgreSQLTestConfig;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
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
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
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
}
