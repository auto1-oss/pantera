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
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Slice with combined basic and bearer token authentication.
 * Supports both Basic and Bearer authentication methods.
 * @since 1.18
 */
public final class CombinedAuthzSlice implements Slice {

    /**
     * Header for pantera login.
     */
    public static final String LOGIN_HDR = "pantera_login";

    /**
     * Origin.
     */
    private final Slice origin;

    /**
     * Basic authentication.
     */
    private final Authentication basicAuth;

    /**
     * Token authentication.
     */
    private final TokenAuthentication tokenAuth;

    /**
     * Access control by permission.
     */
    private final OperationControl control;

    /**
     * Ctor.
     *
     * @param origin Origin slice.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param control Access control by permission.
     */
    public CombinedAuthzSlice(
        final Slice origin,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final OperationControl control
    ) {
        this.origin = origin;
        this.basicAuth = basicAuth;
        this.tokenAuth = tokenAuth;
        this.control = control;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final com.auto1.pantera.asto.Content body
    ) {
        return this.authenticate(headers)
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
                                headers.copy().add(CombinedAuthzSlice.LOGIN_HDR, userName),
                                body
                            );
                        }
                        // Drain (never materialise) the body: prevents the
                        // Vert.x request leak without pre-allocating from the
                        // attacker-declared Content-Length (resource-dos F31).
                        return body.discard().thenApply(ignored ->
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
                                .field("log.source", "application")
                                .log();
                        }
                        if (this.control.allowed(result.user())) {
                            return this.origin.response(
                                line,
                                headers.copy().add(CombinedAuthzSlice.LOGIN_HDR, result.user().name()),
                                body
                            );
                        }
                        // Drain (never materialise) — see the 403 path above.
                        return body.discard().thenApply(ignored2 ->
                            ResponseBuilder.forbidden().build()
                        );
                    }
                    return ResponseBuilder.unauthorized()
                        .header(new WwwAuthenticate(result.challenge()))
                        .completedFuture();
                }
        );
    }

    /**
     * Authenticate using either Basic or Bearer authentication.
     *
     * @param headers Request headers.
     * @return Authentication result.
     */
    private CompletionStage<AuthScheme.Result> authenticate(
        final Headers headers
    ) {
        return new RqHeaders(headers, Authorization.NAME)
            .stream()
            .findFirst()
            .map(
                header -> {
                    final Authorization auth;
                    final String scheme;
                    try {
                        auth = new Authorization(header);
                        scheme = auth.scheme();
                    } catch (final IllegalStateException ex) {
                        // Malformed Authorization header (e.g., bare "Bearer" with no token,
                        // empty credentials, or unrecognized format). Treat as anonymous
                        // so the permission check returns a proper 401 instead of a 500.
                        return CompletableFuture.completedFuture(
                            AuthScheme.result(
                                AuthUser.ANONYMOUS,
                                String.format("%s realm=\"pantera\", %s realm=\"pantera\"",
                                    BasicAuthScheme.NAME, BearerAuthScheme.NAME)
                            )
                        );
                    }
                    if (BasicAuthScheme.NAME.equals(scheme)) {
                        return this.authenticateBasic(auth);
                    } else if (BearerAuthScheme.NAME.equals(scheme)) {
                        return this.authenticateBearer(auth);
                    } else {
                        return CompletableFuture.completedFuture(
                            AuthScheme.result(
                                AuthUser.ANONYMOUS,
                                String.format("%s realm=\"pantera\", %s realm=\"pantera\"",
                                    BasicAuthScheme.NAME, BearerAuthScheme.NAME)
                            )
                        );
                    }
                }
            ).orElseGet(
                () -> CompletableFuture.completedFuture(
                    AuthScheme.result(
                        AuthUser.ANONYMOUS,
                        String.format("%s realm=\"pantera\", %s realm=\"pantera\"",
                            BasicAuthScheme.NAME, BearerAuthScheme.NAME)
                    )
                )
            );
    }

    /**
     * Authenticate using Basic authentication.
     * Runs on a dedicated thread pool to avoid blocking the event loop,
     * especially important when authentication involves external IdP calls (e.g., Okta with MFA).
     *
     * @param auth Authorization header.
     * @return Authentication result.
     */
    private CompletionStage<AuthScheme.Result> authenticateBasic(final Authorization auth) {
        final Authorization.Basic basic = new Authorization.Basic(auth.credentials());
        final CompletionStage<Optional<AuthUser>> resolved;
        if (AuthWorkerPool.jwtShaped(basic.password())) {
            // Package-manager clients (mvn, npm, pip, …) submit API tokens
            // as the Basic password. Validate token-shaped passwords as
            // JWTs first, bound to the claimed username — the DB-backed
            // password provider would reject the token string
            // authoritatively and block fall-through (same regression as
            // docker login, see CombinedAuthScheme#authenticateBasic).
            //
            // Snapshot MDC on the calling (event-loop) thread — tokenAuth.user()
            // completes on whatever thread the JWT validator's own async
            // chain lands on (commonPool, not this class's executor), so the
            // .exceptionally() callback below cannot rely on ThreadLocal MDC
            // still holding this request's trace.id/client.ip.
            final Map<String, String> callerMdc = MDC.getCopyOfContextMap();
            resolved = this.tokenAuth.user(basic.password())
                .exceptionally(err -> {
                    // Infrastructure failure, not a normal auth miss — restore
                    // the caller's MDC snapshot before logging so trace.id/
                    // client.ip correlate correctly, then fall back to the
                    // password check.
                    final Map<String, String> previous = MDC.getCopyOfContextMap();
                    try {
                        if (callerMdc != null) {
                            MDC.setContextMap(callerMdc);
                        } else {
                            MDC.clear();
                        }
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
                    } finally {
                        if (previous != null) {
                            MDC.setContextMap(previous);
                        } else {
                            MDC.clear();
                        }
                    }
                    return Optional.empty();
                })
                // Plain thenCompose, not thenComposeAsync: this continuation
                // only does a cheap filter/check and, when needed, explicitly
                // dispatches the actual blocking password check to
                // AUTH_EXECUTOR itself — an extra implicit commonPool hop here
                // would only add contention for zero benefit.
                .thenCompose(
                    user -> {
                        final Optional<AuthUser> bound = user.filter(
                            usr -> usr.name().equals(basic.username())
                        );
                        if (bound.isPresent()) {
                            return CompletableFuture.completedFuture(bound);
                        }
                        return CompletableFuture.supplyAsync(
                            () -> this.basicAuth.user(basic.username(), basic.password()),
                            AuthWorkerPool.AUTH_EXECUTOR
                        );
                    }
                );
        } else {
            // Offload to worker thread to prevent blocking event loop
            // This is critical for auth providers that make external calls
            // (Okta, Keycloak, etc.)
            resolved = CompletableFuture.supplyAsync(
                () -> this.basicAuth.user(basic.username(), basic.password()),
                AuthWorkerPool.AUTH_EXECUTOR
            );
        }
        return resolved.thenApply(
            user -> AuthScheme.result(
                user,
                String.format("%s realm=\"pantera\", %s realm=\"pantera\"",
                    BasicAuthScheme.NAME, BearerAuthScheme.NAME)
            )
        );
    }

    /**
     * Authenticate using Bearer token authentication.
     *
     * @param auth Authorization header.
     * @return Authentication result.
     */
    private CompletionStage<AuthScheme.Result> authenticateBearer(final Authorization auth) {
        return this.tokenAuth.user(new Authorization.Bearer(auth.credentials()).token())
            .thenApply(
                user -> AuthScheme.result(
                    user,
                    String.format("%s realm=\"pantera\", %s realm=\"pantera\"",
                        BasicAuthScheme.NAME, BearerAuthScheme.NAME)
                )
            );
    }
}
