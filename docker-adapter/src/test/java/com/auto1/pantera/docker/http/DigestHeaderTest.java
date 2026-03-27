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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link DigestHeader}.
 */
public final class DigestHeaderTest {

    @Test
    void shouldHaveExpectedNameAndValue() {
        final DigestHeader header = new DigestHeader(
            new Digest.Sha256(
                "6c3c624b58dbbcd3c0dd82b4c53f04194d1247c6eebdaab7c610cf7d66709b3b"
            )
        );
        MatcherAssert.assertThat(
            header.getKey(),
            new IsEqual<>("Docker-Content-Digest")
        );
        MatcherAssert.assertThat(
            header.getValue(),
            new IsEqual<>("sha256:6c3c624b58dbbcd3c0dd82b4c53f04194d1247c6eebdaab7c610cf7d66709b3b")
        );
    }

    @Test
    void shouldExtractValueFromHeaders() {
        final String digest = "sha256:123";
        final DigestHeader header = new DigestHeader(
            Headers.from(
                new Header("Content-Type", "application/octet-stream"),
                new Header("docker-content-digest", digest),
                new Header("X-Something", "Some Value")
            )
        );
        MatcherAssert.assertThat(header.value().string(), new IsEqual<>(digest));
    }

    @Test
    void shouldFailToExtractValueFromEmptyHeaders() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new DigestHeader(Headers.EMPTY).value()
        );
    }
}
