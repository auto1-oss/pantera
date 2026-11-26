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
package com.auto1.pantera.settings.policy;

/**
 * Policy for failed password logins: a delay that grows with the number
 * of failures, then an account lock.
 *
 * <p>The delay follows the temporary login suspension of JFrog Artifactory:
 * from the configured failure on, logins for the user are refused for
 * {@code first delay x failures squared} seconds, up to the longest delay.
 * The lock follows its user lock policy: after the configured number of
 * failures the user is locked until an admin unlocks it, or until the
 * automatic unlock time has passed when one is set.</p>
 *
 * <p>Every value is an admin setting (card "Failed logins", keys
 * {@code failed_logins_*} in {@code auth_settings}).</p>
 *
 * @param delayFromFailure Failure number from which logins are delayed,
 *  0 for no delay
 * @param firstDelaySeconds Unit of the delay, in seconds
 * @param maxDelaySeconds Longest delay, in seconds
 * @param lockAfterFailures Failures after which the user is locked, 0 for
 *  no lock
 * @param unlockAfterMinutes Minutes after which a lock ends by itself, 0
 *  when only an admin unlocks
 * @param forgetAfterMinutes Minutes without a new failure after which the
 *  count starts again, 0 when failures are never forgotten by time. It ends
 *  no lock
 * @param countRejectedKeys Whether a rejected API key counts as a failed
 *  login
 * @param lockAllCredentials Whether a lock blocks every credential of the
 *  user (password, API keys, sessions, an identity provider's button), or
 *  password sign-in only
 * @since 2.2.15
 */
