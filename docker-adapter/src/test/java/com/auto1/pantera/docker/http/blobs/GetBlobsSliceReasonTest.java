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
package com.auto1.pantera.docker.http.blobs;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.BlobSource;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.ReasonSlice;
import java.io.EOFException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A blob that could not be fetched is answered 404, like a blob that does
 * not exist. The reason on the answer says which: on 2026-11-17 a registry
 * closed a connection under a layer download, and the 404 that followed
 * read as "the layer is not there".
 *
 * @since 2.2.12
 */
final class GetBlobsSliceReasonTest {

    /**
     * Blob digest.
     */
    private static final String DIGEST =
        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    @Timeout(20)
    void aBlobThatCouldNotBeFetchedSaysHowTheFetchFailed() throws Exception {
        final Response answer = new ReasonSlice(
            new GetBlobsSlice(new FailingDocker(new EOFException("early EOF")))
        ).response(
            new RequestLine("GET", "/v2/test/blobs/" + GetBlobsSliceReasonTest.DIGEST, "HTTP/1.1"),
            Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the client still gets 404",
            answer.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the record says the upstream closed the connection",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("connection_closed"))
        );
    }

    /**
     * Registry whose layers cannot be read.
     */
    private static final class FailingDocker implements Docker {

        /**
         * Failure of every layer read.
         */
        private final Throwable failure;

        /**
         * Ctor.
         * @param failure Failure of every layer read
         */
        FailingDocker(final Throwable failure) {
            this.failure = failure;
        }

        @Override
        public String registryName() {
            return "test";
        }

        @Override
        public Repo repo(final String name) {
            final Throwable cause = this.failure;
            return new Repo() {
                @Override
                public Layers layers() {
                    return new Layers() {
                        @Override
                        public CompletableFuture<Digest> put(final BlobSource source) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Void> mount(final Blob blob) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Optional<Blob>> get(final Digest digest) {
                            return CompletableFuture.failedFuture(cause);
                        }

                        @Override
                        public CompletableFuture<Void> delete(final Digest digest) {
                            throw new UnsupportedOperationException();
                        }
                    };
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
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
