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
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LoginState}: what the failed logins of one user name
 * mean under a policy, at a given time.
 */
final class LoginStateTest {

    /**
     * A point in time.
     */
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * Default policy: delay from the second failure, lock at the third,
     * only an admin unlocks.
     */
    private static final FailedLoginsConfig POLICY = FailedLoginsConfig.defaults();

    /**
     * Policy without a lock.
     */
    private static final FailedLoginsConfig NO_LOCK =
        new FailedLoginsConfig(2, 1, 60, 0, 0, 0, false);

    /**
     * Policy that unlocks by itself after 30 minutes.
     */
    private static final FailedLoginsConfig AUTO_UNLOCK =
        new FailedLoginsConfig(2, 1, 60, 3, 30, 0, false);

    @Test
    void nameWithoutFailuresIsAdmitted() {
        MatcherAssert.assertThat(
            LoginState.NONE.admission(LoginStateTest.NOW, LoginStateTest.POLICY),
            new IsEqual<>(Admission.OPEN)
        );
    }

    @Test
    void firstFailureBringsNoDelayAndNoLock() {
        final LoginState state =
            LoginState.NONE.failed(LoginStateTest.NOW, LoginStateTest.POLICY);
        MatcherAssert.assertThat(
            "one failure is counted", state.failures(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the next login is admitted at once",
            state.admission(LoginStateTest.NOW, LoginStateTest.POLICY),
            new IsEqual<>(Admission.OPEN)
        );
    }

    @Test
    void secondFailureDelaysTheNextLoginByFourSeconds() {
        final LoginState state = LoginState.NONE
            .failed(LoginStateTest.NOW, LoginStateTest.POLICY)
            .failed(LoginStateTest.NOW, LoginStateTest.POLICY);
        MatcherAssert.assertThat(
            "right after the failure the wait is four seconds",
            state.admission(LoginStateTest.NOW, LoginStateTest.POLICY),
            new IsEqual<>(Admission.delayed(4L))
        );
        MatcherAssert.assertThat(
            "a started second counts as a whole one",
            state.admission(LoginStateTest.NOW.plusMillis(3001L), LoginStateTest.POLICY),
            new IsEqual<>(Admission.delayed(1L))
        );
        MatcherAssert.assertThat(
            "when the delay is over the login is admitted",
            state.admission(LoginStateTest.NOW.plusSeconds(4L), LoginStateTest.POLICY),
            new IsEqual<>(Admission.OPEN)
        );
    }

    @Test
    void thirdFailureLocksTheName() {
        final LoginState state = LoginStateTest.failures(3, LoginStateTest.POLICY);
        MatcherAssert.assertThat(
            "the name is locked",
            state.admission(LoginStateTest.NOW, LoginStateTest.POLICY),
            new IsEqual<>(Admission.LOCKED)
        );
        MatcherAssert.assertThat(
            "and stays locked, however long ago",
            state.admission(
                LoginStateTest.NOW.plus(Duration.ofDays(3650)), LoginStateTest.POLICY
            ),
            new IsEqual<>(Admission.LOCKED)
        );
    }

    @Test
    void lockKeepsTheTimeItWasSet() {
        final LoginState locked = LoginStateTest.failures(3, LoginStateTest.POLICY);
        final LoginState again =
            locked.failed(LoginStateTest.NOW.plusSeconds(600L), LoginStateTest.POLICY);
        MatcherAssert.assertThat(
            again.lockedAt(), new IsEqual<>(locked.lockedAt())
        );
    }

    @Test
    void withoutLockTheDelayGrowsToItsCap() {
        final LoginState state = LoginStateTest.failures(8, LoginStateTest.NO_LOCK);
        MatcherAssert.assertThat(
            "eight failures do not lock",
            state.locked(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the wait is the longest delay",
            state.admission(LoginStateTest.NOW, LoginStateTest.NO_LOCK),
            new IsEqual<>(Admission.delayed(60L))
        );
    }

    @Test
    void lockEndsByItselfWhenAutomaticUnlockIsOn() {
        final LoginState state = LoginStateTest.failures(3, LoginStateTest.AUTO_UNLOCK);
        MatcherAssert.assertThat(
            "one second before the time the name is locked",
            state.admission(
                LoginStateTest.NOW.plusSeconds(30L * 60L - 1L), LoginStateTest.AUTO_UNLOCK
            ),
            new IsEqual<>(Admission.LOCKED)
        );
        MatcherAssert.assertThat(
            "at the time it is admitted",
            state.admission(
                LoginStateTest.NOW.plusSeconds(30L * 60L), LoginStateTest.AUTO_UNLOCK
            ),
            new IsEqual<>(Admission.OPEN)
        );
    }

    @Test
    void failureAfterAnExpiredLockStartsANewCount() {
        final LoginState state = LoginStateTest.failures(3, LoginStateTest.AUTO_UNLOCK)
            .failed(LoginStateTest.NOW.plusSeconds(3600L), LoginStateTest.AUTO_UNLOCK);
        MatcherAssert.assertThat(
            "the count starts again", state.failures(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "and the name is not locked", state.locked(), new IsEqual<>(false)
        );
    }

    @Test
    void lockSetUnderAnEarlierPolicyHoldsWhenTheLimitIsRaised() {
        final LoginState state = LoginStateTest.failures(3, LoginStateTest.POLICY);
        MatcherAssert.assertThat(
            state.admission(
                LoginStateTest.NOW, new FailedLoginsConfig(2, 1, 60, 50, 0, 0, false)
            ),
            new IsEqual<>(Admission.LOCKED)
        );
    }

    @Test
    void delayIsNotEnforcedOnceDelaysAreSwitchedOff() {
        final LoginState state = LoginStateTest.failures(2, LoginStateTest.NO_LOCK);
        MatcherAssert.assertThat(
            state.admission(
                LoginStateTest.NOW, new FailedLoginsConfig(0, 1, 60, 0, 0, 0, false)
            ),
            new IsEqual<>(Admission.OPEN)
        );
    }

    @Test
    void failuresOlderThanTheForgetTimeDoNotCount() {
        final FailedLoginsConfig forgetful = new FailedLoginsConfig(2, 1, 60, 3, 0, 30, false);
        final LoginState two = LoginStateTest.failures(2, forgetful);
        MatcherAssert.assertThat(
            "one second before the time, a failure is the third and locks",
            two.failed(LoginStateTest.NOW.plusSeconds(30L * 60L - 1L), forgetful).locked(),
            new IsEqual<>(true)
        );
        final LoginState later =
            two.failed(LoginStateTest.NOW.plusSeconds(30L * 60L), forgetful);
        MatcherAssert.assertThat(
            "at the time, a failure is the first of a new count",
            later.failures(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "and locks nobody", later.locked(), new IsEqual<>(false)
        );
    }

    @Test
    void lockIsNotForgottenByTime() {
        final FailedLoginsConfig forgetful = new FailedLoginsConfig(2, 1, 60, 3, 0, 30, false);
        final LoginState locked = LoginStateTest.failures(3, forgetful);
        MatcherAssert.assertThat(
            "the forget time ends no lock",
            locked.admission(LoginStateTest.NOW.plus(Duration.ofDays(30)), forgetful),
            new IsEqual<>(Admission.LOCKED)
        );
        MatcherAssert.assertThat(
            "and a failure of a locked name keeps the lock",
            locked.failed(LoginStateTest.NOW.plus(Duration.ofDays(30)), forgetful).locked(),
            new IsEqual<>(true)
        );
    }

    @Test
    void withoutForgetTimeFailuresCountForever() {
        final LoginState two = LoginStateTest.failures(2, LoginStateTest.POLICY);
        MatcherAssert.assertThat(
            two.failed(LoginStateTest.NOW.plus(Duration.ofDays(3650)), LoginStateTest.POLICY)
                .locked(),
            new IsEqual<>(true)
        );
    }

    @Test
    void lockIsNotInForceOnceTheLockIsSwitchedOff() {
        final LoginState locked = LoginStateTest.failures(3, LoginStateTest.POLICY);
        MatcherAssert.assertThat(
            "with the lock off, a recorded lock stops nobody",
            locked.admission(LoginStateTest.NOW.plusSeconds(600L), LoginStateTest.NO_LOCK),
            new IsEqual<>(Admission.OPEN)
        );
        MatcherAssert.assertThat(
            "and the next failure starts a new count",
            locked.failed(LoginStateTest.NOW.plusSeconds(600L), LoginStateTest.NO_LOCK)
                .failures(),
            new IsEqual<>(1)
        );
    }

    @Test
    void countDoesNotOverflow() {
        final LoginState state = new LoginState(Integer.MAX_VALUE, null, null)
            .failed(LoginStateTest.NOW, LoginStateTest.NO_LOCK);
        MatcherAssert.assertThat(
            state.failures(), new IsEqual<>(Integer.MAX_VALUE)
        );
    }

    /**
     * State after a number of failures at the same moment.
     * @param count Failures
     * @param policy Policy
     * @return State
     */
    private static LoginState failures(final int count, final FailedLoginsConfig policy) {
        LoginState state = LoginState.NONE;
        for (int idx = 0; idx < count; idx += 1) {
            state = state.failed(LoginStateTest.NOW, policy);
        }
        return state;
    }
}