public record FailedLoginsConfig(
    int delayFromFailure,
    int firstDelaySeconds,
    int maxDelaySeconds,
    int lockAfterFailures,
    int unlockAfterMinutes,
    int forgetAfterMinutes,
    boolean countRejectedKeys,
    boolean lockAllCredentials
) {

    /**
     * Default failure number from which logins are delayed.
     */
    public static final int DEFAULT_DELAY_FROM = 2;

    /**
     * Default delay unit, in seconds.
     */
    public static final int DEFAULT_FIRST_DELAY = 1;

    /**
     * Default longest delay, in seconds.
     */
    public static final int DEFAULT_MAX_DELAY = 60;

    /**
     * Default number of failures after which the user is locked.
     */
    public static final int DEFAULT_LOCK_AFTER = 3;

    /**
     * Default automatic unlock time: none, only an admin unlocks.
     */
    public static final int DEFAULT_UNLOCK_AFTER = 0;

    /**
     * Highest failure number a setting may name.
     */
    public static final int MAX_FAILURES = 100;

    /**
     * Longest delay unit, in seconds.
     */
    public static final int MAX_FIRST_DELAY = 60;

    /**
     * Longest delay a setting may name, in seconds.
     */
    public static final int MAX_MAX_DELAY = 3600;

    /**
     * Longest automatic unlock time, in minutes.
     */
    public static final int MAX_UNLOCK_AFTER = 1440;

    /**
     * Default forget time: none, failures count until a successful
     * password login or an unlock.
     */
    public static final int DEFAULT_FORGET_AFTER = 0;

    /**
     * Longest forget time, in minutes (30 days).
     */
    public static final int MAX_FORGET_AFTER = 43_200;

    /**
     * Ctor.
     */
    public FailedLoginsConfig {
        FailedLoginsConfig.inRange(
            "failed_logins_delay_from_failure", delayFromFailure, 0,
            FailedLoginsConfig.MAX_FAILURES
        );
        FailedLoginsConfig.inRange(
            "failed_logins_first_delay_seconds", firstDelaySeconds, 1,
            FailedLoginsConfig.MAX_FIRST_DELAY
        );
        FailedLoginsConfig.inRange(
            "failed_logins_max_delay_seconds", maxDelaySeconds, 1,
            FailedLoginsConfig.MAX_MAX_DELAY
        );
        if (maxDelaySeconds < firstDelaySeconds) {
            throw new IllegalArgumentException(
                "failed_logins_max_delay_seconds must not be less than"
                    + " failed_logins_first_delay_seconds"
            );
        }
        FailedLoginsConfig.inRange(
            "failed_logins_lock_after", lockAfterFailures, 0,
            FailedLoginsConfig.MAX_FAILURES
        );
        FailedLoginsConfig.inRange(
            "failed_logins_unlock_after_minutes", unlockAfterMinutes, 0,
            FailedLoginsConfig.MAX_UNLOCK_AFTER
        );
        FailedLoginsConfig.inRange(
            "failed_logins_forget_after_minutes", forgetAfterMinutes, 0,
            FailedLoginsConfig.MAX_FORGET_AFTER
        );
    }

    /**
     * Policy whose lock blocks every credential, the default.
     *
     * @param delayFromFailure Failure number from which logins are delayed
     * @param firstDelaySeconds Unit of the delay, in seconds
     * @param maxDelaySeconds Longest delay, in seconds
     * @param lockAfterFailures Failures after which the user is locked
     * @param unlockAfterMinutes Minutes after which a lock ends by itself
     * @param forgetAfterMinutes Minutes after which failures are forgotten
     * @param countRejectedKeys Whether a rejected API key counts
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public FailedLoginsConfig(
        final int delayFromFailure, final int firstDelaySeconds, final int maxDelaySeconds,
        final int lockAfterFailures, final int unlockAfterMinutes,
        final int forgetAfterMinutes, final boolean countRejectedKeys
    ) {
        this(
            delayFromFailure, firstDelaySeconds, maxDelaySeconds, lockAfterFailures,
            unlockAfterMinutes, forgetAfterMinutes, countRejectedKeys, true
        );
    }

    /**
     * The defaults: delay from the second failure, one second unit, one
     * minute at most, lock after three failures, only an admin unlocks,
     * failures never forgotten by time, rejected API keys not counted, a
     * lock blocks every credential.
     *
     * @return Default policy
     */
    public static FailedLoginsConfig defaults() {
        return new FailedLoginsConfig(
            FailedLoginsConfig.DEFAULT_DELAY_FROM, FailedLoginsConfig.DEFAULT_FIRST_DELAY,
            FailedLoginsConfig.DEFAULT_MAX_DELAY, FailedLoginsConfig.DEFAULT_LOCK_AFTER,
            FailedLoginsConfig.DEFAULT_UNLOCK_AFTER, FailedLoginsConfig.DEFAULT_FORGET_AFTER,
            false, true
        );
    }

    /**
     * How long logins are refused after the given number of failures.
     *
     * @param failures Failures counted for the user
     * @return Seconds, 0 for no delay
     */
    public long delaySeconds(final int failures) {
        final long delay;
        if (this.delayFromFailure == 0 || failures < this.delayFromFailure) {
            delay = 0L;
        } else {
            final long squared = (long) failures * failures;
            if (squared >= this.maxDelaySeconds) {
                // Also keeps the product below from overflowing.
                delay = this.maxDelaySeconds;
            } else {
                delay = Math.min(this.maxDelaySeconds, this.firstDelaySeconds * squared);
            }
        }
        return delay;
    }

    /**
     * Whether the given number of failures locks the user.
     *
     * @param failures Failures counted for the user
     * @return True when the lock is on and the limit is reached
     */
    public boolean locks(final int failures) {
        return this.lockAfterFailures > 0 && failures >= this.lockAfterFailures;
    }

    /**
     * Whether a lock ends by itself.
     *
     * @return True when an automatic unlock time is set
     */
    public boolean unlocksAutomatically() {
        return this.unlockAfterMinutes > 0;
    }

    private static void inRange(final String key, final int value, final int min, final int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                String.format("%s must be between %d and %d", key, min, max)
            );
        }
    }
}
