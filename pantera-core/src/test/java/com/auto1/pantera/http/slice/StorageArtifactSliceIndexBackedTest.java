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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.blob.BlobStore;
import com.auto1.pantera.asto.blob.CachedBlobStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link StorageArtifactSlice}'s WS1.6 index-backed hosted-read
 * dispatch (spec {@code WS1-storage-for-scale.md} &sect;3.F): a {@link
 * CachedBlobStorage}-backed repository must be served by {@link
 * StorageArtifactSlice}'s new {@code IndexBackedArtifactSlice} path, which
 * issues a single {@code storage.value()} call -- proved with an
 * invocation-counting {@link BlobStore} fake, never a real backend
 * (CLAUDE.md testing doctrine). {@code DiskCacheStorage}/plain-{@code
 * Storage} backends must keep {@code GenericArtifactSlice}'s existing
 * {@code exists()}+{@code value()} behaviour unchanged.
 */
@Timeout(15)
final class StorageArtifactSliceIndexBackedTest {

    @Test
    void coldButPresentKeyIssuesExactlyOneBlobStoreGetAndZeroHead(@TempDir final Path tmp) {
        final CountingBlobStore blobStore = new CountingBlobStore();
        blobStore.seed("artifact.jar", "hello-world".getBytes(StandardCharsets.UTF_8));
        final Storage repoScoped = new SubStorage(
            Key.ROOT,
            new CachedBlobStorage(blobStore, tmp, Duration.ofMinutes(5), Duration.ofSeconds(30))
        );
        final Slice slice = new StorageArtifactSlice(repoScoped);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/artifact.jar"), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat("status", response.status().code(), new IsEqual<>(200));
        MatcherAssert.assertThat(
            response.body().asBytesFuture().join(),
            new IsEqual<>("hello-world".getBytes(StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "the index-backed hosted read must issue exactly ONE blob-store GET (no exists()-driven HEAD first)",
            blobStore.getCalls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "zero HEAD calls proves no redundant exists() probe ran before value()",
            blobStore.headCalls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void warmKeyServesFromDiskWithZeroBlobStoreCalls(@TempDir final Path tmp) {
        final CountingBlobStore blobStore = new CountingBlobStore();
        final CachedBlobStorage cached = new CachedBlobStorage(blobStore, tmp, Duration.ofMinutes(5), Duration.ofSeconds(30));
        final Key key = new Key.From("artifact.jar");
        cached.save(key, new Content.From("bytes-on-disk".getBytes(StandardCharsets.UTF_8))).join();
        blobStore.reset();
        final Storage repoScoped = new SubStorage(Key.ROOT, cached);
        final Slice slice = new StorageArtifactSlice(repoScoped);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/artifact.jar"), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(response.status().code(), new IsEqual<>(200));
        MatcherAssert.assertThat("a disk hit must not touch the blob store at all", blobStore.getCalls.get(), new IsEqual<>(0));
        MatcherAssert.assertThat(blobStore.headCalls.get(), new IsEqual<>(0));
    }

    @Test
    void missingKeyReturns404AfterExactlyOneBlobStoreAttempt(@TempDir final Path tmp) {
        final CountingBlobStore blobStore = new CountingBlobStore();
        final Storage repoScoped = new SubStorage(
            Key.ROOT,
            new CachedBlobStorage(blobStore, tmp, Duration.ofMinutes(5), Duration.ofSeconds(30))
        );
        final Slice slice = new StorageArtifactSlice(repoScoped);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/missing.jar"), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(response.status().code(), new IsEqual<>(404));
        MatcherAssert.assertThat(
            "a 404 must still cost only one blob-store attempt, not a HEAD-then-GET pair",
            blobStore.getCalls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(blobStore.headCalls.get(), new IsEqual<>(0));
    }

    @Test
    void rangeRequestOnIndexBackedStorageServesPartialContent(@TempDir final Path tmp) {
        final CountingBlobStore blobStore = new CountingBlobStore();
        blobStore.seed("artifact.jar", "0123456789".getBytes(StandardCharsets.UTF_8));
        final Storage repoScoped = new SubStorage(
            Key.ROOT,
            new CachedBlobStorage(blobStore, tmp, Duration.ofMinutes(5), Duration.ofSeconds(30))
        );
        final Slice slice = new StorageArtifactSlice(repoScoped);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/artifact.jar"),
            Headers.from("Range", "bytes=2-4"),
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat("Range must still yield 206 Partial Content", response.status().code(), new IsEqual<>(206));
        MatcherAssert.assertThat(
            response.body().asBytesFuture().join(),
            new IsEqual<>("234".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void nonIndexBackedStorageStillUsesGenericExistsThenValue() {
        // Regression guard: a plain (non-CachedBlobStorage) backend must keep
        // GenericArtifactSlice's exists()+value() behaviour unchanged.
        final CountingStorage counting = new CountingStorage(new InMemoryStorage());
        counting.save(new Key.From("plain.txt"), new Content.From("plain".getBytes(StandardCharsets.UTF_8))).join();
        counting.existsCalls.set(0);
        final Slice slice = new StorageArtifactSlice(counting);

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/plain.txt"), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(response.status().code(), new IsEqual<>(200));
        MatcherAssert.assertThat(
            "non-index-backed storage must be unaffected by WS1.6 -- it still calls exists() first",
            counting.existsCalls.get(), new IsEqual<>(1)
        );
    }

    /** Invocation-counting {@link BlobStore} fake, mirroring RecordingBlobStore (pantera-storage-core). */
    private static final class CountingBlobStore implements BlobStore {
        private final Map<String, byte[]> objects = new HashMap<>();
        private final AtomicInteger getCalls = new AtomicInteger();
        private final AtomicInteger headCalls = new AtomicInteger();

        void seed(final String key, final byte[] data) {
            this.objects.put(key, data);
        }

        void reset() {
            this.getCalls.set(0);
            this.headCalls.set(0);
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return CompletableFuture.completedFuture(this.objects.containsKey(key.string()));
        }

        @Override
        public CompletableFuture<? extends Meta> head(final Key key) {
            this.headCalls.incrementAndGet();
            final byte[] data = this.objects.get(key.string());
            return data == null
                ? CompletableFuture.failedFuture(new ValueNotFoundException(key))
                : CompletableFuture.completedFuture(StorageArtifactSliceIndexBackedTest.sizeMeta(data.length));
        }

        @Override
        public CompletableFuture<Content> get(final Key key) {
            this.getCalls.incrementAndGet();
            final byte[] data = this.objects.get(key.string());
            return data == null
                ? CompletableFuture.failedFuture(new ValueNotFoundException(key))
                : CompletableFuture.completedFuture(new Content.From(data));
        }

        @Override
        public CompletableFuture<Void> put(final Key key, final Content content) {
            return content.asBytesFuture().thenApply(bytes -> {
                this.objects.put(key.string(), bytes);
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            this.objects.remove(key.string());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private static Meta sizeMeta(final long size) {
        return new Meta() {
            @Override
            public <T> T read(final ReadOperator<T> opr) {
                final Map<String, String> raw = new HashMap<>();
                Meta.OP_SIZE.put(raw, size);
                return opr.take(raw);
            }
        };
    }

    /** {@link Storage} decorator counting {@code exists()} calls -- proves the GenericArtifactSlice fallback path. */
    private static final class CountingStorage implements Storage {
        private final Storage delegate;
        private final AtomicInteger existsCalls = new AtomicInteger();

        CountingStorage(final Storage delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            this.existsCalls.incrementAndGet();
            return this.delegate.exists(key);
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return this.delegate.metadata(key);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return this.delegate.list(prefix);
        }

        @Override
        public CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
            return this.delegate.list(prefix, delimiter);
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return this.delegate.value(key);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            return this.delegate.save(key, content);
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return this.delegate.move(source, destination);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return this.delegate.delete(key);
        }

        @Override
        public <T> java.util.concurrent.CompletionStage<T> exclusively(
            final Key key, final java.util.function.Function<Storage, java.util.concurrent.CompletionStage<T>> operation
        ) {
            return this.delegate.exclusively(key, operation);
        }
    }
}
