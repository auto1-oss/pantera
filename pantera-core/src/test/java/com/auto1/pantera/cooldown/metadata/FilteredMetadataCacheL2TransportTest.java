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
package com.auto1.pantera.cooldown.metadata;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Ungated unit tests for the 2.2.7 L2 transport hardening of
 * {@link FilteredMetadataCache}: the gzip value codec (multi-MB envelopes
 * must fit inside the L2 read timeout) and the L2 read breaker (a degraded
 * Valkey must not add the read timeout to every metadata serve).
 *
 * @since 2.2.7
 */
final class FilteredMetadataCacheL2TransportTest {

    @Test
    void codecRoundTripsLargeValuesCompressed() {
        // Realistic shape: repetitive JSON compresses hard.
        final StringBuilder json = new StringBuilder("{\"versions\":{");
        for (int idx = 0; idx < 2000; idx = idx + 1) {
            json.append("\"1.0.").append(idx)
                .append("\":{\"dist\":{\"tarball\":\"https://registry/pkg-1.0.")
                .append(idx).append(".tgz\"}},");
        }
        json.append("\"end\":{}}}");
        final byte[] original = json.toString().getBytes(StandardCharsets.UTF_8);

        final byte[] stored = FilteredMetadataCache.l2Encode(original);

        MatcherAssert.assertThat(
            "a large envelope must be stored gzip-compressed (magic bytes)",
            (stored[0] & 0xFF) == 0x1F && (stored[1] & 0xFF) == 0x8B,
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "compression must actually shrink the value",
            stored.length < original.length, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "decode must return the exact original bytes",
            FilteredMetadataCache.l2Decode(stored), new IsEqual<>(original)
        );
    }

    @Test
    void codecStoresSmallValuesRaw() {
        final byte[] small = "{\"tiny\":true}".getBytes(StandardCharsets.UTF_8);
        final byte[] stored = FilteredMetadataCache.l2Encode(small);
        MatcherAssert.assertThat(
            "values under the threshold must be stored raw",
            stored, new IsEqual<>(small)
        );
    }

    @Test
    void codecReadsLegacyUncompressedValues() {
        // A pre-compression entry written by 2.2.6/early-2.2.7 is raw JSON.
        final byte[] legacy = "{\"legacy\":\"envelope\"}".getBytes(StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            "raw (legacy) values must pass through the decoder unchanged",
            FilteredMetadataCache.l2Decode(legacy), new IsEqual<>(legacy)
        );
    }

    @Test
    void codecTreatsCorruptGzipAsMiss() {
        final byte[] corrupt = new byte[64];
        new Random(42).nextBytes(corrupt);
        corrupt[0] = (byte) 0x1F;
        corrupt[1] = (byte) 0x8B;
        MatcherAssert.assertThat(
            "a corrupt compressed value must decode to empty (cache miss), not throw",
            FilteredMetadataCache.l2Decode(corrupt).length, new IsEqual<>(0)
        );
    }

    @Test
    void breakerSkipsL2AfterConsecutiveFailuresAndRecovers() {
        final FilteredMetadataCache cache = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        final AtomicLong now = new AtomicLong();
        cache.nanoClock(now::get);
        MatcherAssert.assertThat(
            "reads allowed initially", cache.l2ReadAllowed(), new IsEqual<>(true)
        );
        cache.recordL2ReadFailure(new java.util.concurrent.TimeoutException("t1"));
        cache.recordL2ReadFailure(new java.util.concurrent.TimeoutException("t2"));
        MatcherAssert.assertThat(
            "two strikes must not trip the breaker",
            cache.l2ReadAllowed(), new IsEqual<>(true)
        );
        cache.recordL2ReadFailure(new java.util.concurrent.TimeoutException("t3"));
        MatcherAssert.assertThat(
            "the third consecutive failure must open the breaker",
            cache.l2ReadAllowed(), new IsEqual<>(false)
        );
        now.addAndGet(Duration.ofSeconds(11).toNanos());
        MatcherAssert.assertThat(
            "the breaker must close again after the skip window",
            cache.l2ReadAllowed(), new IsEqual<>(true)
        );
    }
}
