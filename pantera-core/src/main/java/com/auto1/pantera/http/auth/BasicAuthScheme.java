/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.auth;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.auto1.pantera.http.trace.TraceContextExecutor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Basic authentication method.
 *
 * @since 0.17
 */
@SuppressWarnings("PMD.OnlyOneReturn")
public final class BasicAuthScheme implements AuthScheme {

    /**
     * Basic authentication prefix.
     */
    public static final String NAME = "Basic";

    /**
     * Basic authentication challenge.
     */
    private static final String CHALLENGE =
        String.format("%s realm=\"artipie\"", BasicAuthScheme.NAME);

    /**
     * Pool name for metrics identification.
     */
    public static final String AUTH_POOL_NAME = "pantera.auth.basic";

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
        if (authHeader.isEmpty()) {
            // No credentials provided - return immediately without blocking
            return CompletableFuture.completedFuture(
                AuthScheme.result(AuthUser.ANONYMOUS, BasicAuthScheme.CHALLENGE)
            );
        }
        // Offload auth to worker thread to prevent blocking event loop
        // This is critical for auth providers that make external calls (Okta, Keycloak, etc.)
        return CompletableFuture.supplyAsync(
            () -> AuthScheme.result(this.user(authHeader.get()), BasicAuthScheme.CHALLENGE),
            AUTH_EXECUTOR
        );
    }

    /**
     * Obtains user from authorization header.
     *
     * @param header Authorization header's value
     * @return User if authorised
     */
    private Optional<AuthUser> user(final String header) {
        final Authorization atz = new Authorization(header);
        if (BasicAuthScheme.NAME.equals(atz.scheme())) {
            final Authorization.Basic basic = new Authorization.Basic(atz.credentials());
            return this.auth.user(basic.username(), basic.password());
        }
        return Optional.empty();
    }
}
