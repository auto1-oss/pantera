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
package com.auto1.pantera.http.hm;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import wtf.g4s8.hamcrest.json.JsonContains;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.JsonValueIs;

/**
 * Test case for {@link IsJson}.
 * @since 1.0
 */
final class IsJsonTest {

    @Test
    void matchesJsonObject() {
        MatcherAssert.assertThat(
            "{\"value\": 4}".getBytes(),
            new IsJson(new JsonHas("value", new JsonValueIs(4)))
        );
    }

    @Test
    void doesntMatchObject() {
        MatcherAssert.assertThat(
            "{}".getBytes(),
            Matchers.not(new IsJson(new JsonHas("foo", new JsonValueIs(0))))
        );
    }

    @Test
    void matchesJsonArray() {
        MatcherAssert.assertThat(
            "[1, 2, 3]".getBytes(),
            new IsJson(
                new JsonContains(
                    new JsonValueIs(1),
                    new JsonValueIs(2),
                    new JsonValueIs(3)
                )
            )
        );
    }

    @Test
    void doesntMatchArray() {
        MatcherAssert.assertThat(
            "{}".getBytes(),
            Matchers.not(
                new IsJson(new JsonContains(new JsonValueIs(5)))
            )
        );
    }
}
