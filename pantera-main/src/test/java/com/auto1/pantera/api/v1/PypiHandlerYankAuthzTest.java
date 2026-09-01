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
 * Exploit-regression test for {@code pypi-yank-authz}: before 2.2.9
 * {@link PypiHandler} was constructed without the security policy and
 * registered {@code POST /api/v1/pypi/:repo/:package/:version/yank} and
 * {@code .../unyank} with no authorization handler at all, so the
 * authentication-only {@code /api/v1/*} JWT filter was the sole gate and ANY
 * authenticated user could yank or unyank ANY release in ANY PyPI repository
 * — a lifecycle write that changes {@code pip} resolution (PEP 592) for
 * every consumer.
 *
 * <p>The policy grants global repository CRUD (so the fixture can create the
 * repository) and NO per-repository grant — a yank must be refused.</p>
 *
 * @since 2.2.9
 */
public final class PypiHandlerYankAuthzTest extends AsyncApiTestBase {

    private static final String REPO = "pypi-locked";

    private static final JsonObject PYPI_REPO = new JsonObject()
        .put(
            "repo",
            new JsonObject()
                .put("type", "pypi")
                .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
        );

    @Override
    protected Policy<?> testPolicy() {
        final Permissions perms = new Permissions();
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.CREATE));
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.UPDATE));
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ));
        // No AdapterBasicPermission(REPO, write) — an authenticated caller
        // without lifecycle authority on the repository must not yank.
        final PermissionCollection frozen = perms;
        return user -> frozen;
    }

    @Test
    void authenticatedUserWithoutRepoWriteCannotYank(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final WebClient client = WebClient.create(vertx);
        this.createRepo(client);
        final HttpResponse<Buffer> yank = client
            .post(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/pypi/" + REPO + "/requests/2.31.0/yank")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(new JsonObject().put("reason", "test"))
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "yank must require a repo-scoped write grant, not merely authentication",
            yank.statusCode(), new IsEqual<>(403)
        );
        final HttpResponse<Buffer> unyank = client
            .post(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/pypi/" + REPO + "/requests/2.31.0/unyank")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "unyank must require a repo-scoped write grant, not merely authentication",
            unyank.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }

    private void createRepo(final WebClient client) throws Exception {
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/" + REPO)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(PYPI_REPO)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the fixture must be able to create the pypi repository",
            put.statusCode(), new IsEqual<>(200)
        );
    }
}
