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
package com.auto1.pantera.composer.http;

import java.lang.management.ManagementFactory;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * The archive fingerprint streams every entry: it never holds an entry's
 * decompressed bytes, and it stops at its size limit.
 *
 * @since 2.2.9
 */
final class ArchiveFingerprintTest {

    /**
     * Composer manifest of the test archives.
     */
    private static final String COMPOSER = "{\"name\":\"qa/bomb\",\"version\":\"1.0.0\"}";

    @Test
    void largeCompressibleEntryIsHashedWithoutBufferingIt() throws Exception {
        final long size = 64L << 20;
        final byte[] archive = AddArchiveSliceImmutabilityTest.bomb(
            ArchiveFingerprintTest.COMPOSER, size
        );
        final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().threadId();
        final long before = threads.getThreadAllocatedBytes(thread);
        final Optional<String> print = new ArchiveFingerprint(true, "1.0.0").of(archive);
        final long allocated = threads.getThreadAllocatedBytes(thread) - before;
        MatcherAssert.assertThat(
            "an archive within the limits is fingerprinted",
            print.isPresent(), new IsEqual<>(true)
        );
        // Buffering the 64 MiB entry allocates at least 64 MiB; streaming it
        // allocates a few buffers. The bound is 4x below the buffered floor.
        MatcherAssert.assertThat(
            "the entry is streamed, not buffered",
            allocated < size / 4, new IsEqual<>(true)
        );
    }

    @Test
    void archivePastTheSizeLimitHasNoFingerprint() throws Exception {
        final byte[] archive = AddArchiveSliceImmutabilityTest.bomb(
            ArchiveFingerprintTest.COMPOSER, 300L << 20
        );
        MatcherAssert.assertThat(
            new ArchiveFingerprint(true, "1.0.0").of(archive).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void corruptArchiveHasNoFingerprint() {
        MatcherAssert.assertThat(
            new ArchiveFingerprint(false, "1.0.0").of(new byte[] {1, 2, 3}).isPresent(),
            new IsEqual<>(false)
        );
    }
}
