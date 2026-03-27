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
 * Tests for {@link Authorization.Token}.
 *
 * @since 0.23
 */
public final class AuthorizationTokenTest {

    @Test
    void shouldHaveExpectedValue() {
        MatcherAssert.assertThat(
            new Authorization.Token("abc123").getValue(),
            new IsEqual<>("token abc123")
        );
    }

    @Test
    void shouldHaveExpectedToken() {
        final String token = "098.xyz";
        MatcherAssert.assertThat(
            new Authorization.Token(token).token(),
            new IsEqual<>(token)
        );
    }
}
