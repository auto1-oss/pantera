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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
    void shouldDeleteLayerOnlyThisImageUses() {
        final Digest layer = this.blob("my-alpine", "layer-bytes");
        this.image("my-alpine", "1", layer);
        final Response response = this.delete(
            String.format("/v2/my-alpine/blobs/%s", layer.string())
        );
        ResponseAssert.check(response, RsStatus.ACCEPTED);
        MatcherAssert.assertThat(
            "Blob is gone after delete",
            this.exists(layer),
            new IsEqual<>(false)
        );
    }

    @Test
    void shouldKeepLayerSharedWithAnotherImage() {
        final Digest shared = this.blob("team/a", "shared-base-layer");
        this.image("team/a", "1", shared);
        this.image("team/b", "1", shared);
        MatcherAssert.assertThat(
            "Delete through the other image is refused",
            this.delete(String.format("/v2/team/a/blobs/%s", shared.string())),
            new IsErrorsResponse(RsStatus.CONFLICT, "DENIED")
        );
        MatcherAssert.assertThat(
            "Shared layer survives the delete",
            this.exists(shared),
            new IsEqual<>(true)
        );
    }

    @Test
    void shouldNotDeleteLayerOfAnotherImage() {
        final Digest own = this.blob("team/a", "a-only-layer");
        final Digest foreign = this.blob("team/b", "b-only-layer");
        this.image("team/a", "1", own);
        this.image("team/b", "1", foreign);
        MatcherAssert.assertThat(
            "A layer the image does not reference is unknown to it",
            this.delete(String.format("/v2/team/a/blobs/%s", foreign.string())),
            new IsErrorsResponse(RsStatus.NOT_FOUND, "BLOB_UNKNOWN")
        );
        MatcherAssert.assertThat(
            "The other image's layer survives",
            this.exists(foreign),
            new IsEqual<>(true)
        );
    }

    @Test
    void shouldNotDeleteUnreferencedUpload() {
        final Digest orphan = this.blob("my-alpine", "uploaded-not-pushed");
        MatcherAssert.assertThat(
            "A blob no manifest references cannot be attributed to the image",
            this.delete(String.format("/v2/my-alpine/blobs/%s", orphan.string())),
            new IsErrorsResponse(RsStatus.NOT_FOUND, "BLOB_UNKNOWN")
        );
        MatcherAssert.assertThat(
            "Unreferenced blob survives",
            this.exists(orphan),
            new IsEqual<>(true)
        );
    }

    @Test
    void shouldKeepLayerReferencedByAnotherImagesUntaggedReferrer() {
        final Digest shared = this.blob("team/a", "signature-payload");
        this.image("team/a", "1", shared);
        final Digest subject = this.image("team/b", "1");
        this.referrer("team/b", "sig", subject, shared);
        // The tag delete leaves the referrer pullable by digest (and listed
        // by the referrers API), so it still references the layer.
        this.docker.repo("team/b").manifests()
            .delete(ManifestReference.fromTag("sig")).join();
        MatcherAssert.assertThat(
            "A layer an OCI 1.1 referrer of another image uses is shared",
            this.delete(String.format("/v2/team/a/blobs/%s", shared.string())),
            new IsErrorsResponse(RsStatus.CONFLICT, "DENIED")
        );
        MatcherAssert.assertThat(
            "The referrer's layer survives",
            this.exists(shared),
            new IsEqual<>(true)
        );
    }

    @Test
    void shouldDeleteLayerOnceOtherImageDropsIt() {
        final Digest shared = this.blob("team/a", "formerly-shared-layer");
        this.image("team/a", "1", shared);
        final Digest other = this.image("team/b", "1", shared);
        this.docker.repo("team/b").manifests()
            .delete(ManifestReference.from(other)).join();
        ResponseAssert.check(
            this.delete(String.format("/v2/team/a/blobs/%s", shared.string())),
            RsStatus.ACCEPTED
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

    /**
     * Uploads a blob through the given image.
     *
     * @param name Image name.
     * @param data Blob content.
     * @return Blob digest.
     */
    private Digest blob(final String name, final String data) {
        return this.docker.repo(name).layers()
            .put(new TrustedBlobSource(data.getBytes(StandardCharsets.UTF_8)))
            .toCompletableFuture().join();
    }

    /**
     * Pushes an OCI manifest (own config + the given layers) to an image.
     *
     * @param name Image name.
     * @param tag Tag.
     * @param layers Layer digests.
     * @return Manifest digest.
     */
    private Digest image(final String name, final String tag, final Digest... layers) {
        final Digest config = this.blob(name, String.format("{\"image\":\"%s\"}", name));
        final String body = String.format(
            "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\","
                + "\"digest\":\"%s\",\"size\":1},\"layers\":[%s]}",
            config.string(),
            List.of(layers).stream()
                .map(
                    layer -> String.format(
                        "{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\","
                            + "\"digest\":\"%s\",\"size\":1}",
                        layer.string()
                    )
                )
                .collect(Collectors.joining(","))
        );
        return this.docker.repo(name).manifests()
            .put(
                ManifestReference.fromTag(tag),
                new Content.From(body.getBytes(StandardCharsets.UTF_8))
            ).join().digest();
    }

    /**
     * Pushes an OCI 1.1 referrer (artifact manifest with a {@code subject})
     * whose single layer is {@code layer}.
     *
     * @param name Image name.
     * @param tag Tag.
     * @param subject Subject manifest digest.
     * @param layer Layer digest.
     * @return Referrer manifest digest.
     */
    private Digest referrer(
        final String name, final String tag, final Digest subject, final Digest layer
    ) {
        final Digest config = this.blob(name, String.format("{\"referrer\":\"%s\"}", tag));
        final String body = String.format(
            "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"artifactType\":\"application/vnd.example.sig.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.empty.v1+json\","
                + "\"digest\":\"%s\",\"size\":1},"
                + "\"layers\":[{\"mediaType\":\"application/octet-stream\","
                + "\"digest\":\"%s\",\"size\":1}],"
                + "\"subject\":{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"digest\":\"%s\",\"size\":1}}",
            config.string(), layer.string(), subject.string()
        );
        return this.docker.repo(name).manifests()
            .put(
                ManifestReference.fromTag(tag),
                new Content.From(body.getBytes(StandardCharsets.UTF_8))
            ).join().digest();
    }

    /**
     * Whether a blob is still in the registry-wide store.
     *
     * @param digest Blob digest.
     * @return True if present.
     */
    private boolean exists(final Digest digest) {
        return this.docker.repo("any").layers().get(digest).join().isPresent();
    }

    private Response delete(final String path) {
        return this.slice.response(
            new RequestLine(RqMethod.DELETE, path), Headers.EMPTY, Content.EMPTY
        ).join();
    }
}
