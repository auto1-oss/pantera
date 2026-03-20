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
