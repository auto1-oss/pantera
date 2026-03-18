/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api;

import com.artipie.api.perms.ApiAliasPermission;
import com.artipie.api.perms.ApiRepositoryPermission;
import com.artipie.api.perms.ApiRolePermission;
import com.artipie.api.perms.ApiSearchPermission;
import com.artipie.api.perms.ApiUserPermission;
import com.artipie.asto.misc.Cleanable;
import com.artipie.http.auth.AuthUser;
import com.artipie.http.auth.Authentication;
import com.artipie.security.policy.Policy;
import com.artipie.settings.ArtipieSecurity;
import com.artipie.settings.cache.ArtipieCaches;
import com.artipie.settings.users.CrudUsers;
import com.artipie.http.log.EcsLogger;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.io.StringReader;
import java.security.PermissionCollection;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.eclipse.jetty.http.HttpStatus;

/**
 * REST API methods to manage Artipie users.
 * @since 0.27
 */
public final class UsersRest extends BaseRest {

    /**
     * User name path param.
     */
    private static final String USER_NAME = "uname";

    /**
     * Update user permission.
     */
    private static final ApiUserPermission UPDATE =
        new ApiUserPermission(ApiUserPermission.UserAction.UPDATE);

    /**
     * Create user permission.
     */
    private static final ApiUserPermission CREATE =
        new ApiUserPermission(ApiUserPermission.UserAction.CREATE);

    /**
     * Crud users object.
     */
    private final CrudUsers users;

    /**
     * Artipie authenticated users cache.
     */
    private final Cleanable<String> ucache;

    /**
     * Artipie authenticated users cache.
     */
    private final Cleanable<String> pcache;

    /**
     * Artipie auth.
     */
    private final Authentication auth;

    /**
     * Artipie security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param users Crud users object
     * @param caches Artipie caches
     * @param security Artipie security
     */
    public UsersRest(final CrudUsers users, final ArtipieCaches caches,
        final ArtipieSecurity security) {
        this.users = users;
        this.ucache = caches.usersCache();
        this.pcache = caches.policyCache();
        this.auth = security.authentication();
        this.policy = security.policy();
    }

