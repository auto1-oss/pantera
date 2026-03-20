/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api;

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import java.security.Permission;
import org.apache.http.HttpStatus;

/**
 * Handler to check that user has required permission. If permission is present,
 * vertx passes the request to the next handler (as {@link RoutingContext#next()} method is called),
 * otherwise {@link HttpStatus#SC_FORBIDDEN} is returned and request processing is finished.
 * @since 0.30
 */
public final class AuthzHandler implements Handler<RoutingContext> {

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Permission required for operation.
     */
    private final Permission perm;

    /**
     * Ctor.
     * @param policy Pantera security policy
     * @param perm Permission required for operation
     */
    public AuthzHandler(final Policy<?> policy, final Permission perm) {
        this.policy = policy;
        this.perm = perm;
    }

    @Override
    public void handle(final RoutingContext context) {
        final User usr = context.user();
        if (this.policy.getPermissions(
            new AuthUser(
                usr.principal().getString(AuthTokenRest.SUB),
                usr.principal().getString(AuthTokenRest.CONTEXT)
            )
        ).implies(this.perm)) {
            context.next();
        } else {
            context.response()
                .setStatusCode(HttpStatus.SC_FORBIDDEN)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("code", HttpStatus.SC_FORBIDDEN)
                    .put("message", "Access denied: insufficient permissions")
                    .encode());
        }
    }
}
