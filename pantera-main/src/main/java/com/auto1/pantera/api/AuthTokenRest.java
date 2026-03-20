/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api;

import com.auto1.pantera.auth.OktaAuthContext;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.Tokens;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.util.Optional;
import org.eclipse.jetty.http.HttpStatus;

/**
 * Generate JWT token endpoint.
 * @since 0.2
 */
public final class AuthTokenRest extends BaseRest {

    /**
     * Token field with username.
     */
    public static final String SUB = "sub";

    /**
     * Token field with user context.
     */
    public static final String CONTEXT = "context";

    /**
     * Tokens provider.
     */
    private final Tokens tokens;

    /**
     * Pantera authentication.
     */
    private final Authentication auth;

    /**
     * Ctor.
     *
     * @param provider Vertx JWT auth
     * @param auth Pantera authentication
     */
    public AuthTokenRest(final Tokens provider, final Authentication auth) {
        this.tokens = provider;
        this.auth = auth;
    }

    @Override
    public void init(final RouterBuilder rbr) {
        rbr.operation("getJwtToken")
            .handler(this::getJwtToken)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
    }

    /**
     * Validate user and get jwt token.
     * @param routing Request context
     */
    private void getJwtToken(final RoutingContext routing) {
        final JsonObject body = routing.body().asJsonObject();
        final String mfa = body.getString("mfa_code");
        final String name = body.getString("name");
        final String pass = body.getString("pass");
        final boolean permanent = body.getBoolean("permanent", false);
        // Offload to worker thread to avoid blocking the event loop (MFA push polling)
        routing.vertx().<Optional<AuthUser>>executeBlocking(
            () -> {
                OktaAuthContext.setMfaCode(mfa);
                try {
                    return this.auth.user(name, pass);
                } finally {
                    OktaAuthContext.clear();
                }
            },
            false
        ).onComplete(ar -> {
            if (ar.succeeded()) {
                final Optional<AuthUser> user = ar.result();
                if (user.isPresent()) {
                    final String token = permanent
                        ? this.tokens.generate(user.get(), true)
                        : this.tokens.generate(user.get());
                    routing.response().setStatusCode(HttpStatus.OK_200).end(
                        new JsonObject().put("token", token).encode()
                    );
                } else {
                    sendError(routing, HttpStatus.UNAUTHORIZED_401, "Invalid credentials");
                }
            } else {
                routing.fail(ar.cause());
            }
        });
    }

}
