/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.rq.RequestLine;
import com.artipie.metrics.ArtipieMetrics;
import com.artipie.metrics.MicrometerMetrics;
import io.reactivex.Flowable;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Slice decorator with body-aware IDLE timeout.
 *
 * <p>CRITICAL FIX: Unlike the previous implementation using CompletableFuture.orTimeout(),
 * this tracks the ENTIRE request lifecycle including body streaming, not just
 * response future completion.</p>
 *
 * <p>IMPORTANT: This is an IDLE timeout, not a total timeout. The timer resets
 * on each chunk received. This allows large artifact downloads (5+ minutes) to
 * complete as long as data keeps flowing. Timeout only fires if no data is
 * received for the configured duration.</p>
 *
 * <p>The previous implementation had a race condition:
 * <ul>
 *   <li>orTimeout() was satisfied when Response future completed (headers ready)</li>
 *   <li>Body streaming continued WITHOUT timeout protection</li>
 *   <li>If upstream failed mid-stream, no timeout would fire</li>
 *   <li>This caused "End has already been called" races and complete stalls</li>
 * </ul>
 * </p>
 *
 * <p>Timeout is configured in artipie.yml under meta.http_client.proxy_timeout
 * (default: 60 seconds idle timeout)</p>
 *
 * @since 1.18.26
 */
public final class TimeoutSlice implements Slice {

    /**
     * Shared scheduler for timeout tasks.
     * Uses daemon threads to not prevent JVM shutdown.
     */
    private static final ScheduledExecutorService SCHEDULER =
        Executors.newScheduledThreadPool(2, r -> {
            final Thread t = new Thread(r, "artipie-timeout-scheduler");
            t.setDaemon(true);
            return t;
        });

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Timeout duration in seconds.
     */
    private final long timeoutSeconds;

