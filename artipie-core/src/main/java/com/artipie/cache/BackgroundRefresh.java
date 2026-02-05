/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.http.log.EcsLogger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background refresh service for metadata caching.
 * <p>
 * Handles async refresh of metadata in the background without blocking
 * the request that triggered the refresh.
 * </p>
 *
 * @since 1.0
 */
public final class BackgroundRefresh implements AutoCloseable {

    /**
     * Executor for background refresh tasks.
     */
    private final Executor executor;

    /**
     * In-flight refresh tasks (prevents duplicate refreshes for same key).
     */
    private final ConcurrentMap<String, CompletableFuture<?>> inFlight;

    /**
     * Whether this service owns the executor (should shut it down on close).
     */
    private final boolean ownsExecutor;

    /**
     * Whether the service is closed.
     */
    private final AtomicBoolean closed;

    /**
     * Create with default executor (cached thread pool).
     */
    public BackgroundRefresh() {
        this(Executors.newCachedThreadPool(r -> {
            final Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("artipie-cache-refresh-" + thread.getId());
            return thread;
        }), true);
    }

    /**
     * Create with custom executor.
     *
     * @param executor Executor for background tasks
     */
    public BackgroundRefresh(final Executor executor) {
        this(executor, false);
    }

    /**
     * Private constructor.
     *
     * @param executor Executor for background tasks
     * @param ownsExecutor Whether this instance owns the executor
     */
    private BackgroundRefresh(final Executor executor, final boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.inFlight = new ConcurrentHashMap<>();
        this.closed = new AtomicBoolean(false);
    }

    /**
     * Trigger background refresh for a key.
     * <p>
     * If a refresh is already in progress for this key, this call is ignored
     * to prevent duplicate work.
     * </p>
     *
     * @param key Cache key
     * @param task Refresh task that fetches and saves new content
     */
    public void refresh(
        final Key key,
        final RefreshTask task
    ) {
        if (this.closed.get()) {
            EcsLogger.warn("com.artipie.cache")
                .message("BackgroundRefresh is closed, ignoring refresh request")
                .eventCategory("cache")
                .eventAction("refresh_skip")
                .field("url.path", key.string())
                .log();
            return;
        }

        final String keyPath = key.string();

        // Skip if refresh already in progress
        if (this.inFlight.containsKey(keyPath)) {
            EcsLogger.debug("com.artipie.cache")
                .message("Refresh already in progress, skipping")
                .eventCategory("cache")
                .eventAction("refresh_skip")
                .field("url.path", keyPath)
                .log();
            return;
        }

        // Create refresh future
        final CompletableFuture<Void> future = new CompletableFuture<>();

        // Try to add to in-flight (only one can succeed)
        final CompletableFuture<?> existing = this.inFlight.putIfAbsent(keyPath, future);
        if (existing != null) {
            // Another thread beat us to it
            return;
        }

        EcsLogger.debug("com.artipie.cache")
            .message("Starting background refresh")
            .eventCategory("cache")
            .eventAction("refresh_start")
            .field("url.path", keyPath)
            .log();

        // Run refresh in background
        CompletableFuture.runAsync(() -> {
            try {
                task.execute()
                    .whenComplete((result, error) -> {
                        this.inFlight.remove(keyPath);

                        if (error != null) {
                            EcsLogger.warn("com.artipie.cache")
                                .message("Background refresh failed")
                                .eventCategory("cache")
                                .eventAction("refresh_complete")
                                .eventOutcome("failure")
                                .field("url.path", keyPath)
                                .error(error)
                                .log();
                            future.completeExceptionally(error);
                        } else {
                            EcsLogger.debug("com.artipie.cache")
                                .message("Background refresh completed")
                                .eventCategory("cache")
                                .eventAction("refresh_complete")
                                .eventOutcome("success")
                                .field("url.path", keyPath)
                                .log();
                            future.complete(null);
                        }
                    });
            } catch (final Exception ex) {
                this.inFlight.remove(keyPath);
                EcsLogger.warn("com.artipie.cache")
                    .message("Background refresh failed to start")
                    .eventCategory("cache")
                    .eventAction("refresh_complete")
                    .eventOutcome("failure")
                    .field("url.path", keyPath)
                    .error(ex)
                    .log();
                future.completeExceptionally(ex);
            }
        }, this.executor);
    }

    /**
     * Check if a refresh is in progress for a key.
     *
     * @param key Cache key
     * @return true if refresh is in progress
     */
    public boolean isRefreshing(final Key key) {
        return this.inFlight.containsKey(key.string());
    }

    /**
     * Get number of in-flight refresh operations.
     *
     * @return Number of refreshes in progress
     */
    public int inFlightCount() {
        return this.inFlight.size();
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            EcsLogger.info("com.artipie.cache")
                .message(String.format("Closing BackgroundRefresh service (in_flight_count=%d)", this.inFlight.size()))
                .eventCategory("cache")
                .eventAction("service_close")
                .log();

            // Cancel all in-flight operations
            this.inFlight.values().forEach(future -> future.cancel(true));
            this.inFlight.clear();

            // Shutdown executor if we own it
            if (this.ownsExecutor && this.executor instanceof java.util.concurrent.ExecutorService) {
                ((java.util.concurrent.ExecutorService) this.executor).shutdown();
            }
        }
    }

    /**
     * Refresh task interface.
     */
    @FunctionalInterface
    public interface RefreshTask {
        /**
         * Execute the refresh task.
         *
         * @return Future completing when refresh is done
         */
        CompletableFuture<Optional<Content>> execute();
    }
}
