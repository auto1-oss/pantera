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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
