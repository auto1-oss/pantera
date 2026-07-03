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
package com.auto1.pantera.maven.http;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Materialised filtered-metadata cache for Maven, keyed by upstream content
 * SHA-256 and a coarse time bucket. Sits in front of the per-version
 * {@link com.auto1.pantera.cooldown.metadata.FilteredMetadataCache} so a
 * stable upstream payload produces a single filter+rewrite per bucket window
 * regardless of how many concurrent requests arrive.
 *
 * <p>In-memory only — the cache is intentionally scoped per JVM. Multi-node
 * deployments produce one filtered payload per node; the L1 hit-rate stays
 * high because the underlying registry payloads change rarely (once per
 * release or SNAPSHOT publish).
 *
 * @since 2.2.0
 */
public final class PerInputFilteredMetadataCache {

    /**
     * Bucket granularity — every 1 h boundary forces a re-filter so cooldown
     * blocks that crossed an age threshold get re-evaluated promptly without
     * an explicit invalidation hook.
     */
    private static final Duration BUCKET = Duration.ofHours(1);

    /**
     * Max distinct entries before Caffeine evicts LRU.
     */
    private static final int MAX_SIZE = 50_000;

    /**
     * Entry TTL — never serve a filtered payload older than 24 h regardless
     * of bucket alignment.
     */
    private static final Duration ENTRY_TTL = Duration.ofHours(24);

    private final Cache<String, byte[]> cache;

    public PerInputFilteredMetadataCache() {
        this.cache = Caffeine.newBuilder()
            .maximumSize(MAX_SIZE)
            .expireAfterWrite(ENTRY_TTL.toMillis(), TimeUnit.MILLISECONDS)
            .build();
    }

    /**
     * Lookup the cached filtered bytes for the given upstream sha256 +
     * current bucket. Misses on bucket transitions; the new bucket triggers
     * a fresh filter even if the upstream payload hasn't changed (intentional
     * — cooldown decisions age into and out of the window over time).
     *
     * @param repoType Repository type (e.g. {@code maven-proxy})
     * @param repoName Repository name
     * @param packageName Coordinate (dotted form)
     * @param upstreamSha256 Base64-encoded SHA-256 of the upstream payload
     * @return Cached filtered bytes when present, otherwise empty
     */
    public Optional<byte[]> get(
        final String repoType, final String repoName,
        final String packageName, final String upstreamSha256
    ) {
        final byte[] cached = this.cache.getIfPresent(
            cacheKey(repoType, repoName, packageName, upstreamSha256, currentBucket())
        );
        return Optional.ofNullable(cached);
    }

    /**
     * Insert filtered bytes for the (sha256, current-bucket) tuple.
     */
    public void put(
        final String repoType, final String repoName,
        final String packageName, final String upstreamSha256, final byte[] filtered
    ) {
        this.cache.put(
            cacheKey(repoType, repoName, packageName, upstreamSha256, currentBucket()),
            filtered
        );
    }

    /**
     * Drop every entry for a package coordinate. Used on cooldown block /
     * unblock events so the next request re-filters with the new state.
     */
    public void invalidate(
        final String repoType, final String repoName, final String packageName
    ) {
        final String prefix = "metadata-v2:" + repoType + ":" + repoName + ":" + packageName + ":";
        this.cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * Drop the entire cache.
     */
    public void clear() {
        this.cache.invalidateAll();
    }

    /**
     * Compute the base64-encoded SHA-256 of an upstream metadata payload. The
     * caller passes the result back into {@link #get} / {@link #put}.
     * Package-private — the sole caller {@link CachedProxySlice} is in the same
     * package, and CLAUDE.md forbids public static helpers.
     */
    static String sha256(final byte[] bytes) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(bytes));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String cacheKey(
        final String repoType, final String repoName, final String packageName,
        final String sha256, final long bucket
    ) {
        return "metadata-v2:" + repoType + ":" + repoName + ":" + packageName
            + ":" + sha256 + ":" + bucket;
    }

    private static long currentBucket() {
        return System.currentTimeMillis() / BUCKET.toMillis();
    }
}
