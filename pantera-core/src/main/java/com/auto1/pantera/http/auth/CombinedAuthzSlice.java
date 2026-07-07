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
import com.auto1.pantera.http.trace.TraceContextExecutor;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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
     * Pool name for metrics identification.
     */
    public static final String AUTH_POOL_NAME = "pantera.auth.combined";

    /**
     * Thread pool for blocking authentication operations.
     * This offloads potentially slow operations (like Okta MFA) from the event loop.
     * Pool name: {@value #AUTH_POOL_NAME} (visible in thread dumps and metrics).
     * Wrapped with TraceContextExecutor to propagate MDC (trace.id, user, etc.) to auth threads.
     */
    private static final ExecutorService AUTH_EXECUTOR = TraceContextExecutor.wrap(
        Executors.newCachedThreadPool(
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread = new Thread(runnable);
                    thread.setName(AUTH_POOL_NAME + ".worker-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            }
        )
    );

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
        if (CombinedAuthzSlice.jwtShaped(basic.password())) {
            // Package-manager clients (mvn, npm, pip, …) submit API tokens
            // as the Basic password. Validate token-shaped passwords as
            // JWTs first, bound to the claimed username — the DB-backed
            // password provider would reject the token string
            // authoritatively and block fall-through (same regression as
            // docker login, see CombinedAuthScheme#authenticateBasic).
            resolved = this.tokenAuth.user(basic.password())
                .exceptionally(err -> Optional.empty())
                .thenComposeAsync(
                    user -> {
                        final Optional<AuthUser> bound = user.filter(
                            usr -> usr.name().equals(basic.username())
                        );
                        if (bound.isPresent()) {
                            return CompletableFuture.completedFuture(bound);
                        }
                        return CompletableFuture.supplyAsync(
                            () -> this.basicAuth.user(basic.username(), basic.password()),
                            AUTH_EXECUTOR
                        );
                    }
                );
        } else {
            // Offload to worker thread to prevent blocking event loop
            // This is critical for auth providers that make external calls
            // (Okta, Keycloak, etc.)
            resolved = CompletableFuture.supplyAsync(
                () -> this.basicAuth.user(basic.username(), basic.password()),
                AUTH_EXECUTOR
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
     * Whether a Basic password looks like a compact JWS/JWT — mirrors
     * {@link CombinedAuthScheme}. Signature and claims are NOT verified
     * here; this only decides which validation path runs first.
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
