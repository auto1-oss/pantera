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

import java.time.Duration;
import com.auto1.pantera.settings.policy.LoginThrottleConfig;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LoginThrottle} — SecOps import-misc: the public password
 * login endpoint had no attempt throttling, allowing unbounded online
 * credential guessing. After a threshold of failures for a (username, client
 * IP) key, further attempts are locked out for a window.
 *
 * @since 2.2.9
 */
final class LoginThrottleTest {

    @Test
    void lockoutTripsAfterThresholdFailures() {
        final AtomicLong now = new AtomicLong();
        final LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), now::get);
        MatcherAssert.assertThat(
            "not throttled before any failure",
            throttle.isThrottled("alice|1.2.3.4"), new IsEqual<>(false)
        );
        throttle.recordFailure("alice|1.2.3.4");
        throttle.recordFailure("alice|1.2.3.4");
        MatcherAssert.assertThat(
            "not throttled below the threshold",
            throttle.isThrottled("alice|1.2.3.4"), new IsEqual<>(false)
        );
        throttle.recordFailure("alice|1.2.3.4");
        MatcherAssert.assertThat(
            "throttled once the failure threshold is reached",
            throttle.isThrottled("alice|1.2.3.4"), new IsEqual<>(true)
        );
    }

    @Test
    void successClearsCounter() {
        final AtomicLong now = new AtomicLong();
        final LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), now::get);
        throttle.recordFailure("bob|1.2.3.4");
        throttle.recordFailure("bob|1.2.3.4");
        throttle.recordSuccess("bob|1.2.3.4");
        throttle.recordFailure("bob|1.2.3.4");
        MatcherAssert.assertThat(
            "a successful login resets the failure count",
            throttle.isThrottled("bob|1.2.3.4"), new IsEqual<>(false)
        );
    }

    @Test
    void keysAreIndependent() {
        final AtomicLong now = new AtomicLong();
        final LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), now::get);
        for (int i = 0; i < 3; i = i + 1) {
            throttle.recordFailure("alice|1.2.3.4");
        }
        MatcherAssert.assertThat(
            "a different (user, ip) key is not affected by another's lockout",
            throttle.isThrottled("alice|9.9.9.9"), new IsEqual<>(false)
        );
    }

    @Test
    void lockoutExpiresAfterWindow() {
        final AtomicLong now = new AtomicLong();
        final LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), now::get);
        for (int i = 0; i < 3; i = i + 1) {
            throttle.recordFailure("alice|1.2.3.4");
        }
        MatcherAssert.assertThat(
            "throttled immediately after the failures",
            throttle.isThrottled("alice|1.2.3.4"), new IsEqual<>(true)
        );
        now.addAndGet(Duration.ofMinutes(16).toNanos());
        MatcherAssert.assertThat(
            "lockout lifts after the window elapses",
            throttle.isThrottled("alice|1.2.3.4"), new IsEqual<>(false)
        );
    }

    @Test
    void thresholdChangesApplyToTheNextCheck() {
        final AtomicLong now = new AtomicLong();
        final AtomicReference<LoginThrottleConfig> config =
            new AtomicReference<>(new LoginThrottleConfig(5, 900));
        final LoginThrottle throttle = new LoginThrottle(config::get, now::get);
        throttle.recordFailure("carol|1.2.3.4");
        throttle.recordFailure("carol|1.2.3.4");
        MatcherAssert.assertThat(
            "two failures are under the initial threshold of five",
            throttle.isThrottled("carol|1.2.3.4"), new IsEqual<>(false)
        );
        config.set(new LoginThrottleConfig(2, 900));
        MatcherAssert.assertThat(
            "lowering the threshold to two applies on the next check",
            throttle.isThrottled("carol|1.2.3.4"), new IsEqual<>(true)
        );
    }
}
