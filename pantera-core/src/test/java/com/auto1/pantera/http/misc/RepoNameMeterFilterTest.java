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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Tests for {@link RepoNameMeterFilter}.
 *
 * @since 1.20.13
 */
final class RepoNameMeterFilterTest {

    @Test
    void allowsReposUnderLimit() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new RepoNameMeterFilter(3));
        registry.counter("test.requests", "repo_name", "repo1").increment();
        registry.counter("test.requests", "repo_name", "repo2").increment();
        registry.counter("test.requests", "repo_name", "repo3").increment();
        assertThat(
            "Should have 3 distinct counters",
            registry.find("test.requests").counters().size(),
            equalTo(3)
        );
    }

    @Test
    void capsReposOverLimit() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new RepoNameMeterFilter(2));
        registry.counter("test.requests", "repo_name", "repo1").increment();
        registry.counter("test.requests", "repo_name", "repo2").increment();
        registry.counter("test.requests", "repo_name", "repo3").increment();
        registry.counter("test.requests", "repo_name", "repo4").increment();
        // repo3 and repo4 should be bucketed into "_other"
        assertThat(
            "Should have at most 3 counters (2 named + _other)",
            registry.find("test.requests").counters().size(),
            lessThanOrEqualTo(3)
        );
    }

    @Test
    void passesMetersWithoutRepoTag() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new RepoNameMeterFilter(1));
        registry.counter("test.other", "method", "GET").increment();
        assertThat(
            "Meters without repo_name tag should pass through",
            registry.find("test.other").counters().size(),
            equalTo(1)
        );
    }
}
