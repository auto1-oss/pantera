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
package com.auto1.pantera.auth;

import com.auto1.pantera.settings.policy.LoginThrottleConfig;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LoginThrottle} — SecOps import-misc: the public password
 * login endpoint had no attempt throttling, allowing unbounded online
 * credential guessing. After a threshold of attempts for a (username, client
 * IP) pair, or a larger budget for the username from any address, further
 * attempts are refused for the window (B16).
 *
 * @since 2.2.9
 */
final class LoginThrottleTest {

    @Test
    void lockoutTripsAfterThresholdAttempts() {
        final LoginThrottle throttle = LoginThrottleTest.throttle(3, new AtomicLong());
        for (int idx = 0; idx < 3; idx += 1) {
            MatcherAssert.assertThat(
                "attempt " + idx + " is under the threshold",
                throttle.admit("alice", "1.2.3.4").isPresent(), new IsEqual<>(false)
            );
        }
        MatcherAssert.assertThat(
            "the attempt after the threshold is refused",
            throttle.admit("alice", "1.2.3.4").isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void attemptsAreCountedBeforeTheCredentialCheckCompletes() {
        // B16: check and record were separate steps, so concurrent requests
        // all passed the check before any failure was recorded.
        final LoginThrottle throttle = LoginThrottleTest.throttle(2, new AtomicLong());
        throttle.admit("bob", "1.2.3.4");
        throttle.admit("bob", "1.2.3.4");
        MatcherAssert.assertThat(
            throttle.admit("bob", "1.2.3.4").isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void successClearsThePairCounter() {
        final LoginThrottle throttle = LoginThrottleTest.throttle(3, new AtomicLong());
        throttle.admit("bob", "1.2.3.4");
        throttle.admit("bob", "1.2.3.4");
        throttle.admit("bob", "1.2.3.4");
        throttle.recordSuccess("bob", "1.2.3.4");
        MatcherAssert.assertThat(
            "a successful login resets the pair's count",
            throttle.admit("bob", "1.2.3.4").isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void otherAddressesKeepTheirOwnBudget() {
        final LoginThrottle throttle = LoginThrottleTest.throttle(3, new AtomicLong());
        for (int idx = 0; idx < 3; idx += 1) {
            throttle.admit("alice", "1.2.3.4");
        }
        MatcherAssert.assertThat(
            "a different client address is not locked by another's failures",
            throttle.admit("alice", "9.9.9.9").isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void usernameBudgetCapsGuessingSpreadOverAddresses() {
        // B16: rotating the client address gave unlimited guesses.
        final LoginThrottle throttle = LoginThrottleTest.throttle(2, new AtomicLong());
        boolean refused = false;
        for (int idx = 0; idx < 100 && !refused; idx += 1) {
            refused = throttle.admit("carol", "10.0.0." + idx).isPresent();
        }
        MatcherAssert.assertThat(refused, new IsEqual<>(true));
    }

    @Test
    void retryAfterIsTheRemainingWindow() {
        final AtomicLong now = new AtomicLong();
        final LoginThrottle throttle = LoginThrottleTest.throttle(1, now);
        throttle.admit("dave", "1.2.3.4");
        now.addAndGet(Duration.ofSeconds(100).toNanos());
        MatcherAssert.assertThat(
            throttle.admit("dave", "1.2.3.4"), new IsEqual<>(OptionalLong.of(800L))
        );
    }

    @Test
    void lockoutExpiresAfterWindow() {
        final AtomicLong now = new AtomicLong();
        final LoginThrottle throttle = LoginThrottleTest.throttle(3, now);
        for (int idx = 0; idx < 3; idx += 1) {
            throttle.admit("alice", "1.2.3.4");
        }
        MatcherAssert.assertThat(
            "refused immediately after the attempts",
            throttle.admit("alice", "1.2.3.4").isPresent(), new IsEqual<>(true)
        );
        now.addAndGet(Duration.ofMinutes(16).toNanos());
        MatcherAssert.assertThat(
            "the lockout lifts after the window elapses",
            throttle.admit("alice", "1.2.3.4").isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void thresholdChangesApplyToTheNextCheck() {
        final AtomicReference<LoginThrottleConfig> config =
            new AtomicReference<>(new LoginThrottleConfig(5, 900));
        final LoginThrottle throttle = new LoginThrottle(config::get, new AtomicLong()::get);
        throttle.admit("erin", "1.2.3.4");
        throttle.admit("erin", "1.2.3.4");
        MatcherAssert.assertThat(
            "two attempts are under the initial threshold of five",
            throttle.admit("erin", "1.2.3.4").isPresent(), new IsEqual<>(false)
        );
        config.set(new LoginThrottleConfig(2, 900));
        MatcherAssert.assertThat(
            "lowering the threshold to two applies on the next check",
            throttle.admit("erin", "1.2.3.4").isPresent(), new IsEqual<>(true)
        );
    }

    private static LoginThrottle throttle(final int max, final AtomicLong now) {
        return new LoginThrottle(max, Duration.ofMinutes(15), now::get);
    }
}
