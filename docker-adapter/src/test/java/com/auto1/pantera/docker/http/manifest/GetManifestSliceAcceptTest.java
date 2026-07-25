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
package com.auto1.pantera.docker.http.manifest;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * WS4-docker.7: {@link GetManifestSlice} must honor the client's {@code
 * Accept} header -- serving the stored manifest only when its media type
 * is one the client declared acceptable, and answering {@code 406 Not
 * Acceptable} otherwise. An absent {@code Accept} header, or the universal
 * wildcard media-range, must preserve pre-negotiation behaviour (always
 * serve).
 */
final class GetManifestSliceAcceptTest {

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    private static final String DOCKER_MANIFEST_V2 =
        "application/vnd.docker.distribution.manifest.v2+json";

    @Test
    void servesManifestWhenAcceptListsItsStoredMediaType() {
        final Response response = respond(
            OCI_MANIFEST,
            Headers.from(new Header("Accept", DOCKER_MANIFEST_V2 + "," + OCI_MANIFEST))
        );
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat(
            ContentType.single(response.headers()).getValue(),
            new IsEqual<>(OCI_MANIFEST)
        );
    }

    @Test
    void rejectsWithNotAcceptableWhenAcceptExcludesStoredMediaType() {
        final Response response = respond(
            OCI_MANIFEST,
            Headers.from(new Header("Accept", DOCKER_MANIFEST_V2))
        );
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.NOT_ACCEPTABLE));
        final String body = response.body().asStringFuture().join();
        MatcherAssert.assertThat(body.contains("MANIFEST_UNACCEPTABLE"), new IsEqual<>(true));
    }

    @Test
    void servesManifestWhenAcceptHeaderIsAbsent() {
        final Response response = respond(OCI_MANIFEST, Headers.EMPTY);
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.OK));
    }

    @Test
    void servesManifestWhenAcceptIsUniversalWildcard() {
        final Response response = respond(
            OCI_MANIFEST, Headers.from(new Header("Accept", "*/*"))
        );
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.OK));
    }

    /**
     * Drives {@link GetManifestSlice} for a manifest stored under {@code
     * storedMediaType}, with the given inbound {@code Accept} headers.
     *
     * @param storedMediaType Media type embedded in the stubbed manifest.
     * @param headers Inbound request headers (carries {@code Accept}, if any).
     * @return The slice's response.
     */
    private static Response respond(final String storedMediaType, final Headers headers) {
        final byte[] content = String.format(
            "{\"mediaType\":\"%s\",\"schemaVersion\":2,\"config\":{\"digest\":\"sha256:cfg\"},"
                + "\"layers\":[]}",
            storedMediaType
        ).getBytes(StandardCharsets.UTF_8);
        final Manifest manifest = new Manifest(new Digest.Sha256(content), content);
        final GetManifestSlice slice = new GetManifestSlice(new FakeDocker(manifest));
        return slice.response(
            new RequestLine(RqMethod.GET, "/v2/test/manifests/latest"),
            headers,
            Content.EMPTY
        ).join();
    }

    /**
     * Minimal {@link Docker} stub serving a single fixed {@link Manifest}
     * for any reference, mirroring the fakes already used by {@code
     * HeadManifestSliceTest}/{@code GetManifestSliceMdcTest}.
     */
    private static final class FakeDocker implements Docker {

        private final Manifest manifest;

        FakeDocker(final Manifest manifest) {
            this.manifest = manifest;
        }

        @Override
        public String registryName() {
            return "test";
        }

        @Override
        public Repo repo(final String name) {
            final Manifest found = this.manifest;
            return new Repo() {
                @Override
                public Layers layers() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Manifests manifests() {
                    return new Manifests() {
                        @Override
                        public CompletableFuture<Manifest> put(
                            final ManifestReference ref, final Content body
                        ) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Optional<Manifest>> get(
                            final ManifestReference ref
                        ) {
                            return CompletableFuture.completedFuture(Optional.of(found));
                        }

                        @Override
                        public CompletableFuture<com.auto1.pantera.docker.Tags> tags(
                            final Pagination pagination
                        ) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Void> delete(final ManifestReference ref) {
                            throw new UnsupportedOperationException();
                        }
                    };
                }

                @Override
                public Uploads uploads() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public CompletableFuture<Catalog> catalog(final Pagination pagination) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
