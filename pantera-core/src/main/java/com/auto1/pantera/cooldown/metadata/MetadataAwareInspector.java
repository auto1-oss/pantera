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
package com.auto1.pantera.cooldown.metadata;

import java.time.Instant;
import java.util.Map;

/**
 * Extension interface for {@link com.auto1.pantera.cooldown.api.CooldownInspector} implementations
 * that can accept preloaded release dates from metadata.
 *
 * <p>When metadata contains release timestamps (e.g., NPM's {@code time} object),
 * the cooldown metadata service can preload these dates into the inspector.
 * This avoids additional upstream HTTP requests when evaluating cooldown for versions.</p>
 *
 * <p>Inspectors implementing this interface should:</p>
 * <ol>
 *   <li>Store preloaded dates in a thread-safe manner</li>
 *   <li>Check preloaded dates first in {@code releaseDate()} before hitting upstream</li>
 *   <li>Clear preloaded dates after processing to avoid stale data</li>
 * </ol>
 *
 * @since 1.0
 */
public interface MetadataAwareInspector {

    /**
     * Preload release dates extracted from metadata.
     * Called by the cooldown metadata service before evaluating versions.
     *
     * @param releaseDates Map of version string → release timestamp
     */
    void preloadReleaseDates(Map<String, Instant> releaseDates);

    /**
     * Clear preloaded release dates.
     * Called after metadata processing is complete.
     */
    void clearPreloadedDates();

    /**
     * Check if release dates are currently preloaded.
     *
     * @return {@code true} if preloaded dates are available
     */
    boolean hasPreloadedDates();
}
