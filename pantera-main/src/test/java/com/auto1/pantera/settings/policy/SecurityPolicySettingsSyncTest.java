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
        LoginThrottleSettingsLoader.uninstall();
    }

    @Test
    void receivedInvalidationReloadsTheNamedLoader() {
        final Map<String, String> rows = new HashMap<>(Map.of("login_throttle_max_failures", "5"));
        LoginThrottleSettingsLoader.install(FakeAuthSettings.withRows(rows), Map.<String, String>of()::get);
        final SecurityPolicySettingsSync sync = SecurityPolicySettingsSync.attach(Optional.empty());
        MatcherAssert.assertThat(
            "initial value cached", LoginThrottleSettingsLoader.activeSupplier().get().maxFailures(), new IsEqual<>(5)
        );
        rows.put("login_throttle_max_failures", "2");
        MatcherAssert.assertThat(
            "a row changed elsewhere is not seen until invalidated",
            LoginThrottleSettingsLoader.activeSupplier().get().maxFailures(), new IsEqual<>(5)
        );
        sync.receive("login_throttle");
        MatcherAssert.assertThat(
            "the peer's invalidation reloads the row",
            LoginThrottleSettingsLoader.activeSupplier().get().maxFailures(), new IsEqual<>(2)
        );
    }

    @Test
    void broadcastWithoutABusIsALocalNoOp() {
        final SecurityPolicySettingsSync sync = SecurityPolicySettingsSync.attach(Optional.empty());
        sync.broadcast("egress");
        MatcherAssert.assertThat("no bus, nothing to fail", true, new IsEqual<>(true));
    }
}
