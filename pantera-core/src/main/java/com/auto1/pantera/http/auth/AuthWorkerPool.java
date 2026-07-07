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

import com.auto1.pantera.http.trace.TraceContextExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared support for every Basic-auth-capable scheme in this package
 * ({@link BasicAuthScheme}, {@link CombinedAuthScheme}, {@link CombinedAuthzSlice}):
 * one dedicated thread pool for the blocking DB/IdP password check, and the
 * JWT-shape heuristic used to decide whether a Basic password should be
 * tried as a token first.
 *
 * <p>Previously each of the three schemes built its own independent
 * {@code Executors.newCachedThreadPool(...)}, so a burst of auth traffic
 * spread across Docker/Maven/npm/PyPI could spin up threads across three
 * separately-unbounded pools — harder to capacity-plan and to correlate in
 * thread dumps for what is conceptually one workload. One shared pool fixes
 * that; it is still isolated from {@code ForkJoinPool.commonPool()} so a slow
 * IdP (Okta/Keycloak MFA) can't starve unrelated {@code *Async} work JVM-wide,
 * and wrapped with {@link TraceContextExecutor} so MDC (trace.id, user)
 * propagates to the auth threads.
 *
 * @since 2.2.2
 */
final class AuthWorkerPool {

    /**
     * Pool name for metrics identification and thread dumps.
     */
    static final String POOL_NAME = "pantera.auth.worker";

    /**
     * Shared executor for every scheme's blocking password check.
     */
    static final ExecutorService AUTH_EXECUTOR = TraceContextExecutor.wrap(
        Executors.newCachedThreadPool(
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);

                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread = new Thread(runnable);
                    thread.setName(
                        AuthWorkerPool.POOL_NAME + ".worker-" + this.counter.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                }
            }
        )
    );

    private AuthWorkerPool() {
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
    static boolean jwtShaped(final String password) {
        boolean shaped = false;
        if (password != null && password.startsWith("eyJ")) {
            final String[] parts = password.split("\\.", -1);
            shaped = parts.length == 3 && !parts[1].isEmpty() && !parts[2].isEmpty();
        }
        return shaped;
    }
}
