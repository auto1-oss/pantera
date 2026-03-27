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
package com.auto1.pantera.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;

/**
 * Slice with authorization.
 */
public final class AuthzSlice implements Slice {

    /**
     * Header for pantera login.
     */
    public static final String LOGIN_HDR = "pantera_login";

    /**
     * Origin.
     */
    private final Slice origin;

    /**
     * Authentication scheme.
     */
    private final AuthScheme auth;

    /**
     * Access control by permission.
     */
    private final OperationControl control;

    /**
     * @param origin Origin slice.
     * @param auth Authentication scheme.
     * @param control Access control by permission.
     */
    public AuthzSlice(Slice origin, AuthScheme auth, OperationControl control) {
        this.origin = origin;
        this.auth = auth;
        this.control = control;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        return this.auth.authenticate(headers, line)
            .toCompletableFuture()
            .thenCompose(
                result -> {
                    if (result.status() == AuthScheme.AuthStatus.AUTHENTICATED) {
                        // Set MDC for downstream logging (cooldown, metrics, etc.)
                        // This ensures Bearer/JWT authenticated users are tracked correctly
                        final String userName = result.user().name();
                        if (userName != null && !userName.isEmpty() && !result.user().isAnonymous()) {
                            MDC.put(EcsMdc.USER_NAME, userName);
                        }
                        if (this.control.allowed(result.user())) {
                            return this.origin.response(
                                line,
                                headers.copy().add(AuthzSlice.LOGIN_HDR, userName),
                                body
                            );
                        }
                        // Consume request body to prevent Vert.x request leak
                        return body.asBytesFuture().thenApply(ignored ->
                            ResponseBuilder.forbidden().build()
                        );
                    }
                    if (result.status() == AuthScheme.AuthStatus.NO_CREDENTIALS) {
                        try {
                            final String challenge = result.challenge();
                            if (challenge != null && !challenge.isBlank()) {
                                return ResponseBuilder.unauthorized()
                                    .header(new WwwAuthenticate(challenge))
                                    .completedFuture();
                            }
                        } catch (final UnsupportedOperationException ex) {
                            EcsLogger.debug("com.auto1.pantera.http.auth")
                                .message("Auth scheme does not provide challenge")
                                .error(ex)
                                .log();
                        }
                        if (this.control.allowed(result.user())) {
                            return this.origin.response(
                                line,
                                headers.copy().add(AuthzSlice.LOGIN_HDR, result.user().name()),
                                body
                            );
                        }
                        // Consume request body to prevent Vert.x request leak
                        return body.asBytesFuture().thenApply(ignored2 ->
                            ResponseBuilder.forbidden().build()
                        );
                    }
                    return ResponseBuilder.unauthorized()
                        .header(new WwwAuthenticate(result.challenge()))
                        .completedFuture();
                }
        );
    }
}
