/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.pypi.http;

import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * PyPI proxy cooldown inspector that tracks artifact versions and their release times.
 * This is used to enforce cooldown periods for artifacts in PyPI proxy repositories.
 */
final class PyProxyCooldownInspector implements CooldownInspector {
    /**
     * Cache of artifact versions and their release times.
     * Key format: "artifact:version"
     */
    private final ConcurrentMap<String, Instant> releases;

    /**
     * Default constructor.
     */
    PyProxyCooldownInspector() {
        this.releases = new ConcurrentHashMap<>(0);
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        Objects.requireNonNull(artifact, "Artifact name cannot be null");
        Objects.requireNonNull(version, "Version cannot be null");
        return CompletableFuture.completedFuture(
            Optional.ofNullable(this.releases.get(key(artifact, version)))
        );
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(
        final String artifact,
        final String version
    ) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    /**
     * Register or update the release time for an artifact version.
     *
     * @param artifact The artifact name
     * @param version The artifact version
     * @param release The release time
     */
    void register(final String artifact, final String version, final Instant release) {
        Objects.requireNonNull(artifact, "Artifact name cannot be null");
        Objects.requireNonNull(version, "Version cannot be null");
        Objects.requireNonNull(release, "Release time cannot be null");
        
        final String key = key(artifact, version);
        this.releases.compute(key, (k, existing) -> {
            // Only update if this is a newer release or if no release time was recorded
            if (existing == null || release.isAfter(existing)) {
                return release;
            }
            return existing;
        });
    }

    /**
     * Check if an artifact version is known to this inspector.
     *
     * @param artifact The artifact name
     * @param version The artifact version
     * @return True if the artifact version is known, false otherwise
     */
    boolean known(final String artifact, final String version) {
        return this.releases.containsKey(key(artifact, version));
    }

    /**
     * Create a consistent key for the artifact-version pair.
     *
     * @param artifact The artifact name
     * @param version The artifact version
     * @return A string key in the format "artifact:version" (lowercase)
     */
    private static String key(final String artifact, final String version) {
        return String.format("%s:%s", 
            Objects.requireNonNull(artifact, "Artifact name cannot be null").toLowerCase(), 
            Objects.requireNonNull(version, "Version cannot be null").toLowerCase()
        );
    }
}
