/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.cache;

import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DockerProxyCooldownInspector implements CooldownInspector,
    com.artipie.cooldown.InspectorRegistry.InvalidatableInspector {

    private final ConcurrentMap<String, Instant> releases;

    private final ConcurrentMap<String, String> digestOwners;

    private final ConcurrentMap<String, Boolean> seen;

    public DockerProxyCooldownInspector() {
        this.releases = new ConcurrentHashMap<>();
        this.digestOwners = new ConcurrentHashMap<>();
        this.seen = new ConcurrentHashMap<>();
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        return CompletableFuture.completedFuture(Optional.ofNullable(this.releases.get(key(artifact, version))));
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
        this.seen.putIfAbsent(key, Boolean.TRUE);
        release.ifPresent(value -> this.releases.put(key, value));
        this.digestOwners.put(digestKey(repoName, version), owner);
        digest.ifPresent(value -> this.digestOwners.put(digestKey(repoName, value), owner));
    }

    public void recordRelease(final String artifact, final String version, final Instant release) {
        this.seen.putIfAbsent(key(artifact, version), Boolean.TRUE);
        this.releases.put(key(artifact, version), release);
    }

    public Optional<String> ownerFor(final String repoName, final String digest) {
        return Optional.ofNullable(this.digestOwners.get(digestKey(repoName, digest)));
    }

    public boolean known(final String artifact, final String version) {
        return this.seen.containsKey(key(artifact, version));
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
        this.releases.remove(k);
        this.seen.remove(k);
    }

    /**
     * Clear all cached data.
     * Called when all artifacts for a repository are unblocked.
     */
    public void clearAll() {
        this.releases.clear();
        this.digestOwners.clear();
        this.seen.clear();
    }

    private static String key(final String artifact, final String version) {
        return String.format("%s:%s", artifact, version);
    }

    private static String digestKey(final String repoName, final String digest) {
        return String.format("%s@%s", repoName, digest);
    }
}
