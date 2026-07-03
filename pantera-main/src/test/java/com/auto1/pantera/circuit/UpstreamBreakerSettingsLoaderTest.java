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
package com.auto1.pantera.circuit;

import com.auto1.pantera.http.client.circuitbreaker.CircuitBreakerConfig;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UpstreamBreakerSettingsLoader}: DB-less fallback to
 * hardcoded defaults, and the uninstalled {@code activeSupplier} path.
 * DB-backed resolution shares the exact resolve chain with the
 * long-standing {@link CircuitBreakerSettingsLoader} and is covered by
 * the admin-endpoint round-trip.
 *
 * @since 2.2.0
 */
final class UpstreamBreakerSettingsLoaderTest {

    @AfterEach
    void tearDown() {
        UpstreamBreakerSettingsLoader.uninstall();
    }

    @Test
    void activeSupplierWithoutInstallReturnsDefaults() {
        final CircuitBreakerConfig resolved =
            UpstreamBreakerSettingsLoader.activeSupplier().get();
        final CircuitBreakerConfig defaults = CircuitBreakerConfig.defaults();
        MatcherAssert.assertThat(
            "uninstalled supplier resolves the default failure-rate threshold",
            resolved.failureRateThreshold(),
            new IsEqual<>(defaults.failureRateThreshold())
        );
        MatcherAssert.assertThat(
            "uninstalled supplier resolves the default minimum calls",
            resolved.minimumCalls(), new IsEqual<>(defaults.minimumCalls())
        );
        MatcherAssert.assertThat(
            "uninstalled supplier resolves the default backoff cap",
            resolved.maxBackoff(), new IsEqual<>(defaults.maxBackoff())
        );
    }

    @Test
    void nullDaoLoaderFallsBackToDefaults() {
        final UpstreamBreakerSettingsLoader loader =
            new UpstreamBreakerSettingsLoader(null);
        final CircuitBreakerConfig resolved = loader.get();
        MatcherAssert.assertThat(
            "gate values fall back to defaults without a DB",
            resolved.minimumCalls(),
            new IsEqual<>(CircuitBreakerConfig.defaults().minimumCalls())
        );
        MatcherAssert.assertThat(
            "backoff seed falls back to defaults without a DB",
            resolved.seedBackoff(),
            new IsEqual<>(CircuitBreakerConfig.defaults().seedBackoff())
        );
    }

    @Test
    void installedSupplierIsInvalidatable() {
        UpstreamBreakerSettingsLoader.install(null);
        final UpstreamBreakerSettingsLoader loader =
            UpstreamBreakerSettingsLoader.installed();
        loader.get();
        loader.invalidate();
        MatcherAssert.assertThat(
            "invalidate reloads without error and still resolves",
            UpstreamBreakerSettingsLoader.activeSupplier().get().windowSeconds(),
            new IsEqual<>(CircuitBreakerConfig.defaults().windowSeconds())
        );
    }
}
