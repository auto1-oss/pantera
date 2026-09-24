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
package com.auto1.pantera.settings.runtime;

import java.util.Map;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * R45: the bulkhead permit keys were validated one by one, so a max below
 * min, or an initial outside [min, max], was accepted.
 *
 * @since 2.2.9
 */
final class BulkheadPermitBoundsTest {

    private static final String MIN = "http_client.bulkhead.min_permits";

    private static final String MAX = "http_client.bulkhead.max_permits";

    private static final String INITIAL = "http_client.bulkhead.initial_permits";

    @Test
    void refusesMaxBelowTheDefaultMin() {
        MatcherAssert.assertThat(
            new BulkheadPermitBounds(Map::of)
                .violationAfter(MAX, Optional.of(4)).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void refusesMaxBelowAStoredMin() {
        MatcherAssert.assertThat(
            new BulkheadPermitBounds(() -> Map.of(MIN, BulkheadPermitBoundsTest.row(50)))
                .violationAfter(MAX, Optional.of(40)),
            new IsEqual<>(
                Optional.of("min_permits (50) must not exceed max_permits (40)")
            )
        );
    }

    @Test
    void refusesInitialOutsideTheRange() {
        MatcherAssert.assertThat(
            new BulkheadPermitBounds(Map::of)
                .violationAfter(INITIAL, Optional.of(101)),
            new IsEqual<>(
                Optional.of(
                    "initial_permits (101) must lie between min_permits (5) and max_permits (100)"
                )
            )
        );
    }

    @Test
    void refusesAResetThatBreaksTheRange() {
        // Stored min 200 and initial 300 with max 500; resetting max to its
        // default (100) would leave min above max.
        MatcherAssert.assertThat(
            new BulkheadPermitBounds(
                () -> Map.of(
                    MIN, BulkheadPermitBoundsTest.row(200),
                    INITIAL, BulkheadPermitBoundsTest.row(300),
                    MAX, BulkheadPermitBoundsTest.row(500)
                )
            ).violationAfter(MAX, Optional.empty()).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void acceptsAConsistentChange() {
        MatcherAssert.assertThat(
            new BulkheadPermitBounds(Map::of)
                .violationAfter(MAX, Optional.of(1003)),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void otherKeysAreNotCheckedAndReadNothing() {
        MatcherAssert.assertThat(
            new BulkheadPermitBounds(
                () -> {
                    throw new AssertionError("no read for another key");
                }
            ).violationAfter("http_client.bulkhead.window_seconds", Optional.of(1)),
            new IsEqual<>(Optional.empty())
        );
    }

    private static JsonObject row(final int value) {
        return Json.createObjectBuilder().add("value", value).build();
    }
}
