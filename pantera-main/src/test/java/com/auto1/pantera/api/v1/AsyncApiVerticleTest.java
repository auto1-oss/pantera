/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.v1;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests for AsyncApiVerticle health endpoint and auth.
 */
class AsyncApiVerticleTest extends AsyncApiTestBase {

    @Test
    void healthEndpointReturnsOk(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        request(vertx, ctx, HttpMethod.GET, "/api/v1/health", null, null,
            res -> {
                assertThat(res.statusCode(), is(200));
                assertThat(
                    res.bodyAsJsonObject().getString("status"), is("ok")
                );
            });
    }

    @Test
    void returns401WithoutToken(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        request(vertx, ctx, HttpMethod.GET, "/api/v1/repositories", null, null,
            res -> assertThat(res.statusCode(), is(401)));
    }
}
