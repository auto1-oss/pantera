/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.cache;

import com.artipie.asto.Content;
import com.artipie.docker.Blob;
import com.artipie.docker.Digest;
import com.artipie.docker.Layers;
import com.artipie.docker.asto.BlobSource;
import io.reactivex.Flowable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link CachingBlob}.
 */
final class CachingBlobTest {

    @Test
    void clientReceivesAllBytes() {
        final byte[] data = "hello world blob content".getBytes();
        final Blob origin = fakeBlob(data);
        final CachingBlob blob = new CachingBlob(origin, new NoopLayers());
        final byte[] result = blob.content()
            .thenCompose(Content::asBytesFuture)
            .join();
        Assertions.assertArrayEquals(data, result);
    }

    @Test
    void delegatesDigest() {
        final Digest digest = new Digest.Sha256("abc123");
        final Blob origin = new Blob() {
            @Override public Digest digest() { return digest; }
            @Override public CompletableFuture<Long> size() {
                return CompletableFuture.completedFuture(0L);
            }
            @Override public CompletableFuture<Content> content() {
                return CompletableFuture.completedFuture(Content.EMPTY);
            }
        };
        final CachingBlob blob = new CachingBlob(origin, new NoopLayers());
        Assertions.assertEquals(digest, blob.digest());
    }

    @Test
    void delegatesSize() {
        final byte[] data = "test data".getBytes();
        final Blob origin = fakeBlob(data);
        final CachingBlob blob = new CachingBlob(origin, new NoopLayers());
        Assertions.assertEquals(
            (long) data.length,
            blob.size().join()
        );
    }

    @Test
    void cacheSaveCalledAfterStreamConsumed() throws Exception {
        final byte[] data = "cache this blob".getBytes();
        final Blob origin = fakeBlob(data);
        final RecordingLayers cacheLayers = new RecordingLayers();
        final CachingBlob blob = new CachingBlob(origin, cacheLayers);
        blob.content()
            .thenCompose(Content::asBytesFuture)
            .join();
        // Give async cache save time to complete
        Thread.sleep(500);
        Assertions.assertNotNull(
            cacheLayers.lastPut.get(),
            "Cache put() should have been called"
        );
    }

    private static Blob fakeBlob(final byte[] data) {
        final Digest digest = new Digest.Sha256("faketest");
        return new Blob() {
            @Override public Digest digest() { return digest; }
            @Override public CompletableFuture<Long> size() {
                return CompletableFuture.completedFuture((long) data.length);
            }
            @Override public CompletableFuture<Content> content() {
                return CompletableFuture.completedFuture(
                    new Content.From(
                        data.length,
                        Flowable.just(ByteBuffer.wrap(data))
                    )
                );
            }
        };
    }

    /**
     * Layers impl that does nothing on put().
     */
    private static final class NoopLayers implements Layers {
        @Override
        public CompletableFuture<Digest> put(final BlobSource source) {
            return CompletableFuture.completedFuture(new Digest.Sha256("noop"));
        }
        @Override
        public CompletableFuture<Void> mount(final Blob blob) {
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public CompletableFuture<Optional<Blob>> get(final Digest digest) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /**
     * Layers impl that records put() calls.
     */
    private static final class RecordingLayers implements Layers {
        final AtomicReference<BlobSource> lastPut = new AtomicReference<>();

        @Override
        public CompletableFuture<Digest> put(final BlobSource source) {
            this.lastPut.set(source);
            return CompletableFuture.completedFuture(source.digest());
        }
        @Override
        public CompletableFuture<Void> mount(final Blob blob) {
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public CompletableFuture<Optional<Blob>> get(final Digest digest) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }
}
