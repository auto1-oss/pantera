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
package com.auto1.pantera.maven.metadata;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

class MavenTimestampTest {

    @Test
    void nowReturns14DigitString() {
        final String ts = MavenTimestamp.now();
        MatcherAssert.assertThat(
            "now() must return exactly 14 digits",
            ts.matches("\\d{14}"),
            Matchers.is(true)
        );
    }

    @Test
    void formatProducesCorrectValue() {
        final Instant instant = ZonedDateTime.of(
            2026, 2, 13, 12, 0, 0, 0, ZoneOffset.UTC
        ).toInstant();
        MatcherAssert.assertThat(
            "format() must produce yyyyMMddHHmmss in UTC",
            MavenTimestamp.format(instant),
            Matchers.is("20260213120000")
        );
    }

    @Test
    void formatUsesUtcTimezone() {
        final Instant instant = ZonedDateTime.of(
            2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC
        ).toInstant();
        MatcherAssert.assertThat(
            "format() must use UTC regardless of system timezone",
            MavenTimestamp.format(instant),
            Matchers.is("20260101000000")
        );
    }

    @Test
    void nowStartsWithCurrentYear() {
        final String ts = MavenTimestamp.now();
        MatcherAssert.assertThat(
            "now() should start with 20 (year prefix)",
            ts.startsWith("20"),
            Matchers.is(true)
        );
    }
}
