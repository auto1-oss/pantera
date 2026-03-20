/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api.v1;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link CooldownHandler}.
 * @since 1.21.0
 */
public final class CooldownHandlerTest extends AsyncApiTestBase {

    @Test
    void overviewEndpointReturns200(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/cooldown/overview",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final io.vertx.core.json.JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(
                    body.getJsonArray("repos"),
                    "Response must have 'repos' array"
                );
            }
        );
    }

    @Test
    void blockedEndpointReturnsPaginated(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/cooldown/blocked",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final io.vertx.core.json.JsonObject body = res.bodyAsJsonObject();
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
}
