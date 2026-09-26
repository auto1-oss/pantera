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
package com.auto1.pantera.settings.policy;

import java.util.Map;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * {@link LoginThrottleSettingsLoader}: DB row, then environment, then the
 * defaults of five failures per fifteen minutes.
 *
 * @since 2.2.9
 */
final class LoginThrottleSettingsLoaderTest {

    @AfterEach
    void tearDown() {
        LoginThrottleSettingsLoader.uninstall();
    }

    @Test
    void activeSupplierWithoutInstallReturnsDefaults() {
        final LoginThrottleConfig resolved = LoginThrottleSettingsLoader.activeSupplier().get();
        MatcherAssert.assertThat("five failures", resolved.maxFailures(), new IsEqual<>(5));
        MatcherAssert.assertThat("fifteen minutes", resolved.windowSeconds(), new IsEqual<>(900));
    }

    @Test
    void dbRowWinsOverEnvironment() {
        final LoginThrottleSettingsLoader loader = new LoginThrottleSettingsLoader(
            FakeAuthSettings.withRows(Map.of(
                "login_throttle_max_failures", "3", "login_throttle_window_seconds", "60"
            )),
            Map.of("PANTERA_LOGIN_THROTTLE_MAX_FAILURES", "9")::get
        );
        MatcherAssert.assertThat("failures from the row", loader.get().maxFailures(), new IsEqual<>(3));
        MatcherAssert.assertThat("window from the row", loader.get().windowSeconds(), new IsEqual<>(60));
    }

    @Test
    void envTierWinsOverDefaultWhenNoRow() {
        final LoginThrottleSettingsLoader loader = new LoginThrottleSettingsLoader(
            FakeAuthSettings.empty(),
            Map.of(
                "PANTERA_LOGIN_THROTTLE_MAX_FAILURES", "9",
                "PANTERA_LOGIN_THROTTLE_WINDOW_SECONDS", "120"
            )::get
        );
        MatcherAssert.assertThat("failures from env", loader.get().maxFailures(), new IsEqual<>(9));
        MatcherAssert.assertThat("window from env", loader.get().windowSeconds(), new IsEqual<>(120));
    }

    @Test
    void invalidRowFallsBackToDefaults() {
        final LoginThrottleSettingsLoader loader = new LoginThrottleSettingsLoader(
            FakeAuthSettings.withRows(Map.of("login_throttle_max_failures", "0")), Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            "zero failures cannot be loaded; defaults apply", loader.get().maxFailures(), new IsEqual<>(5)
        );
    }

    @Test
    void configRequiresPositiveValues() {
        Assertions.assertThrows(IllegalArgumentException.class,
            (Executable) () -> new LoginThrottleConfig(0, 900));
        Assertions.assertThrows(IllegalArgumentException.class,
            (Executable) () -> new LoginThrottleConfig(5, 0));
    }


    @Test
    void invalidWindowKeepsConfiguredMaxFailures() {
        final LoginThrottleSettingsLoader loader = new LoginThrottleSettingsLoader(
            FakeAuthSettings.withRows(Map.of(
                "login_throttle_max_failures", "9",
                "login_throttle_window_seconds", "0"
            )),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            "max failures is KEPT despite the invalid sibling key",
            loader.get().maxFailures(), new IsEqual<>(9)
        );
        MatcherAssert.assertThat(
            "the invalid window falls back to its own default",
            loader.get().windowSeconds(),
            new IsEqual<>(LoginThrottleConfig.DEFAULT_WINDOW_SECONDS)
        );
    }
}
