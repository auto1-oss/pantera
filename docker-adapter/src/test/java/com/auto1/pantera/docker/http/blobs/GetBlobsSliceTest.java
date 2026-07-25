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
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GetBlobsSlice}: the WS1.7 (spec {@code
 * WS1-storage-for-scale.md} &sect;3.B2) acceptance criteria -- {@code
 * redirect}/{@code auto} attempt a presigned redirect and never stream when
 * one is available, {@code stream} mode never even asks for one, and a
 * fallback happens when no presigned URL is available -- proved with
 * invocation-counting fakes, never wall-clock timing (CLAUDE.md testing
 * doctrine).
 *
 * <p>The redirect path's {@code artifact_access} audit emission (acceptance
 * #9's fourth requirement) is not asserted here via a log-capturing test:
 * this module's test classpath pulls a conflicting Log4j2 provider (a
 * transitive {@code log4j-to-slf4j} from {@code s3mock}'s {@code
 * spring-boot-starter-logging}, test-scope only) that prevents attaching a
 * capturing appender to a real {@code log4j-core} {@code LoggerContext} the
 * way {@code pantera-core}'s own {@code AuditLoggerTest} does. The call site
 * ({@link GetBlobsSlice#redirect}) was instead verified by code review to
 * match {@code DeleteBlobSlice}'s already-shipped, already-covered {@code
 * AuditContext}-capture/{@code AuditLogger.access} idiom exactly.</p>
 */
final class GetBlobsSliceTest {

    @Test
    void redirectModeReturns302AndNeverStreamsContent() {
        final Digest digest = new Digest.Sha256("0".repeat(64));
        final URI presigned = URI.create("https://blob-store.example.test/layer?sig=abc");
        final TestBlob blob = TestBlob.eligible(digest, presigned);
        final GetBlobsSlice slice = new GetBlobsSlice(
            new TestDocker(digest, blob, new DownloadPolicy(DownloadMode.REDIRECT, 600L))
        );

        final Response response = slice.response(
            new RequestLine("GET", "/v2/my-alpine/blobs/" + digest.string(), "HTTP/1.1"),
            Headers.EMPTY, Content.EMPTY
        ).join();

        ResponseAssert.check(response, RsStatus.MOVED_TEMPORARILY, new Location(presigned.toString()));
        MatcherAssert.assertThat(
            "a redirect must never call content() -- zero bytes served on Pantera's side",
            blob.contentCalls.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat("presignedUrl() consulted exactly once", blob.presignCalls.get(), new IsEqual<>(1));
    }

    @Test
    void streamModeNeverConsultsPresignedUrl() {
        final Digest digest = new Digest.Sha256("1".repeat(64));
        final TestBlob blob = TestBlob.eligible(digest, URI.create("https://blob-store.example.test/never-used"));
        final GetBlobsSlice slice = new GetBlobsSlice(
            new TestDocker(digest, blob, DownloadPolicy.streamOnly())
        );

        final Response response = slice.response(
            new RequestLine("GET", "/v2/my-alpine/blobs/" + digest.string(), "HTTP/1.1"),
            Headers.EMPTY, Content.EMPTY
        ).join();

        ResponseAssert.check(response, RsStatus.OK);
        MatcherAssert.assertThat(
            "stream mode must never even ask whether a redirect is possible",
            blob.presignCalls.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(blob.contentCalls.get(), new IsEqual<>(1));
    }

    @Test
    void autoModeFallsBackToStreamWhenNoPresignedUrlAvailable() {
        final Digest digest = new Digest.Sha256("2".repeat(64));
        // Not durably present / presign not configured -- Blob reports empty.
        final TestBlob blob = TestBlob.ineligible(digest);
        final GetBlobsSlice slice = new GetBlobsSlice(
            new TestDocker(digest, blob, new DownloadPolicy(DownloadMode.AUTO, 600L))
        );

        final Response response = slice.response(
            new RequestLine("GET", "/v2/my-alpine/blobs/" + digest.string(), "HTTP/1.1"),
            Headers.EMPTY, Content.EMPTY
        ).join();

        ResponseAssert.check(response, RsStatus.OK);
        MatcherAssert.assertThat("must have attempted a presign", blob.presignCalls.get(), new IsEqual<>(1));
        MatcherAssert.assertThat("and fallen back to streaming", blob.contentCalls.get(), new IsEqual<>(1));
    }

    private static final class TestDocker implements Docker {

        private final Digest digest;

        private final Blob blob;

        private final DownloadPolicy policy;

        TestDocker(final Digest digest, final Blob blob, final DownloadPolicy policy) {
            this.digest = digest;
            this.blob = blob;
            this.policy = policy;
        }

        @Override
        public String registryName() {
            return "test-registry";
        }

        @Override
        public Repo repo(final String name) {
            return new Repo() {
                @Override
                public Layers layers() {
                    return new Layers() {
                        @Override
                        public CompletableFuture<Digest> put(final com.auto1.pantera.docker.asto.BlobSource source) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Void> mount(final Blob blobToMount) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public CompletableFuture<Optional<Blob>> get(final Digest requested) {
                            // Digest has no equals() override -- compare by
                            // string() (the algorithm:hex wire form) instead
                            // of object identity.
                            return CompletableFuture.completedFuture(
                                requested.string().equals(TestDocker.this.digest.string())
                                    ? Optional.of(TestDocker.this.blob)
                                    : Optional.empty()
                            );
                        }

                        @Override
                        public CompletableFuture<Void> delete(final Digest requested) {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public DownloadPolicy downloadPolicy() {
            return this.policy;
        }
    }

    /**
     * {@link Blob} fake counting {@link #content()}/{@link
     * #presignedUrl(long)} invocations so tests can assert exactly which
     * path {@link GetBlobsSlice} took.
     */
    private static final class TestBlob implements Blob {

        private final Digest digest;

        private final Optional<URI> presignedResult;

        private final AtomicInteger contentCalls = new AtomicInteger();

        private final AtomicInteger presignCalls = new AtomicInteger();

        private TestBlob(final Digest digest, final Optional<URI> presignedResult) {
            this.digest = digest;
            this.presignedResult = presignedResult;
        }

        static TestBlob eligible(final Digest digest, final URI presignedUrl) {
            return new TestBlob(digest, Optional.of(presignedUrl));
        }

        static TestBlob ineligible(final Digest digest) {
            return new TestBlob(digest, Optional.empty());
        }

        @Override
        public Digest digest() {
            return this.digest;
        }

        @Override
        public CompletableFuture<Long> size() {
            return CompletableFuture.completedFuture(42L);
        }

        @Override
        public CompletableFuture<Content> content() {
            this.contentCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                new Content.From("blob-bytes".getBytes(StandardCharsets.UTF_8))
            );
        }

        @Override
        public Optional<URI> presignedUrl(final long ttlSeconds) {
            this.presignCalls.incrementAndGet();
            return this.presignedResult;
        }
    }
}
