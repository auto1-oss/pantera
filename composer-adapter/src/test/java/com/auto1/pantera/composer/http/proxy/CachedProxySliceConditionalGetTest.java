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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromRemoteCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests conditional {@code If-Modified-Since} / 304 revalidation
 * (WS4-composer.7): a {@code 304} skips the merge/rewrite/save cycle and
 * leaves the cached bytes untouched.
 */
final class CachedProxySliceConditionalGetTest {

    private static final String LAST_MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT";

    private static final String PACKAGE_PATH = "/p2/vendor/package.json";

    private static final String PACKAGE_NAME = "vendor/package";

    /**
     * WS6.2 — served-side half of the conditional-request contract: a
     * client that already holds the exact cached representation (its
     * {@code If-Modified-Since} matches the {@code Last-Modified} captured
     * on the original fetch) gets a bodiless {@code 304} straight off the
     * warm cache — no upstream call at all.
     *
     * <p>Uses a real {@link FromRemoteCache} (not {@link Cache#NOP}): only
     * a real cache actually persists the merged/rewritten packument under
     * the plain {@code name} storage key that {@code checkCacheFirst}
     * probes on a subsequent request — with {@code Cache.NOP} every
     * request unconditionally re-enters {@code fetchThroughCache}, which
     * would defeat the point of this test (proving a warm-cache hit takes
     * no second upstream call).
     */
    @Test
    @Timeout(10)
    void clientConditionalGetOnWarmCacheReturns304WithoutUpstreamCall() throws Exception {
        final Storage storage = new InMemoryStorage();
        final ConditionalUpstream upstream = new ConditionalUpstream();
        final CachedProxySlice slice = new CachedProxySlice(
            upstream, new AstoRepository(storage), new FromRemoteCache(storage),
            Optional.empty(), "composer-proxy-test", "http://localhost:8080"
        );
        // Warm the cache — one unconditional upstream fetch captures
        // Last-Modified into lastModifiedStore and (via FromRemoteCache's
        // background tee) persists the merged packument under the plain
        // "vendor/package" storage key.
        final Response first = slice.response(
            new RequestLine(RqMethod.GET, PACKAGE_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, first.status(), "initial fetch succeeds");
        Assertions.assertEquals(1, upstream.calls(), "one upstream call to warm the cache");
        // FromRemoteCache's storage save is a fire-and-forget background
        // tee — wait for it to land before probing for a warm-cache hit.
        awaitPersisted(storage, new Key.From(PACKAGE_NAME));

        // A client conditional GET carrying the exact captured Last-Modified
        // must be served straight from the warm cache as a 304 — no
        // additional upstream call.
        final Response conditional = slice.response(
            new RequestLine(RqMethod.GET, PACKAGE_PATH),
            Headers.from("If-Modified-Since", LAST_MODIFIED),
            Content.EMPTY
        ).join();
        Assertions.assertEquals(
            RsStatus.NOT_MODIFIED, conditional.status(),
            "client If-Modified-Since matching the cached Last-Modified yields 304"
        );
        Assertions.assertEquals(
            1, upstream.calls(),
            "served from the warm cache — no upstream call for the client's conditional GET"
        );
        Assertions.assertEquals(
            0, conditional.body().asBytesFuture().join().length,
            "304 response body is empty"
        );
        final List<Header> lastModifiedHeaders = conditional.headers().find("Last-Modified");
        Assertions.assertEquals(
            1, lastModifiedHeaders.size(), "304 response carries a Last-Modified header"
        );
        Assertions.assertEquals(
            LAST_MODIFIED, lastModifiedHeaders.getFirst().getValue(),
            "304 Last-Modified echoes the cached value"
        );
    }

    /**
     * A client whose {@code If-Modified-Since} is older than the cached
     * {@code Last-Modified} (i.e. the client's copy really is stale) must
     * get the full {@code 200} body, not a 304.
     */
    @Test
    @Timeout(10)
    void clientConditionalGetWithOlderDateGetsFullBody() throws Exception {
        final Storage storage = new InMemoryStorage();
        final ConditionalUpstream upstream = new ConditionalUpstream();
        final CachedProxySlice slice = new CachedProxySlice(
            upstream, new AstoRepository(storage), new FromRemoteCache(storage),
            Optional.empty(), "composer-proxy-test", "http://localhost:8080"
        );
        slice.response(
            new RequestLine(RqMethod.GET, PACKAGE_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        awaitPersisted(storage, new Key.From(PACKAGE_NAME));

        final Response conditional = slice.response(
            new RequestLine(RqMethod.GET, PACKAGE_PATH),
            Headers.from("If-Modified-Since", "Wed, 21 Oct 2010 07:28:00 GMT"),
            Content.EMPTY
        ).join();
        Assertions.assertEquals(
            RsStatus.OK, conditional.status(),
            "an older client If-Modified-Since does not suppress the body"
        );
        Assertions.assertTrue(
            conditional.body().asBytesFuture().join().length > 0,
            "full body is served when the client's copy predates the cached Last-Modified"
        );
    }

    /**
     * Poll until {@code key} is durably present in {@code storage}.
     * {@link FromRemoteCache} tees bytes to the caller while saving a copy
     * in the background, so the write is not guaranteed durable the
     * instant the caller's future completes — poll for the eventual state
     * rather than asserting instantly, per the project's testing doctrine.
     */
    private static void awaitPersisted(final Storage storage, final Key key) throws Exception {
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!storage.exists(key).get(1, TimeUnit.SECONDS)) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Cache entry for " + key.string() + " never persisted");
            }
            Thread.sleep(2);
        }
    }

