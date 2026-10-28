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
package com.auto1.pantera.npm.http;

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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.security.policy.Policy;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.json.Json;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * WS1.7 presigned-direct-download wiring of {@link NpmSlice} (a LOCAL
 * repository): only the {@code .tgz} tarball-byte route is redirect-eligible.
 * The packument and single-version manifest routes MUST keep streaming even
 * under a {@link DownloadMode#REDIRECT} policy with a presign-capable backend,
 * a {@code HEAD} probe on the tarball route never redirects, and the
 * pre-WS1.7 constructor family stays byte-identical to stream-only serving.
 */
@Timeout(15)
final class NpmSlicePresignTest {

    /**
     * Bearer token this test's {@link TokenAuthentication} double accepts.
     */
    private static final String TOKEN = "npm-presign-test-token";

    /**
     * URL the fake presigner hands out.
     */
    private static final String PRESIGNED =
        "https://blobs.example.test/npm-local/plain-pkg/-/plain-pkg-1.0.0.tgz?sig=abc";

    /**
     * Tarball route of the published fixture.
     */
    private static final String TARBALL = "/plain-pkg/-/plain-pkg-1.0.0.tgz";

    /**
     * Redirect policy under test.
     */
    private static final DownloadPolicy REDIRECT = new DownloadPolicy(DownloadMode.REDIRECT, 600L);

    /**
     * Presign-capable storage holding one published package and its tarball.
     */
    private PresigningStorage storage;

    @BeforeEach
    void publishFixture() {
        this.storage = new PresigningStorage(new InMemoryStorage());
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        final Key pkg = new Key.From("plain-pkg");
        layout.addVersion(
            pkg, "1.0.0",
            Json.createObjectBuilder()
                .add("name", "plain-pkg")
                .add("version", "1.0.0")
                .add(
                    "dist",
                    Json.createObjectBuilder()
                        .add("tarball", "http://oldhost/plain-pkg/-/plain-pkg-1.0.0.tgz")
                        .build()
                )
                .build()
        ).toCompletableFuture().join();
        layout.mergeDistTags(
            pkg, Json.createObjectBuilder().add("latest", "1.0.0").build()
        ).toCompletableFuture().join();
        this.storage.save(
            new Key.From("plain-pkg/-/plain-pkg-1.0.0.tgz"),
            new Content.From("tgz-bytes".getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    @Test
    void tarballGetRedirectsUnderRedirectPolicy() {
        final Response tgz = NpmSlicePresignTest.request(
            this.slice(NpmSlicePresignTest.REDIRECT), RqMethod.GET, NpmSlicePresignTest.TARBALL
        );
        MatcherAssert.assertThat(
            "the .tgz tarball-byte route must redirect (302) under REDIRECT policy",
            tgz.status().code(), new IsEqual<>(302)
        );
        MatcherAssert.assertThat(
            "the redirect must point at the presigned URL",
            new RqHeaders.Single(tgz.headers(), Location.NAME).asString(),
            new IsEqual<>(NpmSlicePresignTest.PRESIGNED)
        );
        MatcherAssert.assertThat(
            "exactly one presign for the single .tgz GET",
            this.storage.presignCalls.get(), new IsEqual<>(1)
        );
    }

    @Test
    void metadataRoutesStreamUnderRedirectPolicy() {
        final NpmSlice slice = this.slice(NpmSlicePresignTest.REDIRECT);
        MatcherAssert.assertThat(
            "the packument route must stream (200), never redirect",
            NpmSlicePresignTest.request(slice, RqMethod.GET, "/plain-pkg").status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the single-version manifest route must stream (200), never redirect",
            NpmSlicePresignTest.request(slice, RqMethod.GET, "/plain-pkg/1.0.0").status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "metadata routes must never reach the presigner",
            this.storage.presignCalls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void tarballHeadNeverRedirects() {
        final Response head = NpmSlicePresignTest.request(
            this.slice(NpmSlicePresignTest.REDIRECT), RqMethod.HEAD, NpmSlicePresignTest.TARBALL
        );
        MatcherAssert.assertThat(
            "a HEAD probe on the tarball route is answered by Pantera (200), never a 302",
            head.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "a HEAD probe must never reach the presigner",
            this.storage.presignCalls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void streamOnlyDefaultServesTarballBytesWithoutPresigning() {
        final NpmSlice slice = new NpmSlice(
            NpmSlicePresignTest.baseUrl(), this.storage, Policy.FREE,
            NpmSlicePresignTest.permissiveAuth(), "npm-local", Optional.empty()
        );
        final Response tgz = NpmSlicePresignTest.request(
            slice, RqMethod.GET, NpmSlicePresignTest.TARBALL
        );
        MatcherAssert.assertThat(
            "the pre-WS1.7 constructor family streams the tarball bytes (200)",
            tgz.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the stream-only default must never touch the presigner",
            this.storage.presignCalls.get(), new IsEqual<>(0)
        );
    }

    /**
     * Builds a JWT-only local slice through the {@code Optional<URL>} +
     * {@link DownloadPolicy} constructor {@code RepositorySlices} wires.
     * @param policy Download policy under test
     * @return Slice
     */
    private NpmSlice slice(final DownloadPolicy policy) {
        return new NpmSlice(
            Optional.of(NpmSlicePresignTest.baseUrl()), this.storage, Policy.FREE, null,
            NpmSlicePresignTest.permissiveAuth(), null, "npm-local", Optional.empty(), true,
            SyncArtifactIndexer.NOOP, ArtifactIndex.NOP, policy
        );
    }

    private static Response request(final NpmSlice slice, final RqMethod method, final String path) {
        return slice.response(
            new RequestLine(method, path),
            Headers.from(new Authorization.Bearer(NpmSlicePresignTest.TOKEN)),
            Content.EMPTY
        ).join();
    }

    private static TokenAuthentication permissiveAuth() {
        return token -> CompletableFuture.completedFuture(
            NpmSlicePresignTest.TOKEN.equals(token)
                ? Optional.of(new AuthUser("presign-tester", "test"))
                : Optional.empty()
        );
    }

    private static URL baseUrl() {
        try {
            return URI.create("http://pantera.local").toURL();
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * {@link Storage} that also presigns -- the "bare presigner" composition
     * {@code PresignResolver} resolves without any {@code SubStorage} wrapper.
     */
    private static final class PresigningStorage implements Storage, Presigner {

        /**
         * Backing storage.
         */
        private final Storage delegate;

        /**
         * Number of presign requests observed.
         */
        private final AtomicInteger presignCalls = new AtomicInteger();

        PresigningStorage(final Storage delegate) {
            this.delegate = delegate;
        }

        @Override
        public URI presignGet(final Key key, final long ttlSeconds) {
            this.presignCalls.incrementAndGet();
            return URI.create(NpmSlicePresignTest.PRESIGNED);
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
