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
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link ClientBaseUrlSettingsLoader}: DB-less fallback to
 * hardcoded defaults, the uninstalled {@code activeSupplier} path, and that
 * {@code install}/{@code uninstall} keep {@link ClientBaseUrlSettingsRegistry}
 * — the {@code pantera-core}-side holder {@link ClientBaseUrl} actually
 * reads from — in sync. DB-backed resolution shares the exact resolve chain
 * with the long-standing breaker loaders and is covered by the admin-endpoint
 * round-trip.
 *
 * @since 2.3.0
 */
final class ClientBaseUrlSettingsLoaderTest {

    @AfterEach
    void tearDown() {
        ClientBaseUrlSettingsLoader.uninstall();
    }

    @Test
    void activeSupplierWithoutInstallReturnsDefaults() {
        MatcherAssert.assertThat(
            ClientBaseUrlSettingsLoader.activeSupplier().get(),
            new IsEqual<>(ClientBaseUrlSettings.defaults())
        );
    }

    @Test
    void nullDaoLoaderFallsBackToDefaults() {
        final ClientBaseUrlSettingsLoader loader = new ClientBaseUrlSettingsLoader(null);
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(ClientBaseUrlSettings.defaults()));
    }

    /**
     * Proves the hot-reload wiring end to end at the loader level: {@code
     * install} feeds {@link ClientBaseUrlSettingsRegistry}, and {@code
     * invalidate} — the call the admin PUT endpoint and the cross-node
     * broadcast subscriber both make — is what {@link ClientBaseUrl}
     * ultimately benefits from without any restart.
     */
    @Test
    void installFeedsTheRegistryAndInvalidateIsSafeWithoutADb() {
        ClientBaseUrlSettingsLoader.install(null);
        MatcherAssert.assertThat(
            "install() must also install into the core-side registry",
            ClientBaseUrlSettingsRegistry.active(), new IsEqual<>(ClientBaseUrlSettings.defaults())
        );
        final ClientBaseUrlSettingsLoader loader = ClientBaseUrlSettingsLoader.installed();
        loader.get();
        loader.invalidate();
        MatcherAssert.assertThat(
            "invalidate reloads without error and still resolves",
            ClientBaseUrlSettingsLoader.activeSupplier().get().hostAllowlist(),
            new IsEqual<>(List.of())
        );
    }

    @Test
    void uninstallClearsBothTheLoaderAndTheRegistry() {
        ClientBaseUrlSettingsLoader.install(null);
        ClientBaseUrlSettingsLoader.uninstall();
        MatcherAssert.assertThat(
            "uninstall() must clear the installed() accessor",
            ClientBaseUrlSettingsLoader.installed(), new IsNull<>()
        );
        MatcherAssert.assertThat(
            "uninstall() must also clear the core-side registry",
            ClientBaseUrlSettingsRegistry.active(), new IsEqual<>(ClientBaseUrlSettings.defaults())
        );
    }
}
