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
package com.auto1.pantera.npm.cooldown;

import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.metadata.MetadataAwareInspector;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPM cooldown inspector implementing both CooldownInspector and MetadataAwareInspector.
 * Provides release dates for NPM packages, with support for preloading from metadata.
 *
 * <p>This inspector can work in two modes:</p>
 * <ul>
 *   <li><b>Preloaded mode:</b> Release dates are preloaded from NPM metadata's "time" object.
 *       This is the preferred mode as it avoids additional HTTP requests.</li>
 *   <li><b>Fallback mode:</b> If release date is not preloaded, returns empty.
 *       The cooldown service will then use the default behavior.</li>
 * </ul>
 *
 * @since 1.0
 */
public final class NpmCooldownInspector implements CooldownInspector, MetadataAwareInspector {

    /**
     * Preloaded release dates from metadata.
     * Key: version string, Value: release timestamp
     */
    private final Map<String, Instant> preloadedDates;

    /**
     * Constructor.
     */
    public NpmCooldownInspector() {
        this.preloadedDates = new ConcurrentHashMap<>();
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(
        final String artifact,
        final String version
    ) {
        // First check preloaded dates from metadata
        final Instant preloaded = this.preloadedDates.get(version);
        if (preloaded != null) {
            return CompletableFuture.completedFuture(Optional.of(preloaded));
        }
        // Not preloaded - return empty (cooldown service will use default behavior)
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(
        final String artifact,
        final String version
    ) {
        // NPM dependencies are not evaluated for cooldown in this implementation
        // This could be extended to parse package.json dependencies if needed
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public void preloadReleaseDates(final Map<String, Instant> dates) {
        this.preloadedDates.clear();
        this.preloadedDates.putAll(dates);
    }

    @Override
    public void clearPreloadedDates() {
        this.preloadedDates.clear();
    }

    @Override
    public boolean hasPreloadedDates() {
        return !this.preloadedDates.isEmpty();
    }

    /**
     * Get the number of preloaded release dates.
     * Useful for testing and debugging.
     *
     * @return Number of preloaded dates
     */
    public int preloadedCount() {
        return this.preloadedDates.size();
    }

    /**
     * Check if a specific version has a preloaded release date.
     *
     * @param version Version to check
     * @return true if preloaded
     */
    public boolean hasPreloaded(final String version) {
        return this.preloadedDates.containsKey(version);
    }
}
