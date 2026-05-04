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
package com.auto1.pantera.prefetch;

import com.auto1.pantera.settings.runtime.CircuitBreakerTuning;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PrefetchCircuitBreaker}.
 *
 * <p>Trip math: the breaker opens when {@code drops > threshold * window}
 * within the trailing {@code window} seconds, i.e. recording one more drop
 * than {@code threshold * window} guarantees a trip. We use a mutable clock
 * so we can drive the trailing-window window without sleeping.</p>
 *
 * @since 2.2.0
 */
class PrefetchCircuitBreakerTest {

    @Test
    void tripsAfterDropThresholdExceeded() {
        // threshold=10/sec, window=5s → trip strictly above 50 drops in window.
        final CircuitBreakerTuning tuning = new CircuitBreakerTuning(10, 5, 5);
        final MutableClock clock = new MutableClock(Instant.parse("2026-05-04T10:00:00Z"));
        final PrefetchCircuitBreaker breaker = new PrefetchCircuitBreaker(() -> tuning, clock);

        for (int idx = 0; idx < tuning.dropThresholdPerSec() * tuning.windowSeconds(); idx += 1) {
            breaker.recordDrop();
            clock.advance(Duration.ofMillis(50));
        }
        // 50 drops, exactly at threshold — must NOT yet be open.
        MatcherAssert.assertThat(breaker.isOpen(), new IsEqual<>(false));

        breaker.recordDrop();
        // 51st drop within window — must trip.
        MatcherAssert.assertThat(breaker.isOpen(), new IsEqual<>(true));
    }

    @Test
    void closesAfterDisableMinutes() {
        final CircuitBreakerTuning tuning = new CircuitBreakerTuning(10, 5, 5);
        final MutableClock clock = new MutableClock(Instant.parse("2026-05-04T10:00:00Z"));
        final PrefetchCircuitBreaker breaker = new PrefetchCircuitBreaker(() -> tuning, clock);

        for (int idx = 0; idx <= tuning.dropThresholdPerSec() * tuning.windowSeconds(); idx += 1) {
            breaker.recordDrop();
        }
        MatcherAssert.assertThat(
            "breaker should be tripped",
            breaker.isOpen(),
            new IsEqual<>(true)
        );
        clock.advance(Duration.ofMinutes(tuning.disableMinutes()).plusSeconds(1L));
        MatcherAssert.assertThat(
            "breaker should auto-close after disableMinutes",
            breaker.isOpen(),
            new IsEqual<>(false)
        );
    }

    @Test
    void honorsTuningChange() {
        final AtomicReference<CircuitBreakerTuning> ref = new AtomicReference<>(
            new CircuitBreakerTuning(1_000_000, 60, 5)
        );
        final MutableClock clock = new MutableClock(Instant.parse("2026-05-04T10:00:00Z"));
        final Supplier<CircuitBreakerTuning> supplier = ref::get;
        final PrefetchCircuitBreaker breaker = new PrefetchCircuitBreaker(supplier, clock);

        for (int idx = 0; idx < 11; idx += 1) {
            breaker.recordDrop();
        }
        MatcherAssert.assertThat(
            "stays closed under high-threshold tuning",
            breaker.isOpen(),
            new IsEqual<>(false)
        );

        // Tighten: threshold=10, window=30 → trip strictly above 300.
        // Since we already recorded 11 drops in this window and that is below
        // 10*30=300, we should still be closed; bring us above by recording
        // (300 - 11) + 1 more drops without advancing the clock.
        ref.set(new CircuitBreakerTuning(10, 30, 5));
        // Now record enough drops to exceed 10/sec for 30s = 300 drops total.
        for (int idx = 0; idx < 300; idx += 1) {
            breaker.recordDrop();
        }
        MatcherAssert.assertThat(
            "trips after tuning is tightened and threshold is exceeded",
            breaker.isOpen(),
            new IsEqual<>(true)
        );
    }

    /**
     * Test-only fake clock — package-private, mutable instant.
     */
    private static final class MutableClock extends java.time.Clock {
        private Instant now;

        MutableClock(final Instant start) {
            this.now = start;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }

        void advance(final Duration delta) {
            this.now = this.now.plus(delta);
        }
    }
}
