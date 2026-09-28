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

import java.time.Instant;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UserRevocation}.
 * @since 2.2.9
 */
final class UserRevocationTest {

    /**
     * Revocation instant, mid-second like a real {@code Instant.now()}.
     */
    private static final Instant AT = Instant.parse("2026-09-23T14:07:22.425Z");

    /**
     * Revocation under test: issued at {@link #AT}, lapses a day later.
     */
    private static final UserRevocation REV =
        new UserRevocation(AT, AT.plusSeconds(86_400));

    @Test
    void revokesTokenIssuedBefore() {
        MatcherAssert.assertThat(
            REV.revokes(Instant.parse("2026-09-23T14:07:21Z"), AT.plusSeconds(5)),
            new IsEqual<>(true)
        );
    }

    @Test
    void revokesTokenIssuedEarlierInTheSameSecond() {
        // B45: comparing at whole seconds let a token issued 400 ms before
        // the revocation (same second) survive it.
        MatcherAssert.assertThat(
            REV.revokes(AT.minusMillis(400), AT.plusSeconds(5)),
            new IsEqual<>(true)
        );
    }

    @Test
    void acceptsTokenIssuedAfterTheRevocation() {
        MatcherAssert.assertThat(
            "A login later in the same second must be accepted",
            REV.revokes(AT.plusMillis(100), AT.plusSeconds(5)),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "A later login must be accepted",
            REV.revokes(Instant.parse("2026-09-23T14:08:00Z"), AT.plusSeconds(60)),
            new IsEqual<>(false)
        );
    }

    @Test
    void revokesTokenWithoutIssuedAt() {
        MatcherAssert.assertThat(
            REV.revokes(null, AT.plusSeconds(5)),
            new IsEqual<>(true)
        );
    }

    @Test
    void lapsesAtExpiry() {
        MatcherAssert.assertThat(
            REV.revokes(Instant.parse("2026-09-23T14:00:00Z"), AT.plusSeconds(86_401)),
            new IsEqual<>(false)
        );
    }

    @Test
    void mergeKeepsTheLaterRevocationAndExpiry() {
        final UserRevocation later =
            new UserRevocation(AT.plusSeconds(30), AT.plusSeconds(3_600));
        MatcherAssert.assertThat(
            REV.merge(later),
            new IsEqual<>(new UserRevocation(AT.plusSeconds(30), AT.plusSeconds(86_400)))
        );
    }
}
