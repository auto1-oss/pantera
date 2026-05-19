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
import java.util.Locale;
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
     * Optional stricter cooldown policy applied to Maven/Gradle SNAPSHOT
     * timestamped artifacts. Either sub-field may be empty, in which case
     * the global value is used.
     */
    private volatile SnapshotPolicy snapshotPolicy = SnapshotPolicy.inherit();

    /**
     * Per-repo-name SNAPSHOT policy overrides. Highest-priority lookup for
     * timestamped SNAPSHOT versions; falls through to {@link #snapshotPolicy}
     * then per-type/global.
     */
    private volatile Map<String, SnapshotPolicy> repoNameSnapshotOverrides = new HashMap<>();

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
        final RepoTypeConfig override = this.repoTypeOverrides.get(repoType.toLowerCase(Locale.ROOT));
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
        final RepoTypeConfig override = this.repoTypeOverrides.get(repoType.toLowerCase(Locale.ROOT));
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
     * Remove a per-repo-name cooldown override so the repo falls back to
     * its type-level / global tier. No-op when no override was registered.
     * Thread-safe: replaces the internal map atomically.
     *
     * @param repoName Repository name (never null)
     */
    public void removeRepoNameOverride(final String repoName) {
        Objects.requireNonNull(repoName, "repoName");
        if (!this.repoNameOverrides.containsKey(repoName)) {
            return;
        }
        final Map<String, RepoTypeConfig> copy = new HashMap<>(this.repoNameOverrides);
        copy.remove(repoName);
        this.repoNameOverrides = copy;
    }

    /**
     * Per-repo-name cooldown overrides accessor. Returns a defensive copy so
     * callers cannot mutate the internal map.
     *
     * @return Map of repository name to cooldown config
     */
    public Map<String, RepoTypeConfig> repoNameOverrides() {
        return new HashMap<>(this.repoNameOverrides);
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
     * Update cooldown settings in-place for hot reload (3-arg variant).
     *
     * <p>Preserves the current values of {@link #historyRetentionDays()} and
     * {@link #cleanupBatchLimit()}, which are not known to the YAML bootstrap
     * caller. The DB-load path uses the 5-arg overload to plumb those through.
     *
     * @param newEnabled Whether cooldown is enabled
     * @param newMinAge New global minimum allowed age
     * @param overrides New per-repo-type overrides
     */
    public void update(final boolean newEnabled, final Duration newMinAge,
        final Map<String, RepoTypeConfig> overrides) {
        this.update(
            newEnabled, newMinAge, overrides,
            this.historyRetentionDays, this.cleanupBatchLimit
        );
    }

    /**
     * Update cooldown settings in-place for hot reload (5-arg variant), including
     * background-cleanup tunables sourced from the DB settings blob.
     *
     * <p>Validates the two new tunables; out-of-range values raise
     * {@link IllegalArgumentException} and leave all fields untouched.
     *
     * @param newEnabled Whether cooldown is enabled
     * @param newMinAge New global minimum allowed age
     * @param overrides New per-repo-type overrides
     * @param newHistoryRetentionDays Retention window for cooldown history (days),
     *                                must be in (0, 3650]
     * @param newCleanupBatchLimit Maximum rows per background cleanup iteration,
     *                             must be in (0, 100000]
     */
    public void update(final boolean newEnabled, final Duration newMinAge,
        final Map<String, RepoTypeConfig> overrides,
        final int newHistoryRetentionDays,
        final int newCleanupBatchLimit) {
        if (newHistoryRetentionDays <= 0 || newHistoryRetentionDays > 3650) {
            throw new IllegalArgumentException(
                "historyRetentionDays must be in (0, 3650]"
            );
        }
        if (newCleanupBatchLimit <= 0 || newCleanupBatchLimit > 100_000) {
            throw new IllegalArgumentException(
                "cleanupBatchLimit must be in (0, 100000]"
            );
        }
        this.enabled = newEnabled;
        this.minimumAllowedAge = Objects.requireNonNull(newMinAge);
        this.repoTypeOverrides = new HashMap<>(Objects.requireNonNull(overrides));
        this.historyRetentionDays = newHistoryRetentionDays;
        this.cleanupBatchLimit = newCleanupBatchLimit;
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
     * Global SNAPSHOT policy accessor.
     *
     * @return Current global SNAPSHOT policy (never null; defaults to inherit)
     */
    public SnapshotPolicy snapshotPolicy() {
        return this.snapshotPolicy;
    }

    /**
     * Replace the global SNAPSHOT policy. Thread-safe (volatile field swap).
     *
     * @param policy New policy; null is treated as "inherit"
     */
    public void setSnapshotPolicy(final SnapshotPolicy policy) {
        this.snapshotPolicy = policy == null ? SnapshotPolicy.inherit() : policy;
    }

    /**
     * Per-repo-name SNAPSHOT overrides accessor. Returns a defensive copy so
     * callers cannot mutate the internal map.
     *
     * @return Map of repository name to SNAPSHOT policy
     */
    public Map<String, SnapshotPolicy> repoNameSnapshotOverrides() {
        return new HashMap<>(this.repoNameSnapshotOverrides);
    }

    /**
     * Replace the SNAPSHOT policy for a single repository name. Atomic swap of
     * the volatile map field so concurrent readers observe a consistent view.
     *
     * @param repoName Repository name (never null)
     * @param policy SNAPSHOT policy; null removes any existing override
     */
    public void setRepoNameSnapshotOverride(final String repoName, final SnapshotPolicy policy) {
        Objects.requireNonNull(repoName, "repoName");
        final Map<String, SnapshotPolicy> copy = new HashMap<>(this.repoNameSnapshotOverrides);
        if (policy == null) {
            copy.remove(repoName);
        } else {
            copy.put(repoName, policy);
        }
        this.repoNameSnapshotOverrides = copy;
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

    /**
     * SNAPSHOT-only override sub-knobs. Each field is optional: an empty
     * Optional inherits from the next-higher tier in the precedence ladder
     * (per-repo override → global SNAPSHOT policy → per-type override → global
     * default). Use {@link #inherit()} to indicate "no override".
     */
    public static final class SnapshotPolicy {

        /**
         * Singleton "inherit everything" instance.
         */
        private static final SnapshotPolicy INHERIT = new SnapshotPolicy(null, null);

        private final Boolean enabled;
        private final Duration minimumAllowedAge;

        private SnapshotPolicy(final Boolean enabled, final Duration minimumAllowedAge) {
            this.enabled = enabled;
            this.minimumAllowedAge = minimumAllowedAge;
        }

        /**
         * @return Singleton policy that inherits both fields
         */
        public static SnapshotPolicy inherit() {
            return INHERIT;
        }

        /**
         * Build a policy. Either argument may be null to inherit just that
         * field from the next-higher tier.
         *
         * @param enabled Whether cooldown is enabled for SNAPSHOTs
         * @param minimumAllowedAge Cooldown duration for SNAPSHOTs
         * @return New policy
         */
        public static SnapshotPolicy of(final Boolean enabled, final Duration minimumAllowedAge) {
            if (enabled == null && minimumAllowedAge == null) {
                return INHERIT;
            }
            return new SnapshotPolicy(enabled, minimumAllowedAge);
        }

        /**
         * @return Optional enabled flag — present means this tier sets it
         */
        public Optional<Boolean> enabled() {
            return Optional.ofNullable(this.enabled);
        }

        /**
         * @return Optional minimum allowed age — present means this tier sets it
         */
        public Optional<Duration> minimumAllowedAge() {
            return Optional.ofNullable(this.minimumAllowedAge);
        }

        /**
         * @return true if both fields are unset (inherit everything)
         */
        public boolean isInherit() {
            return this.enabled == null && this.minimumAllowedAge == null;
        }
    }
}
