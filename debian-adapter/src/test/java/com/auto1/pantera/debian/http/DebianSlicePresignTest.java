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
package com.auto1.pantera.debian.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.Presigner;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.debian.GpgConfig;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.auto1.pantera.security.policy.PolicyByUsername;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
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
 * WS1.7 presigned-direct-download tests for {@link DebianSlice}: the shared
 * catch-all GET route serves BOTH {@code .deb} package bytes and the apt
 * indices ({@code Release}, {@code Packages}, ...), so only the {@code .deb}
 * route may redirect. A {@code Release} index GET MUST keep streaming even
 * under a {@link DownloadMode#REDIRECT} policy with a presign-capable backend
 * -- the adapter-level proof that metadata is never redirected.
 */
@Timeout(15)
final class DebianSlicePresignTest {

    private static final String USER = "Alladin";
    private static final String PASS = "openSesame";
    private static final String CODENAME = "my-repo";
    private static final String PRESIGNED = "https://blobs.example.test/deb-repo/nginx.deb?sig=abc";

    @Test
    void debRedirectsButReleaseStreams() {
        final PresigningStorage storage = new PresigningStorage(new InMemoryStorage());
        // Pre-seed the Release index so ReleaseSlice short-circuits to the
        // catch-all serve instead of regenerating it.
        storage.save(new Key.From("dists", CODENAME, "Release"), content("Origin: test\n")).join();
        storage.save(
            new Key.From("pool", "main", "n", "nginx", "nginx_1.0_amd64.deb"), content("deb-bytes")
        ).join();
        final DebianSlice slice = new DebianSlice(
            storage,
            new PolicyByUsername(USER),
            new Authentication.Single(USER, PASS),
            new TestConfig(),
            Optional.empty(),
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP,
            new DownloadPolicy(DownloadMode.REDIRECT, 600L)
        );

        final Response deb = get(slice, "/pool/main/n/nginx/nginx_1.0_amd64.deb");
        MatcherAssert.assertThat(
            "a .deb package GET must redirect (302) under REDIRECT policy",
            deb.status().code(), new IsEqual<>(302)
        );
        MatcherAssert.assertThat(
            "redirect must point at the presigned URL",
            new RqHeaders.Single(deb.headers(), Location.NAME).asString(),
            new IsEqual<>(PRESIGNED)
        );
        MatcherAssert.assertThat(
            "exactly one presign for the single .deb GET",
            storage.presignCalls.get(), new IsEqual<>(1)
        );

        MatcherAssert.assertThat(
            "a Release index GET must stream (200), never redirect",
            get(slice, "/dists/" + CODENAME + "/Release").status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the index GET must not have triggered any further presign attempt",
            storage.presignCalls.get(), new IsEqual<>(1)
        );
    }

    private static Response get(final DebianSlice slice, final String path) {
        return slice.response(
            new RequestLine("GET", path),
            Headers.from(new Authorization.Basic(USER, PASS)),
            Content.EMPTY
        ).toCompletableFuture().join();
    }

    private static Content content(final String value) {
        return new Content.From(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Minimal {@link Config} -- only the codename drives the Release key here. */
    private static final class TestConfig implements Config {

        @Override
        public String codename() {
            return DebianSlicePresignTest.CODENAME;
        }

        @Override
        public Collection<String> components() {
            return Collections.singletonList("main");
        }

        @Override
        public Collection<String> archs() {
            return Collections.singletonList("amd64");
        }

        @Override
        public Optional<GpgConfig> gpg() {
            return Optional.empty();
        }
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
