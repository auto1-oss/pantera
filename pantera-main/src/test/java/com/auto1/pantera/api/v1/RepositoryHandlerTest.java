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
    void hostRootCannotBecomeARepository(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        // SECURITY (2.2.9): a repo manager submitting {type: fs, path: "/"}
        // used to mount the host filesystem as a repository.
        final JsonObject hostRoot = new JsonObject().put(
            "repo",
            new JsonObject()
                .put("type", "file")
                .put("storage", new JsonObject().put("type", "fs").put("path", "/"))
        );
        final HttpResponse<Buffer> put = WebClient.create(vertx)
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/host-root")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(hostRoot)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(
            400, put.statusCode(),
            "an inline fs storage root outside the approved base must be refused"
        );
        ctx.completeNow();
    }

    @Test
    void remotePointingAtCloudMetadataIsRefused(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        // SECURITY (2.2.9): remotes[].url was never validated, so a repo
        // manager could point a proxy at the cloud metadata service and have
        // Pantera fetch it server-side on the next read (SSRF).
        final JsonObject body = new JsonObject().put(
            "repo",
            new JsonObject()
                .put("type", "file-proxy")
                .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
                .put("remotes", new JsonArray().add(
                    new JsonObject().put("url", "http://169.254.169.254/latest/meta-data/")
                ))
        );
        final HttpResponse<Buffer> put = WebClient.create(vertx)
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/ssrf-remote")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(body)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(
            400, put.statusCode(),
            "a remote on the cloud metadata address must be refused at config write"
        );
        ctx.completeNow();
    }

    @Test
    void remoteWithoutHttpSchemeIsRefused(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final JsonObject body = new JsonObject().put(
            "repo",
            new JsonObject()
                .put("type", "file-proxy")
                .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
                .put("remotes", new JsonArray().add(
                    new JsonObject().put("url", "file:///etc/passwd")
                ))
        );
        final HttpResponse<Buffer> put = WebClient.create(vertx)
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/ssrf-scheme")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(body)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(
            400, put.statusCode(),
            "a non-http(s) remote must be refused at config write"
        );
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

    @Test
    void editGroupRepoWithoutStorageReturns200(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final JsonObject groupBody = new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "maven-group")
                    .put("members", new JsonArray().add("maven-hosted-1").add("maven-proxy-1"))
            );
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/my-maven-group",
            groupBody,
            res -> Assertions.assertEquals(200, res.statusCode())
        );
    }

    @Test
    void editGroupRepoWithoutMembersReturns400(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final JsonObject groupBody = new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "npm-group")
            );
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/my-npm-group",
            groupBody,
            res -> {
                Assertions.assertEquals(400, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals("BAD_REQUEST", body.getString("error"));
                Assertions.assertTrue(
                    body.getString("message").contains("members"),
                    "Error message should mention 'members'"
                );
            }
        );
    }

    @Test
    void editGroupRepoWithEmptyMembersReturns400(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final JsonObject groupBody = new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "docker-group")
                    .put("members", new JsonArray())
            );
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/my-docker-group",
            groupBody,
            res -> {
                Assertions.assertEquals(400, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals("BAD_REQUEST", body.getString("error"));
            }
        );
    }

    @Test
    void editNonGroupRepoWithoutStorageReturns400(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final JsonObject noStorageBody = new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "maven-proxy")
            );
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/my-maven-proxy",
            noStorageBody,
            res -> {
                Assertions.assertEquals(400, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals("BAD_REQUEST", body.getString("error"));
                Assertions.assertTrue(
                    body.getString("message").contains("storage"),
                    "Error message should mention 'storage'"
                );
            }
        );
    }

    @Test
    void putWithNonBooleanAnonymousReadReturns400(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        // anonymous_read = "true" (a string) — must be a real JSON boolean.
        final JsonObject body = new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "maven-proxy")
                    .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
                    .put("anonymous_read", "true")
            );
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/anon-read-bad",
            body,
            res -> {
                Assertions.assertEquals(400, res.statusCode());
                final JsonObject resp = res.bodyAsJsonObject();
                Assertions.assertEquals("BAD_REQUEST", resp.getString("error"));
                Assertions.assertTrue(
                    resp.getString("message").contains("anonymous_read"),
                    "Error message should mention 'anonymous_read'"
                );
            }
        );
    }

    @Test
    void putWithNonBooleanAnonymousWriteReturns400(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        // anonymous_write = 1 (a number) — must be a real JSON boolean.
        final JsonObject body = new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "maven-proxy")
                    .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
                    .put("anonymous_write", 1)
            );
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/anon-write-bad",
            body,
            res -> {
                Assertions.assertEquals(400, res.statusCode());
                final JsonObject resp = res.bodyAsJsonObject();
                Assertions.assertEquals("BAD_REQUEST", resp.getString("error"));
                Assertions.assertTrue(
                    resp.getString("message").contains("anonymous_write"),
                    "Error message should mention 'anonymous_write'"
                );
            }
        );
    }

    @Test
    void putWithRelativeUrlReturns400(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        // `url:` is parsed with URI#toURL() when the repository is wired, so a
        // malformed value would fail every later request rather than this write.
        final JsonObject body = new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "maven")
                    .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
                    .put("url", "packages.example.com/maven")
            );
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/bad-url-relative",
            body,
            res -> {
                Assertions.assertEquals(400, res.statusCode());
                final JsonObject resp = res.bodyAsJsonObject();
                Assertions.assertEquals("BAD_REQUEST", resp.getString("error"));
                Assertions.assertTrue(
                    resp.getString("message").contains("url"),
                    "Error message should mention 'url'"
                );
            }
        );
    }

    @Test
    void putWithNonStringUrlReturns400(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final JsonObject body = new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "maven")
                    .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
                    .put("url", 8081)
            );
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/bad-url-number",
            body,
            res -> Assertions.assertEquals(400, res.statusCode())
        );
    }

    @Test
    void putWithAbsoluteUrlIsAccepted(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final JsonObject body = new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "maven")
                    .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
                    .put("url", "https://packages.example.com/maven")
            );
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/good-url",
            body,
            res -> Assertions.assertEquals(200, res.statusCode())
        );
    }

    @Test
    void putWithoutUrlIsAccepted(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        // A hosted npm repository with no url: is valid since 2.2.6 — the
        // client-facing base is resolved per request instead.
        final JsonObject body = new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "npm")
                    .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
            );
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/npm-no-url",
            body,
            res -> Assertions.assertEquals(200, res.statusCode())
        );
    }

}
