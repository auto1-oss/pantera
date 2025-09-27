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
     * Whether cooldown logic is enabled globally.
     */
    private final boolean enabled;

    /** Enable rule: newer-than-cache. */
    private final boolean enableNewerThanCache;

    /** Enable rule: fresh-release. */
    private final boolean enableFreshRelease;

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
        this(enabled, true, true, newerThanCache, freshRelease);
    }

    /**
     * Full constructor allowing to toggle each rule.
     *
     * @param enabled Global enable flag
     * @param enableNewerThanCache Enable newer-than-cache rule
     * @param enableFreshRelease Enable fresh-release rule
     * @param newerThanCache Duration for newer-than-cache rule
     * @param freshRelease Duration for fresh-release rule
     */
    public CooldownSettings(
        final boolean enabled,
        final boolean enableNewerThanCache,
        final boolean enableFreshRelease,
        final Duration newerThanCache,
        final Duration freshRelease
    ) {
        this.enabled = enabled;
        this.enableNewerThanCache = enableNewerThanCache;
        this.enableFreshRelease = enableFreshRelease;
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
     * Whether newer-than-cache cooldown rule is enabled.
     * @return True when enabled
     */
    public boolean newerThanCacheEnabled() {
        return this.enableNewerThanCache;
    }

    /**
     * Whether fresh-release cooldown rule is enabled.
     * @return True when enabled
     */
    public boolean freshReleaseEnabled() {
        return this.enableFreshRelease;
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
        return new CooldownSettings(true, true, true, duration, duration);
    }
}
