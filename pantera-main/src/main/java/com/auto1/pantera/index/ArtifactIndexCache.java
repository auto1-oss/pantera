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
package com.auto1.pantera.index;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bounded Caffeine wrapper around {@link ArtifactIndex#locateByName(String)}.
 *
 * <p>Cuts repeat DB queries for the same artifact name within a single Maven
 * or Gradle resolve. The {@code .pom} and {@code .jar} for the same artifact
 * arrive milliseconds apart on different connections; without this cache they
 * each hit the DB. With it, the second one is an L1 hit (~5 microseconds vs
 * ~30 ms).
 *
 * <h2>Cache Tiers</h2>
 * <ul>
 *   <li><b>Positive cache.</b> Maps {@code artifactName} -&gt; {@code List<String>}
 *       of repo names when the DB returned at least one row. 50 000 entries x
 *       ~250 bytes (name + 1-3 member names + Caffeine entry overhead) ~=
 *       12.5 MB worst case. 10 minute TTL — short enough that uploads land
 *       quickly, long enough that a typical resolve never re-hits the DB.</li>
 *   <li><b>Negative cache.</b> Marks {@code artifactName} as "no repo has it"
 *       (the DB returned an empty list). 50 000 entries x ~120 bytes ~= 6 MB.
 *       30 second TTL — short to limit "I just published it but it 404s"
 *       windows; the {@link #invalidate(String)} hook from the upload-side
 *       sync indexer makes the typical case immediate.</li>
 *   <li><b>In-flight coalescing.</b> Concurrent lookups for the same name
 *       share a single {@code CompletableFuture}; thundering herds on a
 *       popular artifact at TTL-expiry boundaries collapse to one DB call.</li>
 * </ul>
 *
 * <h2>Critical Semantic</h2>
 * <p>{@link ArtifactIndex#locateByName} uses a tri-valued return:</p>
 * <ul>
 *   <li>{@code Optional.of(non-empty)} -&gt; cache as POSITIVE.</li>
 *   <li>{@code Optional.of(empty)} -&gt; cache as NEGATIVE.</li>
 *   <li>{@code Optional.empty()} -&gt; DB ERROR — do NOT cache. Caller falls
 *       back to full fanout; a transient blip must not be sticky.</li>
 * </ul>
 *
 * <h2>GC / Memory Bound</h2>
 * <p>Caffeine uses Window-TinyLFU; bounded size means stable allocation rate
 * once warm. Lazy expiry — no background scanning thread, no STW. Total
 * worst case ~20 MB across both tiers + the in-flight map (typically &lt;100
 * entries during a heavy resolve). &lt;1% of typical JVM heap.</p>
 *
 * <h2>Invalidation</h2>
 * <p>{@code DbSyncArtifactIndexer} calls {@link #invalidate(String)} after
 * every successful upload write so a freshly-published artifact is never
 * masked by a stale negative entry.</p>
 *
 * @since 2.2.0
 */
public final class ArtifactIndexCache implements ArtifactIndex {

    /** Max entries kept on the positive tier (~12.5 MB worst case). */
    private static final long POSITIVE_MAX = 50_000L;

    /** Max entries kept on the negative tier (~6 MB worst case). */
    private static final long NEGATIVE_MAX = 50_000L;

    /** TTL for positive entries. */
    private static final Duration POSITIVE_TTL = Duration.ofMinutes(10);

    /** TTL for negative entries — short, to limit publish-then-404 windows. */
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);

    private final ArtifactIndex delegate;
    private final Cache<String, List<String>> positive;
    private final Cache<String, Boolean> negative;
    private final ConcurrentMap<String, CompletableFuture<Optional<List<String>>>> inFlight;

    public ArtifactIndexCache(final ArtifactIndex delegate) {
        this.delegate = delegate;
        this.positive = Caffeine.newBuilder()
            .maximumSize(POSITIVE_MAX)
            .expireAfterWrite(POSITIVE_TTL)
            .recordStats()
            .build();
        this.negative = Caffeine.newBuilder()
            .maximumSize(NEGATIVE_MAX)
            .expireAfterWrite(NEGATIVE_TTL)
            .recordStats()
            .build();
        this.inFlight = new ConcurrentHashMap<>();
    }

    @Override
    public CompletableFuture<Optional<List<String>>> locateByName(final String artifactName) {
        if (artifactName == null || artifactName.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final List<String> hit = this.positive.getIfPresent(artifactName);
        if (hit != null) {
            return CompletableFuture.completedFuture(Optional.of(hit));
        }
        if (this.negative.getIfPresent(artifactName) != null) {
            return CompletableFuture.completedFuture(Optional.of(Collections.emptyList()));
        }
        // Coalesce concurrent lookups onto a single in-flight promise. We register
        // an empty promise inside computeIfAbsent (cheap, no recursion risk), then
        // dispatch the delegate outside it. The mapping function MUST NOT call
        // ConcurrentHashMap mutators on `this.inFlight` — see JDK-8062841.
        final CompletableFuture<Optional<List<String>>>[] created = new CompletableFuture[1];
        final CompletableFuture<Optional<List<String>>> shared =
            this.inFlight.computeIfAbsent(artifactName, name -> {
                final CompletableFuture<Optional<List<String>>> promise = new CompletableFuture<>();
                created[0] = promise;
                return promise;
            });
        if (created[0] != null) {
            this.dispatchAndPopulate(artifactName, created[0]);
        }
        return shared;
    }

    private void dispatchAndPopulate(
        final String name,
        final CompletableFuture<Optional<List<String>>> promise
    ) {
        this.delegate.locateByName(name).whenComplete((opt, err) -> {
            // Remove from in-flight BEFORE completing the promise so any waiter
            // that wakes up and re-queries sees a clean map. Safe here because
            // this callback runs outside any computeIfAbsent on `inFlight`.
            this.inFlight.remove(name);
            if (err == null && opt != null && opt.isPresent()) {
                final List<String> result = opt.get();
                if (result.isEmpty()) {
                    this.negative.put(name, Boolean.TRUE);
                } else {
                    this.positive.put(name, result);
                }
            }
            // Optional.empty() means DB error — do NOT cache, but DO complete
            // the promise so callers can fall back to fanout.
            if (err != null) {
                promise.completeExceptionally(err);
            } else {
                promise.complete(opt == null ? Optional.empty() : opt);
            }
        });
    }

    /**
     * Drop both positive and negative entries for {@code name}. Called by
     * the sync upload indexer immediately after a write so the next request
     * sees the fresh DB row, not the stale (possibly negative) cache entry.
     *
     * @param artifactName Artifact name to drop from both cache tiers
     */
    public void invalidate(final String artifactName) {
        if (artifactName == null) {
            return;
        }
        this.positive.invalidate(artifactName);
        this.negative.invalidate(artifactName);
    }

    /**
     * Diagnostics for the admin/stats endpoint.
     *
     * @return human-readable stats string covering both cache tiers
     */
    public String stats() {
        return "positive=" + this.positive.stats() + " negative=" + this.negative.stats();
    }

    // ---- Pass-through ArtifactIndex methods (delegate everything else) ----

    @Override
    public CompletableFuture<Void> index(final ArtifactDocument doc) {
        return this.delegate.index(doc);
    }

    @Override
    public CompletableFuture<Void> remove(final String repoName, final String artifactPath) {
        return this.delegate.remove(repoName, artifactPath);
    }

    @Override
    public CompletableFuture<Integer> removePrefix(final String repoName, final String pathPrefix) {
        return this.delegate.removePrefix(repoName, pathPrefix);
    }

    @Override
    public CompletableFuture<SearchResult> search(
        final String query, final int maxResults, final int offset
    ) {
        return this.delegate.search(query, maxResults, offset);
    }

    @Override
    public CompletableFuture<SearchResult> search(
        final String query, final int maxResults, final int offset,
        final String repoType, final String repoName,
        final String sortBy, final boolean sortAsc
    ) {
        return this.delegate.search(query, maxResults, offset, repoType, repoName, sortBy, sortAsc);
    }

    @Override
    public CompletableFuture<List<String>> locate(final String artifactPath) {
        return this.delegate.locate(artifactPath);
    }

    @Override
    public boolean isWarmedUp() {
        return this.delegate.isWarmedUp();
    }

    @Override
    public void setWarmedUp() {
        this.delegate.setWarmedUp();
    }

    @Override
    public CompletableFuture<java.util.Map<String, Object>> getStats() {
        return this.delegate.getStats();
    }

    @Override
    public CompletableFuture<Void> indexBatch(final java.util.Collection<ArtifactDocument> docs) {
        return this.delegate.indexBatch(docs);
    }

    @Override
    public void close() throws IOException {
        this.delegate.close();
    }
}
