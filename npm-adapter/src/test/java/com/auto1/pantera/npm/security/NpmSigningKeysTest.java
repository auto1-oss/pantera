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
package com.auto1.pantera.npm.security;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link NpmSigningKeys}: the registry's own npm package-signing
 * keypair, lazily generated once and persisted as a storage sidecar.
 */
final class NpmSigningKeysTest {

    @Test
    void generatesAndPersistsAKeypairOnFirstUse() {
        final Storage storage = new InMemoryStorage();
        final NpmSigningKeys.SigningKeyPair pair = new NpmSigningKeys(storage)
            .keyPair().toCompletableFuture().join();
        MatcherAssert.assertThat(
            "a keyid was derived",
            pair.keyId().startsWith("SHA256:"),
            new IsEqual<>(true)
        );
    }

    @Test
    void returnsTheSamePersistedKeypairAcrossInstances() {
        final Storage storage = new InMemoryStorage();
        final NpmSigningKeys.SigningKeyPair first = new NpmSigningKeys(storage)
            .keyPair().toCompletableFuture().join();
        final NpmSigningKeys.SigningKeyPair second = new NpmSigningKeys(storage)
            .keyPair().toCompletableFuture().join();
        MatcherAssert.assertThat(
            "the keypair is durable — a second NpmSigningKeys instance over the "
                + "same storage loads the persisted key rather than generating a new one",
            second.keyId(),
            new IsEqual<>(first.keyId())
        );
    }
}
