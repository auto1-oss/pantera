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
package com.auto1.pantera.http.client.retry;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.FailureCause;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.ThreadContext;

/**
 * Sends a request again when the upstream closed the connection before
 * answering it.
 *
 * <p>Every server and load balancer closes kept-alive connections: on an
 * idle timeout, after a number of requests, on a restart. The close can
 * cross a request that is already on its way, and that request then fails
 * although the upstream is healthy. The HTTP client does not repeat it.
 * Without this slice one such close fails an artifact download.</p>
 *
 * <p>A request is sent again only when all of this holds:</p>
 * <ul>
 *   <li>it is a {@code GET} or a {@code HEAD}: reading twice changes nothing
 *   at the upstream, while a {@code POST} or {@code PUT} may already have
 *   been acted on;</li>
 *   <li>it declares an empty body: a body is a stream that the first attempt
 *   has consumed;</li>
 *   <li>the attempt failed because the connection went away
 *   ({@code connection_closed} of {@link FailureCause}). A timeout has
 *   already cost its full wait, and a refused connection, an unknown host or
 *   a failed TLS handshake fails the same way again.</li>
 * </ul>
 *
 * <p>The wrapped slice completes its future once the response headers are
 * in, so a failed future means no part of an answer reached the caller. A
 * connection lost in the middle of a body is not handled here: the caller
 * is already streaming it.</p>
 *
 * <p>Up to {@value #RETRIES} more attempts are made, at once: the failed
 * connection has left the pool, so the next attempt goes out on another
 * one. An answer of any status is returned as it is. This slice sits
 * directly on the transport, inside the circuit breaker, which therefore
 * sees one outcome per request and is not tripped by a close that the next
 * attempt recovers from.</p>
 *
 * @since 2.2.12
 */
public final class RetryingClientSlice implements Slice {

    /**
     * Attempts after the first one.
     */
    private static final int RETRIES = 2;

    /**
     * Client slice of one upstream.
     */
    private final Slice origin;

    /**
     * Upstream host, for the log.
     */
    private final String host;

    /**
     * Ctor.
     * @param origin Client slice of one upstream
     * @param host Upstream host, for the log
     */
    public RetryingClientSlice(final Slice origin, final String host) {
        this.origin = origin;
        this.host = host;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletableFuture<Response> first = this.origin.response(line, headers, body);
        final CompletableFuture<Response> result;
        if (RetryingClientSlice.repeatable(line, body)) {
            result = new CompletableFuture<>();
            // The failure arrives on a thread of the HTTP client: the
            // caller's logging context (trace id, repository) is taken here
            // and put back around each further attempt.
            this.settle(first, line, headers, ThreadContext.getImmutableContext(), 0, result);
        } else {
            result = first;
        }
        return result;
    }

    /**
     * Completes the result with the outcome of an attempt, or with the
     * outcome of the next attempt when this one lost its connection.
     * @param attempt Attempt in flight
     * @param line Request line
     * @param headers Request headers
     * @param context Logging context of the caller
     * @param repeated Attempts made after the first one
     * @param result Future the caller holds
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private void settle(
        final CompletableFuture<Response> attempt, final RequestLine line,
        final Headers headers, final Map<String, String> context, final int repeated,
        final CompletableFuture<Response> result
    ) {
        attempt.whenComplete(
            (response, failure) -> {
                if (failure == null) {
                    result.complete(response);
                } else if (repeated < RetryingClientSlice.RETRIES
                    && RetryingClientSlice.closed(failure)) {
                    this.settle(
                        this.again(line, headers, context, repeated + 1),
                        line, headers, context, repeated + 1, result
                    );
                } else {
                    result.completeExceptionally(failure);
                }
            }
        );
    }

    /**
     * Sends the request once more, under the logging context of the caller.
     * @param line Request line
     * @param headers Request headers
     * @param context Logging context of the caller
     * @param number Number of this repeated attempt, from 1
     * @return Outcome of the attempt
     */
    private CompletableFuture<Response> again(
        final RequestLine line, final Headers headers, final Map<String, String> context,
        final int number
    ) {
        final Map<String, String> before = ThreadContext.getImmutableContext();
        ThreadContext.clearMap();
        ThreadContext.putAll(context);
        CompletableFuture<Response> next;
        try {
            EcsLogger.info("com.auto1.pantera.http.client")
                .message(
                    String.format(
                        "Upstream closed the connection before answering, sending the request again (attempt %d of %d)",
                        number + 1, RetryingClientSlice.RETRIES + 1
                    )
                )
                .eventCategory("network")
                .eventAction("http_request_retry")
                .field("event.reason", "connection_closed")
                .field("http.request.method", line.method().value())
                .field("url.path", line.uri().getPath())
                .field("destination.address", this.host)
                .field("log.source", "application")
                .log();
            next = this.origin.response(line, headers, Content.EMPTY);
        } catch (final RuntimeException ex) {
            // A throw here would vanish inside the completion callback of
            // the failed attempt and leave the caller's future pending.
            next = CompletableFuture.failedFuture(ex);
        } finally {
            ThreadContext.clearMap();
            ThreadContext.putAll(before);
        }
        return next;
    }

    /**
     * Whether a request can be sent a second time without changing what the
     * upstream does or sees.
     * @param line Request line
     * @param body Request body
     * @return True for a GET or HEAD that declares an empty body
     */
    private static boolean repeatable(final RequestLine line, final Content body) {
        final RqMethod method = line.method();
        return (method == RqMethod.GET || method == RqMethod.HEAD)
            && body.size().filter(size -> size == 0L).isPresent();
    }

    /**
     * Whether an attempt failed because its connection went away.
     * @param failure Failure of the attempt
     * @return True if the upstream closed or reset the connection
     */
    private static boolean closed(final Throwable failure) {
        return "connection_closed".equals(new FailureCause(failure).reason());
    }
}
