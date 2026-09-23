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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.BaseCachedProxySlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Cache hits on {@link FileProxySlice} are served from storage, answer 200 and
 * never reach the upstream.
 *
 * @since 2.2.9
 */
final class FileProxySliceCacheHitTest {

    /**
     * Payload.
     */
    private static final byte[] BODY = "payload".getBytes();

    @Test
    void repeatedGetIsServedFromCacheWithoutUpstreamCall() {
        final Storage storage = new InMemoryStorage();
        final AtomicInteger calls = new AtomicInteger();
        final Slice slice = FileProxySliceCacheHitTest.slice(storage, calls);
        final Response first = FileProxySliceCacheHitTest.get(slice, "/dir/a.bin");
        final byte[] firstBody = first.body().asBytes();
        final Response second = FileProxySliceCacheHitTest.get(slice, "/dir/a.bin");
        MatcherAssert.assertThat(
            "First GET is served from upstream",
            first.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "First GET body is the upstream body",
            firstBody, new IsEqual<>(FileProxySliceCacheHitTest.BODY)
        );
        MatcherAssert.assertThat(
            "Second GET is a cache hit",
            second.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Second GET body is the cached body",
            second.body().asBytes(), new IsEqual<>(FileProxySliceCacheHitTest.BODY)
        );
        MatcherAssert.assertThat(
            "Only the first GET reaches the upstream",
            calls.get(), new IsEqual<>(1)
        );
    }

    @Test
    void cacheHitWithoutCacheFirstStorageAnswersOk() {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("dir", "b.bin"), new Content.From(FileProxySliceCacheHitTest.BODY))
            .join();
        final AtomicInteger calls = new AtomicInteger();
        final Response rsp = FileProxySliceCacheHitTest.get(
            new FileProxySlice(
                (line, headers, body) -> {
                    calls.incrementAndGet();
                    return new CompletableFuture<>();
                },
                new FromStorageCache(storage)
            ),
            "/dir/b.bin"
        );
        MatcherAssert.assertThat(
            "Cache hit through the cache pipeline answers 200",
            rsp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Cache hit body is the stored body",
            rsp.body().asBytes(), new IsEqual<>(FileProxySliceCacheHitTest.BODY)
        );
        MatcherAssert.assertThat(
            "Cache hit makes no upstream call",
            calls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void cacheOnlyProbeMissAnswers404WithoutUpstreamCall() {
        final Storage storage = new InMemoryStorage();
        final AtomicInteger calls = new AtomicInteger();
        final Response rsp = FileProxySliceCacheHitTest.slice(storage, calls).response(
            new RequestLine(RqMethod.GET, "/never/cached.bin"),
            FileProxySliceCacheHitTest.cacheOnlyHeaders(), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Cache-only miss answers 404",
            rsp.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "Cache-only miss never reaches the upstream",
            calls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void cacheOnlyProbeHitServesStoredCopy() {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("warm.bin"), new Content.From(FileProxySliceCacheHitTest.BODY))
            .join();
        final AtomicInteger calls = new AtomicInteger();
        final Response rsp = FileProxySliceCacheHitTest.slice(storage, calls).response(
            new RequestLine(RqMethod.GET, "/warm.bin"),
            FileProxySliceCacheHitTest.cacheOnlyHeaders(), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Cache-only hit answers 200",
            rsp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Cache-only hit serves the stored body",
            rsp.body().asBytes(), new IsEqual<>(FileProxySliceCacheHitTest.BODY)
        );
        MatcherAssert.assertThat(
            "Cache-only hit never reaches the upstream",
            calls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void cacheOnlyMarkerWithoutInternalRoutingIsIgnored() {
        final Storage storage = new InMemoryStorage();
        final AtomicInteger calls = new AtomicInteger();
        final Response rsp = FileProxySliceCacheHitTest.slice(storage, calls).response(
            new RequestLine(RqMethod.GET, "/external.bin"),
            Headers.from(BaseCachedProxySlice.CACHE_ONLY_HEADER, "true"), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "An external client cannot force cache-only mode",
            rsp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "The request without the internal header goes upstream",
            calls.get(), new IsEqual<>(1)
        );
    }

    /**
     * Storage-backed slice over a counting upstream.
     * @param storage Storage
     * @param calls Upstream call counter
     * @return Slice
     */
    private static Slice slice(final Storage storage, final AtomicInteger calls) {
        return new FileProxySlice(
            FileProxySliceCacheHitTest.upstream(calls),
            new FromStorageCache(storage), Optional.empty(), "files",
            "file-proxy", NoopCooldownService.INSTANCE, "http://upstream",
            Optional.of(storage)
        );
    }

    /**
     * Headers of a group resolver cache-only probe.
     * @return Headers
     */
    private static Headers cacheOnlyHeaders() {
        return Headers.from(BaseCachedProxySlice.CACHE_ONLY_HEADER, "true")
            .copy()
            .add(EcsLoggingSlice.INTERNAL_ROUTING_HEADER, "true");
    }

    /**
     * Counting upstream serving {@link #BODY}.
     * @param calls Counter
     * @return Slice
     */
    private static Slice upstream(final AtomicInteger calls) {
        return (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(FileProxySliceCacheHitTest.BODY).build()
            );
        };
    }

    /**
     * Issue a GET.
     * @param slice Slice
     * @param path Path
     * @return Response
     */
    private static Response get(final Slice slice, final String path) {
        return slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).join();
    }
}
