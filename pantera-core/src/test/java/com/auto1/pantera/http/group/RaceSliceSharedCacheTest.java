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
package com.auto1.pantera.http.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@link RaceSlice} over targets that share one cache: a cached resource is
 * served by the first target alone; everything else races as before.
 *
 * @since 2.2.11
 */
final class RaceSliceSharedCacheTest {

    private static final RequestLine LINE =
        new RequestLine(RqMethod.GET, "/com/example/lib/1.0/lib-1.0.jar");

    @Test
    void cacheHitAsksOnlyTheFirstTarget() {
        final Counting first = new Counting(RsStatus.OK);
        final Counting second = new Counting(RsStatus.OK);
        final Response res = new RaceSlice(
            List.of(first, second), line -> CompletableFuture.completedFuture(true)
        ).response(LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat("hit is served", res.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat("first target serves the hit", first.calls.get(), new IsEqual<>(1));
        MatcherAssert.assertThat("second target is not asked", second.calls.get(), new IsEqual<>(0));
    }

    @Test
    void cacheMissRacesEveryTarget() {
        final Counting first = new Counting(RsStatus.NOT_FOUND);
        final Counting second = new Counting(RsStatus.OK);
        final Response res = new RaceSlice(
            List.of(first, second), line -> CompletableFuture.completedFuture(false)
        ).response(LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat("second target's 200 wins", res.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat("first target raced", first.calls.get(), new IsEqual<>(1));
        MatcherAssert.assertThat("second target raced", second.calls.get(), new IsEqual<>(1));
    }

    @Test
    void failedHitFallsBackToTheFullRace() {
        final Counting first = new Counting(RsStatus.NOT_FOUND);
        final Counting second = new Counting(RsStatus.OK);
        final Response res = new RaceSlice(
            List.of(first, second), line -> CompletableFuture.completedFuture(true)
        ).response(LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat("fallback race finds the 200", res.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat("first target: hit attempt plus race", first.calls.get(), new IsEqual<>(2));
        MatcherAssert.assertThat("second target raced once", second.calls.get(), new IsEqual<>(1));
    }

    @Test
    void failingPresenceCheckRacesEveryTarget() {
        final Counting first = new Counting(RsStatus.OK);
        final Counting second = new Counting(RsStatus.OK);
        final Response res = new RaceSlice(
            List.of(first, second),
            line -> CompletableFuture.failedFuture(new IllegalStateException("storage down"))
        ).response(LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat("race still answers", res.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat("both targets raced", first.calls.get() + second.calls.get(), new IsEqual<>(2));
    }

    /**
     * Slice answering a fixed status and counting its calls.
     */
    private static final class Counting implements Slice {

        private final AtomicInteger calls = new AtomicInteger();

        private final RsStatus status;

        Counting(final RsStatus status) {
            this.status = status;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.from(this.status).textBody("body").build()
            );
        }
    }
}
