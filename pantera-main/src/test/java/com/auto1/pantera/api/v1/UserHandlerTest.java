/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api.v1;

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
 * Integration tests for {@link UserHandler}.
 */
public final class UserHandlerTest extends AsyncApiTestBase {

    /**
     * PUT body for creating a test user.
     */
    private static final JsonObject USER_BODY = new JsonObject()
        .put("pass", "secret123")
        .put("type", "plain")
        .put("email", "test@example.com");

    @Test
    void listUsersReturnsPaginatedFormat(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/users",
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
    void getUserReturns404WhenMissing(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/users/nonexistent",
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
    void createAndGetUser(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Step 1: PUT the user
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/users/testuser")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(USER_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(201, put.statusCode());
        // Step 2: GET the user
        final HttpResponse<Buffer> get = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/users/testuser")
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

    @Test
    void deleteUser(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Step 1: PUT the user
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/users/testuser")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(USER_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(201, put.statusCode());
        // Step 2: DELETE the user
        final HttpResponse<Buffer> del = client
            .delete(this.port(), AsyncApiTestBase.HOST, "/api/v1/users/testuser")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, del.statusCode());
        ctx.completeNow();
    }
}
