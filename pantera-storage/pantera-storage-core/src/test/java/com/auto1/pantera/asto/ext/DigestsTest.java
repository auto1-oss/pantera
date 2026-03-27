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
package com.auto1.pantera.asto.ext;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link Digests}.
 * @since 0.24
 */
class DigestsTest {

    @ParameterizedTest
    @CsvSource({
        "MD5,MD5",
        "SHA1,SHA-1",
        "SHA256,SHA-256",
        "SHA512,SHA-512"
    })
    void providesCorrectMessageDigestAlgorithm(final Digests item, final String expected) {
        MatcherAssert.assertThat(
            item.get().getAlgorithm(),
            new IsEqual<>(expected)
        );
    }

    @ParameterizedTest
    @CsvSource({
        "md5,MD5",
        "SHA-1,SHA1",
        "sha-256,SHA256",
        "SHa-512,SHA512"
    })
    void returnsCorrectDigestItem(final String from, final Digests item) {
        MatcherAssert.assertThat(
            new Digests.FromString(from).get(),
            new IsEqual<>(item)
        );
    }

    @Test
    void throwsExceptionOnUnknownAlgorithm() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new Digests.FromString("123").get()
        );
    }

}
