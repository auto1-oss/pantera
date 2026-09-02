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

import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.cache.ValkeyConnection;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * An admin write on one node reaches the loader on another over Valkey.
 * Gated by {@code VALKEY_HOST}.
 *
 * @since 2.2.9
 */
final class SecurityPolicySettingsSyncValkeyTest {

    @AfterEach
    void tearDown() {
        LoginThrottleSettingsLoader.uninstall();
    }

    @Test
    @Timeout(20)
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void broadcastFromOneNodeInvalidatesTheOther() throws InterruptedException {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(System.getenv().getOrDefault("VALKEY_PORT", "6379"));
        final Map<String, String> rows = new HashMap<>(Map.of("login_throttle_max_failures", "5"));
        LoginThrottleSettingsLoader.install(FakeAuthSettings.withRows(rows), Map.<String, String>of()::get);
        try (
            ValkeyConnection connA = new ValkeyConnection(host, port, Duration.ofSeconds(2));
            ValkeyConnection connB = new ValkeyConnection(host, port, Duration.ofSeconds(2));
            CacheInvalidationPubSub busA = new CacheInvalidationPubSub(connA);
            CacheInvalidationPubSub busB = new CacheInvalidationPubSub(connB)
        ) {
            SecurityPolicySettingsSync.attach(Optional.of(busB));
            final SecurityPolicySettingsSync nodeA = SecurityPolicySettingsSync.attach(Optional.of(busA));
            MatcherAssert.assertThat(
                "cached before the write", LoginThrottleSettingsLoader.activeSupplier().get().maxFailures(), new IsEqual<>(5)
            );
            rows.put("login_throttle_max_failures", "3");
            nodeA.broadcast("login_throttle");
            int seen = LoginThrottleSettingsLoader.activeSupplier().get().maxFailures();
            while (seen != 3) {
                Thread.sleep(50L);
                seen = LoginThrottleSettingsLoader.activeSupplier().get().maxFailures();
            }
            MatcherAssert.assertThat("node B reloaded the row", seen, new IsEqual<>(3));
        }
    }
}
