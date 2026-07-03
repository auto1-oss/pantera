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
package com.auto1.pantera.http.context;

import java.time.Duration;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Deadline} — verifies the §3.4 contract:
 * {@link #in_createsDeadlineWithPositiveRemaining monotonic construction},
 * {@link #expired_returnsFalseInitially expired behaviour} at and after
 * the boundary, non-negative {@link Deadline#remaining()}, and the clamp
 * semantics of {@link Deadline#remainingClamped(Duration)}.
 */
final class DeadlineTest {

    @Test
    @DisplayName("Deadline.in(d) produces a deadline with ~d remaining")
    void inCreatesDeadlineWithPositiveRemaining() {
        final Deadline d = Deadline.in(Duration.ofSeconds(5));
        final Duration remaining = d.remaining();
        MatcherAssert.assertThat(
            "remaining > 0",
            remaining.toMillis() > 0L, Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "remaining ≤ the budget",
            remaining.compareTo(Duration.ofSeconds(5)) <= 0, Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "remaining close to the budget (≥4s allows for slow CI)",
            remaining.compareTo(Duration.ofSeconds(4)) >= 0, Matchers.is(true)
        );
    }

    @Test
    @DisplayName("expired() is false immediately after construction with a positive budget")
    void expiredReturnsFalseInitially() {
        final Deadline d = Deadline.in(Duration.ofSeconds(2));
        MatcherAssert.assertThat("not expired", d.expired(), Matchers.is(false));
    }

    @Test
    @DisplayName("expired() flips to true once the budget has elapsed")
    void expiredReturnsTrueAfterPassing() throws InterruptedException {
        final Deadline d = Deadline.in(Duration.ofMillis(25));
        // Sleep longer than the TTL; 150ms slack for scheduling jitter on CI.
        Thread.sleep(150L);
        MatcherAssert.assertThat("expired", d.expired(), Matchers.is(true));
    }

    @Test
    @DisplayName("remaining() is clamped at Duration.ZERO once the deadline has passed")
    void remainingClampsToZeroAfterExpiry() throws InterruptedException {
        final Deadline d = Deadline.in(Duration.ofMillis(10));
        Thread.sleep(100L);
        final Duration rem = d.remaining();
        MatcherAssert.assertThat(
            "remaining is ZERO", rem, Matchers.is(Duration.ZERO)
        );
        MatcherAssert.assertThat(
            "never negative", rem.isNegative(), Matchers.is(false)
        );
    }

    @Test
    @DisplayName("remainingClamped(max) returns max when the remaining budget exceeds it")
    void remainingClampedCapsAtMax() {
        final Deadline d = Deadline.in(Duration.ofSeconds(30));
        final Duration cap = Duration.ofSeconds(5);
        final Duration clamped = d.remainingClamped(cap);
        MatcherAssert.assertThat(
            "capped at the max", clamped, Matchers.is(cap)
        );
    }

    @Test
    @DisplayName("remainingClamped(max) returns the remaining budget when it is below max")
    void remainingClampedPassThroughWhenBelowMax() {
        final Deadline d = Deadline.in(Duration.ofMillis(500));
        final Duration cap = Duration.ofMinutes(5);
        final Duration clamped = d.remainingClamped(cap);
        MatcherAssert.assertThat(
            "pass-through, strictly less than cap",
            clamped.compareTo(cap) < 0, Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "pass-through, ≤ initial budget",
            clamped.compareTo(Duration.ofMillis(500)) <= 0, Matchers.is(true)
        );
    }

    @Test
    @DisplayName("remainingClamped(null) throws NullPointerException")
    void remainingClampedRejectsNull() {
        final Deadline d = Deadline.in(Duration.ofSeconds(1));
        try {
            d.remainingClamped(null);
            MatcherAssert.assertThat("expected NPE", false, Matchers.is(true));
        } catch (final NullPointerException expected) {
            // success
            MatcherAssert.assertThat(
                "NPE message references 'max'",
                expected.getMessage(), Matchers.containsString("max")
            );
        }
    }

    @Test
    @DisplayName("expiresAt() returns an Instant close to now + remaining")
    void expiresAtReturnsFutureInstantForPositiveBudget() {
        final Deadline d = Deadline.in(Duration.ofSeconds(10));
        final Duration diff = Duration.between(java.time.Instant.now(), d.expiresAt());
        MatcherAssert.assertThat(
            "expiresAt is in the future",
            diff.isNegative(), Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "within the budget",
            diff.compareTo(Duration.ofSeconds(11)) <= 0, Matchers.is(true)
        );
    }
}
