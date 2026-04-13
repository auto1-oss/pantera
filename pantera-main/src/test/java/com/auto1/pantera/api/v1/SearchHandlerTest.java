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

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link SearchHandler}.
 * @since 1.21.0
 */
public final class SearchHandlerTest extends AsyncApiTestBase {

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
