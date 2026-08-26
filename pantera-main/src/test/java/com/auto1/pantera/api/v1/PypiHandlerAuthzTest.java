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
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.security.Permission;
import java.security.PermissionCollection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Authorization tests for the {@link PypiHandler} yank/unyank endpoints
 * (WS4-pypi.1): a caller without write authority on the target repository
 * must be denied with 403 <strong>before</strong> any storage/sidecar
 * mutation is attempted, and a caller with write authority must succeed.
 *
 * <p>{@link #DENIED_REPO} is intentionally never created via the
 * repositories API — a 403 for a repository that does not even exist
 * proves the authorization check runs strictly before the storage
 * resolution / sidecar write that {@code applyYank}/{@code applyUnyank}
 * would otherwise perform.
 */
public final class PypiHandlerAuthzTest extends AsyncApiTestBase {

    /**
     * The only repository the test policy grants pypi write on.
     */
    private static final String ALLOWED_REPO = "pypi-authz-allowed";

    /**
     * Never created — a 403 here proves the deny fires before any
     * storage/{@code CrudRepoSettings} lookup, i.e. no mutation is attempted.
     */
    private static final String DENIED_REPO = "pypi-authz-denied-nonexistent";

    /**
     * Minimal valid pypi-local repository body (fs storage), mirroring
     * {@code RepositoryHandlerTest.VALID_BODY}.
     */
    private static final JsonObject REPO_BODY = new JsonObject()
        .put(
            "repo",
            new JsonObject()
                .put("type", "pypi-local")
                .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
        );

    @Override
    protected Policy<?> testPolicy() {
        return new RepoScopedPolicy(ALLOWED_REPO);
    }

    @Test
    void yankDeniedWithoutWritePermission(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final HttpResponse<Buffer> res = this.post(
            vertx, String.format("/api/v1/pypi/%s/pkg/1.0/yank", DENIED_REPO)
        );
        MatcherAssert.assertThat(
            "Denies yank without write permission on the target repo",
            res.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }

    @Test
    void unyankDeniedWithoutWritePermission(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final HttpResponse<Buffer> res = this.post(
            vertx, String.format("/api/v1/pypi/%s/pkg/1.0/unyank", DENIED_REPO)
        );
        MatcherAssert.assertThat(
            "Denies unyank without write permission on the target repo",
            res.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }

    @Test
    void yankAllowedWithWritePermission(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.createRepo(vertx, ALLOWED_REPO);
        final HttpResponse<Buffer> res = this.post(
            vertx, String.format("/api/v1/pypi/%s/pkg/1.0/yank", ALLOWED_REPO)
        );
        MatcherAssert.assertThat(
            "Allows yank with write permission on the target repo",
            res.statusCode(), new IsEqual<>(204)
        );
        ctx.completeNow();
    }

    @Test
    void unyankAllowedWithWritePermission(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.createRepo(vertx, ALLOWED_REPO);
        final HttpResponse<Buffer> res = this.post(
            vertx, String.format("/api/v1/pypi/%s/pkg/1.0/unyank", ALLOWED_REPO)
        );
        MatcherAssert.assertThat(
            "Allows unyank with write permission on the target repo",
            res.statusCode(), new IsEqual<>(204)
        );
        ctx.completeNow();
    }

    @Test
    void anonymousYankIsUnauthorized(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final HttpResponse<Buffer> res = WebClient.create(vertx)
            .post(
                this.port(), AsyncApiTestBase.HOST,
                String.format("/api/v1/pypi/%s/pkg/1.0/yank", ALLOWED_REPO)
            )
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "Anonymous request is unauthorized, not merely forbidden",
            res.statusCode(), new IsEqual<>(401)
        );
        ctx.completeNow();
    }

    /**
     * Create a pypi-local repository fixture via the (unrestricted, per
     * {@link RepoScopedPolicy}) repositories API.
     * @param vertx Vertx instance
     * @param name Repository name
     * @throws Exception On error
     */
    private void createRepo(final Vertx vertx, final String name) throws Exception {
        final HttpResponse<Buffer> put = WebClient.create(vertx)
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/" + name)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(REPO_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "Repository fixture is created", put.statusCode(), new IsEqual<>(200)
        );
    }

    /**
     * POST with the test bearer token.
     * @param vertx Vertx instance
     * @param path Request path
     * @return Response
     * @throws Exception On error
     */
    private HttpResponse<Buffer> post(final Vertx vertx, final String path) throws Exception {
        return WebClient.create(vertx)
            .post(this.port(), AsyncApiTestBase.HOST, path)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
    }

    /**
     * Test policy: implies write on exactly one repository (case-insensitive
     * name match, mirroring {@link AdapterBasicPermission}) and implies
     * {@link ApiRepositoryPermission} unconditionally so the repository
     * fixtures used by these tests can be created via the REST API.
     */
    private static final class RepoScopedPolicy implements Policy<PermissionCollection> {

        /**
         * Repository name this policy grants pypi write on.
         */
        private final String allowedRepo;

        /**
         * Ctor.
         * @param allowedRepo Repository name this policy grants pypi write on
         */
        RepoScopedPolicy(final String allowedRepo) {
            this.allowedRepo = allowedRepo;
        }

        @Override
        public PermissionCollection getPermissions(final AuthUser user) {
            return new ScopedPermissions(this.allowedRepo);
        }
    }

    /**
     * Permission collection backing {@link RepoScopedPolicy}.
     */
    private static final class ScopedPermissions extends PermissionCollection {

        /**
         * Repository name this collection grants pypi write on.
         */
        private final String allowedRepo;

        /**
         * Ctor.
         * @param allowedRepo Repository name this collection grants pypi write on
         */
        ScopedPermissions(final String allowedRepo) {
            this.allowedRepo = allowedRepo;
        }

        @Override
        public void add(final Permission permission) {
            throw new UnsupportedOperationException("Fixed test permission set");
        }

        @Override
        public boolean implies(final Permission permission) {
            final boolean res;
            if (permission instanceof ApiRepositoryPermission) {
                res = true;
            } else if (permission instanceof AdapterBasicPermission) {
                res = new AdapterBasicPermission(this.allowedRepo, "*").implies(permission);
            } else {
                res = false;
            }
            return res;
        }

        @Override
        public Enumeration<Permission> elements() {
            return Collections.emptyEnumeration();
        }
    }
}
