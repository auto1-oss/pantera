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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.http.slice.LastModifiedSlice;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A download is dated with its storage time at no extra storage request.
 *
 * <p>The storage returns the modification time with the bytes
 * ({@link Content#lastModified()}), the serve path passes that content on as
 * the response body, and {@link LastModifiedSlice} takes the date from it.
 * On an object store the alternative, a metadata read per download, is one
 * more {@code HEAD} request for every artifact served.</p>
 *
 * @since 2.2.12
 */
final class DownloadDatedWithoutStorageLookupTest {

    /**
     * Artifact path.
     */
    private static final String PATH = "/org/example/dist/1.0/dist-1.0-bin.tar.gz";

    /**
     * Artifact bytes.
     */
    private static final byte[] DATA = "archive".getBytes(StandardCharsets.UTF_8);

    /**
     * Time the artifact was stored.
     */
    private static final Instant STORED = Instant.parse("2018-06-17T18:35:49Z");

    /**
     * {@link #STORED} as an HTTP date.
     */
    private static final String STORED_DATE = "Sun, 17 Jun 2018 18:35:49 GMT";

    @Test
    void hostedDownloadMakesNoMetadataRequest(@TempDir final Path dir) throws Exception {
        final CountingStorage storage = new CountingStorage(new FileStorage(dir));
        storage.save(
            new KeyFromPath(DownloadDatedWithoutStorageLookupTest.PATH),
            new Content.From(DownloadDatedWithoutStorageLookupTest.DATA)
        ).get(30, TimeUnit.SECONDS);
        DownloadDatedWithoutStorageLookupTest.stamp(dir);
        storage.reset();
        final Response response = DownloadDatedWithoutStorageLookupTest.dated(
            new StorageArtifactSlice(storage), storage
        ).response(
            new RequestLine(RqMethod.GET, DownloadDatedWithoutStorageLookupTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the artifact is served", response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "it is dated with its storage time",
            response.headers().values("Last-Modified"),
            new IsEqual<>(List.of(DownloadDatedWithoutStorageLookupTest.STORED_DATE))
        );
        MatcherAssert.assertThat(
            "the body is the artifact",
            response.body().asBytesFuture().get(30, TimeUnit.SECONDS),
            new IsEqual<>(DownloadDatedWithoutStorageLookupTest.DATA)
        );
        MatcherAssert.assertThat(
            "the date cost no metadata request",
            storage.metadataRequests(), new IsEqual<>(0)
        );
    }

    @Test
    void proxyCacheHitMakesNoMetadataRequest(@TempDir final Path dir) throws Exception {
        final CountingStorage storage = new CountingStorage(new FileStorage(dir));
        final AtomicInteger upstream = new AtomicInteger();
        final Slice proxy = DownloadDatedWithoutStorageLookupTest.dated(
            new CachingProxy(
                (line, headers, body) -> {
                    upstream.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.ok()
                            .body(DownloadDatedWithoutStorageLookupTest.DATA).build()
                    );
                },
                storage
            ),
            storage
        );
        proxy.response(
            new RequestLine(RqMethod.GET, DownloadDatedWithoutStorageLookupTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS).body().asBytesFuture().get(30, TimeUnit.SECONDS);
        DownloadDatedWithoutStorageLookupTest.stamp(dir);
        storage.reset();
        final Response hit = proxy.response(
            new RequestLine(RqMethod.GET, DownloadDatedWithoutStorageLookupTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the cached artifact is served", hit.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "it is dated with its storage time",
            hit.headers().values("Last-Modified"),
            new IsEqual<>(List.of(DownloadDatedWithoutStorageLookupTest.STORED_DATE))
        );
        MatcherAssert.assertThat(
            "the body is the cached artifact",
            hit.body().asBytesFuture().get(30, TimeUnit.SECONDS),
            new IsEqual<>(DownloadDatedWithoutStorageLookupTest.DATA)
        );
        MatcherAssert.assertThat(
            "the date cost no metadata request",
            storage.metadataRequests(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the hit did not contact the upstream",
            upstream.get(), new IsEqual<>(1)
        );
    }

    /**
     * The slice dated the way {@code RepositorySlices} wires it.
     * @param origin Serving slice
     * @param storage Repository storage
     * @return Dated slice
     */
    private static Slice dated(final Slice origin, final Storage storage) {
        return new LastModifiedSlice(
            origin, Optional.of(storage), path -> Optional.of(new KeyFromPath(path))
        );
    }

    /**
     * Set the stored artifact's modification time to {@link #STORED}.
     * @param dir Storage directory
     * @throws Exception On error
     */
    private static void stamp(final Path dir) throws Exception {
        Files.setLastModifiedTime(
            dir.resolve(DownloadDatedWithoutStorageLookupTest.PATH.substring(1)),
            FileTime.from(DownloadDatedWithoutStorageLookupTest.STORED)
        );
    }

    /**
     * Proxy on the standard cache flow.
     */
    private static final class CachingProxy extends BaseCachedProxySlice {

        CachingProxy(final Slice upstream, final Storage storage) {
            super(
                upstream,
                new FromStorageCache(storage),
                "test-repo",
                "test",
                "http://upstream",
                Optional.of(storage),
                Optional.empty(),
                ProxyCacheConfig.defaults()
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }
    }

    /**
     * Storage counting the metadata requests made to it.
     */
    private static final class CountingStorage extends Storage.Wrap {

        /**
         * Metadata requests.
         */
        private final AtomicInteger metas;

        CountingStorage(final Storage origin) {
            super(origin);
            this.metas = new AtomicInteger();
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            this.metas.incrementAndGet();
            return super.metadata(key);
        }

        void reset() {
            this.metas.set(0);
        }

        int metadataRequests() {
            return this.metas.get();
        }
    }
}
