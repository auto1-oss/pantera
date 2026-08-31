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
package com.auto1.pantera.http.resilience;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InFlightGuard} — single-flight semantics plus the
 * abandoned-entry takeover that prevents a hung refresh from pinning its
 * key until restart. Time is injected, never slept on.
 *
 * @since 2.2.7
 */
final class InFlightGuardTest {

    @Test
    void deduplicatesWhileFresh() {
        final AtomicLong now = new AtomicLong();
        final InFlightGuard guard = new InFlightGuard(Duration.ofMinutes(10), now::get);
        MatcherAssert.assertThat(
            "first acquisition must succeed",
            guard.tryBegin("openai"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "second acquisition while in flight must be deduplicated",
            guard.tryBegin("openai"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "an unrelated key must not be blocked",
            guard.tryBegin("axios"), new IsEqual<>(true)
        );
    }

    @Test
    void endReleasesTheKey() {
        final AtomicLong now = new AtomicLong();
        final InFlightGuard guard = new InFlightGuard(Duration.ofMinutes(10), now::get);
        guard.tryBegin("openai");
        guard.end("openai");
        MatcherAssert.assertThat(
            "a released key must be acquirable again",
            guard.tryBegin("openai"), new IsEqual<>(true)
        );
    }

    @Test
    void abandonedEntryIsTakenOver() {
        final AtomicLong now = new AtomicLong();
        final InFlightGuard guard = new InFlightGuard(Duration.ofMinutes(10), now::get);
        MatcherAssert.assertThat(
            "initial acquisition must succeed",
            guard.tryBegin("openai"), new IsEqual<>(true)
        );
        // The holder hangs: no end() ever arrives. Advance past maxAge.
        now.addAndGet(Duration.ofMinutes(10).toNanos() + 1);
        MatcherAssert.assertThat(
            "an entry older than maxAge must be taken over, not skipped forever",
            guard.tryBegin("openai"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the takeover must re-arm deduplication for the new holder",
            guard.tryBegin("openai"), new IsEqual<>(false)
        );
    }

    @Test
    void takeoverIsExclusive() {
        final AtomicLong now = new AtomicLong();
        final InFlightGuard guard = new InFlightGuard(Duration.ofMinutes(10), now::get);
        guard.tryBegin("openai");
        now.addAndGet(Duration.ofMinutes(10).toNanos() + 1);
        final boolean first = guard.tryBegin("openai");
        final boolean second = guard.tryBegin("openai");
        MatcherAssert.assertThat(
            "exactly one caller must win the takeover of an abandoned entry",
            first && !second, new IsEqual<>(true)
        );
    }
}
