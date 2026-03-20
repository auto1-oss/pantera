/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.misc.ConfigDefaults;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Deduplicates concurrent requests for the same cache key.
 *
 * <p>When multiple clients request the same artifact simultaneously, only one
 * upstream fetch is performed. Other callers either wait for the signal (SIGNAL
 * strategy) or are coalesced at the storage level (STORAGE strategy).
 *
 * <p>With SIGNAL strategy (default):
 * <ul>
 *   <li>First request: executes the supplier, signals result on completion</li>
 *   <li>Waiting requests: receive the same signal (SUCCESS, NOT_FOUND, ERROR)</li>
 *   <li>After completion: entry is removed from in-flight map</li>
 * </ul>
 *
 * <p>With NONE strategy, every call immediately delegates to the supplier.
 *
 * @since 1.20.13
 */
public final class RequestDeduplicator implements AutoCloseable {

    /**
     * Maximum age of an in-flight entry before it's considered zombie (5 minutes).
     * Configurable via ARTIPIE_DEDUP_MAX_AGE_MS environment variable.
     */
    private static final long MAX_AGE_MS =
        ConfigDefaults.getLong("ARTIPIE_DEDUP_MAX_AGE_MS", 300_000L);

    /**
     * Maps cache key to the in-flight fetch entry (future + creation time).
     */
    private final ConcurrentHashMap<Key, InFlightEntry> inFlight;

    /**
     * Strategy to use.
     */
    private final DedupStrategy strategy;

    /**
     * Cleanup scheduler.
     */
    private final java.util.concurrent.ScheduledExecutorService cleanup;

    /**
     * Ctor.
     * @param strategy Dedup strategy
     */
    public RequestDeduplicator(final DedupStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.inFlight = new ConcurrentHashMap<>();
        this.cleanup = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "dedup-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.cleanup.scheduleAtFixedRate(this::evictStale, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Execute a fetch with deduplication.
     *
     * <p>If a fetch for the same key is already in progress and strategy is SIGNAL,
     * this call returns a future that completes when the existing fetch completes.
     *
     * @param key Cache key identifying the artifact
     * @param fetcher Supplier that performs the actual upstream fetch.
     *                Must complete the returned future with a FetchSignal.
     * @return Future with the fetch signal (SUCCESS, NOT_FOUND, or ERROR)
     */
    public CompletableFuture<FetchSignal> deduplicate(
        final Key key,
        final Supplier<CompletableFuture<FetchSignal>> fetcher
    ) {
        if (this.strategy == DedupStrategy.NONE || this.strategy == DedupStrategy.STORAGE) {
            return fetcher.get();
        }
        final CompletableFuture<FetchSignal> fresh = new CompletableFuture<>();
        final InFlightEntry freshEntry = new InFlightEntry(fresh, System.currentTimeMillis());
        final InFlightEntry existing = this.inFlight.putIfAbsent(key, freshEntry);
        if (existing != null) {
            return existing.future;
        }
        fetcher.get().whenComplete((signal, err) -> {
            this.inFlight.remove(key);
            if (err != null) {
                fresh.complete(FetchSignal.ERROR);
            } else {
                fresh.complete(signal);
            }
        });
        return fresh;
    }

    /**
     * Get the number of currently in-flight requests. For monitoring.
     * @return Count of in-flight dedup entries
     */
    public int inFlightCount() {
        return this.inFlight.size();
    }

    /**
     * Remove entries that have been in-flight for too long (zombie protection).
     */
    private void evictStale() {
        final long now = System.currentTimeMillis();
        this.inFlight.entrySet().removeIf(entry -> {
            if (now - entry.getValue().createdAt > MAX_AGE_MS) {
                entry.getValue().future.complete(FetchSignal.ERROR);
                return true;
            }
            return false;
        });
    }

    /**
     * Shuts down the cleanup scheduler and completes all in-flight entries with ERROR.
     * Should be called when the deduplicator is no longer needed.
     */
    @Override
    public void close() {
        this.cleanup.shutdownNow();
        this.inFlight.values().forEach(
            entry -> entry.future.complete(FetchSignal.ERROR)
        );
        this.inFlight.clear();
    }

    /**
     * Alias for {@link #close()}, for explicit lifecycle management.
     */
    public void shutdown() {
        this.close();
    }

    /**
     * In-flight entry tracking future and creation time.
     */
    private static final class InFlightEntry {
        /**
         * The future for the in-flight fetch.
         */
        final CompletableFuture<FetchSignal> future;

        /**
         * Timestamp when this entry was created.
         */
        final long createdAt;

        /**
         * Ctor.
         * @param future The future for the in-flight fetch
         * @param createdAt Timestamp when this entry was created
         */
        InFlightEntry(final CompletableFuture<FetchSignal> future, final long createdAt) {
            this.future = future;
            this.createdAt = createdAt;
        }
    }

    /**
     * Signal indicating the outcome of a deduplicated fetch.
     *
     * @since 1.20.13
     */
    public enum FetchSignal {
        /**
         * Upstream returned 200 and content is now cached in storage.
         * Waiting callers should read from cache.
         */
        SUCCESS,

        /**
         * Upstream returned 404. Negative cache has been updated.
         * Waiting callers should return 404.
         */
        NOT_FOUND,

        /**
         * Upstream returned an error (5xx, timeout, exception).
         * Waiting callers should return 503 or fall back to stale cache.
         */
        ERROR
    }
}
