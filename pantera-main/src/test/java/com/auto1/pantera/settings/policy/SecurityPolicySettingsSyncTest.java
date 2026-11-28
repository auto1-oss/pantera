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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SecurityPolicySettingsSync}: an invalidation received from a peer
 * makes the local loader re-read the database row.
 *
 * @since 2.2.9
 */
final class SecurityPolicySettingsSyncTest {

    @AfterEach
    void tearDown() {
        FailedLoginsSettingsLoader.uninstall();
        PasswordSignInSettingsLoader.uninstall();
    }

    @Test
    void passwordSignInChangedOnAPeerIsReloadedHere() {
        final Map<String, String> rows = new HashMap<>(Map.of("password_sign_in_okta", "all"));
        PasswordSignInSettingsLoader.install(FakeAuthSettings.withRows(rows), name -> null);
        final SecurityPolicySettingsSync sync = SecurityPolicySettingsSync.attach(Optional.empty());
        MatcherAssert.assertThat(
            "initial value cached",
            PasswordSignInSettingsLoader.activeSupplier().get().mode("okta"), new IsEqual<>("all")
        );
        rows.put("password_sign_in_okta", "off");
        sync.receive("password_sign_in");
        MatcherAssert.assertThat(
            "the peer's change applies on this node",
            PasswordSignInSettingsLoader.activeSupplier().get().mode("okta"), new IsEqual<>("off")
        );
    }

    @Test
    void receivedInvalidationReloadsTheNamedLoader() {
        final Map<String, String> rows = new HashMap<>(Map.of("failed_logins_second_factor_limit", "5"));
        FailedLoginsSettingsLoader.install(FakeAuthSettings.withRows(rows), Map.<String, String>of()::get);
        final SecurityPolicySettingsSync sync = SecurityPolicySettingsSync.attach(Optional.empty());
        MatcherAssert.assertThat(
            "initial value cached", FailedLoginsSettingsLoader.activeSupplier().get().secondFactorLimit(), new IsEqual<>(5)
        );
        rows.put("failed_logins_second_factor_limit", "2");
        MatcherAssert.assertThat(
            "a row changed elsewhere is not seen until invalidated",
            FailedLoginsSettingsLoader.activeSupplier().get().secondFactorLimit(), new IsEqual<>(5)
        );
        sync.receive("failed_logins");
        MatcherAssert.assertThat(
            "the peer's invalidation reloads the row",
            FailedLoginsSettingsLoader.activeSupplier().get().secondFactorLimit(), new IsEqual<>(2)
        );
    }

    @Test
    void broadcastWithoutABusIsALocalNoOp() {
        final SecurityPolicySettingsSync sync = SecurityPolicySettingsSync.attach(Optional.empty());
        sync.broadcast("egress");
        MatcherAssert.assertThat("no bus, nothing to fail", true, new IsEqual<>(true));
    }
}
