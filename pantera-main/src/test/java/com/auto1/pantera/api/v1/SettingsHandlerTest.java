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
 * Integration tests for {@link SettingsHandler}.
 *
 * @since 1.21
 */
public final class SettingsHandlerTest extends AsyncApiTestBase {

    @Test
    void getSettingsReturnsPort(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/settings",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(body);
                Assertions.assertTrue(
                    body.containsKey("port"),
                    "Response must contain 'port' field"
                );
            }
        );
    }

    @Test
    void getSettingsReturnsVersion(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/settings",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(body);
                Assertions.assertNotNull(
                    body.getString("version"),
                    "Response must contain non-null 'version' field"
                );
            }
        );
    }
}
