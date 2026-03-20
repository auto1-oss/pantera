/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.group;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link GroupMetadataCache} stale fallback.
 */
class GroupMetadataCacheTest {

    @Test
    void getStaleReturnsEmptyWhenNeverCached() throws Exception {
        final GroupMetadataCache cache = new GroupMetadataCache("test-group");
        final Optional<byte[]> result = cache.getStale("/com/example/maven-metadata.xml")
            .get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "Stale returns empty when never cached",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void getStaleReturnsDataAfterPut() throws Exception {
        final GroupMetadataCache cache = new GroupMetadataCache("test-group");
        final byte[] data = "<metadata>test</metadata>"
            .getBytes(StandardCharsets.UTF_8);
        cache.put("/com/example/stale1/maven-metadata.xml", data);
        final Optional<byte[]> result = cache.getStale(
            "/com/example/stale1/maven-metadata.xml"
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "Stale returns data that was previously put",
            result.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Stale data matches what was put",
            new String(result.get(), StandardCharsets.UTF_8),
            Matchers.equalTo("<metadata>test</metadata>")
        );
    }

    @Test
    void getStaleReturnsPreviousDataAfterInvalidate() throws Exception {
        final GroupMetadataCache cache = new GroupMetadataCache("test-group");
        final byte[] data = "<metadata>stale-data</metadata>"
            .getBytes(StandardCharsets.UTF_8);
        cache.put("/com/example/stale2/maven-metadata.xml", data);
        // Invalidate removes from L1/L2 but NOT from last-known-good
        cache.invalidate("/com/example/stale2/maven-metadata.xml");
        // Primary get should return empty (invalidated)
        final Optional<byte[]> primary = cache.get(
            "/com/example/stale2/maven-metadata.xml"
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "Primary cache returns empty after invalidate",
            primary.isPresent(),
            Matchers.is(false)
        );
        // Stale should still return the data
        final Optional<byte[]> stale = cache.getStale(
            "/com/example/stale2/maven-metadata.xml"
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "Stale still returns data after invalidate",
            stale.isPresent(),
            Matchers.is(true)
        );
    }

    @Test
    void getStaleUpdatesWithLatestPut() throws Exception {
        final GroupMetadataCache cache = new GroupMetadataCache("test-group");
        cache.put("/com/example/stale3/maven-metadata.xml",
            "v1".getBytes(StandardCharsets.UTF_8));
        cache.put("/com/example/stale3/maven-metadata.xml",
            "v2".getBytes(StandardCharsets.UTF_8));
        final Optional<byte[]> result = cache.getStale(
            "/com/example/stale3/maven-metadata.xml"
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "Stale returns most recently put data",
            new String(result.get(), StandardCharsets.UTF_8),
            Matchers.equalTo("v2")
        );
    }
}
