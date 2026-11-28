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
package com.auto1.pantera.auth.failedlogins;

import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.time.Duration;
import java.time.Instant;

/**
 * Failed logins counted for one user name, and what they mean under a
 * policy at a given time. The rules of the login delay and the account
 * lock live here and nowhere else: every store keeps this state and asks
 * it.
 *
 * <ul>
 *   <li>A delay is not stored: it is the time of the last failure plus the
 *   delay the policy gives for the count, so a changed policy applies at
 *   once.</li>
 *   <li>A lock is stored with its time and holds whatever the policy says
 *   later about the limit. It ends when an admin removes it, when the
 *   lock is switched off, or, with automatic unlock on, when its time has
 *   passed. A failure after such an end starts a new count.</li>
 *   <li>With a forget time set, a failure that comes that long after the
 *   last one starts a new count as well.</li>
 *   <li>Second factors that were not confirmed are counted apart and never
 *   delay or lock. They count while each comes within the pause time of
 *   the previous one; from the limit on, password sign-in is paused until
 *   the pause time has passed since the last one. Like the delay, the
 *   pause is not stored.</li>
 * </ul>
 *
 * @param failures Failed logins counted
 * @param lastFailure Time of the last one, null when there is none
 * @param lockedAt Time the user was locked, null when not locked
 * @param unconfirmed Second factors not confirmed, counted
 * @param lastUnconfirmed Time of the last one, null when there is none
 * @since 2.2.15
 */
public record LoginState(
    int failures, Instant lastFailure, Instant lockedAt,
    int unconfirmed, Instant lastUnconfirmed
) {

    /**
     * State of a name without failed logins.
     */
    public static final LoginState NONE = new LoginState(0, null, null, 0, null);

    /**
     * State without unconfirmed second factors.
     *
     * @param failures Failed logins counted
     * @param lastFailure Time of the last one, null when there is none
     * @param lockedAt Time the user was locked, null when not locked
     */
    public LoginState(final int failures, final Instant lastFailure, final Instant lockedAt) {
        this(failures, lastFailure, lockedAt, 0, null);
    }

    /**
     * Whether a lock is recorded. It may have expired: see
     * {@link #admission(Instant, FailedLoginsConfig)}.
     *
     * @return True when the user was locked and nobody unlocked it
     */
    public boolean locked() {
        return this.lockedAt != null;
    }

    /**
     * Whether a login for this name may be checked now.
     *
     * @param now Current time
     * @param policy Policy
     * @return Admission
     */
    public Admission admission(final Instant now, final FailedLoginsConfig policy) {
        final Admission res;
        if (this.locked()) {
            if (this.lockExpired(now, policy)) {
                res = Admission.OPEN;
            } else {
                res = Admission.LOCKED;
            }
        } else {
            res = this.waiting(now, policy);
        }
        return res;
    }

    /**
     * The state after one more sign-in whose password was accepted but
     * whose second factor was not confirmed. The failed logins, their delay
     * and the lock stay as they are.
     *
     * @param now Time of the sign-in
     * @param policy Policy
     * @return New state
     */
    public LoginState unconfirmed(final Instant now, final FailedLoginsConfig policy) {
        final int count;
        if (this.lastUnconfirmed == null || !now.isBefore(
            this.lastUnconfirmed.plus(Duration.ofMinutes(policy.secondFactorPauseMinutes()))
        )) {
            count = 1;
        } else if (this.unconfirmed == Integer.MAX_VALUE) {
            count = this.unconfirmed;
        } else {
            count = this.unconfirmed + 1;
        }
        return new LoginState(this.failures, this.lastFailure, this.lockedAt, count, now);
    }

    /**
     * When the pause after unconfirmed second factors ends.
     *
     * @param policy Policy
     * @return The end, null when the count has not reached the limit or the
     *  pause is off. It may lie in the past
     */
    public Instant pausedUntil(final FailedLoginsConfig policy) {
        final Instant res;
        if (this.lastUnconfirmed != null && policy.pauses(this.unconfirmed)) {
            res = this.lastUnconfirmed.plus(Duration.ofMinutes(policy.secondFactorPauseMinutes()));
        } else {
            res = null;
        }
        return res;
    }

    /**
     * The state after one more failed login.
     *
     * @param now Time of the failure
     * @param policy Policy
     * @return New state
     */
    public LoginState failed(final Instant now, final FailedLoginsConfig policy) {
        final LoginState res;
        if (this.lockExpired(now, policy) || this.forgotten(now, policy)) {
            res = new LoginState(0, null, null, this.unconfirmed, this.lastUnconfirmed)
                .failed(now, policy);
        } else {
            final int count;
            if (this.failures == Integer.MAX_VALUE) {
                count = this.failures;
            } else {
                count = this.failures + 1;
            }
            Instant lock = this.lockedAt;
            if (lock == null && policy.locks(count)) {
                lock = now;
            }
            res = new LoginState(count, now, lock, this.unconfirmed, this.lastUnconfirmed);
        }
        return res;
    }

    /**
     * Whether the counted failures are too old to count.
     *
     * @param now Current time
     * @param policy Policy
     * @return True when a forget time is set, it has passed since the last
     *  failure, and the user is not locked: time ends no lock
     */
    public boolean forgotten(final Instant now, final FailedLoginsConfig policy) {
        return !this.locked() && policy.forgetAfterMinutes() > 0 && this.lastFailure != null
            && !now.isBefore(
                this.lastFailure.plus(Duration.ofMinutes(policy.forgetAfterMinutes()))
            );
    }

    /**
     * Whether the recorded lock is no longer in force: the lock was
     * switched off, or automatic unlock is on and its time has passed.
     *
     * @param now Current time
     * @param policy Policy
     * @return True when the recorded lock stops nobody any more
     */
    public boolean lockExpired(final Instant now, final FailedLoginsConfig policy) {
        return this.locked() && (
            policy.lockAfterFailures() == 0
                || policy.unlocksAutomatically() && !now.isBefore(
                    this.lockedAt.plus(Duration.ofMinutes(policy.unlockAfterMinutes()))
                )
        );
    }

    /**
     * What applies to a name that is not locked: the delay after wrong
     * passwords or the pause after unconfirmed second factors, whichever
     * ends later.
     *
     * @param now Current time
     * @param policy Policy
     * @return Open, delayed or paused
     */
    private Admission waiting(final Instant now, final FailedLoginsConfig policy) {
        final long delay = policy.delaySeconds(this.failures);
        Instant delayed = null;
        if (delay > 0L && this.lastFailure != null) {
            delayed = this.lastFailure.plusSeconds(delay);
        }
        final Instant paused = this.pausedUntil(policy);
        final Admission res;
        if (paused != null && now.isBefore(paused)
            && (delayed == null || !paused.isBefore(delayed))) {
            res = Admission.paused(LoginState.seconds(now, paused));
        } else if (delayed != null && now.isBefore(delayed)) {
            res = Admission.delayed(LoginState.seconds(now, delayed));
        } else {
            res = Admission.OPEN;
        }
        return res;
    }

    /**
     * Whole seconds from now until a time, rounded up.
     *
     * @param now Current time
     * @param until Time in the future
     * @return Seconds
     */
    private static long seconds(final Instant now, final Instant until) {
        return (Duration.between(now, until).toMillis() + 999L) / 1000L;
    }
}
