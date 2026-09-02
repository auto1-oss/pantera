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
package com.auto1.pantera.api;

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import org.apache.http.HttpStatus;

/**
 * Repository-scoped authorization handler for the management API.
 *
 * <p>{@link AuthzHandler} enforces exactly one global, repository-agnostic
 * permission (e.g. {@code api_repository_permissions:read}). Every
 * repo-name-bearing route ({@code /api/v1/repositories/:name/...}) ALSO needs
 * the per-repository grant the data plane enforces —
 * {@link AdapterBasicPermission}{@code (name, read|write|delete)} — otherwise a
 * principal holding the coarse global bit can read or mutate artifacts,
 * lifecycle state, or backends of repositories it has no grant on (the
 * 2.2.9 {@code artifact-repo-authz} / {@code pypi-yank-authz} /
 * {@code storage-alias-authz} BOLA family). This handler resolves the
 * repository from the named path parameter and requires that grant, in
 * addition to whatever global gate precedes it.</p>
 *
 * <p>Fails closed: no authenticated user → 401; missing path parameter →
 * 400; missing grant → 403 (matching {@link AuthzHandler}'s denial shape).</p>
 *
 * @since 2.2.9
 */
public final class RepoAuthzHandler implements Handler<RoutingContext> {

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Name of the path parameter carrying the repository name
     * (e.g. {@code "name"} or {@code "repo"}).
     */
    private final String param;

    /**
     * Repository action the caller must hold on the resolved repository.
     */
    private final Action action;

    /**
     * Ctor.
     * @param policy Pantera security policy
     * @param param Path parameter naming the repository
     * @param action Required repository action (read / write / delete)
     */
    public RepoAuthzHandler(final Policy<?> policy, final String param, final Action action) {
        this.policy = policy;
        this.param = param;
        this.action = action;
    }

    @Override
    public void handle(final RoutingContext context) {
        final User usr = context.user();
        if (usr == null) {
            RepoAuthzHandler.deny(context, HttpStatus.SC_UNAUTHORIZED, "Authentication required");
            return;
        }
        final String repo = context.pathParam(this.param);
        if (repo == null || repo.isEmpty()) {
            RepoAuthzHandler.deny(
                context, HttpStatus.SC_BAD_REQUEST, "Repository name is required"
            );
            return;
        }
        final boolean allowed = this.policy.getPermissions(
            new AuthUser(
                usr.principal().getString(AuthTokenRest.SUB),
                usr.principal().getString(AuthTokenRest.CONTEXT)
            )
        ).implies(new AdapterBasicPermission(repo, this.action));
        if (allowed) {
            context.next();
        } else {
            RepoAuthzHandler.deny(
                context, HttpStatus.SC_FORBIDDEN,
                "Access denied: insufficient permissions on repository"
            );
        }
    }

    /**
     * Finish the request with a JSON denial.
     * @param context Routing context
     * @param status HTTP status
     * @param message Denial message
     */
    private static void deny(final RoutingContext context, final int status, final String message) {
        context.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("code", status).put("message", message).encode());
    }
}
