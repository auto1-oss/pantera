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
package com.auto1.pantera.http.misc;

import org.junit.jupiter.api.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link ConfigDefaults}.
 *
 * @since 1.20.13
 */
final class ConfigDefaultsTest {

    @Test
    void returnsDefaultWhenNotSet() {
        assertThat(
            ConfigDefaults.get("PANTERA_TEST_NONEXISTENT_XYZ_123", "fallback"),
            equalTo("fallback")
        );
    }

    @Test
    void returnsDefaultIntWhenNotSet() {
        assertThat(
            ConfigDefaults.getInt("PANTERA_TEST_NONEXISTENT_INT_456", 42),
            equalTo(42)
        );
    }

    @Test
    void returnsDefaultOnInvalidInt() {
        // System env won't have this, so it falls through to default
        assertThat(
            ConfigDefaults.getInt("PANTERA_TEST_NONEXISTENT_789", 100),
            equalTo(100)
        );
    }

    @Test
    void returnsDefaultLongWhenNotSet() {
        assertThat(
            ConfigDefaults.getLong("PANTERA_TEST_NONEXISTENT_LONG", 120000L),
            equalTo(120000L)
        );
    }
}
