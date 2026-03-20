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
 * Integration tests for {@link ArtifactHandler}.
 */
public final class ArtifactHandlerTest extends AsyncApiTestBase {

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
    void treeEndpointReturns200(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Step 1: create the repo so it exists
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/myrepo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(VALID_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode());
        // Step 2: call the tree endpoint
        final HttpResponse<Buffer> res = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/myrepo/tree")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, res.statusCode());
        final JsonObject body = res.bodyAsJsonObject();
        Assertions.assertNotNull(body.getJsonArray("items"), "Response must have 'items' array");
        ctx.completeNow();
    }

    @Test
    void artifactDetailRequiresPath(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/repositories/myrepo/artifact",
            res -> Assertions.assertEquals(400, res.statusCode())
        );
    }
}
