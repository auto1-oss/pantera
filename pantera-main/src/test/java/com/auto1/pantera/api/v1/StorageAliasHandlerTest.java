/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api.v1;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link StorageAliasHandler}.
 */
public final class StorageAliasHandlerTest extends AsyncApiTestBase {

    /**
     * Sample alias configuration body.
     */
    private static final JsonObject ALIAS_BODY = new JsonObject()
        .put("type", "fs")
        .put("path", "/var/pantera/data");

    @Test
    void listGlobalAliasesReturnsArray(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/storages",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonArray body = res.bodyAsJsonArray();
                Assertions.assertNotNull(body, "Response body must be a JSON array");
            }
        );
    }

    @Test
    void createAndListGlobalAlias(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Step 1: PUT the alias
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/storages/default")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(ALIAS_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode());
        // Step 2: GET list and verify alias appears
        final HttpResponse<Buffer> get = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/storages")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, get.statusCode());
        final JsonArray aliases = get.bodyAsJsonArray();
        Assertions.assertNotNull(aliases);
        final boolean found = aliases.stream()
            .anyMatch(obj -> {
                if (obj instanceof JsonObject) {
                    final String alias = ((JsonObject) obj).getString("name");
                    return "default".equals(alias);
                }
                return false;
            });
        Assertions.assertTrue(found, "Alias 'default' should appear in the list after creation");
        ctx.completeNow();
    }

    @Test
    void deleteGlobalAlias(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Step 1: PUT the alias
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/storages/default")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(ALIAS_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode());
        // Step 2: DELETE the alias
        final HttpResponse<Buffer> del = client
            .delete(this.port(), AsyncApiTestBase.HOST, "/api/v1/storages/default")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, del.statusCode());
        ctx.completeNow();
    }
}
