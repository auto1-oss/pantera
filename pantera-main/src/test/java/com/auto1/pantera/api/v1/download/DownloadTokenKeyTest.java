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
package com.auto1.pantera.api.v1.download;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Specification of download-token signing-key resolution
 * ({@link DownloadTokenKey}). Before 2.2.9 the key silently fell back to
 * {@code pantera-download-<pid>-<user.name>} — deterministic in the shipped
 * container — so tokens were forgeable by anyone. The key must now come from
 * the operator secret, or a persisted SecureRandom value shared by HA nodes,
 * or (single-instance, no store) an ephemeral SecureRandom value; never from
 * process metadata.
 *
 * @since 2.2.9
 */
final class DownloadTokenKeyTest {

    private static final String LEGACY = "pantera-download-" + ProcessHandle.current().pid()
        + "-" + System.getProperty("user.name", "default");

    @Test
    void operatorSecretIsUsedVerbatim() {
        final String secret = "an-operator-secret-of-at-least-32-bytes!!";
        MatcherAssert.assertThat(
            "the configured secret must be the key",
            DownloadTokenKey.resolve(
                Map.of(DownloadTokenKey.ENV, secret), Optional.empty()
            ),
            new IsEqual<>(secret.getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void shortOperatorSecretFailsClosed() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> DownloadTokenKey.resolve(
                Map.of(DownloadTokenKey.ENV, "too-short"), Optional.empty()
            ),
            "a secret under 32 bytes must abort startup, not weaken the key"
        );
    }

    @Test
    void withoutSecretOrStoreTheKeyIsRandomNeverTheLegacyDerivation() {
        final byte[] first = DownloadTokenKey.resolve(Map.of(), Optional.empty());
        final byte[] second = DownloadTokenKey.resolve(Map.of(), Optional.empty());
        MatcherAssert.assertThat(
            "the fallback must never be the predictable pid/username string",
            Arrays.equals(first, LEGACY.getBytes(StandardCharsets.UTF_8)), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "an ephemeral key must carry real entropy (256 bits)",
            first.length >= 32, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "two independent resolutions must not agree — proves randomness, not a constant",
            Arrays.equals(first, second), new IsEqual<>(false)
        );
    }

    @Test
    void persistedKeyIsSharedAcrossNodes() {
        final Map<String, String> db = new ConcurrentHashMap<>();
        final DownloadTokenKey.Store store = new DownloadTokenKey.Store() {
            @Override
            public Optional<String> get(final String key) {
                return Optional.ofNullable(db.get(key));
            }

            @Override
            public void putIfAbsent(final String key, final String value) {
                db.putIfAbsent(key, value);
            }
        };
        final byte[] nodeA = DownloadTokenKey.resolve(Map.of(), Optional.of(store));
        final byte[] nodeB = DownloadTokenKey.resolve(Map.of(), Optional.of(store));
        MatcherAssert.assertThat(
            "a second node resolving from the same store must obtain the same key",
            nodeA, new IsEqual<>(nodeB)
        );
        MatcherAssert.assertThat(
            "the persisted key must never be the legacy derivation",
            Arrays.equals(nodeA, LEGACY.getBytes(StandardCharsets.UTF_8)), new IsEqual<>(false)
        );
    }
}
