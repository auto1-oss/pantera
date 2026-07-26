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
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.Presigner;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.auto1.pantera.http.rq.RqMethod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link StorageArtifactSlice}'s WS1.7 (spec {@code
 * WS1-storage-for-scale.md} &sect;3.B2) presigned-direct-download dispatch:
 *
 * <ul>
 *   <li>{@link DownloadPolicy#streamOnly()} always streams, even when the
 *   backing storage CAN presign -- this is exactly what keeps metadata routes
 *   (which are wired with the stream-only default) from ever redirecting.</li>
 *   <li>{@link DownloadMode#REDIRECT} issues a {@code 302} to the presigned
 *   URL when the storage composition supports it.</li>
 *   <li>{@link DownloadMode#REDIRECT} falls back to streaming, unchanged, when
 *   the storage has no presigner.</li>
 *   <li>A non-GET method never redirects.</li>
 * </ul>
 */
@Timeout(15)
final class StorageArtifactSlicePresignTest {

    private static final String PRESIGNED = "https://blobs.example.test/myrepo/artifact.jar?sig=abc";

    @Test
    void streamOnlyServesBytesEvenWhenPresignerAvailable() {
        final PresigningStorage backend = new PresigningStorage(new InMemoryStorage());
        backend.save(new Key.From("myrepo", "artifact.jar"), content("payload")).join();
        final Storage repoScoped = new SubStorage(new Key.From("myrepo"), backend);
        final Slice slice = new StorageArtifactSlice(repoScoped, DownloadPolicy.streamOnly());

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/artifact.jar"), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "stream-only must serve 200, never a 302",
            response.status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "stream-only must serve the actual bytes",
            response.body().asBytesFuture().join(),
            new IsEqual<>("payload".getBytes(StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "stream-only must NOT even attempt to sign (metadata-route safety)",
            backend.presignCalls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void redirectModeIssues302ForDurablePresignerBackedKey() {
        final PresigningStorage backend = new PresigningStorage(new InMemoryStorage());
        backend.save(new Key.From("myrepo", "artifact.jar"), content("payload")).join();
        final Storage repoScoped = new SubStorage(new Key.From("myrepo"), backend);
        final Slice slice = new StorageArtifactSlice(
            repoScoped, new DownloadPolicy(DownloadMode.REDIRECT, 600L)
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/artifact.jar"), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "redirect mode must answer 302 Found",
            response.status().code(), new IsEqual<>(302)
        );
        MatcherAssert.assertThat(
            "redirect must point at the presigned URL",
            new RqHeaders.Single(response.headers(), Location.NAME).asString(),
            new IsEqual<>(PRESIGNED)
        );
        MatcherAssert.assertThat(
            "the presigner must have been used exactly once",
            backend.presignCalls.get(), new IsEqual<>(1)
        );
    }

    @Test
    void redirectModeStreamsWhenNoPresigner() {
        final Storage backend = new InMemoryStorage();
        backend.save(new Key.From("myrepo", "artifact.jar"), content("payload")).join();
        final Storage repoScoped = new SubStorage(new Key.From("myrepo"), backend);
        final Slice slice = new StorageArtifactSlice(
            repoScoped, new DownloadPolicy(DownloadMode.REDIRECT, 600L)
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/artifact.jar"), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "with no presigner in the storage composition, redirect mode must fall back to a 200 stream",
            response.status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            response.body().asBytesFuture().join(),
            new IsEqual<>("payload".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void nonGetMethodNeverRedirects() {
        final PresigningStorage backend = new PresigningStorage(new InMemoryStorage());
        backend.save(new Key.From("myrepo", "artifact.jar"), content("payload")).join();
        final Storage repoScoped = new SubStorage(new Key.From("myrepo"), backend);
        final Slice slice = new StorageArtifactSlice(
            repoScoped, new DownloadPolicy(DownloadMode.REDIRECT, 600L)
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.HEAD, "/artifact.jar"), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "a non-GET request must never be answered with a presigned redirect",
            response.status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "no presign attempt on a non-GET method",
            backend.presignCalls.get(), new IsEqual<>(0)
        );
    }

    private static Content content(final String value) {
        return new Content.From(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@link Storage} that also implements {@link Presigner} -- the "bare
     * presigner" composition {@link com.auto1.pantera.asto.blob.PresignResolver}
     * treats as durably present (plain {@code S3Storage} with no cache
     * wrapper). Records how many times a URL was signed.
     */
    private static final class PresigningStorage implements Storage, Presigner {

        private final Storage delegate;
        private final AtomicInteger presignCalls = new AtomicInteger();

        PresigningStorage(final Storage delegate) {
            this.delegate = delegate;
        }

        @Override
        public URI presignGet(final Key key, final long ttlSeconds) {
            this.presignCalls.incrementAndGet();
            return URI.create(PRESIGNED);
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
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
        public CompletableFuture<Void> save(final Key key, final Content data) {
            return this.delegate.save(key, data);
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
        public <T> CompletionStage<T> exclusively(
            final Key key, final Function<Storage, CompletionStage<T>> operation
        ) {
            return this.delegate.exclusively(key, operation);
        }
    }
}
