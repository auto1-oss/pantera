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
import org.junit.jupiter.api.Test;

/**
 * The password sign-in policy is read from the settings table, then the
 * environment, then the defaults.
 *
 * @since 2.2.15
 */
final class PasswordSignInSettingsLoaderTest {

    @AfterEach
    void uninstall() {
        PasswordSignInSettingsLoader.uninstall();
    }

    @Test
    void activeSupplierWithoutInstallReturnsDefaults() {
        MatcherAssert.assertThat(
            PasswordSignInSettingsLoader.activeSupplier().get(),
            new IsEqual<>(PasswordSignInConfig.defaults())
        );
    }

    @Test
    void databaseRowsWinOverEnvironment() {
        MatcherAssert.assertThat(
            new PasswordSignInSettingsLoader(
                FakeAuthSettings.withRows(
                    Map.of("password_sign_in_okta", "form", "password_sign_in_keycloak", "off")
                ),
                Map.of(
                    "PANTERA_PASSWORD_SIGN_IN_OKTA", "off",
                    "PANTERA_PASSWORD_SIGN_IN_GITHUB", "form"
                )::get
            ).get(),
            new IsEqual<>(
                new PasswordSignInConfig(
                    Map.of("okta", "form", "keycloak", "off", "github", "form")
                )
            )
        );
    }

    @Test
    void valueThatIsNoModeFallsThroughToTheNextTier() {
        MatcherAssert.assertThat(
            new PasswordSignInSettingsLoader(
                FakeAuthSettings.withRows(
                    Map.of("password_sign_in_okta", "maybe", "password_sign_in_keycloak", " OFF ")
                ),
                Map.of("PANTERA_PASSWORD_SIGN_IN_OKTA", "form")::get
            ).get(),
            new IsEqual<>(
                new PasswordSignInConfig(
                    Map.of("okta", "form", "keycloak", "off", "github", "all")
                )
            )
        );
    }

    @Test
    void installedLoaderIsWhatTheActiveSupplierReads() {
        PasswordSignInSettingsLoader.install(
            FakeAuthSettings.withRows(Map.of("password_sign_in_okta", "off")), name -> null
        );
        MatcherAssert.assertThat(
            PasswordSignInSettingsLoader.activeSupplier().get().mode("okta"),
            new IsEqual<>("off")
        );
    }
}