    /**
     * Ctor with explicit timeout in seconds.
     *
     * @param origin Origin slice
     * @param timeoutSeconds Timeout duration in seconds
     */
    public TimeoutSlice(final Slice origin, final long timeoutSeconds) {
        this.origin = origin;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final CompletableFuture<Response> result = new CompletableFuture<>();
        final AtomicBoolean completed = new AtomicBoolean(false);
        final long startTime = System.currentTimeMillis();
        final String requestId = line.method() + " " + line.uri().getPath();

        // Use AtomicReference for idle timeout - allows resetting on each chunk
        final AtomicReference<ScheduledFuture<?>> idleTimeoutRef = new AtomicReference<>();

        // Schedule initial idle timeout for response future phase
        final ScheduledFuture<?> initialTimeout = SCHEDULER.schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                final long elapsed = System.currentTimeMillis() - startTime;

                EcsLogger.warn("com.artipie.http.TimeoutSlice")
                    .message("Idle timeout fired waiting for response: " + requestId)
                    .eventCategory("http")
                    .eventAction("idle_timeout")
                    .eventOutcome("timeout")
                    .field("http.request.id", requestId)
                    .field("http.request.timeout", this.timeoutSeconds)
                    .duration(elapsed)
                    .log();

                recordTimeoutMetric("response_future", elapsed);

                result.completeExceptionally(new TimeoutException(
                    String.format("Request timed out after %d ms idle (limit: %d s) - no response received",
                        elapsed, this.timeoutSeconds)
                ));
            }
        }, this.timeoutSeconds, TimeUnit.SECONDS);
        idleTimeoutRef.set(initialTimeout);

        this.origin.response(line, headers, body)
            .whenComplete((response, error) -> {
                if (error != null) {
                    // Origin failed - cancel timeout and propagate error
                    if (completed.compareAndSet(false, true)) {
                        cancelIdleTimeout(idleTimeoutRef);
                        result.completeExceptionally(error);
                    }
                } else if (completed.get()) {
                    // Timeout already fired - drain response body to prevent resource leaks
                    EcsLogger.debug("com.artipie.http.TimeoutSlice")
                        .message("Draining response body after timeout: " + requestId)
                        .eventCategory("http")
                        .eventAction("body_drain")
                        .field("http.request.id", requestId)
                        .log();

                    drainBody(response);
                } else {
                    // Response headers received - wrap body with IDLE timeout streaming
                    // Timer will reset on each chunk, allowing large downloads to complete
                    final Content wrappedBody = wrapBodyWithIdleTimeout(
                        response.body(),
                        completed,
                        idleTimeoutRef,
                        this.timeoutSeconds,
                        startTime,
                        requestId
                    );
                    // Create new Response record with wrapped body
                    result.complete(new Response(response.status(), response.headers(), wrappedBody));
                }
            });

        return result;
    }

    /**
     * Drain response body to prevent resource leaks.
     * Called when timeout fires after response future completed but before body consumed.
     *
     * @param response Response to drain
     */
    private static void drainBody(final Response response) {
        if (response != null && response.body() != null) {
            response.body().asBytesFuture()
                .exceptionally(e -> {
                    // Drain silently - we don't care about the content
                    return new byte[0];
                });
        }
    }

    /**
     * Record timeout metric safely.
     *
     * @param phase Phase where timeout occurred (response_future, body_streaming)
     * @param elapsedMs Elapsed time in milliseconds
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void recordTimeoutMetric(final String phase, final long elapsedMs) {
        try {
            if (ArtipieMetrics.isEnabled() && MicrometerMetrics.isInitialized()) {
                MicrometerMetrics.getInstance().recordRequestTimeout(phase, elapsedMs);
            }
        } catch (final Exception e) {
            // Ignore metric errors
        }
    }

    /**
     * Wrap body content with IDLE timeout-aware streaming.
     *
     * <p>This is the key fix: the timeout continues to be enforced during body
     * streaming, not just during response future completion.</p>
     *
     * <p>IMPORTANT: This is an IDLE timeout - the timer resets on each chunk.
     * Large downloads can take 5+ minutes as long as data keeps flowing.
     * Timeout only fires if no data received for the configured duration.</p>
     *
     * @param original Original body content
     * @param completed Completion flag shared with timeout task
     * @param idleTimeoutRef Reference to current idle timeout task (gets reset on each chunk)
     * @param timeoutSeconds Idle timeout duration in seconds
     * @param startTime Request start time for metrics
     * @param requestId Request identifier for logging
     * @return Wrapped content with idle timeout tracking
     */
    private Content wrapBodyWithIdleTimeout(
        final Content original,
        final AtomicBoolean completed,
        final AtomicReference<ScheduledFuture<?>> idleTimeoutRef,
        final long timeoutSeconds,
        final long startTime,
        final String requestId
    ) {
        if (original == null) {
            cancelIdleTimeout(idleTimeoutRef);
            markIdleCompleted(completed, startTime, requestId, "empty_body");
            return null;
        }

        // Get size hint if available
        final Optional<Long> sizeHint = original.size();
        final AtomicLong bytesStreamed = new AtomicLong(0);
        final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

        // Wrap body with IDLE timeout - timer resets on each chunk
        final Flowable<ByteBuffer> wrappedBody = Flowable.fromPublisher(original)
            .doOnSubscribe(subscription -> {
                // CRITICAL: Reset timeout when body streaming STARTS (subscription begins)
                // This cancels the response-phase timeout and starts body-streaming timeout
                lastActivityTime.set(System.currentTimeMillis());
                resetIdleTimeout(idleTimeoutRef, completed, lastActivityTime, timeoutSeconds, requestId);
            })
            .doOnNext(buffer -> {
                // Check if timeout has fired - if so, abort streaming
                if (completed.get()) {
                    final long idleMs = System.currentTimeMillis() - lastActivityTime.get();
                    throw new TimeoutException("Body streaming aborted - idle timeout fired after " +
                        idleMs + "ms of no data (limit: " + timeoutSeconds + "s)");
                }

                // Data received - reset the idle timeout
                lastActivityTime.set(System.currentTimeMillis());
                resetIdleTimeout(idleTimeoutRef, completed, lastActivityTime, timeoutSeconds, requestId);

                bytesStreamed.addAndGet(buffer.remaining());
            })
            .doOnComplete(() -> {
                cancelIdleTimeout(idleTimeoutRef);
                markIdleCompleted(completed, startTime, requestId, "success");
                recordBodyStreamingMetric(bytesStreamed.get(), System.currentTimeMillis() - startTime, "success");
            })
            .doOnError(error -> {
                cancelIdleTimeout(idleTimeoutRef);
                markIdleCompleted(completed, startTime, requestId, "error");
                recordBodyStreamingMetric(bytesStreamed.get(), System.currentTimeMillis() - startTime, "error");

                // Check if this is a stream closed error (the incident root cause)
                if (isStreamClosedError(error)) {
                    recordStreamClosedMetric();
                }
            })
            .doOnCancel(() -> {
                cancelIdleTimeout(idleTimeoutRef);
                markIdleCompleted(completed, startTime, requestId, "cancelled");
                recordBodyStreamingMetric(bytesStreamed.get(), System.currentTimeMillis() - startTime, "cancelled");
            });

        return new Content.From(sizeHint, wrappedBody);
    }

    /**
     * Reset the idle timeout - cancel old task and schedule new one.
     * Called on each chunk received to implement idle timeout behavior.
     */
    private void resetIdleTimeout(
        final AtomicReference<ScheduledFuture<?>> idleTimeoutRef,
        final AtomicBoolean completed,
        final AtomicLong lastActivityTime,
        final long timeoutSeconds,
        final String requestId
    ) {
        // Cancel existing timeout
        final ScheduledFuture<?> oldTask = idleTimeoutRef.get();
        if (oldTask != null) {
            oldTask.cancel(false);
        }

        // Schedule new idle timeout
        final ScheduledFuture<?> newTask = SCHEDULER.schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                final long idleMs = System.currentTimeMillis() - lastActivityTime.get();
                EcsLogger.warn("com.artipie.http.TimeoutSlice")
                    .message("Idle timeout fired - no data for " + idleMs + "ms: " + requestId)
                    .eventCategory("http")
                    .eventAction("idle_timeout")
                    .eventOutcome("timeout")
                    .field("http.request.id", requestId)
                    .field("http.request.timeout", timeoutSeconds)
                    .duration(idleMs)
                    .log();

                recordTimeoutMetric("idle_timeout", idleMs);
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        idleTimeoutRef.set(newTask);
    }

    /**
     * Cancel idle timeout task.
     */
    private static void cancelIdleTimeout(final AtomicReference<ScheduledFuture<?>> idleTimeoutRef) {
        final ScheduledFuture<?> task = idleTimeoutRef.get();
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * Mark request as completed for idle timeout.
     */
    private static void markIdleCompleted(
        final AtomicBoolean completed,
        final long startTime,
        final String requestId,
        final String reason
    ) {
        if (completed.compareAndSet(false, true)) {
            final long elapsed = System.currentTimeMillis() - startTime;
            EcsLogger.debug("com.artipie.http.TimeoutSlice")
                .message("Body streaming completed (" + reason + "): " + requestId)
                .eventCategory("http")
                .eventAction("body_complete")
                .eventOutcome(reason)
                .field("http.request.id", requestId)
                .duration(elapsed)
                .log();
        }
    }

    /**
     * Mark request as completed and cancel timeout.
     *
     * @param completed Completion flag
     * @param timeoutTask Timeout task to cancel
     * @param startTime Request start time
     * @param requestId Request identifier
     * @param reason Completion reason for logging
     */
    private static void markCompleted(
        final AtomicBoolean completed,
        final ScheduledFuture<?> timeoutTask,
        final long startTime,
        final String requestId,
        final String reason
    ) {
        if (completed.compareAndSet(false, true)) {
            timeoutTask.cancel(false);

            final long elapsed = System.currentTimeMillis() - startTime;
            EcsLogger.debug("com.artipie.http.TimeoutSlice")
                .message("Body streaming completed (" + reason + "): " + requestId)
                .eventCategory("http")
                .eventAction("body_complete")
                .eventOutcome(reason)
                .field("http.request.id", requestId)
                .duration(elapsed)
                .log();
        }
    }

    /**
     * Check if error is a stream closed error (HttpClosedException).
     *
     * @param error Error to check
     * @return true if stream closed error
     */
    private static boolean isStreamClosedError(final Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            final String message = cause.getMessage();
            final String className = cause.getClass().getName();
            if ((message != null && message.contains("Stream was closed")) ||
                className.contains("HttpClosedException")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Record body streaming metric safely.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void recordBodyStreamingMetric(final long bytes, final long durationMs, final String result) {
        try {
            if (ArtipieMetrics.isEnabled() && MicrometerMetrics.isInitialized()) {
                MicrometerMetrics.getInstance().recordBodyStreaming(bytes, durationMs, result);
            }
        } catch (final Exception e) {
            // Ignore metric errors
        }
    }

    /**
     * Record stream closed metric (the incident trigger).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void recordStreamClosedMetric() {
        try {
            if (ArtipieMetrics.isEnabled() && MicrometerMetrics.isInitialized()) {
                MicrometerMetrics.getInstance().recordStreamClosed();
            }
        } catch (final Exception e) {
            // Ignore metric errors
        }
    }
}
