/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.auth;

import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.headers.Authorization;
import com.artipie.http.headers.Header;
import com.artipie.http.headers.WwwAuthenticate;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqHeaders;
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
     * Header for artipie login.
     */
    public static final String LOGIN_HDR = "artipie_login";

    /**
     * Pool name for metrics identification.
     */
    public static final String AUTH_POOL_NAME = "artipie.auth.combined";

    /**
     * Thread pool for blocking authentication operations.
     * This offloads potentially slow operations (like Okta MFA) from the event loop.
     * Pool name: {@value #AUTH_POOL_NAME} (visible in thread dumps and metrics).
     */
    private static final ExecutorService AUTH_EXECUTOR = Executors.newCachedThreadPool(
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
        final RequestLine line, final Headers headers, final com.artipie.asto.Content body
    ) {
        return this.authenticate(headers, line)
            .toCompletableFuture()
            .thenCompose(
                result -> {
                    if (result.status() == AuthScheme.AuthStatus.AUTHENTICATED) {
                        // Set MDC for downstream logging (cooldown, metrics, etc.)
                        // This ensures Bearer/JWT authenticated users are tracked correctly
                        final String userName = result.user().name();
                        if (userName != null && !userName.isEmpty() && !result.user().isAnonymous()) {
                            MDC.put("user.name", userName);
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
                        } catch (final UnsupportedOperationException ignored) {
                            // fall through when scheme does not provide challenge
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
     * @param line Request line.
     * @return Authentication result.
     */
    private CompletionStage<AuthScheme.Result> authenticate(
        final Headers headers, final RequestLine line
    ) {
        return new RqHeaders(headers, Authorization.NAME)
            .stream()
            .findFirst()
            .map(
                header -> {
                    final Authorization auth = new Authorization(header);
                    final String scheme = auth.scheme();
                    
                    if (BasicAuthScheme.NAME.equals(scheme)) {
                        return this.authenticateBasic(auth);
                    } else if (BearerAuthScheme.NAME.equals(scheme)) {
                        return this.authenticateBearer(auth);
                    } else {
                        return CompletableFuture.completedFuture(
                            AuthScheme.result(
                                AuthUser.ANONYMOUS,
                                String.format("%s realm=\"artipie\", %s realm=\"artipie\"",
                                    BasicAuthScheme.NAME, BearerAuthScheme.NAME)
                            )
                        );
                    }
                }
            ).orElseGet(
                () -> CompletableFuture.completedFuture(
                    AuthScheme.result(
                        AuthUser.ANONYMOUS,
                        String.format("%s realm=\"artipie\", %s realm=\"artipie\"",
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
        // Offload to worker thread to prevent blocking event loop
        // This is critical for auth providers that make external calls (Okta, Keycloak, etc.)
        return CompletableFuture.supplyAsync(
            () -> {
                final Optional<AuthUser> user = this.basicAuth.user(
                    basic.username(), basic.password()
                );
                return AuthScheme.result(
                    user,
                    String.format("%s realm=\"artipie\", %s realm=\"artipie\"",
                        BasicAuthScheme.NAME, BearerAuthScheme.NAME)
                );
            },
            AUTH_EXECUTOR
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
                    String.format("%s realm=\"artipie\", %s realm=\"artipie\"",
                        BasicAuthScheme.NAME, BearerAuthScheme.NAME)
                )
            );
    }
}
