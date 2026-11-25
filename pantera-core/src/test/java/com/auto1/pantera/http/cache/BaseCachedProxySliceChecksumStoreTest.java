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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A checksum file of a cached artifact that is missing from the storage is
 * fetched from the upstream once and stored.
 *
 * @since 2.2.14
 */
final class BaseCachedProxySliceChecksumStoreTest {

    /**
     * Path of the artifact.
     */
    private static final String JAR = "/org/acme/app/1.0/app-1.0.jar";

    /**
     * Path of its checksum file.
     */
    private static final String SHA1 = BaseCachedProxySliceChecksumStoreTest.JAR + ".sha1";

    /**
     * Checksum the upstream answers.
     */
    private static final String SUM = "da39a3ee5e6b4b0d3255bfef95601890afd80709";

    @Test
    void checksumOfACachedArtifactIsFetchedOnceThenServedWithoutTheUpstream() {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("org/acme/app/1.0/app-1.0.jar"),
            new Content.From("jar".getBytes(StandardCharsets.UTF_8))
        ).join();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger status = new AtomicInteger(RsStatus.OK.code());
        final Slice slice = new Checksums(
            BaseCachedProxySliceChecksumStoreTest.upstream(calls, status), storage
        );
        final Response first = BaseCachedProxySliceChecksumStoreTest.get(slice);
        MatcherAssert.assertThat(
            "the first request is answered with the upstream's checksum",
            new String(first.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>(BaseCachedProxySliceChecksumStoreTest.SUM)
        );
        // The upstream goes down.
        status.set(RsStatus.SERVICE_UNAVAILABLE.code());
        final Response second = BaseCachedProxySliceChecksumStoreTest.get(slice);
        MatcherAssert.assertThat(
            "the second request is served although the upstream is down",
            second.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "with the same checksum",
            new String(second.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>(BaseCachedProxySliceChecksumStoreTest.SUM)
        );
        MatcherAssert.assertThat(
            "and the upstream was asked once",
            calls.get(), new IsEqual<>(1)
        );
    }

    @Test
    void checksumIsStoredAlsoWhenItsArtifactIsNotCached() {
        final Storage storage = new InMemoryStorage();
        final AtomicInteger calls = new AtomicInteger();
        final Slice slice = new Checksums(
            BaseCachedProxySliceChecksumStoreTest.upstream(
                calls, new AtomicInteger(RsStatus.OK.code())
            ),
            storage
        );
        final Response first = BaseCachedProxySliceChecksumStoreTest.get(slice);
        first.body().asBytes();
        BaseCachedProxySliceChecksumStoreTest.get(slice).body().asBytes();
        MatcherAssert.assertThat(
            "the checksum is served",
            first.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "and stored, though the artifact was never fetched",
            storage.exists(new Key.From("org/acme/app/1.0/app-1.0.jar.sha1")).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "so the second request did not ask the upstream",
            calls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "and only the checksum file was written",
            storage.list(Key.ROOT).join().size(), new IsEqual<>(1)
        );
    }

    @Test
    void failedChecksumFetchStoresNothing() {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("org/acme/app/1.0/app-1.0.jar"),
            new Content.From("jar".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Response resp = BaseCachedProxySliceChecksumStoreTest.get(
            new Checksums(
                BaseCachedProxySliceChecksumStoreTest.upstream(
                    new AtomicInteger(), new AtomicInteger(RsStatus.NOT_FOUND.code())
                ),
                storage
            )
        );
        MatcherAssert.assertThat(
            "the upstream's answer is relayed",
            resp.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "and nothing is stored",
            storage.exists(new Key.From("org/acme/app/1.0/app-1.0.jar.sha1")).join(),
            new IsEqual<>(false)
        );
    }

    private static Response get(final Slice slice) {
        return slice.response(
            new RequestLine(RqMethod.GET, BaseCachedProxySliceChecksumStoreTest.SHA1),
            Headers.EMPTY, Content.EMPTY
        ).join();
    }

    /**
     * Upstream that counts its calls and answers the checksum with the
     * given status.
     */
    private static Slice upstream(final AtomicInteger calls, final AtomicInteger status) {
        return (line, headers, body) -> {
            calls.incrementAndGet();
            final RsStatus code = RsStatus.byCode(status.get());
            final CompletableFuture<Response> res;
            if (code == RsStatus.OK) {
                res = CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body(
                        BaseCachedProxySliceChecksumStoreTest.SUM.getBytes(StandardCharsets.UTF_8)
                    ).build()
                );
            } else {
                res = CompletableFuture.completedFuture(ResponseBuilder.from(code).build());
            }
            return res;
        };
    }

    /**
     * Proxy slice that knows {@code .sha1} files as checksum files.
     */
    private static final class Checksums extends BaseCachedProxySlice {

        Checksums(final Slice upstream, final Storage storage) {
            super(
                upstream, new FromStorageCache(storage), "test-repo", "test",
                "http://upstream", Optional.of(storage), Optional.empty(),
                ProxyCacheConfig.defaults()
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }

        @Override
        protected boolean isChecksumSidecar(final String path) {
            return path.endsWith(".sha1");
        }
    }
}