    @Override
    public void init(final RouterBuilder rbr) {
        rbr.operation("getCurrentUser")
            .handler(this::getCurrentUser)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("listAllUsers")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiUserPermission(ApiUserPermission.UserAction.READ)
                )
            )
            .handler(this::listAllUsers)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("getUser")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiUserPermission(ApiUserPermission.UserAction.READ)
                )
            )
            .handler(this::getUser)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("putUser")
            .handler(this::putUser)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("deleteUser")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiUserPermission(ApiUserPermission.UserAction.DELETE)
                )
            )
            .handler(this::deleteUser)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("alterPassword")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiUserPermission(ApiUserPermission.UserAction.CHANGE_PASSWORD)
                )
            )
            .handler(this::alterPassword)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("enable")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiUserPermission(ApiUserPermission.UserAction.ENABLE)
                )
            )
            .handler(this::enableUser)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("disable")
            .handler(
                new AuthzHandler(
                    this.policy, new ApiUserPermission(ApiUserPermission.UserAction.ENABLE)
                )
            )
            .handler(this::disableUser)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
    }

    /**
     * Removes user.
     * @param context Request context
     */
    private void deleteUser(final RoutingContext context) {
        final String uname = context.pathParam(UsersRest.USER_NAME);
        try {
            this.users.remove(uname);
        } catch (final IllegalStateException err) {
            EcsLogger.error("com.artipie.api")
                .message("Failed to remove user")
                .eventCategory("api")
                .eventAction("user_remove")
                .eventOutcome("failure")
                .field("user.name", uname)
                .error(err)
                .log();
            sendError(context, HttpStatus.NOT_FOUND_404, "User not found");
            return;
        }
        this.ucache.invalidate(uname);
        this.pcache.invalidate(uname);
        context.response().setStatusCode(HttpStatus.OK_200).end();
    }

    /**
     * Removes user.
     * @param context Request context
     */
    private void enableUser(final RoutingContext context) {
        final String uname = context.pathParam(UsersRest.USER_NAME);
        try {
            this.users.enable(uname);
        } catch (final IllegalStateException err) {
            EcsLogger.error("com.artipie.api")
                .message("Failed to enable user")
                .eventCategory("api")
                .eventAction("user_enable")
                .eventOutcome("failure")
                .field("user.name", uname)
                .error(err)
                .log();
            sendError(context, HttpStatus.NOT_FOUND_404, "User not found");
            return;
        }
        this.ucache.invalidate(uname);
        this.pcache.invalidate(uname);
        context.response().setStatusCode(HttpStatus.OK_200).end();
    }

    /**
     * Removes user.
     * @param context Request context
     */
    private void disableUser(final RoutingContext context) {
        final String uname = context.pathParam(UsersRest.USER_NAME);
        try {
            this.users.disable(uname);
        } catch (final IllegalStateException err) {
            EcsLogger.error("com.artipie.api")
                .message("Failed to disable user")
                .eventCategory("api")
                .eventAction("user_disable")
                .eventOutcome("failure")
                .field("user.name", uname)
                .error(err)
                .log();
            sendError(context, HttpStatus.NOT_FOUND_404, "User not found");
            return;
        }
        this.ucache.invalidate(uname);
        this.pcache.invalidate(uname);
        context.response().setStatusCode(HttpStatus.OK_200).end();
    }

    /**
     * Create or replace existing user taking into account permissions of the
     * logged-in user.
     * @param context Request context
     */
    private void putUser(final RoutingContext context) {
        final String uname = context.pathParam(UsersRest.USER_NAME);
        final Optional<JsonObject> existing = this.users.get(uname);
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                context.user().principal().getString(AuthTokenRest.SUB),
                context.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        if (existing.isPresent() && perms.implies(UsersRest.UPDATE)
            || existing.isEmpty() && perms.implies(UsersRest.CREATE)) {
            this.users.addOrUpdate(
                Json.createReader(new StringReader(context.body().asString())).readObject(), uname
            );
            this.ucache.invalidate(uname);
            this.pcache.invalidate(uname);
            context.response().setStatusCode(HttpStatus.CREATED_201).end();
        } else {
            sendError(context, HttpStatus.FORBIDDEN_403, "Insufficient permissions");
        }
    }

    /**
     * Get single user info.
     * @param context Request context
     */
    private void getUser(final RoutingContext context) {
        final Optional<JsonObject> usr = this.users.get(
            context.pathParam(UsersRest.USER_NAME)
        );
        if (usr.isPresent()) {
            context.response().setStatusCode(HttpStatus.OK_200).end(usr.get().toString());
        } else {
            sendError(context, HttpStatus.NOT_FOUND_404, "User not found");
        }
    }

    /**
     * List all users.
     * @param context Request context
     */
    private void listAllUsers(final RoutingContext context) {
        context.response().setStatusCode(HttpStatus.OK_200).end(this.users.list().toString());
    }

    /**
     * Alter user password.
     * @param context Routing context
     */
    private void alterPassword(final RoutingContext context) {
        final String uname = context.pathParam(UsersRest.USER_NAME);
        final JsonObject body = readJsonObject(context);
        final Optional<AuthUser> usr = this.auth.user(uname, body.getString("old_pass"));
        if (usr.isPresent()) {
            try {
                this.users.alterPassword(uname, body);
                context.response().setStatusCode(HttpStatus.OK_200).end();
                this.ucache.invalidate(uname);
            } catch (final IllegalStateException err) {
                EcsLogger.error("com.artipie.api")
                    .message("Failed to alter user password")
                    .eventCategory("api")
                    .eventAction("user_password_change")
                    .eventOutcome("failure")
                    .field("user.name", uname)
                    .error(err)
                    .log();
                sendError(context, HttpStatus.NOT_FOUND_404, "User not found");
            }
        } else {
            sendError(context, HttpStatus.UNAUTHORIZED_401, "Invalid old password");
        }
    }

    /**
     * Get current authenticated user info and effective permissions.
     * @param context Request context
     */
    private void getCurrentUser(final RoutingContext context) {
        final String sub = context.user().principal().getString(AuthTokenRest.SUB);
        final String ctx = context.user().principal().getString(AuthTokenRest.CONTEXT);
        final AuthUser authUser = new AuthUser(sub, ctx);
        final PermissionCollection perms = this.policy.getPermissions(authUser);
        final io.vertx.core.json.JsonObject permissions = new io.vertx.core.json.JsonObject()
            .put("api_repository_permissions",
                perms.implies(new ApiRepositoryPermission(
                    ApiRepositoryPermission.RepositoryAction.READ)))
            .put("api_user_permissions",
                perms.implies(new ApiUserPermission(ApiUserPermission.UserAction.READ)))
            .put("api_role_permissions",
                perms.implies(new ApiRolePermission(ApiRolePermission.RoleAction.READ)))
            .put("api_alias_permissions",
                perms.implies(new ApiAliasPermission(ApiAliasPermission.AliasAction.READ)))
            .put("api_cache_permissions", false)
            .put("api_search_permissions",
                perms.implies(ApiSearchPermission.READ));
        final io.vertx.core.json.JsonObject result = new io.vertx.core.json.JsonObject()
            .put("name", sub)
            .put("context", ctx != null ? ctx : "artipie")
            .put("permissions", permissions);
        final Optional<JsonObject> userInfo = this.users.get(sub);
        if (userInfo.isPresent()) {
            final JsonObject info = userInfo.get();
            if (info.containsKey("email")) {
                result.put("email", info.getString("email"));
            }
            if (info.containsKey("groups")) {
                result.put("groups",
                    new JsonArray(info.getJsonArray("groups").getValuesAs(
                        javax.json.JsonString.class).stream()
                        .map(javax.json.JsonString::getString)
                        .collect(java.util.stream.Collectors.toList())));
            }
        }
        context.response()
            .setStatusCode(HttpStatus.OK_200)
            .putHeader("Content-Type", "application/json")
            .end(result.encode());
    }

}
