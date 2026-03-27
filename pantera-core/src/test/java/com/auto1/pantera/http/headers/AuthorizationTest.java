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
 * Test case for {@link Authorization}.
 *
 * @since 0.12
 */
public final class AuthorizationTest {

    @Test
    void shouldHaveExpectedName() {
        MatcherAssert.assertThat(
            new Authorization("Basic abc").getKey(),
            new IsEqual<>("Authorization")
        );
    }

    @Test
    void shouldHaveExpectedValue() {
        final String value = "Basic 123";
        MatcherAssert.assertThat(
            new Authorization(value).getValue(),
            new IsEqual<>(value)
        );
    }

    @Test
    void shouldExtractValueFromHeaders() {
        final String value = "Bearer abc";
        final Authorization header = new Authorization(
            Headers.from(
                new Header("Content-Length", "11"),
                new Header("authorization", value),
                new Header("X-Something", "Some Value")
            )
        );
        MatcherAssert.assertThat(header.getValue(), new IsEqual<>(value));
    }

    @Test
    void shouldHaveExpectedScheme() {
        MatcherAssert.assertThat(
            new Authorization("Digest abc===").scheme(),
            new IsEqual<>("Digest")
        );
    }

    @Test
    void shouldHaveExpectedCredentials() {
        MatcherAssert.assertThat(
            new Authorization("Bearer 123.abc").credentials(),
            new IsEqual<>("123.abc")
        );
    }

    @Test
    void shouldFailToParseSchemeWhenInvalidFormat() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new Authorization("some_text").scheme()
        );
    }

    @Test
    void shouldFailToParseCredentialsWhenInvalidFormat() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new Authorization("whatever").credentials()
        );
    }

    @Test
    void shouldFailToExtractValueFromEmptyHeaders() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new Authorization(Headers.EMPTY).getValue()
        );
    }

    @Test
    void shouldFailToExtractValueWhenNoAuthorizationHeaders() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new Authorization(
                Headers.from("Content-Type", "text/plain")
            ).getValue()
        );
    }

    @Test
    void shouldFailToExtractValueFromMultipleHeaders() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new Authorization(
                Headers.from(
                    new Authorization("Bearer one"),
                    new Authorization("Bearer two")
                )
            ).getValue()
        );
    }
}
