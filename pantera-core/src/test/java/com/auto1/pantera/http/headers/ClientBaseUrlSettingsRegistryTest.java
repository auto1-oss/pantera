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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link ClientBaseUrlSettingsRegistry}: the fallback used before
 * {@code pantera-main} ever installs a DB-backed loader, and that {@link
 * ClientBaseUrlSettingsRegistry#active()} re-resolves the installed supplier
 * on every call rather than caching a snapshot from install time.
 */
final class ClientBaseUrlSettingsRegistryTest {

    @AfterEach
    void tearDown() {
        ClientBaseUrlSettingsRegistry.uninstall();
    }

    @Test
    void activeWithoutInstallReturnsDefaults() {
        MatcherAssert.assertThat(
            ClientBaseUrlSettingsRegistry.active(), new IsEqual<>(ClientBaseUrlSettings.defaults())
        );
    }

    @Test
    void activeReResolvesTheInstalledSupplierOnEveryCall() {
        final AtomicReference<ClientBaseUrlSettings> live =
            new AtomicReference<>(new ClientBaseUrlSettings(false, List.of("a.example.com")));
        ClientBaseUrlSettingsRegistry.install(live::get);
        MatcherAssert.assertThat(
            "first call reflects the value at install time",
            ClientBaseUrlSettingsRegistry.active().hostAllowlist(),
            new IsEqual<>(List.of("a.example.com"))
        );
        live.set(new ClientBaseUrlSettings(true, List.of("b.example.com")));
        MatcherAssert.assertThat(
            "second call — same installed supplier reference — reflects the new value",
            ClientBaseUrlSettingsRegistry.active(),
            new IsEqual<>(new ClientBaseUrlSettings(true, List.of("b.example.com")))
        );
    }

    @Test
    void uninstallRestoresTheDefaultFallback() {
        ClientBaseUrlSettingsRegistry.install(() -> new ClientBaseUrlSettings(true, List.of()));
        ClientBaseUrlSettingsRegistry.uninstall();
        MatcherAssert.assertThat(
            ClientBaseUrlSettingsRegistry.active(), new IsEqual<>(ClientBaseUrlSettings.defaults())
        );
    }
}
