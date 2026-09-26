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
package com.auto1.pantera.auth.oidc;

import com.auto1.pantera.cache.ValkeyConnection;
import java.time.Duration;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A login started through one Valkey connection (node A) completes through
 * another (node B) exactly once. Gated by {@code VALKEY_HOST}.
 *
 * @since 2.2.9
 */
final class ValkeySsoLoginStateStoreTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")
    void stateIssuedOnOneNodeIsConsumedOnceOnAnother() {
        final String host = System.getenv("VALKEY_HOST");
        final int port = Integer.parseInt(System.getenv().getOrDefault("VALKEY_PORT", "6379"));
        try (
            ValkeyConnection nodeA = new ValkeyConnection(host, port, Duration.ofSeconds(2));
            ValkeyConnection nodeB = new ValkeyConnection(host, port, Duration.ofSeconds(2))
        ) {
            final SsoLoginStateStore first = new ValkeySsoLoginStateStore(nodeA, Duration.ofMinutes(1));
            final SsoLoginStateStore second = new ValkeySsoLoginStateStore(nodeB, Duration.ofMinutes(1));
            final String state = first.newState();
            final String nonce = first.issue(state).toCompletableFuture().join();
            MatcherAssert.assertThat(
                "the other node sees the nonce on the first consume",
                second.consume(state).toCompletableFuture().join(), new IsEqual<>(Optional.of(nonce))
            );
            MatcherAssert.assertThat(
                "neither node can consume it again",
                first.consume(state).toCompletableFuture().join(), new IsEqual<>(Optional.empty())
            );
            MatcherAssert.assertThat(
                "an unknown state is refused",
                second.consume("nope").toCompletableFuture().join(), new IsEqual<>(Optional.empty())
            );
        }
    }
}
