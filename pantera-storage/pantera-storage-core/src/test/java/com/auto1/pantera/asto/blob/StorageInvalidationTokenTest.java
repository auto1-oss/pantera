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
package com.auto1.pantera.asto.blob;

import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Wire-encoding round-trip and defensive-decode tests for {@link
 * StorageInvalidationToken} (WS1.5, spec {@code WS1-storage-for-scale.md}
 * &sect;3.E).
 */
final class StorageInvalidationTokenTest {

    @Test
    void encodeThenDecodeRoundTripsWithADigest() {
        final StorageInvalidationToken original = new StorageInvalidationToken("/cache/repo-a", "abc123", 42L);
        final Optional<StorageInvalidationToken> decoded = StorageInvalidationToken.decode(original.encode());
        MatcherAssert.assertThat(decoded, new IsEqual<>(Optional.of(original)));
    }

    @Test
    void encodeThenDecodeRoundTripsWithANullDigest() {
        final StorageInvalidationToken original = new StorageInvalidationToken("/cache/repo-a", null, 7L);
        final Optional<StorageInvalidationToken> decoded = StorageInvalidationToken.decode(original.encode());
        MatcherAssert.assertThat(decoded, new IsEqual<>(Optional.of(original)));
    }

    @Test
    void decodeRejectsANullString() {
        MatcherAssert.assertThat(StorageInvalidationToken.decode(null), new IsEqual<>(Optional.empty()));
    }

    @Test
    void decodeRejectsAStringWithTooFewFields() {
        MatcherAssert.assertThat(StorageInvalidationToken.decode("only-one-field"), new IsEqual<>(Optional.empty()));
    }

    @Test
    void decodeRejectsANonNumericTimestamp() {
        final String malformed = "/cache/repo-a" + '\u0001' + "digest" + '\u0001' + "not-a-number";
        MatcherAssert.assertThat(StorageInvalidationToken.decode(malformed), new IsEqual<>(Optional.empty()));
    }
}
