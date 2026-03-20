/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.v1;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link DashboardHandler}.
 */
public final class DashboardHandlerTest extends AsyncApiTestBase {

    @Test
    void statsReturnsRepoCount(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/dashboard/stats",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(body);
                Assertions.assertTrue(
                    body.containsKey("repo_count"),
                    "Response must contain repo_count"
                );
                Assertions.assertEquals(0, body.getInteger("repo_count"));
                Assertions.assertEquals(0, body.getInteger("artifact_count"));
                Assertions.assertEquals("0", body.getString("total_storage"));
                Assertions.assertEquals(0, body.getInteger("blocked_count"));
            }
        );
    }

    @Test
    void requestsReturnsPlaceholder(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/dashboard/requests",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(body);
                Assertions.assertEquals("24h", body.getString("period"));
                Assertions.assertNotNull(
                    body.getJsonArray("data"),
                    "Response must contain data array"
                );
            }
        );
    }

    @Test
    void reposByTypeReturnsEmptyWhenNoRepos(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/dashboard/repos-by-type",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(body);
                Assertions.assertNotNull(
                    body.getJsonObject("types"),
                    "Response must contain types object"
                );
                Assertions.assertTrue(
                    body.getJsonObject("types").isEmpty(),
                    "types should be empty when no repos exist"
                );
            }
        );
    }
}
