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
package com.auto1.pantera.http.client.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RetryAfter} parser. Pins the two RFC 7231
 * forms plus the malformed-input fallback.
 *
 * @since 2.2.0
 */
final class RetryAfterTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-10-21T07:28:00Z"), ZoneId.of("UTC")
    );

    @Test
    void parsesDeltaSeconds() {
        MatcherAssert.assertThat(
            RetryAfter.parse("120", FIXED_CLOCK),
            new IsEqual<>(Duration.ofSeconds(120))
        );
    }

    @Test
    void parsesHttpDate() {
        // 21 Oct 2026 07:30:00 GMT is 120 s after the fixed clock.
        MatcherAssert.assertThat(
            RetryAfter.parse("Wed, 21 Oct 2026 07:30:00 GMT", FIXED_CLOCK),
            new IsEqual<>(Duration.ofSeconds(120))
        );
    }

    @Test
    void pastHttpDateBecomesZero() {
        MatcherAssert.assertThat(
            "a date in the past is not a forward delay",
            RetryAfter.parse("Wed, 21 Oct 2020 07:28:00 GMT", FIXED_CLOCK),
            new IsEqual<>(Duration.ZERO)
        );
    }

    @Test
    void nullAndBlankReturnZero() {
        MatcherAssert.assertThat(
            RetryAfter.parse(null, FIXED_CLOCK), new IsEqual<>(Duration.ZERO)
        );
        MatcherAssert.assertThat(
            RetryAfter.parse("", FIXED_CLOCK), new IsEqual<>(Duration.ZERO)
        );
        MatcherAssert.assertThat(
            RetryAfter.parse("   ", FIXED_CLOCK), new IsEqual<>(Duration.ZERO)
        );
    }

    @Test
    void malformedInputFallsBackToZero() {
        MatcherAssert.assertThat(
            RetryAfter.parse("not-a-date", FIXED_CLOCK),
            new IsEqual<>(Duration.ZERO)
        );
        MatcherAssert.assertThat(
            "negative delta-seconds is invalid",
            RetryAfter.parse("-5", FIXED_CLOCK),
            new IsEqual<>(Duration.ZERO)
        );
    }
}
