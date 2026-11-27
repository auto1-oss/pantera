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

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link FailedLoginsConfig}: the policy of the login delay and
 * the account lock.
 */
final class FailedLoginsConfigTest {

    @Test
    void defaultsDelayFromTheSecondFailureAndLockAtTheThird() {
        final FailedLoginsConfig cfg = FailedLoginsConfig.defaults();
        MatcherAssert.assertThat(
            "delays start with the second failure",
            cfg.delayFromFailure(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the first delay unit is one second",
            cfg.firstDelaySeconds(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "a delay is never longer than a minute",
            cfg.maxDelaySeconds(), new IsEqual<>(60)
        );
        MatcherAssert.assertThat(
            "the lock comes after three failures",
            cfg.lockAfterFailures(), new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "only an admin unlocks",
            cfg.unlockAfterMinutes(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "failures are never forgotten by time",
            cfg.forgetAfterMinutes(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "a lock blocks every credential",
            cfg.lockAllCredentials(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "rejected API keys are not counted",
            cfg.countRejectedKeys(), new IsEqual<>(false)
        );
    }

    @ParameterizedTest
    @CsvSource({
        // failures, delay in seconds
        "0, 0", "1, 0", "2, 4", "3, 9", "4, 16", "7, 49", "8, 60", "9, 60", "100, 60",
        "2147483647, 60"
    })
    void delayGrowsWithTheSquareOfTheFailuresUpToTheCap(final int failures, final long delay) {
        MatcherAssert.assertThat(
            new FailedLoginsConfig(2, 1, 60, 0, 0, 0, false).delaySeconds(failures),
            new IsEqual<>(delay)
        );
    }

    @ParameterizedTest
    @CsvSource({
        // from, first, max, failures, delay
        "1, 1, 60, 1, 1",
        "5, 1, 60, 4, 0",
        "5, 1, 60, 5, 25",
        "2, 60, 3600, 2, 240",
        "2, 60, 3600, 8, 3600",
        "2, 60, 3600, 100, 3600",
        "2, 5, 5, 2, 5",
        "0, 1, 60, 50, 0"
    })
    void delayFollowsTheConfiguredValues(
        final int from, final int first, final int max, final int failures, final long delay
    ) {
        MatcherAssert.assertThat(
            new FailedLoginsConfig(from, first, max, 0, 0, 0, false).delaySeconds(failures),
            new IsEqual<>(delay)
        );
    }

    @ParameterizedTest
    @CsvSource({
        // lock after, failures, locks
        "3, 2, false", "3, 3, true", "3, 4, true", "1, 1, true", "100, 99, false",
        "100, 100, true", "0, 1, false", "0, 1000, false"
    })
    void lockComesAtTheConfiguredFailure(
        final int after, final int failures, final boolean locks
    ) {
        MatcherAssert.assertThat(
            new FailedLoginsConfig(2, 1, 60, after, 0, 0, false).locks(failures),
            new IsEqual<>(locks)
        );
    }

    @ParameterizedTest
    @CsvSource({
        // from, first, max, lock, unlock
        "-1, 1, 60, 3, 0",
        "101, 1, 60, 3, 0",
        "2, 0, 60, 3, 0",
        "2, 61, 3600, 3, 0",
        "2, 1, 0, 3, 0",
        "2, 1, 3601, 3, 0",
        "2, 30, 10, 3, 0",
        "2, 1, 60, -1, 0",
        "2, 1, 60, 101, 0",
        "2, 1, 60, 3, -1",
        "2, 1, 60, 3, 1441"
    })
    void valuesOutsideTheirRangeAreRejected(
        final int from, final int first, final int max, final int lock, final int unlock
    ) {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new FailedLoginsConfig(from, first, max, lock, unlock, 0, false)
        );
    }

    @ParameterizedTest
    @CsvSource({"-1", "43201"})
    void forgetTimeOutsideItsRangeIsRejected(final int forget) {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new FailedLoginsConfig(2, 1, 60, 3, 0, forget, false)
        );
    }

    @ParameterizedTest
    @CsvSource({"0", "1", "43200"})
    void forgetTimeAtTheEndsOfItsRangeIsAccepted(final int forget) {
        Assertions.assertDoesNotThrow(
            () -> new FailedLoginsConfig(2, 1, 60, 3, 0, forget, false)
        );
    }

    @ParameterizedTest
    @CsvSource({
        "0, 1, 1, 0, 0",
        "100, 60, 3600, 100, 1440",
        "2, 60, 60, 1, 1"
    })
    void valuesAtTheEndsOfTheirRangeAreAccepted(
        final int from, final int first, final int max, final int lock, final int unlock
    ) {
        Assertions.assertDoesNotThrow(
            () -> new FailedLoginsConfig(from, first, max, lock, unlock, 0, true)
        );
    }

    @Test
    void defaultsPauseAfterFiveUnconfirmedSecondFactorsForFifteenMinutes() {
        final FailedLoginsConfig def = FailedLoginsConfig.defaults();
        MatcherAssert.assertThat(
            java.util.List.of(def.secondFactorLimit(), def.secondFactorPauseMinutes()),
            new IsEqual<>(java.util.List.of(5, 15))
        );
    }

    @ParameterizedTest
    @CsvSource({"-1, 15", "101, 15", "5, 0", "5, 1441"})
    void secondFactorValuesOutsideTheirRangeAreRejected(final int limit, final int pause) {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> FailedLoginsConfig.defaults().withSecondFactor(limit, pause)
        );
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "100, 1440"})
    void secondFactorValuesAtTheEndsOfTheirRangeAreAccepted(final int limit, final int pause) {
        Assertions.assertDoesNotThrow(
            () -> FailedLoginsConfig.defaults().withSecondFactor(limit, pause)
        );
    }

    @ParameterizedTest
    @CsvSource({"0, 10, false", "5, 4, false", "5, 5, true", "5, 6, true"})
    void pauseComesAtTheLimit(final int limit, final int unconfirmed, final boolean pauses) {
        MatcherAssert.assertThat(
            FailedLoginsConfig.defaults().withSecondFactor(limit, 15).pauses(unconfirmed),
            new IsEqual<>(pauses)
        );
    }
}
