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
package com.auto1.pantera.npm.http.attestation;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link AttestationStore}: a durable per-version sidecar for
 * {@code npm publish --provenance} bundles.
 */
final class AttestationStoreTest {

    @Test
    void storesAndReadsBackABundle() {
        final Storage storage = new InMemoryStorage();
        final AttestationStore store = new AttestationStore(storage);
        final byte[] bundle = "{\"predicateType\":\"https://slsa.dev/provenance/v1\"}"
            .getBytes(StandardCharsets.UTF_8);
        store.store("@scope/pkg", "1.0.0", bundle).join();
        final Optional<byte[]> read = store.read("@scope/pkg", "1.0.0").join();
        MatcherAssert.assertThat("bundle was found", read.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(
            new String(read.get(), StandardCharsets.UTF_8),
            new IsEqual<>("{\"predicateType\":\"https://slsa.dev/provenance/v1\"}")
        );
    }

    @Test
    void readReturnsEmptyWhenNoneStored() {
        final AttestationStore store = new AttestationStore(new InMemoryStorage());
        MatcherAssert.assertThat(
            store.read("never-published", "1.0.0").join().isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void doesNotCollideBetweenDifferentVersionsOfSamePackage() {
        final Storage storage = new InMemoryStorage();
        final AttestationStore store = new AttestationStore(storage);
        store.store("pkg", "1.0.0", "one".getBytes(StandardCharsets.UTF_8)).join();
        store.store("pkg", "2.0.0", "two".getBytes(StandardCharsets.UTF_8)).join();
        MatcherAssert.assertThat(
            "v1 bundle unaffected by v2 store",
            new String(store.read("pkg", "1.0.0").join().orElseThrow(), StandardCharsets.UTF_8),
            new IsEqual<>("one")
        );
        MatcherAssert.assertThat(
            new String(store.read("pkg", "2.0.0").join().orElseThrow(), StandardCharsets.UTF_8),
            new IsEqual<>("two")
        );
    }
}
