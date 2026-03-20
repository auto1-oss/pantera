/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link StreamThroughCache}.
 */
final class StreamThroughCacheTest {

    private Storage storage;
    private StreamThroughCache cache;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.cache = new StreamThroughCache(this.storage);
    }

    @Test
    @Timeout(10)
    void cachesContentFromRemote() throws Exception {
        final Key key = new Key.From("test", "artifact.jar");
        final byte[] data = "test-content-for-caching".getBytes();
        final Optional<? extends Content> result = this.cache.load(
            key,
            () -> CompletableFuture.completedFuture(Optional.of(new Content.From(data))),
            CacheControl.Standard.ALWAYS
        ).toCompletableFuture().join();
        assertThat("Content should be present", result.isPresent(), is(true));
        final byte[] loaded = result.get().asBytesFuture().join();
        assertThat(loaded, equalTo(data));
    }

    @Test
    @Timeout(10)
    void cachesLargeContentIntact() throws Exception {
        final Key key = new Key.From("test", "large-artifact.jar");
        final byte[] data = new byte[256 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        final Optional<? extends Content> result = this.cache.load(
            key,
            () -> CompletableFuture.completedFuture(Optional.of(new Content.From(data))),
            CacheControl.Standard.ALWAYS
        ).toCompletableFuture().join();
        assertThat("Content should be present", result.isPresent(), is(true));
        final byte[] loaded = result.get().asBytesFuture().join();
        assertThat("Content integrity after cache", loaded, equalTo(data));
    }
}
