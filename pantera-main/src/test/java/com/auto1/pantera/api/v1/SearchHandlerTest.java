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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.DbArtifactIndex;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link SearchHandler}.
 * @since 1.21.0
 */
public final class SearchHandlerTest extends AsyncApiTestBase {

    /**
     * Real {@link DbArtifactIndex}, backed by the shared Testcontainers
     * Postgres, used only by the {@code pathPrefix} tests below — every
     * other test in this class relies on the {@link ArtifactIndex#NOP}
     * default (empty search), which those tests don't disturb since
     * {@code testIndex()} is only overridden here, not in the base class.
     */
    private static DbArtifactIndex pathPrefixIndex;

    @BeforeAll
    static void setUpPathPrefixIndex() {
        SearchHandlerTest.pathPrefixIndex = new DbArtifactIndex(AsyncApiTestBase.sharedDs());
    }

    @AfterAll
    static void tearDownPathPrefixIndex() throws Exception {
        // Clean up the rows this class inserted directly via SQL — sharedDs()
        // is one Postgres instance reused by every AsyncApiTestBase subclass
        // in this JVM fork, so leaving them behind would leak test data into
        // sibling test classes' searches.
        try (Connection conn = AsyncApiTestBase.sharedDs().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "DELETE FROM artifacts WHERE repo_name LIKE 'search-handler-pathprefix-test-%'"
            );
        }
        if (SearchHandlerTest.pathPrefixIndex != null) {
            SearchHandlerTest.pathPrefixIndex.close();
        }
    }

    @Override
    protected ArtifactIndex testIndex() {
        return SearchHandlerTest.pathPrefixIndex;
    }

    @Test
    void searchResponseIncludesPathPrefixForGemAndConda(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        this.insertRow(
            "gem", "search-handler-pathprefix-test-gem",
            "search-handler-pathprefix-marker-rails", "7.0.4", 100L,
            "gems/search-handler-pathprefix-marker-rails-7.0.4.gem"
        );
        this.insertRow(
            "conda", "search-handler-pathprefix-test-conda",
            "search-handler-pathprefix-marker-numpy_linux-64", "1.21.0", 200L,
            "linux-64/search-handler-pathprefix-marker-numpy-1.21.0-py39_0.tar.bz2"
        );
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/search?q=search-handler-pathprefix-marker",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonArray items = res.bodyAsJsonObject().getJsonArray("items");
                MatcherAssert.assertThat(
                    "Should find both seeded rows", items.size(), new IsEqual<>(2)
                );
                for (int idx = 0; idx < items.size(); idx++) {
                    final JsonObject item = items.getJsonObject(idx);
                    MatcherAssert.assertThat(
                        "Every item here was seeded with a path_prefix",
                        item.getString("path_prefix"),
                        new IsNot<>(new IsNull<>())
                    );
                }
            }
        );
    }

    /**
     * Insert an {@code artifacts} row directly via SQL with a {@code
     * path_prefix}, bypassing {@link DbArtifactIndex#index}, which never
     * sets that column (it's populated by {@code DbConsumer}/{@code
     * DbSyncArtifactIndexer} from {@code ArtifactEvent}).
     *
     * @param repoType Repository type
     * @param repoName Repository name
     * @param name Artifact name (the {@code artifact_path} column)
     * @param version Artifact version
     * @param size Artifact size
     * @param pathPrefix Real storage key to store
     * @throws Exception On SQL error
     */
    private void insertRow(
        final String repoType, final String repoName, final String name,
        final String version, final long size, final String pathPrefix
    ) throws Exception {
        try (Connection conn = AsyncApiTestBase.sharedDs().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO artifacts (repo_type, repo_name, name, version, size, "
                     + "created_date, owner, path_prefix) VALUES (?,?,?,?,?,?,?,?)"
             )) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setString(3, name);
            stmt.setString(4, version);
            stmt.setLong(5, size);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.setString(7, "system");
            stmt.setString(8, pathPrefix);
            stmt.executeUpdate();
        }
    }

    @Test
    void searchRequiresQueryParam(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/search",
            res -> Assertions.assertEquals(400, res.statusCode())
        );
    }

    @Test
    void searchReturnsResults(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/search?q=test",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(
                    body.getJsonArray("items"),
                    "Response must have 'items' array"
                );
                Assertions.assertTrue(
                    body.containsKey("page"),
                    "Response must have 'page' field"
                );
                Assertions.assertTrue(
                    body.containsKey("size"),
                    "Response must have 'size' field"
                );
                Assertions.assertTrue(
                    body.containsKey("total"),
                    "Response must have 'total' field"
                );
                Assertions.assertTrue(
                    body.containsKey("hasMore"),
                    "Response must have 'hasMore' field"
                );
            }
        );
    }

    @Test
    void reindexReturns202(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.POST, "/api/v1/search/reindex",
            res -> {
                Assertions.assertEquals(202, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals(
                    "started", body.getString("status"),
                    "Response status must be 'started'"
                );
            }
        );
    }

    @Test
    void locateRequiresPathParam(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/search/locate",
            res -> Assertions.assertEquals(400, res.statusCode())
        );
    }

    @Test
    void locateReturnsRepositories(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/search/locate?path=com/example/lib/1.0/lib.jar",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(
                    body.getJsonArray("repositories"),
                    "Response must have 'repositories' array"
                );
                Assertions.assertTrue(
                    body.containsKey("count"),
                    "Response must have 'count' field"
                );
            }
        );
    }

    @Test
    void statsReturnsJsonObject(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/search/stats",
            res -> Assertions.assertEquals(200, res.statusCode())
        );
    }

    @Test
    void rejectsExcessiveOffset(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        // page=600, size=100 → offset = 60000 which exceeds MAX_OFFSET (10000)
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/search?q=test&page=600&size=100",
            res -> {
                Assertions.assertEquals(400, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertTrue(
                    body.getString("message").contains("10000"),
                    "Error message should mention the max offset limit"
                );
            }
        );
    }
}
