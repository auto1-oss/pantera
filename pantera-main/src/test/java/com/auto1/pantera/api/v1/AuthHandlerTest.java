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
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for AuthHandler endpoints.
 */
class AuthHandlerTest extends AsyncApiTestBase {

    @Test
    void tokenEndpointReturnsJwt(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        final JsonObject body = new JsonObject()
            .put("name", "pantera")
            .put("pass", "secret");
        request(vertx, ctx, HttpMethod.POST, "/api/v1/auth/token", body, null,
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                assertThat(json.getString("token"), notNullValue());
            });
    }

    @Test
    void providersEndpointReturnsArray(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        request(vertx, ctx, HttpMethod.GET, "/api/v1/auth/providers", null, null,
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                assertThat(json.getJsonArray("providers"), notNullValue());
                assertThat(json.getJsonArray("providers").size() > 0, is(true));
            });
    }

    @Test
    void meEndpointReturnsCurrentUser(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        request(vertx, ctx, HttpMethod.GET, "/api/v1/auth/me",
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                assertThat(json.getString("name"), is("pantera"));
            });
    }

    @Test
    void meEndpointReturnsPermissions(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        request(vertx, ctx, HttpMethod.GET, "/api/v1/auth/me",
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                assertThat(json.getJsonObject("permissions"), notNullValue());
            });
    }
}
