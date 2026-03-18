/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.v1;

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
            .put("name", "artipie")
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
                assertThat(json.getString("name"), is("artipie"));
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
