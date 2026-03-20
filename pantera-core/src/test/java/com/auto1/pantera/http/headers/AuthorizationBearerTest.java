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

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Authorization.Bearer}.
 *
 * @since 0.12
 */
public final class AuthorizationBearerTest {

    @Test
    void shouldHaveExpectedValue() {
        MatcherAssert.assertThat(
            new Authorization.Bearer("mF_9.B5f-4.1JqM").getValue(),
            new IsEqual<>("Bearer mF_9.B5f-4.1JqM")
        );
    }

    @Test
    void shouldHaveExpectedToken() {
        final String token = "123.abc";
        MatcherAssert.assertThat(
            new Authorization.Bearer(token).token(),
            new IsEqual<>(token)
        );
    }
}
