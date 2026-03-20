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
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.asto.BlobSource;
import io.reactivex.Flowable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.reactivex.disposables.Disposable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    void cachedDataMatchesOriginal() throws Exception {
        final byte[] data = new byte[256 * 1024]; // 256KB blob
        new java.util.Random(42).nextBytes(data);
        final Blob origin = fakeBlob(data);
        final CapturingLayers cacheLayers = new CapturingLayers();
        final CachingBlob blob = new CachingBlob(origin, cacheLayers);
        final byte[] received = blob.content()
            .thenCompose(Content::asBytesFuture)
            .join();
        Assertions.assertArrayEquals(data, received, "Client must receive original data");
        // Give async cache save time to complete
        Thread.sleep(1000);
        Assertions.assertNotNull(
            cacheLayers.savedContent.get(),
            "Cache put() should have been called"
        );
        final byte[] cached = cacheLayers.savedContent.get()
            .asBytesFuture().join();
        Assertions.assertArrayEquals(data, cached,
            "Cached data must match original blob content"
        );
    }

    @Test
    void cachesMultiChunkBlob() throws Exception {
        final byte[] chunk1 = "first chunk of data ".getBytes();
        final byte[] chunk2 = "second chunk of data".getBytes();
        final byte[] expected = new byte[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, expected, 0, chunk1.length);
        System.arraycopy(chunk2, 0, expected, chunk1.length, chunk2.length);
        final Digest digest = new Digest.Sha256("multichunk");
        final Blob origin = new Blob() {
            @Override public Digest digest() { return digest; }
            @Override public CompletableFuture<Long> size() {
                return CompletableFuture.completedFuture((long) expected.length);
            }
            @Override public CompletableFuture<Content> content() {
                return CompletableFuture.completedFuture(
                    new Content.From(
                        expected.length,
                        Flowable.just(
                            ByteBuffer.wrap(chunk1),
                            ByteBuffer.wrap(chunk2)
                        )
                    )
                );
            }
        };
        final CapturingLayers cacheLayers = new CapturingLayers();
        final CachingBlob blob = new CachingBlob(origin, cacheLayers);
        final byte[] received = blob.content()
            .thenCompose(Content::asBytesFuture)
            .join();
        Assertions.assertArrayEquals(expected, received);
        Thread.sleep(1000);
        Assertions.assertNotNull(cacheLayers.savedContent.get());
        final byte[] cached = cacheLayers.savedContent.get()
            .asBytesFuture().join();
        Assertions.assertArrayEquals(expected, cached,
            "Multi-chunk blob must be fully cached"
        );
    }

    /**
     * Simulates VertxSliceServer behaviour: subscribes to the blob content, receives ALL
     * bytes, then cancels the subscription before onComplete fires.  This is the race that
     * CachingBlob must handle: cancel wins the AtomicBoolean CAS but all bytes are already
     * in the temp file, so the blob should still be cached.
     */
    @Test
    void cacheSaveCalledWhenCancelledAfterAllBytes() throws Exception {
        final byte[] data = "cancel-beats-complete blob content".getBytes();
        final Blob origin = fakeBlob(data);
        final RecordingLayers cacheLayers = new RecordingLayers();
        final CachingBlob blob = new CachingBlob(origin, cacheLayers);
        final Content content = blob.content().join();
        final CountDownLatch done = new CountDownLatch(1);
        // Subscribe via raw Subscriber so we can cancel after receiving all bytes
        Flowable.fromPublisher(content).subscribe(new Subscriber<ByteBuffer>() {
            private Subscription sub;
            private int received;
            @Override public void onSubscribe(final Subscription s) {
                this.sub = s;
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(final ByteBuffer buf) {
                this.received += buf.remaining();
                if (this.received >= data.length) {
                    // Cancel after all bytes consumed — exactly what VertxSliceServer does
                    this.sub.cancel();
                    done.countDown();
                }
            }
            @Override public void onError(final Throwable t) { done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });
        done.await(5L, TimeUnit.SECONDS);
        Thread.sleep(500L);
        Assertions.assertNotNull(
            cacheLayers.lastPut.get(),
            "Cache put() must be called when cancel fires after all bytes are received"
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

    /**
     * Layers impl that captures the actual content bytes from put() calls
     * using an InMemoryStorage.
     */
    private static final class CapturingLayers implements Layers {
        final com.auto1.pantera.asto.memory.InMemoryStorage storage =
            new com.auto1.pantera.asto.memory.InMemoryStorage();
        final com.auto1.pantera.asto.Key key = new com.auto1.pantera.asto.Key.From("test");
        final AtomicReference<Content> savedContent = new AtomicReference<>();

        @Override
        public CompletableFuture<Digest> put(final BlobSource source) {
            return source.saveTo(storage, key)
                .thenCompose(nothing -> storage.value(key))
                .thenApply(content -> {
                    savedContent.set(content);
                    return source.digest();
                });
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
