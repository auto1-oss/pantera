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
package com.auto1.pantera.cooldown.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
    private volatile boolean enabled;

    /**
     * Minimum allowed age for an artifact release. If an artifact's release time
     * is within this window (i.e. too fresh), it will be blocked until it reaches
     * the minimum allowed age.
     */
    private volatile Duration minimumAllowedAge;

    /**
     * Per-repo-type overrides.
     * Key: repository type (maven, npm, docker, etc.)
     * Value: RepoTypeConfig with enabled flag and minimum age
     */
    private volatile Map<String, RepoTypeConfig> repoTypeOverrides;

    /**
     * Per-repo-name overrides (highest priority, beats type and global).
     * Key: repository name (e.g. "my-pypi-proxy")
     * Value: RepoTypeConfig with enabled flag and minimum age
     */
    private volatile Map<String, RepoTypeConfig> repoNameOverrides;

    /**
     * How many days of cooldown history to retain before the background
     * purge deletes it. Read live by {@code CooldownCleanupFallback} every
     * tick so admin-UI changes take effect without a restart.
     *
     * <p>Defaults to 90 days; Task 8 will plumb this through
     * {@link #update(boolean, Duration, Map)} from the DB-settings blob.
     */
    private volatile int historyRetentionDays = 90;

    /**
     * Maximum rows the background cleanup / purge workers move per batch.
     * Read live by {@code CooldownCleanupFallback} every tick. Keeping this
     * bounded caps the per-iteration lock footprint on the artifact_cooldowns
     * and artifact_cooldowns_history tables.
     *
     * <p>Defaults to 10 000 rows; Task 8 will plumb this through
     * {@link #update(boolean, Duration, Map)} from the DB-settings blob.
     */
    private volatile int cleanupBatchLimit = 10_000;

    /**
     * Ctor with global settings only.
     *
     * @param enabled Whether cooldown logic is enabled
     * @param minimumAllowedAge Minimum allowed age duration for fresh releases
     */
    public CooldownSettings(final boolean enabled, final Duration minimumAllowedAge) {
        this(enabled, minimumAllowedAge, new HashMap<>(), new HashMap<>());
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
        this(enabled, minimumAllowedAge, repoTypeOverrides, new HashMap<>());
    }

    /**
     * Full ctor with per-repo-type and per-repo-name overrides.
     *
     * @param enabled Whether cooldown logic is enabled globally
     * @param minimumAllowedAge Global minimum allowed age duration
     * @param repoTypeOverrides Per-repo-type configuration overrides
     * @param repoNameOverrides Per-repo-name configuration overrides (highest priority)
     */
    public CooldownSettings(
        final boolean enabled,
        final Duration minimumAllowedAge,
        final Map<String, RepoTypeConfig> repoTypeOverrides,
        final Map<String, RepoTypeConfig> repoNameOverrides
    ) {
        this.enabled = enabled;
        this.minimumAllowedAge = Objects.requireNonNull(minimumAllowedAge);
        this.repoTypeOverrides = Objects.requireNonNull(repoTypeOverrides);
        this.repoNameOverrides = Objects.requireNonNull(repoNameOverrides);
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
     * Check whether a per-repo-name override is registered for this repository.
     *
     * @param repoName Repository name
     * @return {@code true} if an override exists for this repo name
     */
    public boolean isRepoNameOverridePresent(final String repoName) {
        return this.repoNameOverrides.containsKey(repoName);
    }

    /**
     * Check if cooldown is enabled for a specific repository name.
     * Only valid when {@link #isRepoNameOverridePresent(String)} returns {@code true}.
     *
     * @param repoName Repository name
     * @return {@code true} if cooldown is enabled for this repo
     */
    public boolean enabledForRepoName(final String repoName) {
        final RepoTypeConfig override = this.repoNameOverrides.get(repoName);
        return override != null && override.enabled();
    }

    /**
     * Get minimum allowed age for a specific repository name.
     * Only valid when {@link #isRepoNameOverridePresent(String)} returns {@code true}.
     *
     * @param repoName Repository name
     * @return Minimum allowed age for this repo
     */
    public Duration minimumAllowedAgeForRepoName(final String repoName) {
        final RepoTypeConfig override = this.repoNameOverrides.get(repoName);
        return override != null ? override.minimumAllowedAge() : this.minimumAllowedAge;
    }

    /**
     * Register or update a per-repo-name cooldown override.
     * Thread-safe: replaces the internal map atomically.
     *
     * @param repoName Repository name
     * @param enabled Whether cooldown is enabled for this repo
     * @param duration Minimum allowed age for this repo
     */
    public void setRepoNameOverride(final String repoName, final boolean enabled, final Duration duration) {
        final Map<String, RepoTypeConfig> copy = new HashMap<>(this.repoNameOverrides);
        copy.put(repoName, new RepoTypeConfig(enabled, Objects.requireNonNull(duration)));
        this.repoNameOverrides = copy;
    }

    /**
     * Get a copy of per-repo-type overrides.
     *
     * @return Map of repo type to config
     */
    public Map<String, RepoTypeConfig> repoTypeOverrides() {
        return new HashMap<>(this.repoTypeOverrides);
    }

    /**
     * History retention in days — rows in the cooldown history table older
     * than this are purged by the background cleanup worker.
     *
     * @return retention window, in days
     */
    public int historyRetentionDays() {
        return this.historyRetentionDays;
    }

    /**
     * Batch size used by the background cleanup / purge workers.
     *
     * @return maximum rows moved or deleted per iteration
     */
    public int cleanupBatchLimit() {
        return this.cleanupBatchLimit;
    }

    /**
     * Update cooldown settings in-place for hot reload.
     *
     * @param newEnabled Whether cooldown is enabled
     * @param newMinAge New global minimum allowed age
     * @param overrides New per-repo-type overrides
     */
    public void update(final boolean newEnabled, final Duration newMinAge,
        final Map<String, RepoTypeConfig> overrides) {
        this.enabled = newEnabled;
        this.minimumAllowedAge = Objects.requireNonNull(newMinAge);
        this.repoTypeOverrides = new HashMap<>(Objects.requireNonNull(overrides));
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
