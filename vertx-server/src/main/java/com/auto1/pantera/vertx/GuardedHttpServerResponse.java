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
package com.auto1.pantera.vertx;

import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.reactivex.core.http.HttpServerResponse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe guard wrapper for HTTP server responses.
 *
 * <p>This wrapper ensures that response operations (especially end()) are performed
 * at most once, preventing race conditions between:
 * <ul>
 *   <li>Normal response completion path</li>
 *   <li>Timeout handler path</li>
 *   <li>Error handler path</li>
 * </ul>
 *
 * <p>The guard tracks which code path "won" the race and logs any duplicate attempts,
 * preventing the "End has already been called" errors that cause connection stalls.
 *
 * @since 1.18.25
 */
public final class GuardedHttpServerResponse {

    /**
     * The underlying Vert.x response.
     */
    private final HttpServerResponse delegate;

    /**
     * Request identifier for logging (method + path + trace context).
     */
    private final String requestId;

    /**
     * Flag indicating if the response has been terminated.
     */
    private final AtomicBoolean terminated;

    /**
     * The code path that won the race (for debugging).
     */
    private final AtomicReference<String> terminatedBy;

    /**
     * Constructor.
     *
     * @param delegate The underlying Vert.x HTTP server response
     * @param requestId Request identifier for logging
     */
    public GuardedHttpServerResponse(
        final HttpServerResponse delegate,
        final String requestId
    ) {
        this.delegate = delegate;
        this.requestId = requestId;
        this.terminated = new AtomicBoolean(false);
        this.terminatedBy = new AtomicReference<>(null);
    }

    /**
     * Get the underlying response (for operations that don't need guarding).
     *
     * @return The underlying HttpServerResponse
     */
    public HttpServerResponse delegate() {
        return this.delegate;
    }

    /**
     * Check if this response has been terminated.
     *
     * @return true if end() has been called (by this guard)
     */
    public boolean isTerminated() {
        return this.terminated.get();
    }

    /**
     * Check if the underlying response has ended.
     * Note: This may be true even if isTerminated() is false (if ended externally).
     *
     * @return true if the underlying response has ended
     */
    public boolean ended() {
        return this.delegate.ended();
    }

    /**
     * Thread-safe end() that ensures only one call succeeds.
     *
     * @param caller Identifier for the calling code path (for logging)
     * @return true if this call ended the response, false if already ended
     */
    public boolean safeEnd(final String caller) {
        if (this.terminated.compareAndSet(false, true)) {
            this.terminatedBy.set(caller);
            try {
                if (!this.delegate.ended()) {
                    this.delegate.end();
                }
                return true;
            } catch (Exception e) {
                // Response may have been ended by Vert.x internally
                EcsLogger.debug("com.auto1.pantera.vertx")
                    .message(String.format("Response end() failed (likely already ended by Vert.x), caller=%s", caller))
                    .eventCategory("web")
                    .eventAction("response_end")
                    .field("http.request.id", this.requestId)
                    .error(e)
                    .log();
                return false;
            }
        } else {
            // Already terminated by another path
            EcsLogger.warn("com.auto1.pantera.vertx")
                .message(String.format("End has already been called: '%s', caller=%s, terminatedBy=%s", this.requestId, caller, this.terminatedBy.get()))
                .eventCategory("web")
                .eventAction("response_end_duplicate")
                .field("http.request.id", this.requestId)
                .log();
            return false;
        }
    }

    /**
     * Thread-safe end() with body that ensures only one call succeeds.
     *
     * @param caller Identifier for the calling code path (for logging)
     * @param body Response body to write
     * @return true if this call ended the response, false if already ended
     */
    public boolean safeEnd(final String caller, final String body) {
        if (this.terminated.compareAndSet(false, true)) {
            this.terminatedBy.set(caller);
            try {
                if (!this.delegate.ended()) {
                    this.delegate.end(body);
                }
                return true;
            } catch (Exception e) {
                EcsLogger.debug("com.auto1.pantera.vertx")
                    .message(String.format("Response end(body) failed (likely already ended by Vert.x), caller=%s", caller))
                    .eventCategory("web")
                    .eventAction("response_end")
                    .field("http.request.id", this.requestId)
                    .error(e)
                    .log();
                return false;
            }
        } else {
            EcsLogger.warn("com.auto1.pantera.vertx")
                .message(String.format("End has already been called: '%s', caller=%s, terminatedBy=%s", this.requestId, caller, this.terminatedBy.get()))
                .eventCategory("web")
                .eventAction("response_end_duplicate")
                .field("http.request.id", this.requestId)
                .log();
            return false;
        }
    }

    /**
     * Send an error response safely.
     *
     * @param caller Identifier for the calling code path
     * @param statusCode HTTP status code
     * @param message Error message
     * @return true if the error was sent, false if response already ended
     */
    public boolean safeSendError(final String caller, final int statusCode, final String message) {
        if (this.terminated.compareAndSet(false, true)) {
            this.terminatedBy.set(caller);
            try {
                if (!this.delegate.ended()) {
                    // Only set status if headers not yet written
                    if (!this.delegate.headWritten()) {
                        this.delegate.setStatusCode(statusCode);
                    }
                    this.delegate.end(message);
                }
                return true;
            } catch (Exception e) {
                EcsLogger.debug("com.auto1.pantera.vertx")
                    .message(String.format("Error response failed (likely already ended), caller=%s", caller))
                    .eventCategory("web")
                    .eventAction("response_error")
                    .field("http.request.id", this.requestId)
                    .error(e)
                    .log();
                return false;
            }
        } else {
            EcsLogger.warn("com.auto1.pantera.vertx")
                .message(String.format("End has already been called: '%s', caller=%s, terminatedBy=%s", this.requestId, caller, this.terminatedBy.get()))
                .eventCategory("web")
                .eventAction("response_error_duplicate")
                .field("http.request.id", this.requestId)
                .log();
            return false;
        }
    }

    /**
     * Mark as terminated without ending (for when Vert.x ends it automatically).
     * Used when response.toSubscriber() is used and Vert.x will call end() automatically.
     *
     * @param caller Identifier for the calling code path
     * @return true if this was the first termination, false if already terminated
     */
    public boolean markTerminated(final String caller) {
        if (this.terminated.compareAndSet(false, true)) {
            this.terminatedBy.set(caller);
            return true;
        }
        return false;
    }
}
