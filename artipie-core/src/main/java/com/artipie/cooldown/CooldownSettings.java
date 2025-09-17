/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import java.time.Duration;
import java.util.Objects;

/**
 * Global cooldown configuration.
 */
public final class CooldownSettings {

    /**
     * Default cooldown in hours when configuration is absent.
     */
    public static final long DEFAULT_HOURS = 72L;

    /**
     * Whether cooldown logic is enabled.
     */
    private final boolean enabled;

    /**
     * Cooldown window applied when a newer version than cached is requested.
     */
    private final Duration newerThanCache;

    /**
     * Cooldown window applied for freshly released artifacts when nothing is cached yet.
     */
    private final Duration freshRelease;

    /**
     * Ctor.
     *
     * @param enabled Whether cooldown logic is enabled
     * @param newerThanCache Cooldown duration for newer-than-cache requests
     * @param freshRelease Cooldown duration for first-time releases
     */
    public CooldownSettings(
        final boolean enabled,
        final Duration newerThanCache,
        final Duration freshRelease
    ) {
        this.enabled = enabled;
        this.newerThanCache = Objects.requireNonNull(newerThanCache);
        this.freshRelease = Objects.requireNonNull(freshRelease);
    }

    /**
     * Enabled flag.
     *
     * @return {@code true} if cooldown is enabled
     */
    public boolean enabled() {
        return this.enabled;
    }

    /**
     * Cooldown duration used when cached artifact exists but a newer version is requested.
     *
     * @return Cooldown duration
     */
    public Duration newerThanCache() {
        return this.newerThanCache;
    }

    /**
     * Cooldown duration used for freshly released artifacts with no cached version.
     *
     * @return Cooldown duration
     */
    public Duration freshRelease() {
        return this.freshRelease;
    }

    /**
     * Creates default configuration (enabled, 72 hours for both windows).
     *
     * @return Default cooldown settings
     */
    public static CooldownSettings defaults() {
        final Duration duration = Duration.ofHours(DEFAULT_HOURS);
        return new CooldownSettings(true, duration, duration);
    }
}