    @Test
    void notModifiedSkipsMergeAndLeavesCacheUnchanged() {
        final Storage storage = new InMemoryStorage();
        final ConditionalUpstream upstream = new ConditionalUpstream();
        final CachedProxySlice slice = new CachedProxySlice(
            upstream, new AstoRepository(storage), Cache.NOP,
            Optional.empty(), "composer-proxy-test", "http://localhost:8080"
        );

        // Initial fetch — cache miss, populates storage AND the
        // Last-Modified store consulted by revalidateOrRefresh.
        final Response first = slice.response(
            new RequestLine(RqMethod.GET, PACKAGE_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, first.status(), "initial fetch succeeds");
        Assertions.assertEquals(1, upstream.calls(), "exactly one upstream call so far");
        final byte[] cachedBefore = storage.value(new Key.From(PACKAGE_NAME + ".json")).join()
            .asBytesFuture().join();

        // Conditional revalidation — upstream honours If-Modified-Since
        // with a 304; the merge/rewrite/save pipeline must not run again.
        final Response revalidated = slice.revalidateOrRefresh(
            new RequestLine(RqMethod.GET, PACKAGE_PATH), PACKAGE_NAME
        ).join();

        Assertions.assertEquals(RsStatus.OK, revalidated.status(), "304 surfaces as a served 200 to the caller");
        Assertions.assertEquals(2, upstream.calls(), "one additional (conditional) upstream call");
        Assertions.assertEquals(
            Optional.of(LAST_MODIFIED), upstream.lastConditionalHeader(),
            "the conditional request carried the previously-captured Last-Modified value"
        );
        Assertions.assertEquals(
            0, upstream.lastResponseBodyBytes(),
            "the 304 response carried zero body bytes — no metadata re-transfer"
        );
        final byte[] cachedAfter = storage.value(new Key.From(PACKAGE_NAME + ".json")).join()
            .asBytesFuture().join();
        Assertions.assertArrayEquals(
            cachedBefore, cachedAfter, "cached bytes are unchanged by a 304 revalidation"
        );
    }

    /**
     * Serves a mergeable packument with a fixed {@code Last-Modified} on
     * the first (unconditional) call, then a bodiless {@code 304} whenever
     * the caller's {@code If-Modified-Since} matches that value.
     */
    private static final class ConditionalUpstream implements Slice {

        private static final byte[] BODY = (
            "{\"packages\":{\"vendor/package\":{\"1.0.0\":"
                + "{\"name\":\"vendor/package\",\"version\":\"1.0.0\"}}}}"
        ).getBytes(StandardCharsets.UTF_8);

        private final AtomicInteger calls = new AtomicInteger();
        private final List<Optional<String>> conditionalHeadersSeen = new ArrayList<>();
        private int lastBodyBytes;

        int calls() {
            return this.calls.get();
        }

        Optional<String> lastConditionalHeader() {
            return this.conditionalHeadersSeen.get(this.conditionalHeadersSeen.size() - 1);
        }

        int lastResponseBodyBytes() {
            return this.lastBodyBytes;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.calls.incrementAndGet();
            final List<Header> ims = headers.find("If-Modified-Since");
            final Optional<String> seen = ims.isEmpty()
                ? Optional.empty() : Optional.of(ims.getFirst().getValue());
            this.conditionalHeadersSeen.add(seen);
            if (seen.filter(LAST_MODIFIED::equals).isPresent()) {
                this.lastBodyBytes = 0;
                return CompletableFuture.completedFuture(
                    new Response(RsStatus.NOT_MODIFIED, Headers.EMPTY, Content.EMPTY)
                );
            }
            this.lastBodyBytes = BODY.length;
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Last-Modified", LAST_MODIFIED)
                    .body(BODY)
                    .build()
            );
        }
    }
}
