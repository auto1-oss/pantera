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
package com.auto1.pantera.http.log;

import com.auto1.pantera.http.UpstreamCircuitOpenException;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Machine-readable cause of a failure that Pantera answers with a 5xx, for the
 * {@code event.reason} field of the log record that explains that answer.
 *
 * <p>The value is a short, bounded snake_case token (never exception text),
 * so operators can aggregate 5xx answers by cause; the exception itself goes
 * into {@code error.*} through {@link EcsLogger#error(Throwable)}.</p>
 *
 * <p>Classification walks the cause chain (bounded) and keys on exception
 * types; Jetty and egress exceptions are matched by class name because this
 * module does not depend on the HTTP client.</p>
 *
 * @since 2.2.11
 */
public final class FailureCause {

    /**
     * Max cause-chain depth inspected.
     */
    private static final int MAX_DEPTH = 8;

    /**
     * Throwable as caught.
     */
    private final Throwable error;

    /**
     * Ctor.
     * @param error Throwable as caught (may be wrapped in CompletionException)
     */
    public FailureCause(final Throwable error) {
        this.error = error;
    }

    /**
     * The real cause: the first throwable of the chain that is not a
     * {@link CompletionException} / {@link ExecutionException} wrapper.
     *
     * @return Unwrapped throwable
     */
    public Throwable root() {
        Throwable cur = this.error;
        int depth = 0;
        while ((cur instanceof CompletionException || cur instanceof ExecutionException)
            && cur.getCause() != null && depth < MAX_DEPTH) {
            cur = cur.getCause();
            depth += 1;
        }
        return cur;
    }

    /**
     * Cause category for {@code event.reason}.
     *
     * @return Snake_case token, {@code internal_error} when nothing matched
     */
    public String reason() {
        Throwable cur = this.root();
        int depth = 0;
        while (cur != null && depth < MAX_DEPTH) {
            final String reason = FailureCause.classify(cur);
            if (reason != null) {
                return reason;
            }
            cur = cur.getCause();
            depth += 1;
        }
        return this.root() instanceof IOException ? "io_error" : "internal_error";
    }

    /**
     * Classify one throwable of the chain.
     *
     * @param err Throwable
     * @return Reason, or null to keep walking the chain
     */
    private static String classify(final Throwable err) {
        final String name = err.getClass().getName();
        final String msg = err.getMessage() == null
            ? "" : err.getMessage().toLowerCase(Locale.ROOT);
        final String reason;
        if (err instanceof UpstreamCircuitOpenException) {
            reason = "circuit_open";
        } else if (name.endsWith("EgressDeniedException")) {
            reason = "egress_denied";
        } else if (err instanceof java.net.UnknownHostException
            || err instanceof java.nio.channels.UnresolvedAddressException) {
            reason = "unknown_host";
        } else if (err instanceof java.net.ConnectException) {
            reason = msg.contains("timed out") ? "connect_timeout" : "connection_refused";
        } else if (err instanceof java.net.SocketTimeoutException) {
            reason = msg.contains("connect") ? "connect_timeout" : "read_timeout";
        } else if (err instanceof TimeoutException) {
            reason = msg.contains("idle") ? "idle_timeout" : "request_timeout";
        } else if (err instanceof javax.net.ssl.SSLException) {
            reason = "tls_error";
        } else if (err instanceof RejectedExecutionException) {
            reason = "request_rejected";
        } else if (err instanceof java.nio.channels.ClosedChannelException
            || name.endsWith("EofException")
            || msg.contains("connection reset") || msg.contains("broken pipe")) {
            reason = "connection_closed";
        } else {
            reason = null;
        }
        return reason;
    }
}
