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

import com.auto1.pantera.api.perms.ApiRepositoryPermission;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the artifact-management BOLA
 * ({@code artifact-repo-authz}): before 2.2.9 every repo-name-bearing
 * artifact route ({@code /tree}, {@code /artifact}, {@code /artifact/pull},
 * {@code /artifact/download}, {@code /artifact/download-token}, and the
 * artifact / package DELETE routes) was gated ONLY by the global,
 * repository-agnostic {@code api_repository_permissions} bit and never
 * re-applied the per-repository {@code AdapterBasicPermission(name, read|delete)}
 * that the download/serve path enforces. A principal holding the global
 * read bit but NO read grant on a given repository could browse, resolve
 * and download its artifacts; the same split let a global delete bit
 * delete artifacts in repositories the principal has no delete grant on.
 *
 * <p>The policy here grants exactly that split: global repository CRUD
 * (so the test can create the repository and reach the route gates) and
 * <b>no</b> {@code AdapterBasicPermission} on any repository.</p>
 *
 * @since 2.2.9
 */
public final class ArtifactHandlerRepoScopeTest extends AsyncApiTestBase {

    private static final String REPO = "scope-locked";

    private static final JsonObject VALID_BODY = new JsonObject()
        .put(
            "repo",
            new JsonObject()
                .put("type", "maven-proxy")
                .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
        );

    @Override
    protected Policy<?> testPolicy() {
        final Permissions perms = new Permissions();
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.CREATE));
        // UPDATE lets the fixture re-PUT a repository that a sibling test
        // already created (Vert.x API tests share one server per class).
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.UPDATE));
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ));
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.DELETE));
        // Deliberately NO AdapterBasicPermission(REPO, ...) — the global bits
        // must not be enough to read or delete a repository's artifacts.
        final PermissionCollection frozen = perms;
        return user -> frozen;
    }

    @Test
    void globalReadBitDoesNotGrantArtifactReadOnAnUnauthorizedRepo(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.createRepo(client);
        final HttpResponse<Buffer> tree = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/" + REPO + "/tree")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "global api_repository read must NOT authorize browsing a repo the principal has no read grant on",
            tree.statusCode(), new IsEqual<>(403)
        );
        final HttpResponse<Buffer> detail = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/" + REPO + "/artifact?path=a/b.jar")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "artifact detail must be repo-scoped, not global-read-gated",
            detail.statusCode(), new IsEqual<>(403)
        );
        final HttpResponse<Buffer> download = client
            .get(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/" + REPO + "/artifact/download?path=a/b.jar")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "artifact download must be repo-scoped, not global-read-gated",
            download.statusCode(), new IsEqual<>(403)
        );
        final HttpResponse<Buffer> token = client
            .post(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/" + REPO + "/artifact/download-token")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(new JsonObject().put("path", "a/b.jar"))
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a download token must not be minted for a repo the principal cannot read",
            token.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }

    @Test
    void globalDeleteBitDoesNotGrantArtifactDeleteOnAnUnauthorizedRepo(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.createRepo(client);
        final HttpResponse<Buffer> artifact = client
            .delete(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/" + REPO + "/artifacts?path=a/b.jar")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "global api_repository delete must NOT authorize deleting artifacts in a repo without a delete grant",
            artifact.statusCode(), new IsEqual<>(403)
        );
        final HttpResponse<Buffer> pkg = client
            .delete(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/" + REPO + "/packages?path=a")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "package-folder delete must be repo-scoped, not global-delete-gated",
            pkg.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }

    private void createRepo(final WebClient client) throws Exception {
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/" + REPO)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(VALID_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the test fixture must be able to create the repository (global CREATE is granted)",
            put.statusCode(), new IsEqual<>(200)
        );
    }
}
