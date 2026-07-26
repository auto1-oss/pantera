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
package com.auto1.pantera.hex.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.Presigner;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
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
 * WS1.7 presigned-direct-download tests for {@link DownloadSlice}: only the
 * {@code /tarballs/} package-byte route is redirect-eligible; the {@code
 * /packages/} registry-metadata route MUST keep streaming even under a {@link
 * DownloadMode#REDIRECT} policy with a presign-capable backend. Adapter-level
 * proof that registry metadata is never redirected.
 */
@Timeout(15)
final class DownloadSlicePresignTest {

    private static final String PRESIGNED =
        "https://blobs.example.test/tarballs/decimal-2.0.0.tar?sig=abc";

    @Test
    void tarballRedirectsButPackagesStream() {
        final PresigningStorage storage = new PresigningStorage(new InMemoryStorage());
        storage.save(new Key.From("tarballs/decimal-2.0.0.tar"), content("tar")).join();
        storage.save(new Key.From("packages/decimal"), content("registry")).join();
        final DownloadSlice slice =
            new DownloadSlice(storage, new DownloadPolicy(DownloadMode.REDIRECT, 600L));

        final Response tarball = get(slice, "/tarballs/decimal-2.0.0.tar");
        MatcherAssert.assertThat(
            "the package-tarball GET must redirect (302) under REDIRECT policy",
            tarball.status().code(), new IsEqual<>(302)
        );
        MatcherAssert.assertThat(
            "redirect must point at the presigned URL",
            new RqHeaders.Single(tarball.headers(), Location.NAME).asString(),
            new IsEqual<>(PRESIGNED)
        );
        MatcherAssert.assertThat(
            "exactly one presign for the single tarball GET",
            storage.presignCalls.get(), new IsEqual<>(1)
        );

        MatcherAssert.assertThat(
            "the /packages/ registry-metadata route must stream (200), never redirect",
            get(slice, "/packages/decimal").status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "registry metadata must not have triggered any further presign attempts",
            storage.presignCalls.get(), new IsEqual<>(1)
        );
    }

    private static Response get(final DownloadSlice slice, final String path) {
        return slice.response(
            new RequestLine("GET", path), Headers.EMPTY, Content.EMPTY
        ).toCompletableFuture().join();
    }

    private static Content content(final String value) {
        return new Content.From(value.getBytes(StandardCharsets.UTF_8));
    }

    /** {@link Storage} that also presigns -- the "bare presigner" composition. */
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
