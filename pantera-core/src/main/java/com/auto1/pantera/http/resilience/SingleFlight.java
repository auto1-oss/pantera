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
package com.auto1.pantera.http.resilience;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Unified per-key request coalescer — one {@code loader.get()} invocation per
 * concurrent burst of {@link #load} calls sharing the same key.
 *
 * <p>Consolidates the three hand-rolled coalescers that lived in {@code
 * GroupSlice.inFlightFanouts}, {@code MavenGroupSlice.inFlightMetadataFetches},
 * and the legacy cache-write in-flight map into one Caffeine-backed
 * implementation. See §6.4 of {@code docs/analysis/v2.2-target-architecture.md}
 * and anti-patterns A6, A7, A8, A9 in {@code v2.1.3-architecture-review.md}.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>Coalescing.</b> N concurrent {@code load(k, loader)} calls for the
 *       same key invoke {@code loader.get()} exactly once; all N callers receive
 *       the same terminal value or exception.</li>
 *   <li><b>Fresh-after-complete.</b> On loader completion (normal or exceptional)
 *       the entry is invalidated so the next {@link #load} for that key triggers
 *       a fresh fetch — the cache holds <em>in-flight</em> state, never results.</li>
 *   <li><b>Zombie eviction.</b> An entry that never completes is evicted by
 *       Caffeine's {@code expireAfterWrite(inflightTtl)}; the next {@link #load}
 *       starts a fresh loader. Closes A8.</li>
 *   <li><b>Exception propagation.</b> When the loader completes exceptionally,
 *       every waiting caller receives the same exception; the entry is still
 *       invalidated so the next {@link #load} retries.</li>
 *   <li><b>No call-site throw.</b> {@link #load} never throws — loader failures
 *       surface only inside the returned {@link CompletableFuture}.</li>
 *   <li><b>Cancellation isolation.</b> Cancelling one caller's returned future
 *       never cancels the loader or other callers' futures. The loader runs to
 *       completion regardless of caller cancellation.</li>
 *   <li><b>Stack-flat completion.</b> Followers receive completion on the
 *       configured {@code executor}, never on the leader's stack — fixes the
 *       v2.1.3 regression where {@code GroupSlice.inFlightFanouts} blew the
 *       stack at ~400 synchronously-completing followers (commit {@code ccc155f6}).</li>
 * </ul>
 *
 * <h2>Implementation notes</h2>
 *
 * Caffeine's {@link AsyncCache#get(Object, java.util.function.BiFunction)} is
 * atomic per-key: exactly one bifunction invocation observes an absent mapping
 * and installs the loader's future; concurrent callers join the same future.
 * We wrap that shared future per caller so that (a) caller-side cancellation
 * cannot cancel the loader, (b) completion is dispatched via the executor
 * rather than synchronously on the leader's stack.
 *
 * @param <K> Key type.
 * @param <V> Value type returned by the loader.
 * @since 2.2.0
 */
public final class SingleFlight<K, V> {

    /**
     * Caffeine async cache of in-flight loads. Entries are bounded by {@code
     * maxInFlight} and expire after {@code inflightTtl} once the loader
     * future completes (Caffeine does not apply {@code expireAfterWrite} to
     * pending futures). Zombie protection for <em>non</em>-completing loaders
     * is provided separately via {@link CompletableFuture#orTimeout(long,
     * TimeUnit)} on the wrapped loader future — see {@link #load}.
     *
     * <p>The cache is populated exclusively via {@link AsyncCache#get(Object,
     * java.util.function.BiFunction)} — never via a loading cache — so a
     * {@code get} without a loader would throw. That is by design: this cache
     * holds <em>in-flight work</em>, not a key/value store.
     */
    private final AsyncCache<K, V> cache;

    /**
     * Executor used for stack-flat completion of waiters. All completions
     * (both the raw Caffeine future's and the per-caller forwarders) hop to
     * this executor so a synchronously-completing loader never runs a
     * follower's callback on its own stack.
     */
    private final Executor executor;

    /**
     * Zombie-protection timeout. A loader whose future is still pending after
     * this duration is force-completed with {@link java.util.concurrent.TimeoutException}
     * via {@link CompletableFuture#orTimeout(long, TimeUnit)}, which in turn
     * triggers the {@code whenCompleteAsync(invalidate)} hook and frees the
     * cache slot. This closes A8.
     */
    private final Duration inflightTtl;

    /**
     * Create a single-flight coalescer.
     *
     * @param inflightTtl Maximum time an in-flight entry may remain in the
     *                    cache. Entries older than this are evicted by
     *                    Caffeine's time-based expiry — acts as zombie
     *                    protection for loaders that never complete.
     * @param maxInFlight Maximum number of distinct in-flight keys. When
     *                    exceeded, Caffeine evicts the least-recently-used
     *                    entry. Existing waiters on an evicted entry still
     *                    receive their value from the underlying loader
     *                    future — eviction only prevents coalescing of
     *                    <em>future</em> calls for that key.
     * @param executor    Executor used for stack-flat follower completion.
     *                    Must not be {@code null}. For a server context this
     *                    is typically the common worker pool or a dedicated
     *                    {@code ForkJoinPool}.
     */
    public SingleFlight(
        final Duration inflightTtl,
        final int maxInFlight,
        final Executor executor
    ) {
        Objects.requireNonNull(inflightTtl, "inflightTtl");
        Objects.requireNonNull(executor, "executor");
        if (inflightTtl.isNegative() || inflightTtl.isZero()) {
            throw new IllegalArgumentException(
                "inflightTtl must be strictly positive: " + inflightTtl
            );
        }
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException(
                "maxInFlight must be strictly positive: " + maxInFlight
            );
        }
        this.executor = executor;
        this.inflightTtl = inflightTtl;
        this.cache = Caffeine.newBuilder()
            // expireAfterWrite applies only to COMPLETED futures in an
            // AsyncCache. Pending zombies are bounded by orTimeout (see
            // #load), not by this policy.
            .expireAfterWrite(inflightTtl)
            .maximumSize(maxInFlight)
            .executor(executor)
            .buildAsync();
    }

    /**
     * Load-or-join: concurrent calls for the same key share one
     * {@code loader.get()} invocation.
     *
     * <p>The returned future is independent of the shared loader future:
     * cancelling it never cancels the loader. Downstream {@code thenCompose}
     * / {@code whenComplete} callbacks attached to it run on the configured
     * executor, not on the leader's stack.
     *
     * @param key    Non-null coalescing key.
     * @param loader Supplier invoked exactly once per concurrent burst for
     *               {@code key}. Must return a non-null {@link CompletionStage}.
     *               Exceptions thrown synchronously by the supplier are
     *               propagated as an exceptionally-completed future.
     * @return A new {@link CompletableFuture} completing with the loader's
     *         value or exception on the configured executor.
     */
    public CompletableFuture<V> load(
        final K key,
        final Supplier<CompletionStage<V>> loader
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        final long ttlMillis = this.inflightTtl.toMillis();
        final CompletableFuture<V> shared = this.cache.get(
            key,
            (k, e) -> {
                final CompletableFuture<V> source;
                try {
                    source = loader.get().toCompletableFuture();
                } catch (final RuntimeException ex) {
                    final CompletableFuture<V> failed = new CompletableFuture<>();
                    failed.completeExceptionally(ex);
                    return failed;
                }
                // Zombie eviction: a loader whose future is still pending
                // after ttlMillis is force-completed with TimeoutException.
                // We wrap in a NEW CompletableFuture so the caller's original
                // future (if they hold a reference to it) is not mutated.
                // The wrapper propagates the source's terminal state when
                // available; otherwise orTimeout fires and the wrapper
                // completes exceptionally. Either way the
                // whenCompleteAsync(invalidate) hook frees the cache slot.
                final CompletableFuture<V> wrapped = new CompletableFuture<>();
                source.whenComplete((value, err) -> {
                    if (err != null) {
                        wrapped.completeExceptionally(err);
                    } else {
                        wrapped.complete(value);
                    }
                });
                wrapped.orTimeout(ttlMillis, TimeUnit.MILLISECONDS);
                return wrapped;
            }
        );
        shared.whenCompleteAsync(
            (value, err) -> this.cache.synchronous().invalidate(key),
            this.executor
        );
        final CompletableFuture<V> forwarded = new CompletableFuture<>();
        shared.whenCompleteAsync(
            (value, err) -> {
                if (err != null) {
                    forwarded.completeExceptionally(err);
                } else {
                    forwarded.complete(value);
                }
            },
            this.executor
        );
        return forwarded;
    }

    /**
     * Evict an in-flight entry for {@code key} without completing it.
     *
     * <p>Does not cancel any already-dispatched loader — the loader's future
     * continues to completion, but the next {@link #load} for the same key
     * invokes a fresh loader rather than joining the previous one.
     *
     * @param key Key to evict. May be {@code null}: a no-op in that case.
     */
    public void invalidate(final K key) {
        if (key != null) {
            this.cache.synchronous().invalidate(key);
        }
    }

    /**
     * Current number of in-flight entries. Intended for metrics and tests.
     *
     * <p>The estimate is eventually consistent — concurrent completions may
     * race with this read. Caffeine recommends
     * {@code cache.synchronous().estimatedSize()} for monotonic bounds; we
     * expose it as {@code inFlightCount} for parity with the legacy
     * coalescer APIs.
     *
     * @return Approximate count of distinct keys currently in-flight.
     */
    public int inFlightCount() {
        return (int) this.cache.synchronous().estimatedSize();
    }
}
