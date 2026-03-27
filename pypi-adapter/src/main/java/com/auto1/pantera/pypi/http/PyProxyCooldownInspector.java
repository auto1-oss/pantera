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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.cooldown.CooldownDependency;
import com.auto1.pantera.cooldown.CooldownInspector;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * PyPI proxy cooldown inspector that tracks artifact versions and their release times.
 * This is used to enforce cooldown periods for artifacts in PyPI proxy repositories.
 * 
 * Uses bounded Caffeine cache to prevent unbounded memory growth in Old Gen.
 */
final class PyProxyCooldownInspector implements CooldownInspector,
    com.auto1.pantera.cooldown.InspectorRegistry.InvalidatableInspector {
    /**
     * Bounded cache of artifact versions and their release times.
     * Key format: "artifact:version"
     * Max 10,000 entries, expire after 24 hours (cooldown is typically 7 days, so cache hit rate will be high)
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Instant> releases;

    private final com.auto1.pantera.http.Slice metadata;

    /**
     * Default constructor.
     */
    PyProxyCooldownInspector() {
        this(null);
    }

    PyProxyCooldownInspector(final com.auto1.pantera.http.Slice metadata) {
        this.releases = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(10_000)  // Limit memory usage
            .expireAfterWrite(Duration.ofHours(24))  // Auto-evict old entries
            .recordStats()  // Enable metrics
            .build();
        this.metadata = metadata;
    }

    @Override
    public void invalidate(final String artifact, final String version) {
        this.releases.invalidate(key(artifact, version));
    }

    @Override
    public void clearAll() {
        this.releases.invalidateAll();
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        Objects.requireNonNull(artifact, "Artifact name cannot be null");
        Objects.requireNonNull(version, "Version cannot be null");
        final String key = key(artifact, version);
        final Instant cached = this.releases.getIfPresent(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        if (this.metadata == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return this.fetchReleaseDate(artifact, version).thenApply(release -> {
            release.ifPresent(instant -> this.releases.put(key, instant));
            return release;
        });
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
        final Instant existing = this.releases.getIfPresent(key);
        // Only update if this is a newer release or if no release time was recorded
        if (existing == null || release.isAfter(existing)) {
            this.releases.put(key, release);
        }
    }

    /**
     * Check if an artifact version is known to this inspector.
     *
     * @param artifact The artifact name
     * @param version The artifact version
     * @return True if the artifact version is known, false otherwise
     */
    boolean known(final String artifact, final String version) {
        return this.releases.getIfPresent(key(artifact, version)) != null;
    }

    private CompletableFuture<Optional<Instant>> fetchReleaseDate(
        final String artifact,
        final String version
    ) {
        final java.net.URI uri = java.net.URI.create(
            String.format("/pypi/%s/%s/json", artifact, version)
        );
        final com.auto1.pantera.http.rq.RequestLine line = new com.auto1.pantera.http.rq.RequestLine(
            com.auto1.pantera.http.rq.RqMethod.GET,
            uri,
            "HTTP/1.1"
        );
        com.auto1.pantera.http.log.EcsLogger.debug("com.auto1.pantera.pypi")
            .message("Fetching release date from PyPI JSON API")
            .eventCategory("repository")
            .eventAction("cooldown_inspection")
            .field("package.name", artifact)
            .field("package.version", version)
            .field("url.full", uri.toString())
            .log();
        return this.metadata.response(
            line,
            com.auto1.pantera.http.Headers.EMPTY,
            com.auto1.pantera.asto.Content.EMPTY
        ).toCompletableFuture().thenCompose(response -> {
            if (!response.status().success()) {
                com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.pypi")
                    .message("PyPI JSON API returned non-success status")
                    .eventCategory("repository")
                    .eventAction("cooldown_inspection")
                    .eventOutcome("failure")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .field("http.response.status_code", response.status().code())
                    .log();
                return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
            }
            return response.body().asBytesFuture().thenApply(bytes -> {
                try (javax.json.JsonReader reader = javax.json.Json.createReader(
                    new java.io.StringReader(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                )) {
                    final javax.json.JsonObject root = reader.readObject();
                    // PyPI JSON API structure: { "urls": [ { "upload_time_iso_8601": "..." } ] }
                    final javax.json.JsonArray urls = root.getJsonArray("urls");
                    if (urls == null || urls.isEmpty()) {
                        com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.pypi")
                            .message("No 'urls' field or empty urls array in PyPI JSON response")
                            .eventCategory("repository")
                            .eventAction("cooldown_inspection")
                            .eventOutcome("failure")
                            .field("package.name", artifact)
                            .field("package.version", version)
                            .log();
                        return java.util.Optional.empty();
                    }
                    // Get the first file's upload time (all files in a release have the same upload time)
                    final javax.json.JsonObject first = urls.getJsonObject(0);
                    final String iso = first.getString("upload_time_iso_8601", null);
                    if (iso == null) {
                        com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.pypi")
                            .message("No upload_time_iso_8601 field in PyPI JSON response")
                            .eventCategory("repository")
                            .eventAction("cooldown_inspection")
                            .eventOutcome("failure")
                            .field("package.name", artifact)
                            .field("package.version", version)
                            .log();
                        return java.util.Optional.empty();
                    }
                    try {
                        final java.time.Instant releaseDate = java.time.Instant.parse(iso);
                        com.auto1.pantera.http.log.EcsLogger.debug("com.auto1.pantera.pypi")
                            .message("Found release date")
                            .eventCategory("repository")
                            .eventAction("cooldown_inspection")
                            .eventOutcome("success")
                            .field("package.name", artifact)
                            .field("package.version", version)
                            .field("package.name", releaseDate.toString())
                            .log();
                        return java.util.Optional.of(releaseDate);
                    } catch (final Exception ex) {
                        com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.pypi")
                            .message("Failed to parse upload_time_iso_8601: " + iso)
                            .eventCategory("repository")
                            .eventAction("cooldown_inspection")
                            .eventOutcome("failure")
                            .field("package.name", artifact)
                            .field("package.version", version)
                            .error(ex)
                            .log();
                        return java.util.Optional.empty();
                    }
                } catch (final Exception ex) {
                    com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.pypi")
                        .message("Failed to parse PyPI JSON response")
                        .eventCategory("repository")
                        .eventAction("cooldown_inspection")
                        .eventOutcome("failure")
                        .field("package.name", artifact)
                        .field("package.version", version)
                        .error(ex)
                        .log();
                    return java.util.Optional.empty();
                }
            });
        });
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
