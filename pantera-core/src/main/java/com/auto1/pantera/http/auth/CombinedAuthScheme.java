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
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Authentication scheme that supports both Basic and Bearer token authentication.
 *
 * <p>Basic credentials whose password is a compact JWT are validated as
 * tokens first (bound to the supplied username), then fall back to the
 * regular password check. Docker and other registry clients can only
 * submit credentials via Basic — the challenge this scheme emits carries
 * no token-endpoint realm — so API tokens arrive as the Basic password
 * ({@code docker login -u user -p <api-token>}). Without the token-first
 * step, an authoritative password provider (e.g. the DB-backed check)
 * rejects the JWT string outright and blocks fall-through to any
 * token-aware provider, locking token holders out of every registry
 * client.
 *
 * @since 1.18
 */
public final class CombinedAuthScheme implements AuthScheme {

    /**
     * Challenge advertised on anonymous and failed authentication.
     */
    private static final String CHALLENGE = String.format(
        "%s realm=\"pantera\", %s realm=\"pantera\"",
        BasicAuthScheme.NAME, BearerAuthScheme.NAME
    );

    /**
     * Basic authentication.
     */
    private final Authentication basicAuth;

    /**
     * Token authentication.
     */
    private final TokenAuthentication tokenAuth;

    /**
     * Ctor.
     *
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     */
    public CombinedAuthScheme(
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth
    ) {
        this.basicAuth = basicAuth;
        this.tokenAuth = tokenAuth;
    }

    @Override
    public CompletionStage<Result> authenticate(
        final Headers headers,
        final RequestLine line
    ) {
        return new RqHeaders(headers, Authorization.NAME)
            .stream()
            .findFirst()
            .map(Authorization::new)
            .map(
                auth -> {
                    if (BasicAuthScheme.NAME.equals(auth.scheme())) {
                        return this.authenticateBasic(auth);
                    } else if (BearerAuthScheme.NAME.equals(auth.scheme())) {
                        return this.authenticateBearer(auth);
                    }
                    return CompletableFuture.completedFuture(
                        AuthScheme.result(AuthUser.ANONYMOUS, CombinedAuthScheme.CHALLENGE)
                    );
                }
            )
            .orElseGet(
                () -> CompletableFuture.completedFuture(
                    AuthScheme.result(AuthUser.ANONYMOUS, CombinedAuthScheme.CHALLENGE)
                )
            );
    }

    /**
     * Authenticate using Basic authentication.
     *
     * <p>A token-shaped password is validated as a JWT first and must
     * resolve to the supplied username; anything else (malformed token,
     * foreign subject, plain password) goes through the regular
     * password check, so passwords that merely look like a JWT keep
     * working.
     *
     * @param auth Authorization header
     * @return Authentication result
     */
    private CompletionStage<AuthScheme.Result> authenticateBasic(final Authorization auth) {
        final Authorization.Basic basic = new Authorization.Basic(auth.credentials());
        final CompletionStage<Optional<AuthUser>> resolved;
        if (jwtShaped(basic.password())) {
            resolved = this.tokenAuth.user(basic.password())
                .exceptionally(err -> {
                    // Infrastructure failure (revocation store unreachable,
                    // key load error), NOT a normal auth miss — that returns
                    // an empty Optional without throwing. Log it, then fall
                    // back to the password check rather than 500.
                    EcsLogger.warn("com.auto1.pantera.http.auth")
                        .message("Token validation errored for a Basic-password"
                            + " token; falling back to password authentication")
                        .eventCategory("authentication")
                        .eventAction("token_validate")
                        .eventOutcome("failure")
                        .field("user.name", basic.username())
                        .error(err)
                        .field("log.source", "application")
                        .log();
                    return Optional.empty();
                })
                .thenApply(user -> user.filter(usr -> usr.name().equals(basic.username())))
                .thenCompose(
                    user -> user.isPresent()
                        ? CompletableFuture.completedFuture(user)
                        : CompletableFuture.supplyAsync(
                            () -> this.basicAuth.user(basic.username(), basic.password())
                        )
                );
        } else {
            // Offload the (potentially blocking, DB/IdP-backed) password
            // check off the calling thread — mirrors BasicAuthScheme. The
            // /v2/ Docker ping runs this on a Vert.x event-loop thread.
            resolved = CompletableFuture.supplyAsync(
                () -> this.basicAuth.user(basic.username(), basic.password())
            );
        }
        return resolved.thenApply(
            user -> AuthScheme.result(user, CombinedAuthScheme.CHALLENGE)
        );
    }

    /**
     * Authenticate using Bearer token authentication.
     *
     * @param auth Authorization header
     * @return Authentication result
     */
    private CompletionStage<AuthScheme.Result> authenticateBearer(final Authorization auth) {
        return this.tokenAuth.user(new Authorization.Bearer(auth.credentials()).token())
            .thenApply(user -> AuthScheme.result(user, CombinedAuthScheme.CHALLENGE));
    }

    /**
     * Whether a Basic password looks like a compact JWS/JWT: three
     * dot-separated segments whose header opens with {@code eyJ}
     * (base64url of {@code {"}). Signature and claims are NOT verified
     * here — this only decides which validation path runs first.
     *
     * @param password Basic password
     * @return True if the password should be tried as a token
     */
    private static boolean jwtShaped(final String password) {
        boolean shaped = false;
        if (password != null && password.startsWith("eyJ")) {
            final String[] parts = password.split("\\.", -1);
            shaped = parts.length == 3 && !parts[1].isEmpty() && !parts[2].isEmpty();
        }
        return shaped;
    }
}
