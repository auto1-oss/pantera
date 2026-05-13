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
import java.sql.Connection;
import java.sql.PreparedStatement;
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
     * With real sizes hydrated from the artifacts DB (rows seeded directly
     * before the request), sort=size&sort_dir=asc returns files in ascending
     * size order; desc reverses. Names are intentionally anti-alphabetical
     * vs size, so a broken comparator that fell back to name order would
     * flip the observed ordering and the assertion would fail. Without the
     * DB seed, all hydrated sizes would be 0 and name ordering would
     * determine the result — so the DB-seed step is load-bearing for this
     * test's protective value.
     */
    @Test
    void treeSortBySizeOrdersFiles(@TempDir final Path tempStorage,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        // Clean any leftover rows from a previous run of this test so the
        // INSERTs below cannot hit the UNIQUE(repo_name, name, version)
        // constraint under Surefire retries or parallel execution.
        try (Connection conn = sharedDs().getConnection();
             PreparedStatement del = conn.prepareStatement(
                 "DELETE FROM artifacts WHERE repo_name = ?")) {
            del.setString(1, "size-order");
            del.executeUpdate();
        }
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
        // Seed three files on disk with names intentionally anti-alphabetical
        // vs size so the test can distinguish a correct size comparator from
        // a broken one that falls back to name order.
        final Path repoDir = tempStorage.resolve("size-order");
        Files.createDirectories(repoDir);
        Files.write(repoDir.resolve("z-small.bin"), new byte[16]);
        Files.write(repoDir.resolve("m-medium.bin"), new byte[1024]);
        Files.write(repoDir.resolve("a-large.bin"), new byte[65536]);
        // Seed matching DB rows so hydration populates real sizes.
        final long now = System.currentTimeMillis();
        try (Connection conn = sharedDs().getConnection();
             PreparedStatement ins = conn.prepareStatement(
                 "INSERT INTO artifacts "
                     + "(repo_type, repo_name, name, version, size, "
                     + "created_date, release_date, owner, path_prefix) "
                     + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (final String[] row : new String[][] {
                {"z-small.bin", "16"},
                {"m-medium.bin", "1024"},
                {"a-large.bin", "65536"}
            }) {
                ins.setString(1, "file");
                ins.setString(2, "size-order");
                ins.setString(3, row[0]);
                ins.setString(4, "1");
                ins.setLong(5, Long.parseLong(row[1]));
                ins.setLong(6, now);
                ins.setLong(7, now);
                ins.setString(8, "");
                ins.setNull(9, java.sql.Types.VARCHAR);
                ins.executeUpdate();
            }
        }
        final HttpResponse<Buffer> asc = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/size-order/tree?sort=size&sort_dir=asc")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, asc.statusCode());
        final JsonArray ascItems = asc.bodyAsJsonObject().getJsonArray("items");
        Assertions.assertEquals(3, ascItems.size());
        Assertions.assertEquals("z-small.bin",
            ascItems.getJsonObject(0).getString("name"));
        Assertions.assertEquals("m-medium.bin",
            ascItems.getJsonObject(1).getString("name"));
        Assertions.assertEquals("a-large.bin",
            ascItems.getJsonObject(2).getString("name"));
        final HttpResponse<Buffer> desc = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/size-order/tree?sort=size&sort_dir=desc")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, desc.statusCode());
        final JsonArray descItems = desc.bodyAsJsonObject().getJsonArray("items");
        Assertions.assertEquals("a-large.bin",
            descItems.getJsonObject(0).getString("name"));
        Assertions.assertEquals("m-medium.bin",
            descItems.getJsonObject(1).getString("name"));
        Assertions.assertEquals("z-small.bin",
            descItems.getJsonObject(2).getString("name"));
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

    /**
     * For repo types where file paths don't match the artifacts DB's
     * name column (Go local, npm, PyPI, Docker, Helm, Debian), the
     * tree endpoint falls back to reading size/modified from storage
     * metadata and deriving artifact_kind from the filename.
     */
    @Test
    void treeFallsBackToStorageMetadataWhenDbHasNoRows(
        @TempDir final Path tempStorage,
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final WebClient client = WebClient.create(vertx);
        try (Connection conn = sharedDs().getConnection();
             PreparedStatement del = conn.prepareStatement(
                 "DELETE FROM artifacts WHERE repo_name = ?")) {
            del.setString(1, "fallback-repo");
            del.executeUpdate();
        }
        final JsonObject body = new JsonObject().put(
            "repo",
            new JsonObject().put("type", "file")
                .put("storage", new JsonObject().put("type", "fs")
                    .put("path", tempStorage.toString()))
        );
        Assertions.assertEquals(200, client
            .put(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/fallback-repo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(body)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS)
            .statusCode());
        final Path repoDir = tempStorage.resolve("fallback-repo");
        Files.createDirectories(repoDir);
        Files.write(repoDir.resolve("v1.0.1.info"), new byte[128]);
        Files.write(repoDir.resolve("v1.0.1.mod"), new byte[256]);
        Files.write(repoDir.resolve("v1.0.1.zip"), new byte[4096]);
        final HttpResponse<Buffer> res = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/fallback-repo/tree")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, res.statusCode());
        final JsonArray items = res.bodyAsJsonObject().getJsonArray("items");
        Assertions.assertEquals(3, items.size());
        for (int i = 0; i < items.size(); i++) {
            final JsonObject item = items.getJsonObject(i);
            Assertions.assertTrue(item.getLong("size", -1L) > 0,
                "Size should be hydrated from storage: " + item.encode());
            Assertions.assertNotNull(item.getString("modified"),
                "Modified should be hydrated from storage: " + item.encode());
            Assertions.assertNotNull(item.getString("artifact_kind"),
                "Kind should be derived from filename: " + item.encode());
        }
        // Items are sorted by name (default), so: v1.0.1.info, v1.0.1.mod, v1.0.1.zip
        Assertions.assertEquals("METADATA",
            items.getJsonObject(0).getString("artifact_kind")); // v1.0.1.info
        Assertions.assertEquals("METADATA",
            items.getJsonObject(1).getString("artifact_kind")); // v1.0.1.mod
        Assertions.assertEquals("ARTIFACT",
            items.getJsonObject(2).getString("artifact_kind")); // v1.0.1.zip
        ctx.completeNow();
    }
}
