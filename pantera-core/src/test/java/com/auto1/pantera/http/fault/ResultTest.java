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
package com.auto1.pantera.http.fault;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Result#map} and {@link Result#flatMap} on the two variants.
 */
final class ResultTest {

    private static final Fault FAULT = new Fault.NotFound("g", "a", "v");

    @Test
    void factoriesProduceExpectedTypes() {
        MatcherAssert.assertThat(
            Result.ok(42), Matchers.instanceOf(Result.Ok.class)
        );
        MatcherAssert.assertThat(
            Result.err(FAULT), Matchers.instanceOf(Result.Err.class)
        );
    }

    @Test
    void okMapAppliesFunction() {
        final Result<Integer> mapped = Result.ok(1).map(v -> v + 2);
        MatcherAssert.assertThat(mapped, Matchers.instanceOf(Result.Ok.class));
        MatcherAssert.assertThat(
            ((Result.Ok<Integer>) mapped).value(), Matchers.is(3)
        );
    }

    @Test
    void errMapShortCircuits() {
        final Result<Integer> mapped = Result.<Integer>err(FAULT).map(v -> v + 2);
        MatcherAssert.assertThat(mapped, Matchers.instanceOf(Result.Err.class));
        MatcherAssert.assertThat(
            "fault preserved",
            ((Result.Err<Integer>) mapped).fault(), Matchers.sameInstance(FAULT)
        );
    }

    @Test
    void okFlatMapChainsNewResult() {
        final Result<String> chained = Result.ok(10)
            .flatMap(v -> Result.ok("v=" + v));
        MatcherAssert.assertThat(chained, Matchers.instanceOf(Result.Ok.class));
        MatcherAssert.assertThat(
            ((Result.Ok<String>) chained).value(), Matchers.is("v=10")
        );
    }

    @Test
    void okFlatMapCanReturnErr() {
        final Result<String> chained = Result.ok(10)
            .flatMap(v -> Result.err(FAULT));
        MatcherAssert.assertThat(chained, Matchers.instanceOf(Result.Err.class));
        MatcherAssert.assertThat(
            ((Result.Err<String>) chained).fault(), Matchers.sameInstance(FAULT)
        );
    }

    @Test
    void errFlatMapShortCircuits() {
        final Result<String> chained = Result.<Integer>err(FAULT)
            .flatMap(v -> Result.ok("should not run"));
        MatcherAssert.assertThat(chained, Matchers.instanceOf(Result.Err.class));
        MatcherAssert.assertThat(
            "original fault preserved",
            ((Result.Err<String>) chained).fault(), Matchers.sameInstance(FAULT)
        );
    }
}
