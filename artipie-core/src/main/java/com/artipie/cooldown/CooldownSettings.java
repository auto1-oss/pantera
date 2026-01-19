/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Global and per-repo-type cooldown configuration.
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
     * Per-repo-type overrides.
     * Key: repository type (maven, npm, docker, etc.)
     * Value: RepoTypeConfig with enabled flag and minimum age
     */
    private final Map<String, RepoTypeConfig> repoTypeOverrides;

    /**
     * Ctor with global settings only.
     *
     * @param enabled Whether cooldown logic is enabled
     * @param minimumAllowedAge Minimum allowed age duration for fresh releases
     */
    public CooldownSettings(final boolean enabled, final Duration minimumAllowedAge) {
        this(enabled, minimumAllowedAge, new HashMap<>());
    }

    /**
     * Ctor with per-repo-type overrides.
     *
     * @param enabled Whether cooldown logic is enabled globally
     * @param minimumAllowedAge Global minimum allowed age duration
     * @param repoTypeOverrides Per-repo-type configuration overrides
     */
    public CooldownSettings(
        final boolean enabled,
        final Duration minimumAllowedAge,
        final Map<String, RepoTypeConfig> repoTypeOverrides
    ) {
        this.enabled = enabled;
        this.minimumAllowedAge = Objects.requireNonNull(minimumAllowedAge);
        this.repoTypeOverrides = Objects.requireNonNull(repoTypeOverrides);
    }

    /**
     * Check if cooldown is enabled globally.
     *
     * @return {@code true} if cooldown is enabled globally
     */
    public boolean enabled() {
        return this.enabled;
    }

    /**
     * Check if cooldown is enabled for specific repository type.
     * Uses per-repo-type override if present, otherwise falls back to global.
     *
     * @param repoType Repository type (maven, npm, docker, etc.)
     * @return {@code true} if cooldown is enabled for this repo type
     */
    public boolean enabledFor(final String repoType) {
        final RepoTypeConfig override = this.repoTypeOverrides.get(repoType.toLowerCase());
        return override != null ? override.enabled() : this.enabled;
    }

    /**
     * Get global minimum allowed age duration for releases.
     *
     * @return Duration of minimum allowed age
     */
    public Duration minimumAllowedAge() {
        return this.minimumAllowedAge;
    }

    /**
     * Get minimum allowed age for specific repository type.
     * Uses per-repo-type override if present, otherwise falls back to global.
     *
     * @param repoType Repository type (maven, npm, docker, etc.)
     * @return Minimum allowed age for this repo type
     */
    public Duration minimumAllowedAgeFor(final String repoType) {
        final RepoTypeConfig override = this.repoTypeOverrides.get(repoType.toLowerCase());
        return override != null ? override.minimumAllowedAge() : this.minimumAllowedAge;
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

    /**
     * Per-repository-type configuration.
     */
    public static final class RepoTypeConfig {
        private final boolean enabled;
        private final Duration minimumAllowedAge;

        /**
         * Constructor.
         *
         * @param enabled Whether cooldown is enabled for this repo type
         * @param minimumAllowedAge Minimum allowed age for this repo type
         */
        public RepoTypeConfig(final boolean enabled, final Duration minimumAllowedAge) {
            this.enabled = enabled;
            this.minimumAllowedAge = Objects.requireNonNull(minimumAllowedAge);
        }

        public boolean enabled() {
            return this.enabled;
        }

        public Duration minimumAllowedAge() {
            return this.minimumAllowedAge;
        }
    }
}
