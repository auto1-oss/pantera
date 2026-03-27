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
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.docker.proxy.ProxyDocker;
import com.auto1.pantera.docker.http.TrimmedDocker;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;


final class HeadManifestSliceTest {

    @Test
    void returnsContentLengthFromManifest() {
        final byte[] content = "{\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\",\"schemaVersion\":2}"
            .getBytes(StandardCharsets.UTF_8);
        final Manifest manifest = new Manifest(new Digest.Sha256(content), content);
        final HeadManifestSlice slice = new HeadManifestSlice(new FakeDocker(manifest));
        final Response response = slice.response(
            new RequestLine("HEAD", "/v2/test/manifests/latest", "HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            new ContentLength(response.headers()).longValue(),
            Matchers.equalTo((long) content.length)
        );
        MatcherAssert.assertThat(
            response.headers().find("Docker-Content-Digest")
                .stream().map(Header::getValue).findFirst().orElse(null),
            Matchers.equalTo(manifest.digest().string())
        );
    }

    @Test
    void proxyManifestListKeepsContentLength() {
        final byte[] content = (
            "{\"schemaVersion\":2,\"mediaType\":"
                + "\"application/vnd.docker.distribution.manifest.list.v2+json\"," 
                + "\"manifests\":[]}"
        ).getBytes(StandardCharsets.UTF_8);
        final Digest digest = new Digest.Sha256(content);
        final AtomicReference<RequestLine> captured = new AtomicReference<>();
        final ProxyDocker docker = new ProxyDocker(
            "local",
            (line, headers, body) -> {
                captured.set(line);
                if (line.method() != RqMethod.GET
                    || !line.uri().getPath().equals(
                        String.format("/v2/library/test/manifests/%s", digest.string())
                    )
                ) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.notFound().build()
                    );
                }
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header(ContentType.json())
                        .header(new DigestHeader(digest))
                        .header(new ContentLength(content.length))
                        .body(new Content.From(content))
                        .build()
                );
            },
            URI.create("https://registry-1.docker.io")
        );
        final HeadManifestSlice slice = new HeadManifestSlice(docker);
        final Response response = slice.response(
            new RequestLine("HEAD", "/v2/test/manifests/" + digest.string(), "HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Proxy should request manifest from normalized path",
            captured.get().uri().getPath(),
            Matchers.equalTo(String.format("/v2/library/test/manifests/%s", digest.string()))
        );
        MatcherAssert.assertThat(
            new ContentLength(response.headers()).longValue(),
            Matchers.equalTo((long) content.length)
        );
    }


    private static final class FakeDocker implements Docker {

        private final Manifest manifest;

        private FakeDocker(final Manifest manifest) {
            this.manifest = manifest;
        }

        @Override
        public String registryName() {
            return "test";
        }

        @Override
        public Repo repo(String name) {
            return new Repo() {
                @Override
                public Layers layers() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Manifests manifests() {
                    return new Manifests() {
                        @Override
                        public CompletableFuture<Manifest> put(ManifestReference ref, Content content) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Optional<Manifest>> get(ManifestReference ref) {
                            return CompletableFuture.completedFuture(Optional.of(manifest));
                        }

                        @Override
                        public CompletableFuture<com.auto1.pantera.docker.Tags> tags(Pagination pagination) {
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
        public CompletableFuture<Catalog> catalog(Pagination pagination) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
