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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.Presigner;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.security.policy.PolicyByUsername;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * WS1.7 presigned-direct-download tests for {@link PhpComposer}/{@link
 * DownloadArchiveSlice}: the dist archive download is redirect-eligible, while
 * a Composer metadata route ({@code /p2/available-packages.json}) MUST keep
 * streaming even under a {@link DownloadMode#REDIRECT} policy with a
 * presign-capable backend. A redirect hands the client the IDENTICAL stored
 * bytes, so the client's {@code dist.shasum} (SHA-1) verification is unaffected.
 */
@Timeout(15)
final class DownloadArchiveSlicePresignTest {

    private static final String USER = "Alladin";
    private static final String PASS = "openSesame";
    private static final String PRESIGNED =
        "https://blobs.example.test/vendor/pkg/1.0.0.zip?sig=abc";

    @Test
    void distRedirectsButMetadataStreams() {
        final PresigningStorage storage = new PresigningStorage(new InMemoryStorage());
        storage.save(new Key.From("vendor/pkg/1.0.0.zip"), content("zip")).join();
        final Slice slice = new PhpComposer(
            new AstoRepository(
                storage, Optional.of("http://localhost/php-repo"), Optional.of("php-repo")
            ),
            new PolicyByUsername(USER),
            new Authentication.Single(USER, PASS),
            null,
            "php-repo",
            Optional.empty(),
            SyncArtifactIndexer.NOOP,
            ArtifactIndex.NOP,
            new DownloadPolicy(DownloadMode.REDIRECT, 600L)
        );

        final Response dist = get(slice, "/vendor/pkg/1.0.0.zip");
        MatcherAssert.assertThat(
            "the dist-archive GET must redirect (302) under REDIRECT policy",
            dist.status().code(), new IsEqual<>(302)
        );
        MatcherAssert.assertThat(
            "redirect must point at the presigned URL",
            new RqHeaders.Single(dist.headers(), Location.NAME).asString(),
            new IsEqual<>(PRESIGNED)
        );
        MatcherAssert.assertThat(
            "exactly one presign for the single dist GET",
            storage.presignCalls.get(), new IsEqual<>(1)
        );

        MatcherAssert.assertThat(
            "the /p2/ metadata route must stream (200), never redirect",
            get(slice, "/p2/available-packages.json").status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "metadata must not have triggered any further presign attempts",
            storage.presignCalls.get(), new IsEqual<>(1)
        );
    }

    private static Response get(final Slice slice, final String path) {
        return slice.response(
            new RequestLine("GET", path),
            Headers.from(new Authorization.Basic(USER, PASS)),
            Content.EMPTY
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
