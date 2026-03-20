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

import com.auto1.pantera.http.Headers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link Location}.
 */
public final class LocationTest {

    @Test
    void shouldHaveExpectedName() {
        MatcherAssert.assertThat(
            new Location("http://pantera.com/").getKey(),
            new IsEqual<>("Location")
        );
    }

    @Test
    void shouldHaveExpectedValue() {
        final String value = "http://pantera.com/something";
        MatcherAssert.assertThat(
            new Location(value).getValue(),
            new IsEqual<>(value)
        );
    }

    @Test
    void shouldExtractValueFromHeaders() {
        final String value = "http://pantera.com/resource";
        final Location header = new Location(
            Headers.from(
                new Header("Content-Length", "11"),
                new Header("location", value),
                new Header("X-Something", "Some Value")
            )
        );
        MatcherAssert.assertThat(header.getValue(), new IsEqual<>(value));
    }

    @Test
    void shouldFailToExtractValueFromEmptyHeaders() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new Location(Headers.EMPTY).getValue()
        );
    }

    @Test
    void shouldFailToExtractValueWhenNoLocationHeaders() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new Location(
                Headers.from("Content-Type", "text/plain")
            ).getValue()
        );
    }

    @Test
    void shouldFailToExtractValueFromMultipleHeaders() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new Location(
                Headers.from(
                    new Location("http://pantera.com/1"),
                    new Location("http://pantera.com/2")
                )
            ).getValue()
        );
    }
}
