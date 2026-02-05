/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.stream;

import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A ReadStream that duplicates data to two WriteStream destinations.
 * Implements proper backpressure: pauses source if EITHER destination is full,
 * resumes only when BOTH destinations are ready.
 *
 * <p>If the secondary destination fails, it can be detached and streaming
 * continues to the primary destination only.</p>
 *
 * @param <T> The data type (typically Buffer)
 * @since 1.21.0
 */
public final class TeeReadStream<T> implements ReadStream<T> {

    /**
     * Source stream.
     */
    private final ReadStream<T> source;

    /**
     * Primary destination (e.g., client response).
     */
    private final WriteStream<T> primary;

    /**
     * Secondary destination (e.g., cache file).
     */
    private final WriteStream<T> secondary;

    /**
     * Whether secondary is detached due to error.
     */
    private final AtomicBoolean secondaryDetached;

    /**
     * Whether primary destination is ready for more data.
     */
    private volatile boolean primaryReady;

    /**
     * Whether secondary destination is ready for more data.
     */
    private volatile boolean secondaryReady;

    /**
     * Whether source is currently paused.
     */
    private final AtomicBoolean paused;

    /**
     * Handler for data events.
     */
    private Handler<T> dataHandler;

    /**
     * Handler for end event.
     */
    private Handler<Void> endHandler;

    /**
     * Handler for exceptions.
     */
    private Handler<Throwable> exceptionHandler;

    /**
     * Constructor.
     *
     * @param source Source stream to read from
     * @param primary Primary destination (always receives data)
     * @param secondary Secondary destination (can be detached on error)
     */
    public TeeReadStream(
        final ReadStream<T> source,
        final WriteStream<T> primary,
        final WriteStream<T> secondary
    ) {
        this.source = Objects.requireNonNull(source);
        this.primary = Objects.requireNonNull(primary);
        this.secondary = Objects.requireNonNull(secondary);
        this.secondaryDetached = new AtomicBoolean(false);
        this.primaryReady = true;
        this.secondaryReady = true;
        this.paused = new AtomicBoolean(false);
    }

    /**
     * Start streaming. Sets up handlers and begins flow.
     *
     * @return this
     */
    public TeeReadStream<T> start() {
        // Set up drain handlers for backpressure
        this.primary.drainHandler(v -> {
            this.primaryReady = true;
            this.maybeResume();
        });

        this.secondary.drainHandler(v -> {
            this.secondaryReady = true;
            this.maybeResume();
        });

        // Set up source handlers
        this.source.handler(this::handleData);
        this.source.endHandler(this::handleEnd);
        this.source.exceptionHandler(this::handleException);

        return this;
    }

    /**
     * Detach secondary destination. Data will only flow to primary.
     * Used when cache write fails but client should still receive data.
     */
    public void detachSecondary() {
        this.secondaryDetached.set(true);
        this.secondaryReady = true; // Always "ready" when detached
        this.maybeResume();
    }

    /**
     * Check if secondary is detached.
     *
     * @return true if secondary is detached
     */
    public boolean isSecondaryDetached() {
        return this.secondaryDetached.get();
    }

    @Override
    public TeeReadStream<T> handler(final Handler<T> handler) {
        this.dataHandler = handler;
        return this;
    }

    @Override
    public TeeReadStream<T> pause() {
        this.source.pause();
        this.paused.set(true);
        return this;
    }

    @Override
    public TeeReadStream<T> resume() {
        this.paused.set(false);
        this.source.resume();
        return this;
    }

    @Override
    public TeeReadStream<T> fetch(final long amount) {
        this.source.fetch(amount);
        return this;
    }

    @Override
    public TeeReadStream<T> endHandler(final Handler<Void> handler) {
        this.endHandler = handler;
        return this;
    }

    @Override
    public TeeReadStream<T> exceptionHandler(final Handler<Throwable> handler) {
        this.exceptionHandler = handler;
        return this;
    }

    /**
     * Handle incoming data from source.
     *
     * @param data The data chunk
     */
    private void handleData(final T data) {
        // Always write to primary
        this.primary.write(data);

        // Write to secondary if not detached
        if (!this.secondaryDetached.get()) {
            this.secondary.write(data);
        }

        // Check backpressure
        final boolean primaryFull = this.primary.writeQueueFull();
        final boolean secondaryFull = !this.secondaryDetached.get()
            && this.secondary.writeQueueFull();

        if (primaryFull) {
            this.primaryReady = false;
        }
        if (secondaryFull) {
            this.secondaryReady = false;
        }

        if (primaryFull || secondaryFull) {
            this.source.pause();
            this.paused.set(true);
        }

        // Forward to downstream handler
        if (this.dataHandler != null) {
            this.dataHandler.handle(data);
        }
    }

    /**
     * Handle end of stream.
     *
     * @param ignored Void
     */
    private void handleEnd(final Void ignored) {
        if (this.endHandler != null) {
            this.endHandler.handle(null);
        }
    }

    /**
     * Handle exception from source.
     *
     * @param error The error
     */
    private void handleException(final Throwable error) {
        if (this.exceptionHandler != null) {
            this.exceptionHandler.handle(error);
        }
    }

    /**
     * Resume source if both destinations are ready.
     */
    private void maybeResume() {
        if (this.paused.get() && this.primaryReady && this.secondaryReady) {
            this.paused.set(false);
            this.source.resume();
        }
    }
}
