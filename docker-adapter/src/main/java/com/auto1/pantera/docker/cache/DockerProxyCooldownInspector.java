/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.docker.misc.OfficialImageName;
import com.auto1.pantera.http.misc.ConfigDefaults;

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
public final class DockerProxyCooldownInspector implements CooldownInspector {

    @FunctionalInterface
    public interface ReleaseDateCallback {
        void onRelease(String artifact, String version, Instant release);
    }

    private volatile ReleaseDateCallback releaseDateCallback;

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

    /**
     * Official-image naming rule: release dates recorded by the cache layer
     * under the client's spelling ({@code nginx}) and looked up by the
     * cooldown layer under the canonical one ({@code library/nginx}) must
     * land on the same key.
     */
    private final OfficialImageName names;

    /**
     * The repository's inspector an {@link #overlay()} reads what it does
     * not hold itself from; absent for the repository's own inspector.
     */
    private final Optional<DockerProxyCooldownInspector> base;

    public DockerProxyCooldownInspector() {
        this(new OfficialImageName(false));
    }

    /**
     * Ctor.
     *
     * @param names Official-image naming rule of the proxied upstream
     */
    public DockerProxyCooldownInspector(final OfficialImageName names) {
        this(names, Optional.empty());
    }

    /**
     * Ctor.
     *
     * @param names Official-image naming rule of the proxied upstream
     * @param base Inspector to read what this one does not hold from
     */
    private DockerProxyCooldownInspector(
        final OfficialImageName names, final Optional<DockerProxyCooldownInspector> base
    ) {
        this.names = names;
        this.base = base;
        final long expiryHours = ConfigDefaults.getLong(
            "PANTERA_DOCKER_CACHE_EXPIRY_HOURS", 24L
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
        final String key = key(artifact, version);
        return CompletableFuture.completedFuture(
            Optional.ofNullable(this.releases.getIfPresent(key)).or(
                () -> this.base.map(shared -> shared.releases.getIfPresent(key))
            )
        );
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

    public void setReleaseDateCallback(final ReleaseDateCallback callback) {
        this.releaseDateCallback = callback;
    }

    public void recordRelease(final String artifact, final String version, final Instant release) {
        final String key = key(artifact, version);
        if (this.seen.getIfPresent(key) == null) {
            this.seen.put(key, Boolean.TRUE);
        }
        this.releases.put(key, release);
        final ReleaseDateCallback cb = this.releaseDateCallback;
        if (cb != null) {
            try {
                cb.onRelease(this.names.normalize(artifact), version, release);
            } catch (final Exception ignored) {
                // persistence failure must not break the hot path
            }
        }
    }

    /**
     * An inspector for one request that must leave the repository as it
     * found it: a Troubleshoot replay ({@code RequestTrace}) evaluates
     * cooldown as a client's request does. The overlay answers with the
     * release dates this inspector knows and with those recorded on the
     * overlay itself; what is recorded on it stays there and is never
     * handed to the {@link ReleaseDateCallback}, so the request leaves no
     * release date behind, neither here nor in the database.
     *
     * @return Overlay of this inspector
     */
    public DockerProxyCooldownInspector overlay() {
        return new DockerProxyCooldownInspector(this.names, Optional.of(this));
    }

    public Optional<String> ownerFor(final String repoName, final String digest) {
        return Optional.ofNullable(this.digestOwners.getIfPresent(digestKey(repoName, digest)));
    }

    public boolean known(final String artifact, final String version) {
        return this.seen.getIfPresent(key(artifact, version)) != null
            || this.base.map(shared -> shared.known(artifact, version)).orElse(false);
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

    private String key(final String artifact, final String version) {
        return String.format("%s:%s", this.names.normalize(artifact), version);
    }

    private static String digestKey(final String repoName, final String digest) {
        return String.format("%s@%s", repoName, digest);
    }
}
