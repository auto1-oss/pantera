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

import java.time.Duration;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * The in-memory {@link SsoLoginStateStore} keeps the single-use contract
 * behind the asynchronous interface.
 *
 * @since 2.2.9
 */
final class InMemorySsoLoginStateStoreTest {

    @Test
    void nonceIsConsumedExactlyOnce() {
        final SsoLoginStateStore store =
            new InMemorySsoLoginStateStore(new SsoNonceStore(Duration.ofMinutes(10)));
        final String state = store.newState();
        final String nonce = store.issue(state).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "first consume yields the nonce",
            store.consume(state).toCompletableFuture().join(), new IsEqual<>(Optional.of(nonce))
        );
        MatcherAssert.assertThat(
            "second consume yields nothing",
            store.consume(state).toCompletableFuture().join(), new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "a null state yields nothing",
            store.consume(null).toCompletableFuture().join(), new IsEqual<>(Optional.empty())
        );
    }
}
