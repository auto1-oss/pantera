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
import com.auto1.pantera.api.perms.ApiRolePermission;
import com.auto1.pantera.api.perms.ApiUserPermission;
import com.auto1.pantera.db.dao.RoleDao;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.io.StringReader;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.concurrent.TimeUnit;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression tests for {@code privesc-role}: before 2.2.9 the
 * generic user and role PUT upsert routes collapsed independently
 * privileged sub-operations — password reset, role assignment, permission
 * authoring — under a single coarse UPDATE/CREATE authorization with no
 * privilege ceiling and no protected built-ins, and forwarded the whole
 * body to the DAO sinks. A delegated user manager could self-assign the
 * {@code admin} role or reset any password without the change-password
 * grant; a delegated role editor could author {@code all_permission} into
 * a role, rewrite the built-in {@code admin} role, or grant permissions
 * they did not themselves hold.
 *
 * <p>The policy models exactly such a delegated manager: user CREATE +
 * UPDATE, role CREATE + UPDATE, repository read — and NOTHING more (no
 * AllPermission, no CHANGE_PASSWORD, no admin role).</p>
 *
 * @since 2.2.9
 */
public final class PrivilegeEscalationTest extends AsyncApiTestBase {

    /**
     * The caller's own username (the subject of {@code TEST_TOKEN}).
     */
    private static final String SELF = "pantera";

    @BeforeAll
    static void seedAdminRole() throws Exception {
        // The built-in admin role, as the bootstrap migrator creates it.
        new RoleDao(AsyncApiTestBase.sharedDs()).addOrUpdate(
            Json.createReader(new StringReader("{\"all_permission\":{}}")).readObject(),
            "admin"
        );
    }

    @Override
    protected Policy<?> testPolicy() {
        final Permissions perms = new Permissions();
        perms.add(new ApiUserPermission(ApiUserPermission.UserAction.CREATE));
        perms.add(new ApiUserPermission(ApiUserPermission.UserAction.UPDATE));
        perms.add(new ApiRolePermission(ApiRolePermission.RoleAction.CREATE));
        perms.add(new ApiRolePermission(ApiRolePermission.RoleAction.UPDATE));
        perms.add(new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ));
        final PermissionCollection frozen = perms;
        return user -> frozen;
    }

    @Test
    void userManagerCannotSelfAssignTheAdminRole(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final HttpResponse<Buffer> res = this.put(vertx, "/api/v1/users/" + SELF,
            new JsonObject().put("roles", new JsonArray().add("admin")));
        MatcherAssert.assertThat(
            "a caller must not be able to assign a role they do not themselves hold (admin)",
            res.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }

    @Test
    void userManagerCannotResetAPasswordWithoutChangePasswordGrant(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final HttpResponse<Buffer> create = this.put(vertx, "/api/v1/users/privesc-bob",
            new JsonObject().put("email", "bob@example.test"));
        MatcherAssert.assertThat("fixture: create bob", create.statusCode(), new IsEqual<>(201));
        final HttpResponse<Buffer> reset = this.put(vertx, "/api/v1/users/privesc-bob",
            new JsonObject().put("password", "Correct-Horse-Battery-9"));
        MatcherAssert.assertThat(
            "resetting another user's password requires the change-password grant, not just UPDATE",
            reset.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }

    @Test
    void roleEditorCannotAuthorAllPermission(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final HttpResponse<Buffer> res = this.put(vertx, "/api/v1/roles/privesc-super",
            new JsonObject().put("permissions", new JsonObject().put("all_permission", new JsonObject())));
        MatcherAssert.assertThat(
            "a role editor without AllPermission must not be able to author all_permission",
            res.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }

    @Test
    void roleEditorCannotRewriteTheBuiltInAdminRole(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final HttpResponse<Buffer> res = this.put(vertx, "/api/v1/roles/admin",
            new JsonObject().put("permissions", new JsonObject().put(
                "api_repository_permissions", new JsonObject().put("read", true))));
        MatcherAssert.assertThat(
            "the built-in admin role must be protected from non-admin edits",
            res.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }

    @Test
    void roleEditorCannotGrantAPermissionTheyDoNotHold(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        // Caller holds api_repository READ only; granting DELETE exceeds them.
        final HttpResponse<Buffer> res = this.put(vertx, "/api/v1/roles/privesc-deleter",
            new JsonObject().put("permissions", new JsonObject().put(
                "api_repository_permissions", new JsonObject().put("delete", true))));
        MatcherAssert.assertThat(
            "a role editor must not grant a permission above their own effective set (privilege ceiling)",
            res.statusCode(), new IsEqual<>(403)
        );
        ctx.completeNow();
    }

    private HttpResponse<Buffer> put(final Vertx vertx, final String path, final JsonObject body)
        throws Exception {
        return WebClient.create(vertx)
            .put(this.port(), AsyncApiTestBase.HOST, path)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(body).toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
    }
}
