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
    void groupMembersAreListedInOrder(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        // B96: the endpoint read "remotes" (a proxy's upstreams), so every
        // group answered an empty member list.
        final WebClient client = WebClient.create(vertx);
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/members-grp")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(new JsonObject().put(
                "repo",
                new JsonObject()
                    .put("type", "maven-group")
                    .put("members", new JsonArray().add("maven-local").add("maven-central"))
            ))
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode(), "group must be created");
        final HttpResponse<Buffer> get = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/members-grp/members")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(
            new JsonArray().add("maven-local").add("maven-central"),
            get.bodyAsJsonObject().getJsonArray("members"),
            "members must be the group's member repositories in declared order"
        );
        ctx.completeNow();
    }

    @Test
    void unsupportedRepositoryTypeIsRefused(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        // B57: type "binary" was stored with 200 and then every request to
        // the repository answered 500 "Unsupported repository type".
        this.request(
            vertx, ctx,
            HttpMethod.PUT, "/api/v1/repositories/bin-repo",
            new JsonObject().put(
                "repo",
                new JsonObject()
                    .put("type", "binary")
                    .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
            ),
            res -> {
                Assertions.assertEquals(400, res.statusCode(), "unknown type must be refused");
                Assertions.assertTrue(
                    res.bodyAsJsonObject().getString("message").contains("binary"),
                    "the message must name the refused type"
                );
            }
        );
    }

    @Test
    void vertxFileStorageOutsideApprovedRootsIsRefused(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        // B12: only type "fs" was checked, so "vertx-file" mounted any path.
        final JsonObject body = new JsonObject().put(
            "repo",
            new JsonObject()
                .put("type", "file")
                .put("storage", new JsonObject().put("type", "vertx-file").put("path", "/etc"))
        );
        final HttpResponse<Buffer> put = WebClient.create(vertx)
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/vx-root")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(body)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(
            400, put.statusCode(),
            "a vertx-file storage root outside the approved base must be refused"
        );
        ctx.completeNow();
    }

    @Test
    void existingRepoOutsideApprovedRootsStaysEditable(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        // A repository saved before the 2.2.9 roots existed: the UI re-sends
        // its unchanged storage block on every save, which must not be
        // refused; moving it to another unapproved path still is.
        final String legacy = "/opt/pantera-legacy";
        new com.auto1.pantera.db.dao.RepositoryDao(AsyncApiTestBase.sharedDs()).save(
            new com.auto1.pantera.api.RepositoryName.Simple("legacy-root"),
            javax.json.Json.createObjectBuilder().add(
                "repo", javax.json.Json.createObjectBuilder()
                    .add("type", "file")
                    .add("storage", javax.json.Json.createObjectBuilder()
                        .add("type", "fs").add("path", legacy))
            ).build()
        );
        final JsonObject edited = RepositoryHandlerTest.fileRepo(legacy);
        edited.getJsonObject("repo").put("anonymous_read", true);
        final WebClient client = WebClient.create(vertx);
        final HttpResponse<Buffer> keep = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/legacy-root")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(edited)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        final HttpResponse<Buffer> move = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/legacy-root")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(RepositoryHandlerTest.fileRepo("/opt/elsewhere"))
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(
            200, keep.statusCode(), "an update keeping the saved fs path must be accepted"
        );
        Assertions.assertEquals(
            400, move.statusCode(), "moving to an unapproved fs path must still be refused"
        );
        ctx.completeNow();
    }

    private static JsonObject fileRepo(final String path) {
        return new JsonObject().put(
            "repo",
            new JsonObject()
                .put("type", "file")
                .put("storage", new JsonObject().put("type", "fs").put("path", path))
        );
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
    void deleteAuditCarriesTheClientIp(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        // R19: the delete is audited from the removal's completion stage on
        // a worker thread; the client IP was read from the (empty) MDC there.
        final java.util.List<com.auto1.pantera.audit.AuditEvent> events =
            new java.util.concurrent.CopyOnWriteArrayList<>();
        final com.auto1.pantera.audit.AuditServiceRegistry reg =
            com.auto1.pantera.audit.AuditServiceRegistry.instance();
        reg.setSharedService(event -> {
            events.add(event);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        try {
            final WebClient client = WebClient.create(vertx);
            final HttpResponse<Buffer> put = client
                .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/audit-ip")
                .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
                .sendJsonObject(VALID_BODY)
                .toCompletionStage().toCompletableFuture()
                .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
            Assertions.assertEquals(200, put.statusCode(), "repository must be created");
            final HttpResponse<Buffer> del = client
                .delete(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/audit-ip")
                .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
                .send()
                .toCompletionStage().toCompletableFuture()
                .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
            Assertions.assertEquals(200, del.statusCode(), "delete must succeed");
            org.awaitility.Awaitility.await()
                .atMost(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS)
                .until(() -> events.stream().anyMatch(
                    evt -> "REPO_DELETE".equals(evt.action())
                        && "audit-ip".equals(evt.target())
                ));
            final com.auto1.pantera.audit.AuditEvent deleted = events.stream()
                .filter(
                    evt -> "REPO_DELETE".equals(evt.action())
                        && "audit-ip".equals(evt.target())
                )
                .findFirst().orElseThrow();
            Assertions.assertNotNull(
                deleted.ipAddress(), "the delete audit must carry the client IP"
            );
        } finally {
            reg.clear();
        }
        ctx.completeNow();
    }

    @Test
    void deleteRepoRemovesItsDataOnly(final Vertx vertx, final VertxTestContext ctx,
        @org.junit.jupiter.api.io.TempDir final java.nio.file.Path root) throws Exception {
        // B06: a repository created through the API has no YAML file; its
        // data used to survive DELETE and reappear when the name was reused.
        final java.nio.file.Path mine = root.resolve("del-data").resolve("secret.txt");
        final java.nio.file.Path sibling = root.resolve("del-data-2").resolve("keep.txt");
        java.nio.file.Files.createDirectories(mine.getParent());
        java.nio.file.Files.createDirectories(sibling.getParent());
        java.nio.file.Files.writeString(mine, "SECRET");
        java.nio.file.Files.writeString(sibling, "KEEP");
        final WebClient client = WebClient.create(vertx);
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/del-data")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(RepositoryHandlerTest.fileRepo(root.toString()))
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode(), "repository must be created");
        final HttpResponse<Buffer> del = client
            .delete(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/del-data")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, del.statusCode(), "delete must succeed");
        Assertions.assertFalse(
            java.nio.file.Files.exists(mine),
            "the repository's data must be gone once DELETE has answered"
        );
        Assertions.assertTrue(
            java.nio.file.Files.exists(sibling),
            "another repository sharing the storage root must be untouched"
        );
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
