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
package com.auto1.pantera.http.headers;

import java.time.Instant;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link LastModified}.
 *
 * @since 2.2.12
 */
final class LastModifiedTest {

    @Test
    void hasHeaderName() {
        MatcherAssert.assertThat(
            new LastModified(Instant.parse("2018-06-17T18:35:49Z")).getKey(),
            new IsEqual<>("Last-Modified")
        );
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "2018-06-17T18:35:49Z | Sun, 17 Jun 2018 18:35:49 GMT",
        // A day of month below ten keeps its leading zero: clients that
        // parse the HTTP date strictly reject "Tue, 3 Nov 2026 ...".
        "2026-11-03T09:05:07Z | Tue, 03 Nov 2026 09:05:07 GMT",
        // Sub-second precision is dropped, never rounded up.
        "2026-01-01T00:00:00.999Z | Thu, 01 Jan 2026 00:00:00 GMT"
    })
    void formatsInstantAsHttpDate(final String instant, final String expected) {
        MatcherAssert.assertThat(
            new LastModified(Instant.parse(instant)).getValue(),
            new IsEqual<>(expected)
        );
    }
}
