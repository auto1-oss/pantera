/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
