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
package com.auto1.pantera.http.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Generic stale-while-revalidate (SWR) metadata cache primitive.
 *
 * <p>Encapsulates the SWR pattern used by metadata files that change infrequently but
 * must remain reasonably current (Maven {@code maven-metadata.xml}, Composer
 * {@code packages.json}, PyPI simple index pages, npm packument tips, etc.). The
 * primitive is parameterised on both the cache key type {@code K} and the value type
 * {@code V} so each adapter can re-use the same logic instead of reinventing it.
 *
 * <h2>Three-state TTL contract</h2>
 *
 * <p>Every entry has two thresholds derived from its write timestamp:
 *
 * <ul>
 *   <li><b>Fresh</b> — entry age &lt;= {@code softTtl}. The cached value is returned
 *       immediately. No upstream call is made. No background work scheduled.</li>
 *   <li><b>Soft-stale</b> — {@code softTtl} &lt; age &lt;= {@code hardTtl}. The cached
 *       value is returned immediately (a stale read is always cheaper than a network
 *       hop), AND a background refresh is scheduled to repopulate the entry before the
 *       hard window elapses. The caller's response is never blocked on the refresh —
 *       this is the whole point of SWR: client latency stays constant while staleness
 *       is bounded.</li>
 *   <li><b>Hard-stale or absent</b> — age &gt; {@code hardTtl} (or no entry). Treated as
 *       a true cache miss: the loader is invoked synchronously and the caller's future
 *       only completes when the loader resolves. This is the only path that blocks on
 *       upstream, and it runs at most once per (key, hardTtl) window.</li>
 * </ul>
 *
 * <h2>Single-flight refresh (thundering-herd protection)</h2>
 *
 * <p>When many requests for the same key cross the soft-TTL boundary together, a naive
 * implementation would spawn one background refresh per request — the so-called
 * thundering-herd problem that defeats the whole point of caching. {@code
 * SwrMetadataCache} avoids this with a {@link ConcurrentHashMap.KeySetView} of keys
 * currently being refreshed: {@code refreshing.add(key)} returns {@code true} only for
 * the first arrival; later arrivals see {@code false} and skip the loader entirely. The
 * leader removes the key from the set in a {@code whenComplete} hook after the loader
 * resolves (whether successfully or exceptionally), so the next soft-stale read can
 * trigger a fresh refresh.
 *
 * <h2>Why TTL is enforced manually (not via {@code expireAfterWrite})</h2>
 *
 * <p>Caffeine's {@code expireAfterWrite} would remove the entry the moment it crossed
 * the configured TTL, which collapses the soft and hard stages into a single
 * fail-closed boundary and forces every subsequent caller to block on the loader until
 * the refresh completes. SWR specifically wants soft-stale reads to succeed against
 * the old value while the refresh runs in the background, so we keep the entry in
 * Caffeine indefinitely (subject only to {@code maximumSize} eviction) and inspect the
 * {@code writtenAt} timestamp on every read.
 *
 * <h2>Thread model</h2>
 *
 * <p>The background refresh is dispatched via {@link CompletableFuture#runAsync} on the
 * common fork-join pool. The Vert.x event loop must never block, so callers must use
 * {@link #get(Object, Supplier)}'s returned future and never call {@code .get()} or
 * {@code .join()} on it from an event-loop thread.
 *
 * <h2>Metrics</h2>
 *
 * <p>When a {@link MeterRegistry} is supplied, three counters are emitted, tagged with
 * the {@code cacheName} passed to the constructor:
 *
 * <ul>
 *   <li>{@code pantera_metadata_swr_hit_fresh} — read served from cache, no refresh.</li>
 *   <li>{@code pantera_metadata_swr_hit_stale} — read served from cache, background
 *       refresh scheduled (note: incremented for every soft-stale read, not just the
 *       leader, so dividing by the loader-invocation count gives the average dedup
 *       fan-in).</li>
 *   <li>{@code pantera_metadata_swr_miss} — loader awaited synchronously (absent or
 *       hard-stale).</li>
 * </ul>
 *
 * <p>If the registry is {@code null}, metrics are silently skipped — useful for unit
 * tests and lightweight embedding.
 *
 * @param <K> cache key type (anything with a sensible {@code equals}/{@code hashCode})
 * @param <V> cached value type
 * @since 2.2.0
 */
public final class SwrMetadataCache<K, V> {

    /**
     * Soft TTL: reads past this age trigger background refresh but still return cached value.
     */
    private final Duration softTtl;

    /**
     * Hard TTL: reads past this age block on the loader (treated as a miss).
     */
    private final Duration hardTtl;

    /**
     * Caffeine-backed L1 store. Window TinyLFU eviction; size-bounded only. TTL is
     * enforced manually against {@link Entry#writtenAt} so we can distinguish soft from
     * hard expiry.
     */
    private final Cache<K, Entry<V>> store;

    /**
     * Keys whose background refresh is currently in flight. Used to dedup concurrent
     * soft-stale reads down to a single loader invocation.
     */
    private final ConcurrentHashMap.KeySetView<K, Boolean> refreshing;

    /**
     * Counter for fresh-window cache hits (or {@code null} when no registry).
     */
    private final Counter hitFresh;

    /**
     * Counter for soft-stale hits that trigger background refresh (or {@code null}).
     */
    private final Counter hitStale;

    /**
     * Counter for synchronous loader invocations: hard-stale or absent (or {@code null}).
     */
    private final Counter miss;

    /**
     * Create an SWR cache with no metrics.
     *
     * @param softTtl soft TTL (returns cached, schedules background refresh past this)
     * @param hardTtl hard TTL (treats entry as miss past this; must be &gt;= soft)
     * @param maxSize Caffeine {@code maximumSize} for the L1 store
     */
    public SwrMetadataCache(final Duration softTtl, final Duration hardTtl, final int maxSize) {
        this(softTtl, hardTtl, maxSize, null, "default");
    }

    /**
     * Create an SWR cache with optional Micrometer instrumentation.
     *
     * @param softTtl soft TTL (returns cached, schedules background refresh past this)
     * @param hardTtl hard TTL (treats entry as miss past this; must be &gt;= soft)
     * @param maxSize Caffeine {@code maximumSize} for the L1 store
     * @param metrics meter registry for counters; {@code null} disables metrics
     * @param cacheName tag value applied to every counter (e.g. {@code "maven-metadata"})
     */
    public SwrMetadataCache(
        final Duration softTtl,
        final Duration hardTtl,
        final int maxSize,
        final MeterRegistry metrics,
        final String cacheName
    ) {
        if (softTtl == null || hardTtl == null) {
            throw new IllegalArgumentException("softTtl and hardTtl must be non-null");
        }
        if (hardTtl.compareTo(softTtl) < 0) {
            throw new IllegalArgumentException(
                "hardTtl must be >= softTtl (got soft=" + softTtl + ", hard=" + hardTtl + ")"
            );
        }
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive (got " + maxSize + ")");
        }
        this.softTtl = softTtl;
        this.hardTtl = hardTtl;
        // No expireAfterWrite: TTL is enforced manually so soft vs hard are distinguishable.
        this.store = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .recordStats()
            .build();
        this.refreshing = ConcurrentHashMap.newKeySet();
        if (metrics == null) {
            this.hitFresh = null;
            this.hitStale = null;
            this.miss = null;
        } else {
            final String name = cacheName == null ? "default" : cacheName;
            final Tags tags = Tags.of("cache", name);
            this.hitFresh = Counter.builder("pantera_metadata_swr_hit_fresh")
                .tags(tags).register(metrics);
            this.hitStale = Counter.builder("pantera_metadata_swr_hit_stale")
                .tags(tags).register(metrics);
            this.miss = Counter.builder("pantera_metadata_swr_miss")
                .tags(tags).register(metrics);
        }
    }

    /**
     * Read a cached value or invoke the loader, honoring the SWR contract.
     *
     * <p>The loader is a {@link Supplier} (not a direct {@link CompletableFuture}) so
     * it is only evaluated when actually needed — fresh hits never construct a fetch
     * future at all. The loader's result is wrapped in {@link Optional} so callers can
     * represent upstream-404 (empty optional) distinctly from "value present"; an empty
     * optional is cached just like any other value, so subsequent reads within the
     * soft window are served immediately without re-fetching.
     *
     * @param key cache key; must be non-null
     * @param loader supplier of the upstream fetch; invoked once per (key, hardTtl)
     *               window on the miss path, and once per (key, soft-stale event) on
     *               the soft-stale path (deduped across concurrent callers)
     * @return future resolving to the cached or freshly-loaded value
     */
    public CompletableFuture<Optional<V>> get(
        final K key,
        final Supplier<CompletableFuture<Optional<V>>> loader
    ) {
        final Entry<V> cached = this.store.getIfPresent(key);
        if (cached == null) {
            return this.loadSynchronously(key, loader);
        }
        final Duration age = Duration.between(cached.writtenAt, Instant.now());
        if (age.compareTo(this.softTtl) <= 0) {
            // Fresh hit: serve cached, no upstream interaction.
            if (this.hitFresh != null) {
                this.hitFresh.increment();
            }
            return CompletableFuture.completedFuture(cached.value);
        }
        if (age.compareTo(this.hardTtl) > 0) {
            // Hard-stale: treat as miss, await loader.
            return this.loadSynchronously(key, loader);
        }
        // Soft-stale: serve cached AND schedule single-flight background refresh.
        if (this.hitStale != null) {
            this.hitStale.increment();
        }
        this.scheduleBackgroundRefresh(key, loader);
        return CompletableFuture.completedFuture(cached.value);
    }

    /**
     * Invalidate a specific cache entry. Any in-flight background refresh for the same
     * key is left to complete (its result will simply repopulate the entry).
     *
     * @param key the key to remove
     */
    public void invalidate(final K key) {
        this.store.invalidate(key);
    }

    /**
     * Drop every entry from the cache. Useful for tests and operational reset.
     */
    public void clear() {
        this.store.invalidateAll();
    }

    /**
     * Approximate number of cached entries (Caffeine's {@code estimatedSize}).
     *
     * @return entry count, eventually consistent
     */
    public long size() {
        return this.store.estimatedSize();
    }

    /**
     * Synchronous-loader path: invoked on absent and hard-stale reads. Caches the
     * loader's result on success and clears the entry on failure so the next call
     * re-attempts the upstream fetch instead of serving a stale value past hardTtl.
     */
    private CompletableFuture<Optional<V>> loadSynchronously(
        final K key,
        final Supplier<CompletableFuture<Optional<V>>> loader
    ) {
        if (this.miss != null) {
            this.miss.increment();
        }
        return loader.get().whenComplete((value, error) -> {
            if (error == null && value != null) {
                this.store.put(key, new Entry<>(value, Instant.now()));
            } else {
                this.store.invalidate(key);
            }
        });
    }

    /**
     * Soft-stale path: enqueue one background refresh per key. Concurrent callers that
     * lose the {@code refreshing.add} race exit immediately — only the leader runs the
     * loader. The leader writes the result back into the store and clears itself from
     * the {@code refreshing} set so the next soft-stale window can fire again.
     */
    private void scheduleBackgroundRefresh(
        final K key,
        final Supplier<CompletableFuture<Optional<V>>> loader
    ) {
        if (!this.refreshing.add(key)) {
            return;
        }
        CompletableFuture.runAsync(() ->
            loader.get().whenComplete((value, error) -> {
                try {
                    if (error == null && value != null) {
                        this.store.put(key, new Entry<>(value, Instant.now()));
                    }
                    // On error, deliberately leave the existing entry in place: the next
                    // read still serves stale (capped by hardTtl) instead of degrading
                    // immediately to a miss. If the failure persists, the entry will age
                    // past hardTtl and the synchronous-miss path will take over.
                } finally {
                    this.refreshing.remove(key);
                }
            })
        );
    }

    /**
     * Cache entry binding a value to the wall-clock time it was written. TTL is computed
     * against {@link #writtenAt} on every read; no auto-expiry runs in the background.
     *
     * @param <V> cached value type
     */
    private static final class Entry<V> {
        private final Optional<V> value;
        private final Instant writtenAt;

        Entry(final Optional<V> value, final Instant writtenAt) {
            this.value = value;
            this.writtenAt = writtenAt;
        }
    }
}
