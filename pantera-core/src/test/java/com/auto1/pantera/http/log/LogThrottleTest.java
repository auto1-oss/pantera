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
package com.auto1.pantera.http.log;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LogThrottle}.
 *
 * @since 2.2.14
 */
final class LogThrottleTest {

    @Test
    void firstOccurrenceIsLogged() {
        MatcherAssert.assertThat(
            new LogThrottle(Duration.ofHours(1), 16, () -> 0L).admit("a"),
            new IsEqual<>(OptionalLong.of(0L))
        );
    }

    @Test
    void repeatsWithinTheIntervalAreSkipped() {
        final LogThrottle throttle = new LogThrottle(Duration.ofHours(1), 16, () -> 0L);
        throttle.admit("a");
        MatcherAssert.assertThat(throttle.admit("a"), new IsEqual<>(OptionalLong.empty()));
    }

    @Test
    void afterTheIntervalOneRecordReportsWhatWasSkipped() {
        final AtomicLong now = new AtomicLong(0L);
        final LogThrottle throttle = new LogThrottle(Duration.ofHours(1), 16, now::get);
        throttle.admit("a");
        for (int idx = 0; idx < 339; idx += 1) {
            throttle.admit("a");
        }
        now.set(Duration.ofHours(1).toMillis());
        MatcherAssert.assertThat(
            "the record after the interval carries the skipped count",
            throttle.admit("a"), new IsEqual<>(OptionalLong.of(339L))
        );
        MatcherAssert.assertThat(
            "and the next occurrence is skipped again",
            throttle.admit("a"), new IsEqual<>(OptionalLong.empty())
        );
    }

    @Test
    void keysAreIndependent() {
        final LogThrottle throttle = new LogThrottle(Duration.ofHours(1), 16, () -> 0L);
        throttle.admit("registry-1.docker.io|auth.docker.io");
        MatcherAssert.assertThat(
            throttle.admit("pypi.org|files.pythonhosted.org"),
            new IsEqual<>(OptionalLong.of(0L))
        );
    }
}
