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

import com.auto1.pantera.api.perms.ApiSearchPermission;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.ArtifactIndexCache;
import com.auto1.pantera.index.DbArtifactIndex;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for {@code search-authz}: before 2.2.9 the search
 * endpoint post-filtered DOCUMENTS by the caller's per-repository read
 * grant but served {@code total}, {@code hasMore}, {@code type_counts} and
 * {@code repo_counts} straight from an unscoped SQL result. In production
 * the index is an {@link ArtifactIndexCache} (never a bare
 * {@link DbArtifactIndex}), so the SQL-scoped branch was dead and the scope
 * resolver additionally failed OPEN for every DB user (no enumerable adapter
 * permissions → "unrestricted"). A user holding {@code api_search:read} and
 * ZERO repository grants — a supported role shape — received the global
 * artifact totals and the names of every restricted repository.
 *
 * <p>This test wraps a real DB index in {@link ArtifactIndexCache} exactly
 * as production does, seeds an artifact in a repository the caller cannot
 * read, and requires the aggregates to be empty.</p>
 *
 * @since 2.2.9
 */
public final class SearchHandlerScopeTest extends AsyncApiTestBase {

    private static final String SECRET_REPO = "search-scope-secret-repo";

    private static final String TERM = "scopeleakprobe";

    private static DbArtifactIndex dbIndex;

    @BeforeAll
    static void seed() throws Exception {
        SearchHandlerScopeTest.dbIndex = new DbArtifactIndex(AsyncApiTestBase.sharedDs());
        try (Connection conn = AsyncApiTestBase.sharedDs().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO artifacts (repo_type, repo_name, name, version, size, "
                     + "created_date, owner) VALUES (?,?,?,?,?,?,?)"
             )) {
            stmt.setString(1, "maven");
            stmt.setString(2, SECRET_REPO);
            stmt.setString(3, TERM);
            stmt.setString(4, "1.0.0");
            stmt.setLong(5, 10L);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.setString(7, "system");
            stmt.executeUpdate();
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (Connection conn = AsyncApiTestBase.sharedDs().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM artifacts WHERE repo_name = '" + SECRET_REPO + "'");
        }
        if (SearchHandlerScopeTest.dbIndex != null) {
            SearchHandlerScopeTest.dbIndex.close();
        }
    }

    @Override
    protected ArtifactIndex testIndex() {
        // Production shape: the DB index is always wrapped in the cache.
        return new ArtifactIndexCache(SearchHandlerScopeTest.dbIndex);
    }

    @Override
    protected Policy<?> testPolicy() {
        final Permissions perms = new Permissions();
        // search:read only — NO AdapterBasicPermission on any repository.
        perms.add(ApiSearchPermission.READ);
        final PermissionCollection frozen = perms;
        return user -> frozen;
    }

    @Test
    void userWithNoRepositoryGrantSeesNoAggregates(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final WebClient client = WebClient.create(vertx);
        final HttpResponse<Buffer> res = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/search?q=" + TERM)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat("search must succeed", res.statusCode(), new IsEqual<>(200));
        final JsonObject body = res.bodyAsJsonObject();
        MatcherAssert.assertThat(
            "documents must not leak (post-filter already covered this)",
            body.getJsonArray("items").size(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "total must be scoped to the caller's readable repositories, not the global count",
            body.getLong("total"), new IsEqual<>(0L)
        );
        MatcherAssert.assertThat(
            "repo_counts must not name a repository the caller cannot read",
            body.getJsonObject("repo_counts").containsKey(SECRET_REPO), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "type_counts must be scoped too",
            body.getJsonObject("type_counts").isEmpty(), new IsEqual<>(true)
        );
        ctx.completeNow();
    }
}
