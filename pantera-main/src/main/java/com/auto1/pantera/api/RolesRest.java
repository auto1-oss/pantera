/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api;

import com.auto1.pantera.api.perms.ApiRolePermission;
import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.users.CrudRoles;
import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.io.StringReader;
import java.security.PermissionCollection;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.eclipse.jetty.http.HttpStatus;

/**
 * REST API methods to manage Pantera roles.
 * @since 0.27
 */
public final class RolesRest extends BaseRest {

    /**
     * Update role permission.
     */
    private static final ApiRolePermission UPDATE =
        new ApiRolePermission(ApiRolePermission.RoleAction.UPDATE);

    /**
     * Create role permission.
     */
    private static final ApiRolePermission CREATE =
        new ApiRolePermission(ApiRolePermission.RoleAction.CREATE);

    /**
     * Role name path param.
     */
    private static final String ROLE_NAME = "role";

    /**
     * Crud roles object.
     */
    private final CrudRoles roles;

    /**
     * Pantera policy cache.
     */
    private final Cleanable<String> cache;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param roles Crud roles object
     * @param cache Pantera authenticated roles cache
     * @param policy Pantera policy cache
     */
    public RolesRest(final CrudRoles roles, final Cleanable<String> cache, final Policy<?> policy) {
        this.roles = roles;
        this.cache = cache;
        this.policy = policy;
    }

    @Override
    public void init(final RouterBuilder rbr) {
        rbr.operation("listAllRoles")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiRolePermission(ApiRolePermission.RoleAction.READ)
                )
            )
            .handler(this::listAllRoles)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("getRole")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiRolePermission(ApiRolePermission.RoleAction.READ)
                )
            )
            .handler(this::getRole)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("putRole")
            .handler(this::putRole)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("deleteRole")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiRolePermission(ApiRolePermission.RoleAction.DELETE)
                )
            )
            .handler(this::deleteRole)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("enable")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiRolePermission(ApiRolePermission.RoleAction.ENABLE)
                )
            )
            .handler(this::enableRole)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("disable")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiRolePermission(ApiRolePermission.RoleAction.ENABLE)
                )
            )
            .handler(this::disableRole)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
    }

    /**
     * Removes user.
     * @param context Request context
     */
    private void deleteRole(final RoutingContext context) {
        final String uname = context.pathParam(RolesRest.ROLE_NAME);
        try {
            this.roles.remove(uname);
        } catch (final IllegalStateException err) {
            EcsLogger.error("com.auto1.pantera.api")
                .message("Failed to remove role")
                .eventCategory("api")
                .eventAction("role_remove")
                .eventOutcome("failure")
                .field("user.roles", uname)
                .error(err)
                .log();
            sendError(context, HttpStatus.NOT_FOUND_404, "Role not found");
            return;
        }
        this.cache.invalidate(uname);
        context.response().setStatusCode(HttpStatus.OK_200).end();
    }

    /**
     * Removes user.
     * @param context Request context
     */
    private void enableRole(final RoutingContext context) {
        final String uname = context.pathParam(RolesRest.ROLE_NAME);
        try {
            this.roles.enable(uname);
        } catch (final IllegalStateException err) {
            EcsLogger.error("com.auto1.pantera.api")
                .message("Failed to enable role")
                .eventCategory("api")
                .eventAction("role_enable")
                .eventOutcome("failure")
                .field("user.roles", uname)
                .error(err)
                .log();
            sendError(context, HttpStatus.NOT_FOUND_404, "Role not found");
            return;
        }
        this.cache.invalidate(uname);
        context.response().setStatusCode(HttpStatus.OK_200).end();
    }

    /**
     * Removes user.
     * @param context Request context
     */
    private void disableRole(final RoutingContext context) {
        final String uname = context.pathParam(RolesRest.ROLE_NAME);
        try {
            this.roles.disable(uname);
        } catch (final IllegalStateException err) {
            EcsLogger.error("com.auto1.pantera.api")
                .message("Failed to disable role")
                .eventCategory("api")
                .eventAction("role_disable")
                .eventOutcome("failure")
                .field("user.roles", uname)
                .error(err)
                .log();
            sendError(context, HttpStatus.NOT_FOUND_404, "Role not found");
            return;
        }
        this.cache.invalidate(uname);
        context.response().setStatusCode(HttpStatus.OK_200).end();
    }

    /**
     * Create or replace existing user.
     * @param context Request context
     */
    private void putRole(final RoutingContext context) {
        final String uname = context.pathParam(RolesRest.ROLE_NAME);
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                context.user().principal().getString(AuthTokenRest.SUB),
                context.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        final Optional<JsonObject> existing = this.roles.get(uname);
        if (existing.isPresent() && perms.implies(RolesRest.UPDATE)
            || existing.isEmpty() && perms.implies(RolesRest.CREATE)) {
            this.roles.addOrUpdate(
                Json.createReader(new StringReader(context.body().asString())).readObject(),
                uname
            );
            this.cache.invalidate(uname);
            context.response().setStatusCode(HttpStatus.CREATED_201).end();
        } else {
            sendError(context, HttpStatus.FORBIDDEN_403, "Insufficient permissions");
        }
    }

    /**
     * Get single user info.
     * @param context Request context
     */
    private void getRole(final RoutingContext context) {
        final Optional<JsonObject> usr = this.roles.get(
            context.pathParam(RolesRest.ROLE_NAME)
        );
        if (usr.isPresent()) {
            context.response().setStatusCode(HttpStatus.OK_200).end(usr.get().toString());
        } else {
            sendError(context, HttpStatus.NOT_FOUND_404, "Role not found");
        }
    }

    /**
     * List all roles.
     * @param context Request context
     */
    private void listAllRoles(final RoutingContext context) {
        context.response().setStatusCode(HttpStatus.OK_200).end(this.roles.list().toString());
    }

}
