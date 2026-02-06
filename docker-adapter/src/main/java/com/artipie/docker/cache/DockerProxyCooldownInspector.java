/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.cache;

import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.http.misc.ConfigDefaults;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Docker cooldown inspector with bounded caches to prevent memory leaks.
 * Uses Caffeine cache with automatic eviction to limit Old Gen growth.
 */
public final class DockerProxyCooldownInspector implements CooldownInspector,
    com.artipie.cooldown.InspectorRegistry.InvalidatableInspector {

    /**
     * Bounded cache of image release dates.
     * Max 10,000 entries, expire after 24 hours.
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Instant> releases;

    /**
     * Bounded cache of digest to image name mappings.
     * Max 50,000 entries (digests are more numerous), expire after 24 hours.
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, String> digestOwners;

    /**
     * Bounded cache of seen digests (for deduplication).
     * Max 50,000 entries, expire after 1 hour.
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> seen;

    public DockerProxyCooldownInspector() {
        final long expiryHours = ConfigDefaults.getLong(
            "ARTIPIE_DOCKER_CACHE_EXPIRY_HOURS", 24L
        );
        this.releases = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofHours(expiryHours))
            .recordStats()
            .build();
        this.digestOwners = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(50_000)  // More digests than images
            .expireAfterWrite(Duration.ofHours(expiryHours))
            .recordStats()
            .build();
        this.seen = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(Duration.ofHours(1))  // Shorter TTL for seen cache
            .recordStats()
            .build();
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        return CompletableFuture.completedFuture(Optional.ofNullable(this.releases.getIfPresent(key(artifact, version))));
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    public void register(
        final String artifact,
        final String version,
        final Optional<Instant> release,
        final String owner,
        final String repoName,
        final Optional<String> digest
    ) {
        final String key = key(artifact, version);
        if (this.seen.getIfPresent(key) == null) {
            this.seen.put(key, Boolean.TRUE);
        }
        release.ifPresent(value -> this.releases.put(key, value));
        this.digestOwners.put(digestKey(repoName, version), owner);
        digest.ifPresent(value -> this.digestOwners.put(digestKey(repoName, value), owner));
    }

    public void recordRelease(final String artifact, final String version, final Instant release) {
        final String key = key(artifact, version);
        if (this.seen.getIfPresent(key) == null) {
            this.seen.put(key, Boolean.TRUE);
        }
        this.releases.put(key, release);
    }

    public Optional<String> ownerFor(final String repoName, final String digest) {
        return Optional.ofNullable(this.digestOwners.getIfPresent(digestKey(repoName, digest)));
    }

    public boolean known(final String artifact, final String version) {
        return this.seen.getIfPresent(key(artifact, version)) != null;
    }

    public boolean isBlocked(final String artifact, final String digest) {
        return false;
    }

    /**
     * Invalidate cached release date for specific artifact.
     * Called when artifact is manually unblocked.
     * 
     * @param artifact Artifact name
     * @param version Version
     */
    public void invalidate(final String artifact, final String version) {
        final String k = key(artifact, version);
        this.releases.invalidate(k);
        this.seen.invalidate(k);
    }

    /**
     * Clear all cached data.
     * Called when all artifacts for a repository are unblocked.
     */
    public void clearAll() {
        this.releases.invalidateAll();
        this.digestOwners.invalidateAll();
        this.seen.invalidateAll();
    }

    private static String key(final String artifact, final String version) {
        return String.format("%s:%s", artifact, version);
    }

    private static String digestKey(final String repoName, final String digest) {
        return String.format("%s@%s", repoName, digest);
    }
}
