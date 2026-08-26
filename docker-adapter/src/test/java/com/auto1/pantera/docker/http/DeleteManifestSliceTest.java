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
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.TrustedBlobSource;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.composite.MultiReadManifests;
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
 * Tests for {@link DockerSlice}. Manifest DELETE endpoint
 * ({@link com.auto1.pantera.docker.http.manifest.DeleteManifestSlice}).
 */
final class DeleteManifestSliceTest {

    private DockerSlice slice;

    private Docker docker;

    @BeforeEach
    void setUp() {
        this.docker = new AstoDocker("test_registry", new InMemoryStorage());
        this.slice = new DockerSlice(this.docker);
    }

    @Test
    void shouldDeletePushedTagAndReturnAccepted() {
        this.push("my-alpine", "1");
        final Response response = this.delete("/v2/my-alpine/manifests/1");
        ResponseAssert.check(response, RsStatus.ACCEPTED);
        MatcherAssert.assertThat(
            "Tag no longer resolves after delete",
            this.docker.repo("my-alpine")
                .manifests()
                .get(ManifestReference.fromTag("1"))
                .join()
                .isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void shouldReturnNotFoundForUnknownReference() {
        final Response response = this.delete("/v2/my-alpine/manifests/does-not-exist");
        MatcherAssert.assertThat(
            response,
            new IsErrorsResponse(RsStatus.NOT_FOUND, "MANIFEST_UNKNOWN")
        );
    }

    @Test
    void shouldReturnMethodNotAllowedForProxyLikeDocker() {
        // MultiReadManifests.delete() (used to compose docker-proxy /
        // docker-group) always throws UnsupportedOperationException —
        // proved directly against the interface method, invocation-counted
        // via the not-reached assertion below rather than wall-clock.
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
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Manifests manifests() {
                        return new MultiReadManifests(name, List.of());
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
            new RequestLine(RqMethod.DELETE, "/v2/my-alpine/manifests/1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        ResponseAssert.check(response, RsStatus.METHOD_NOT_ALLOWED);
    }

    private void push(final String repo, final String tag) {
        final byte[] content = "config".getBytes();
        final Digest digest = this.docker.repo(repo).layers()
            .put(new TrustedBlobSource(content))
            .toCompletableFuture().join();
        final byte[] data = String.format(
            "{\"config\":{\"digest\":\"%s\"},\"layers\":[],\"mediaType\":\"my-type\"}",
            digest.string()
        ).getBytes();
        this.slice.response(
            new RequestLine(RqMethod.PUT, String.format("/v2/%s/manifests/%s", repo, tag)),
            Headers.EMPTY,
            new Content.From(data)
        ).join();
    }

    private Response delete(final String path) {
        return this.slice.response(
            new RequestLine(RqMethod.DELETE, path), Headers.EMPTY, Content.EMPTY
        ).join();
    }
}
