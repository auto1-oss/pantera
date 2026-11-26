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
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Basic authentication method.
 *
 * @since 0.17
 */
public final class BasicAuthScheme implements AuthScheme {

    /**
     * Basic authentication prefix.
     */
    public static final String NAME = "Basic";

    /**
     * Basic authentication challenge.
     */
    private static final String CHALLENGE =
        String.format("%s realm=\"pantera\"", BasicAuthScheme.NAME);

    /**
     * Authentication.
     */
    private final Authentication auth;

    /**
     * Ctor.
     * @param auth Authentication.
     */
    public BasicAuthScheme(final Authentication auth) {
        this.auth = auth;
    }

    @Override
    public CompletionStage<Result> authenticate(
        Headers headers, RequestLine line
    ) {
        final Optional<String> authHeader = new RqHeaders(headers, Authorization.NAME)
            .stream()
            .findFirst();
        if (authHeader.isEmpty() || !new Authorization(authHeader.get()).parseable()) {
            // No credentials, or a scheme-less value this scheme cannot read
            // (answered like a missing header: 401 + challenge, never 500)
            // - return immediately without blocking
            return CompletableFuture.completedFuture(
                AuthScheme.result(AuthUser.ANONYMOUS, BasicAuthScheme.CHALLENGE)
            );
        }
        // Offload auth to worker thread to prevent blocking event loop
        // This is critical for auth providers that make external calls (Okta, Keycloak, etc.)
        return CompletableFuture.supplyAsync(
            () -> AuthScheme.result(
                this.outcome(authHeader.get(), headers), BasicAuthScheme.CHALLENGE
            ),
            AuthWorkerPool.AUTH_EXECUTOR
        );
    }

    /**
     * Checks the credentials of an authorization header.
     *
     * @param header Authorization header's value
     * @param request Headers of the request
     * @return Outcome of the check, with the refusal the chain noted
     */
    private PasswordCheck.Outcome outcome(final String header, final Headers request) {
        final Authorization atz = new Authorization(header);
        PasswordCheck.Outcome res =
            new PasswordCheck.Outcome(Optional.empty(), Optional.empty());
        if (BasicAuthScheme.NAME.equals(atz.scheme())) {
            final Authorization.Basic basic = new Authorization.Basic(atz.credentials());
            boolean readable;
            try {
                basic.username();
                basic.password();
                readable = true;
            } catch (final IllegalArgumentException ex) {
                // Undecodable Base64 or no ':' separator: a failed login
                // (401), not a server error.
                readable = false;
            }
            if (readable) {
                res = new PasswordCheck(this.auth, request)
                    .now(basic.username(), basic.password());
            }
        }
        return res;
    }
}
