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

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.log.RequestReasons;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Answer to a request whose login failed.
 *
 * <p>Wrong credentials are answered {@code 401} with the scheme's
 * challenge. When the sign-in chain said why it refused the login (an
 * {@link AuthRefusal}), the answer takes the refusal's status,
 * {@code Retry-After} and text, and the reason is noted for the access
 * record. Only a {@code 401} carries the challenge: a client that is told
 * the account is locked, or to wait, is not asked for other credentials.</p>
 *
 * @since 2.2.15
 */
final class FailedLogin {

    /**
     * Result of the authentication.
     */
    private final AuthScheme.Result result;

    /**
     * Headers of the request.
     */
    private final Headers request;

    /**
     * Ctor.
     *
     * @param result Result of the authentication, of status FAILED
     * @param request Headers of the request
     */
    FailedLogin(final AuthScheme.Result result, final Headers request) {
        this.result = result;
        this.request = request;
    }

    /**
     * The answer.
     *
     * @return Response
     */
    CompletableFuture<Response> response() {
        final Optional<AuthRefusal> refusal = this.result.refusal();
        final RsStatus status = refusal.map(AuthRefusal::status).orElse(RsStatus.UNAUTHORIZED);
        final ResponseBuilder builder = ResponseBuilder.from(status);
        if (status == RsStatus.UNAUTHORIZED) {
            builder.header(new WwwAuthenticate(this.result.challenge()));
        }
        if (refusal.isPresent()) {
            final AuthRefusal why = refusal.get();
            RequestReasons.instance().note(this.request, why.reason());
            why.retryAfter().ifPresent(
                seconds -> builder.header("Retry-After", Long.toString(seconds))
            );
            if (!why.message().isEmpty()) {
                builder.textBody(why.message());
            }
        }
        return builder.completedFuture();
    }
}
