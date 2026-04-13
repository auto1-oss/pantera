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
package com.auto1.pantera.http.trace;

import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

/**
 * Captures the current SLF4J MDC (trace.id, span.id, client.ip, etc.)
 * and restores it inside a callback running on a different thread.
 *
 * <p>MDC is backed by {@code ThreadLocal}, so state set on the Vert.x
 * event loop thread is NOT visible on worker threads used by
 * {@code executeBlocking}. Without this utility, logs emitted from
 * inside a blocking auth call would be missing all request-scoped
 * fields.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * ctx.vertx().executeBlocking(
 *     MdcPropagation.withMdc(() -> auth.user(name, pass)),
 *     false
 * );
 * }</pre>
 * The captured MDC is the one present at the call site (event loop
 * thread). On the worker thread the captured map is installed before
 * the callable runs and fully cleared after.</p>
 *
 * @since 2.1.0
 */
public final class MdcPropagation {

    private MdcPropagation() {
    }

    /**
     * Wrap a {@link Callable} so it restores the caller's MDC context
     * on whichever thread it ends up running.
     *
     * @param callable The original callable
     * @param <T> Return type
     * @return A callable that installs + clears MDC around the original
     */
    public static <T> Callable<T> withMdc(final Callable<T> callable) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                return callable.call();
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wrap a {@link Runnable} so it restores the caller's MDC context
     * on whichever thread it ends up running.
     *
     * @param runnable The original runnable
     * @return A runnable that installs + clears MDC around the original
     */
    public static Runnable withMdc(final Runnable runnable) {
        final Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            final Map<String, String> prior = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
