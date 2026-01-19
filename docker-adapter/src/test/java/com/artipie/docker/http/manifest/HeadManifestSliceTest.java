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
import com.artipie.docker.http.DigestHeader;
import com.artipie.docker.manifest.Manifest;
import com.artipie.docker.misc.Pagination;
import com.artipie.docker.proxy.ProxyDocker;
import com.artipie.docker.http.TrimmedDocker;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.headers.ContentLength;
import com.artipie.http.headers.Header;
import com.artipie.http.headers.ContentType;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
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
                        public CompletableFuture<com.artipie.docker.Tags> tags(Pagination pagination) {
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
