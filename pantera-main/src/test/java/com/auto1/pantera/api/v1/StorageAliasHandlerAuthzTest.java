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

import com.auto1.pantera.api.perms.ApiAliasPermission;
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
 * Exploit-regression test for {@code storage-alias-authz}: before 2.2.9
 * {@code PUT /api/v1/repositories/:name/storages/:alias} was composed with
 * the alias READ permission (its global sibling correctly uses CREATE), and
 * performed no repository-scoped authorization — so a principal holding
 * only the read-only alias grant could durably rewrite a repository's
 * storage backend configuration (an upsert that DbRepositories consumes
 * to build the backing Storage).
 *
 * <p>The policy grants global repository CRUD (fixture) and the alias READ
 * bit only — the per-repository alias write must be refused.</p>
 *
 * @since 2.2.9
 */
public final class StorageAliasHandlerAuthzTest extends AsyncApiTestBase {

    private static final String REPO = "alias-locked";

    private static final JsonObject ALIAS_BODY = new JsonObject()
        .put("type", "fs")
        .put("path", "/var/pantera/data");

    @Override
    protected Policy<?> testPolicy() {
        final Permissions perms = new Permissions();
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.CREATE));
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.UPDATE));
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ));
        // The read-only alias grant — must NOT authorize alias writes.
        perms.add(new ApiAliasPermission(ApiAliasPermission.AliasAction.READ));
        final PermissionCollection frozen = perms;
        return user -> frozen;
    }

    @Test
    void readOnlyAliasGrantCannotWriteAPerRepositoryAlias(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final WebClient client = WebClient.create(vertx);
        final HttpResponse<Buffer> repo = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/" + REPO)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(new JsonObject().put("repo", new JsonObject()
                .put("type", "maven-proxy")
                .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))))
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the fixture must be able to create the repository",
            repo.statusCode(), new IsEqual<>(200)
        );
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST,
                "/api/v1/repositories/" + REPO + "/storages/evil")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(ALIAS_BODY)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a read-only alias grant must not authorize writing a repository's storage alias",
            put.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }
}
