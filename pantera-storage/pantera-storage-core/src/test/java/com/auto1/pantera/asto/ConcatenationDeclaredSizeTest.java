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
package com.auto1.pantera.asto;

import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the declared-size pre-allocation in
 * {@link Concatenation}.
 *
 * <p>Before 2.2.9 {@code Concatenation.single()} used
 * {@code ByteBuffer.allocate(expectedSize)} as the reduce seed, and the
 * expected size came straight from the request's attacker-declared
 * {@code Content-Length}. The seed is evaluated eagerly, before a single
 * body byte arrives, so an unauthenticated {@code PUT} with
 * {@code Content-Length: 2000000000} and no body allocated ~2 GiB on the
 * heap of an {@code -XX:+ExitOnOutOfMemoryError} JVM — process death.</p>
 *
 * <p>The seed must be bounded: the declared size is a HINT capped at a
 * sane pre-allocation threshold, and the buffer grows only as real bytes
 * arrive. The result must still be exactly the bytes that were sent.</p>
 *
 * @since 2.2.9
 */
final class ConcatenationDeclaredSizeTest {

    /**
     * 300 MiB: large enough that an eager seed is unmistakable on the
     * resulting capacity, small enough to allocate on any test JVM so the
     * RED run fails on the assertion rather than on an OutOfMemoryError.
     */
    private static final long DECLARED = 300L * 1024L * 1024L;

    @Test
    void declaredSizeDoesNotDriveThePreallocation() {
        final byte[] real = "ten  bytes".getBytes(StandardCharsets.UTF_8);
        final ByteBuffer result = Concatenation.withSize(
            Flowable.just(ByteBuffer.wrap(real)), DECLARED
        ).single().blockingGet();
        MatcherAssert.assertThat(
            "the concatenation must yield exactly the bytes that were sent",
            new Remaining(result).bytes(), new IsEqual<>(real)
        );
        MatcherAssert.assertThat(
            "the buffer must not be pre-allocated from the untrusted declared size "
                + "(capacity must stay within the pre-allocation cap)",
            result.capacity() <= Concatenation.MAX_PREALLOCATION,
            new IsEqual<>(true)
        );
    }

    @Test
    void bodyLargerThanTheCappedSeedStillConcatenatesCompletely() {
        // Real bytes exceed the pre-allocation cap: the buffer must grow,
        // not overflow (the old exact-size seed would have thrown
        // BufferOverflowException on any under-declared body).
        final int chunk = 256 * 1024;
        final int chunks = 8;
        final byte[] expected = new byte[chunk * chunks];
        for (int idx = 0; idx < expected.length; idx = idx + 1) {
            expected[idx] = (byte) idx;
        }
        final ByteBuffer result = Concatenation.withSize(
            Flowable.range(0, chunks).map(
                idx -> ByteBuffer.wrap(expected, idx * chunk, chunk).slice()
            ),
            // Declared a tiny size: the real body is far bigger.
            16L
        ).single().blockingGet();
        MatcherAssert.assertThat(
            "an under-declared body must still be concatenated completely",
            new Remaining(result).bytes(), new IsEqual<>(expected)
        );
    }
}
