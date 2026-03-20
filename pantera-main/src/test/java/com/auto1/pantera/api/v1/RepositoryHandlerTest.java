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
 * Integration tests for {@link RepositoryHandler}.
 */
public final class RepositoryHandlerTest extends AsyncApiTestBase {

    /**
     * Valid repo body: maven-proxy with fs storage.
     */
    private static final JsonObject VALID_BODY = new JsonObject()
        .put(
            "repo",
            new JsonObject()
                .put("type", "maven-proxy")
                .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
        );

    @Test
    void listReposReturnsPaginatedFormat(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/repositories",
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
    void createRepoAndGet(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Step 1: PUT the repo
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/myrepo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(VALID_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode());
        // Step 2: GET the repo
        final HttpResponse<Buffer> get = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/myrepo")
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
    void headReturns200IfExists(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Step 1: PUT the repo
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/headrepo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(VALID_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode());
        // Step 2: HEAD it
        final HttpResponse<Buffer> head = client
            .head(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/headrepo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, head.statusCode());
        ctx.completeNow();
    }

    @Test
    void headReturns404IfMissing(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.HEAD, "/api/v1/repositories/nonexistent-repo-xyz",
            res -> Assertions.assertEquals(404, res.statusCode())
        );
    }

    @Test
    void deleteRepo(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Step 1: PUT the repo
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/deleteme")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(VALID_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode());
        // Step 2: DELETE it
        final HttpResponse<Buffer> del = client
            .delete(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/deleteme")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, del.statusCode());
        ctx.completeNow();
    }

    @Test
    void getRepoReturns404IfMissing(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/repositories/no-such-repo-abc",
            res -> {
                Assertions.assertEquals(404, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals("NOT_FOUND", body.getString("error"));
                Assertions.assertEquals(404, body.getInteger("status"));
                Assertions.assertNotNull(body.getString("message"));
            }
        );
    }
}
