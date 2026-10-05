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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.TrustedBlobSource;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.composite.MultiReadLayers;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link DockerSlice}. Blob DELETE endpoint
 * ({@link com.auto1.pantera.docker.http.blobs.DeleteBlobSlice}).
 */
final class DeleteBlobSliceTest {

    private DockerSlice slice;

    private Docker docker;

    @BeforeEach
    void setUp() {
        this.docker = new AstoDocker("test_registry", new InMemoryStorage());
        this.slice = new DockerSlice(this.docker);
    }

    @Test
    void shouldDeleteExistingBlobAndReturnAccepted() {
        final Digest digest = this.docker.repo("my-alpine").layers()
            .put(new TrustedBlobSource("layer-bytes".getBytes()))
            .toCompletableFuture().join();
        final Response response = this.delete(
            String.format("/v2/my-alpine/blobs/%s", digest.string())
        );
        ResponseAssert.check(response, RsStatus.ACCEPTED);
        MatcherAssert.assertThat(
            "Blob is gone after delete",
            this.docker.repo("my-alpine").layers().get(digest).join().isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void shouldReturnNotFoundForUnknownDigest() {
        final Response response = this.delete(
            "/v2/my-alpine/blobs/sha256:" + "0".repeat(64)
        );
        MatcherAssert.assertThat(
            response,
            new IsErrorsResponse(RsStatus.NOT_FOUND, "BLOB_UNKNOWN")
        );
    }

    @Test
    void shouldReturnMethodNotAllowedForProxyLikeDocker() {
        // MultiReadLayers.delete() (used to compose docker-proxy /
        // docker-group) always throws UnsupportedOperationException.
        final Docker proxyLike = new Docker() {
            @Override
            public String registryName() {
                return "proxy-like";
            }

            @Override
            public Repo repo(final String name) {
                return new Repo() {
                    @Override
                    public Layers layers() {
                        return new MultiReadLayers(List.of());
                    }

                    @Override
                    public Manifests manifests() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Uploads uploads() {
                        throw new UnsupportedOperationException();
                    }
                };
            }

            @Override
            public CompletableFuture<Catalog> catalog(final Pagination pagination) {
                throw new UnsupportedOperationException();
            }
        };
        final DockerSlice proxySlice = new DockerSlice(proxyLike);
        final Response response = proxySlice.response(
            new RequestLine(RqMethod.DELETE, "/v2/my-alpine/blobs/sha256:" + "1".repeat(64)),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        ResponseAssert.check(response, RsStatus.METHOD_NOT_ALLOWED);
    }

    private Response delete(final String path) {
        return this.slice.response(
            new RequestLine(RqMethod.DELETE, path), Headers.EMPTY, Content.EMPTY
        ).join();
    }
}
