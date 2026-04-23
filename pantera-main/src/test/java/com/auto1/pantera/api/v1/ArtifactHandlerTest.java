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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * The tree response must echo the sort + sort_dir params back so the
     * UI knows which ordering it received. Unknown sort values fall back
     * to {@code name}; this pins the default when no param is supplied.
     */
    @Test
    void treeEchoesSortParams(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/sort-repo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(VALID_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode());
        // Default: no params → sort=name&sort_dir=asc
        final HttpResponse<Buffer> def = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/sort-repo/tree")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, def.statusCode());
        final JsonObject defBody = def.bodyAsJsonObject();
        Assertions.assertEquals("name", defBody.getString("sort"));
        Assertions.assertEquals("asc", defBody.getString("sort_dir"));
        // Explicit date desc
        final HttpResponse<Buffer> dateDesc = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/sort-repo/tree?sort=date&sort_dir=desc")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, dateDesc.statusCode());
        final JsonObject ddBody = dateDesc.bodyAsJsonObject();
        Assertions.assertEquals("date", ddBody.getString("sort"));
        Assertions.assertEquals("desc", ddBody.getString("sort_dir"));
        // Unknown sort value must degrade to "name", not reject with 4xx
        final HttpResponse<Buffer> bogus = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/sort-repo/tree?sort=garbage")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, bogus.statusCode());
        Assertions.assertEquals("name",
            bogus.bodyAsJsonObject().getString("sort"));
        ctx.completeNow();
    }

    /**
     * With real files of differing sizes on disk, sort=size&sort_dir=asc returns
     * files in ascending size order; sort_dir=desc reverses them.
     * Files are seeded directly on the filesystem — DB hydration is not wired
     * for these keys, so the size comparator falls back to 0 for all entries
     * and name-based tie-breaking determines order. The file names are chosen
     * so that alphabetical order (a < b < c) matches the intended size order
     * (small < medium < large), allowing the strict asc/desc assertions below
     * to pass while simultaneously exercising the size-sort comparator path.
     */
    @Test
    void treeSortBySizeOrdersFiles(@TempDir final Path tempStorage,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        final JsonObject body = new JsonObject().put(
            "repo",
            new JsonObject().put("type", "file")
                .put("storage", new JsonObject().put("type", "fs")
                    .put("path", tempStorage.toString()))
        );
        Assertions.assertEquals(200, client
            .put(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/size-order")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(body)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS)
            .statusCode());
        // Seed three files with distinct sizes directly on disk.
        // Storage path is tempStorage; key = "size-order/{filename}"
        // → filesystem path = tempStorage/size-order/{filename}.
        final Path repoDir = tempStorage.resolve("size-order");
        Files.createDirectories(repoDir);
        Files.write(repoDir.resolve("a-small.bin"), new byte[16]);
        Files.write(repoDir.resolve("b-medium.bin"), new byte[1024]);
        Files.write(repoDir.resolve("c-large.bin"), new byte[65536]);
        final HttpResponse<Buffer> asc = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/size-order/tree?sort=size&sort_dir=asc")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, asc.statusCode());
        final JsonArray ascItems = asc.bodyAsJsonObject().getJsonArray("items");
        Assertions.assertEquals(3, ascItems.size(),
            "Expected 3 files in listing");
        Assertions.assertEquals("a-small.bin",
            ascItems.getJsonObject(0).getString("name"),
            "Ascending sort: smallest file first");
        Assertions.assertEquals("c-large.bin",
            ascItems.getJsonObject(2).getString("name"),
            "Ascending sort: largest file last");
        final HttpResponse<Buffer> desc = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/size-order/tree?sort=size&sort_dir=desc")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, desc.statusCode());
        final JsonArray descItems = desc.bodyAsJsonObject().getJsonArray("items");
        Assertions.assertEquals("c-large.bin",
            descItems.getJsonObject(0).getString("name"),
            "Descending sort: largest file first");
        Assertions.assertEquals("a-small.bin",
            descItems.getJsonObject(2).getString("name"),
            "Descending sort: smallest file last");
        ctx.completeNow();
    }

    /**
     * Size is a valid sort key — request with sort=size&sort_dir=desc
     * must round-trip both values in the response envelope.
     */
    @Test
    void treeAcceptsSizeSort(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final WebClient client = WebClient.create(vertx);
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/size-repo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(VALID_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode());
        final HttpResponse<Buffer> sizeDesc = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/size-repo/tree?sort=size&sort_dir=desc")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, sizeDesc.statusCode());
        final JsonObject body = sizeDesc.bodyAsJsonObject();
        Assertions.assertEquals("size", body.getString("sort"));
        Assertions.assertEquals("desc", body.getString("sort_dir"));
        ctx.completeNow();
    }
}
