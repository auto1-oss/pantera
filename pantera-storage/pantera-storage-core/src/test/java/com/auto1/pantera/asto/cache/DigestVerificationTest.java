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
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ext.Digests;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.codec.binary.Hex;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link DigestVerification}.
 *
 * @since 0.25
 */
final class DigestVerificationTest {

    @Test
    void validatesCorrectDigest() throws Exception {
        final boolean result = new DigestVerification(
            Digests.MD5,
            Hex.decodeHex("5289df737df57326fcdd22597afb1fac")
        ).validate(
            new Key.From("any"),
            () -> CompletableFuture.supplyAsync(
                () -> Optional.of(new Content.From(new byte[]{1, 2, 3}))
            )
        ).toCompletableFuture().get();
        MatcherAssert.assertThat(result, Matchers.is(true));
    }

    @Test
    void doesntValidatesIncorrectDigest() throws Exception {
        final boolean result = new DigestVerification(
            Digests.MD5, new byte[16]
        ).validate(
            new Key.From("other"),
            () -> CompletableFuture.supplyAsync(
                () -> Optional.of(new Content.From(new byte[]{1, 2, 3}))
            )
        ).toCompletableFuture().get();
        MatcherAssert.assertThat(result, Matchers.is(false));
    }

    @Test
    void doesntValidateAbsentContent() throws Exception {
        MatcherAssert.assertThat(
            new DigestVerification(
                Digests.MD5, new byte[16]
            ).validate(
                new Key.From("something"),
                () -> CompletableFuture.supplyAsync(Optional::empty)
            ).toCompletableFuture().get(),
            Matchers.is(false)
        );
    }
}
