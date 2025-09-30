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

    /**
     * Minimum allowed age for an artifact release. If an artifact's release time
     * is within this window (i.e. too fresh), it will be blocked until it reaches
     * the minimum allowed age.
     */
    private final Duration minimumAllowedAge;

    /**
     * Ctor.
     *
     * @param enabled Whether cooldown logic is enabled
     * @param minimumAllowedAge Minimum allowed age duration for fresh releases
     */
    public CooldownSettings(final boolean enabled, final Duration minimumAllowedAge) {
        this.enabled = enabled;
        this.minimumAllowedAge = Objects.requireNonNull(minimumAllowedAge);
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
     * Minimum allowed age duration for releases.
     *
     * @return Duration of minimum allowed age
     */
    public Duration minimumAllowedAge() {
        return this.minimumAllowedAge;
    }

    /**
     * Creates default configuration (enabled, 72 hours minimum allowed age).
     *
     * @return Default cooldown settings
     */
    public static CooldownSettings defaults() {
        final Duration duration = Duration.ofHours(DEFAULT_HOURS);
        return new CooldownSettings(true, duration);
    }
}
