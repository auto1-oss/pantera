/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.http.manifest;

import com.artipie.asto.Content;
import com.artipie.docker.Catalog;
import com.artipie.docker.Digest;
import com.artipie.docker.Docker;
import com.artipie.docker.Layers;
import com.artipie.docker.ManifestReference;
import com.artipie.docker.Manifests;
import com.artipie.docker.Repo;
import com.artipie.docker.asto.Uploads;
import com.artipie.docker.manifest.Manifest;
import com.artipie.docker.misc.Pagination;
import com.artipie.http.Headers;
import com.artipie.http.auth.AuthzSlice;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.scheduling.ArtifactEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests: {@link HeadManifestSlice} must propagate {@code user.name} MDC
 * across the {@code body.asBytesFuture().thenCompose()} async boundary so that
 * {@code CacheManifests.get()} captures the correct owner when creating artifact events.
 *
 * <p>Docker clients send a HEAD request before GET, so {@code HeadManifestSlice}
 * triggers {@code CacheManifests.get()} first. Without this fix the MDC is not set
 * on the async thread and {@code requestOwner} is null, resulting in {@code UNKNOWN}
 * being written to the database.
 *
 * @since 1.20.13
 */
final class HeadManifestSliceMdcTest {

    @Test
    void setsMdcUserNameFromArtipieLoginHeaderBeforeCallingDockerLayer() {
        final AtomicReference<String> capturedMdc = new AtomicReference<>("not-set");
        final byte[] content = (
            "{\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\","
                + "\"schemaVersion\":2}"
        ).getBytes(StandardCharsets.UTF_8);
        final Manifest manifest = new Manifest(new Digest.Sha256(content), content);
        final HeadManifestSlice slice = new HeadManifestSlice(
            new MdcCapturingDocker(capturedMdc, manifest)
        );
        slice.response(
            new RequestLine(RqMethod.HEAD, "/v2/my-image/manifests/latest"),
            new Headers(List.of(new Header(AuthzSlice.LOGIN_HDR, "alice"))),
            Content.EMPTY
        ).join();
        assertEquals(
            "alice",
            capturedMdc.get(),
            "MDC user.name must equal the artipie_login header value on the docker-layer thread"
        );
    }

    @Test
    void setsMdcUserNameToUnknownWhenNoLoginHeader() {
        final AtomicReference<String> capturedMdc = new AtomicReference<>("not-set");
        MDC.remove("user.name");
        final byte[] content = (
            "{\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\","
                + "\"schemaVersion\":2}"
        ).getBytes(StandardCharsets.UTF_8);
        final Manifest manifest = new Manifest(new Digest.Sha256(content), content);
        final HeadManifestSlice slice = new HeadManifestSlice(
            new MdcCapturingDocker(capturedMdc, manifest)
        );
        slice.response(
            new RequestLine(RqMethod.HEAD, "/v2/my-image/manifests/latest"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(
            ArtifactEvent.DEF_OWNER,
            capturedMdc.get(),
            "MDC user.name must be UNKNOWN when no artipie_login header is present"
        );
    }

    /**
     * Docker stub that records the MDC {@code user.name} value at the moment
     * {@code manifests().get()} is called (i.e., inside the thenCompose callback).
     */
    private static final class MdcCapturingDocker implements Docker {

        private final AtomicReference<String> capturedMdc;

        private final Manifest manifest;

        MdcCapturingDocker(final AtomicReference<String> capturedMdc,
                           final Manifest manifest) {
            this.capturedMdc = capturedMdc;
            this.manifest = manifest;
        }

        @Override
        public String registryName() {
            return "test";
        }

        @Override
        public Repo repo(final String name) {
            final Manifest m = this.manifest;
            final AtomicReference<String> ref = this.capturedMdc;
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
                            final ManifestReference mref, final Content content
                        ) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Optional<Manifest>> get(
                            final ManifestReference mref
                        ) {
                            ref.set(MDC.get("user.name"));
                            return CompletableFuture.completedFuture(Optional.of(m));
                        }

                        @Override
                        public CompletableFuture<com.artipie.docker.Tags> tags(
                            final Pagination pagination
                        ) {
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
