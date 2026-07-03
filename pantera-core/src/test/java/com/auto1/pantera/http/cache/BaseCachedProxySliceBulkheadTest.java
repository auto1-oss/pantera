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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.resilience.BulkheadLimits;
import com.auto1.pantera.http.resilience.RepoBulkhead;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * T-P12 acceptance test — a {@link BaseCachedProxySlice} fronted by a
 * {@link RepoBulkhead} with {@code maxConcurrent=2} should accept 2
 * concurrent requests and reject the next 3 with a 503 carrying
 * {@code Retry-After} and {@code X-Pantera-Bulkhead-Overflow: true}.
 *
 * @since 2.2.0
 */
final class BaseCachedProxySliceBulkheadTest {

    @Test
    void overflowReturnsFiveOhThreeWithRetryAfter() throws Exception {
        final Storage storage = new InMemoryStorage();
        final SlowUpstream upstream = new SlowUpstream(Duration.ofMillis(400));
        final BulkheadLimits tight = new BulkheadLimits(2, 8, Duration.ofSeconds(1));
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "overflow-test-repo", tight, ForkJoinPool.commonPool()
        );
        final BulkheadTestSlice slice = new BulkheadTestSlice(upstream, storage);
        slice.setBulkhead(bulkhead);

        final int fired = 5;
        @SuppressWarnings("unchecked")
        final CompletableFuture<Response>[] futures = new CompletableFuture[fired];
        for (int i = 0; i < fired; i++) {
            futures[i] = slice.response(
                new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0-" + i + ".jar"),
                Headers.EMPTY, Content.EMPTY
            );
        }

        CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

        int ok = 0;
        int overload = 0;
        for (final CompletableFuture<Response> f : futures) {
            final Response r = f.get();
            if (r.status() == RsStatus.OK) {
                ok++;
            } else if (r.status() == RsStatus.SERVICE_UNAVAILABLE) {
                overload++;
                MatcherAssert.assertThat(
                    "overflow responses carry the bulkhead marker header",
                    r.headers().single("X-Pantera-Bulkhead-Overflow").getValue(),
                    new IsEqual<>("true")
                );
                MatcherAssert.assertThat(
                    "overflow responses carry Retry-After",
                    r.headers().single("Retry-After").getValue(),
                    new IsEqual<>("1")
                );
            }
        }
        MatcherAssert.assertThat(
            "exactly maxConcurrent (2) requests succeed",
            ok, new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the remaining 3 are overflowed with 503",
            overload, new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "upstream slice was only called for the admitted requests",
            upstream.calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void noBulkheadConfiguredMeansNoGating() throws Exception {
        final Storage storage = new InMemoryStorage();
        final SlowUpstream upstream = new SlowUpstream(Duration.ofMillis(0));
        final BulkheadTestSlice slice = new BulkheadTestSlice(upstream, storage);
        // No setBulkhead call — slice is unconfigured and registry is empty.

        final Response r = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0.jar"),
            Headers.EMPTY, Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "unconfigured slice passes through to upstream",
            r.status(), new IsEqual<>(RsStatus.OK)
        );
    }

    /**
     * Test subclass that calls super with the simple no-cooldown ctor
     * and exposes {@link BaseCachedProxySlice#setBulkhead}.
     */
    private static final class BulkheadTestSlice extends BaseCachedProxySlice {

        BulkheadTestSlice(final Slice upstream, final Storage storage) {
            super(
                upstream,
                new FromStorageCache(storage),
                "overflow-test-repo",
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
     * Upstream that sleeps for {@code delay} before returning 200.
     * Lets us pile up concurrent requests inside the bulkhead.
     */
    private static final class SlowUpstream implements Slice {

        private final Duration delay;

        private final AtomicInteger calls = new AtomicInteger();

        SlowUpstream(final Duration delay) {
            this.delay = delay;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.calls.incrementAndGet();
            if (this.delay.isZero()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body("ok".getBytes()).build()
                );
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(this.delay.toMillis());
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return ResponseBuilder.ok().body("ok".getBytes()).build();
            });
        }
    }
}
