/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http.blobs;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class HeadBlobsSliceTest {

    @Test
    void setsContentLengthFromLayerSize() {
        final Digest digest = new Digest.Sha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        final HeadBlobsSlice slice = new HeadBlobsSlice(new TestDocker(digest, 1024L));
        final Response response = slice.response(
            new RequestLine(
                "HEAD",
                "/v2/test/blobs/" + digest.string(),
                "HTTP/1.1"
            ),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            new ContentLength(response.headers()).longValue(),
            Matchers.equalTo(1024L)
        );
        MatcherAssert.assertThat(
            response.headers().find("Docker-Content-Digest")
                .stream().map(Header::getValue).findFirst().orElse(null),
            Matchers.equalTo(digest.string())
        );
    }

    private static final class TestDocker implements Docker {

        private final Digest digest;

        private final long size;

        private TestDocker(final Digest digest, final long size) {
            this.digest = digest;
            this.size = size;
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
                    return new Layers() {
                        @Override
                        public CompletableFuture<Digest> put(com.auto1.pantera.docker.asto.BlobSource source) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Void> mount(Blob blob) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Optional<Blob>> get(Digest digestRequest) {
                            return CompletableFuture.completedFuture(
                                Optional.of(new TestBlob(digest, size))
                            );
                        }
                    };
                }

                @Override
                public Manifests manifests() {
                    return new Manifests() {
                        @Override
                        public CompletableFuture<Manifest> put(ManifestReference ref, Content content) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Optional<com.auto1.pantera.docker.manifest.Manifest>> get(ManifestReference ref) {
                            return CompletableFuture.completedFuture(Optional.empty());
                        }

                        @Override
                        public CompletableFuture<Tags> tags(Pagination pagination) {
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

    private static final class TestBlob implements Blob {

        private final Digest digest;

        private final long size;

        private TestBlob(final Digest digest, final long size) {
            this.digest = digest;
            this.size = size;
        }

        @Override
        public Digest digest() {
            return this.digest;
        }

        @Override
        public CompletableFuture<Long> size() {
            return CompletableFuture.completedFuture(this.size);
        }

        @Override
        public CompletableFuture<Content> content() {
            return CompletableFuture.completedFuture(
                new Content.From("test".getBytes(StandardCharsets.UTF_8))
            );
        }
    }
}
