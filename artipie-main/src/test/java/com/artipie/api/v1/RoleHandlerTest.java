/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.v1;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link RoleHandler}.
 */
public final class RoleHandlerTest extends AsyncApiTestBase {

    /**
     * PUT body for creating a test role.
     */
    private static final JsonObject ROLE_BODY = new JsonObject()
        .put(
            "permissions",
            new JsonObject().put(
                "api_repository",
                new JsonObject().put("read", true).put("write", false)
            )
        );

    @Test
    void listRolesReturnsPaginatedFormat(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/roles",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(body.getJsonArray("items"));
                Assertions.assertTrue(body.containsKey("page"));
                Assertions.assertTrue(body.containsKey("size"));
                Assertions.assertTrue(body.containsKey("total"));
                Assertions.assertTrue(body.containsKey("hasMore"));
            }
        );
    }

    @Test
    void getRoleReturns404WhenMissing(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/roles/nonexistent",
            res -> {
                Assertions.assertEquals(404, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals("NOT_FOUND", body.getString("error"));
                Assertions.assertEquals(404, body.getInteger("status"));
                Assertions.assertNotNull(body.getString("message"));
            }
        );
    }

    @Test
    void createAndGetRole(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Step 1: PUT the role
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/roles/testrole")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(ROLE_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(201, put.statusCode());
        // Step 2: GET the role
        final HttpResponse<Buffer> get = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/roles/testrole")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, get.statusCode());
        final String body = get.bodyAsString();
        Assertions.assertNotNull(body);
        Assertions.assertFalse(body.isBlank());
        ctx.completeNow();
    }
}
