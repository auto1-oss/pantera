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
import com.auto1.pantera.http.resilience.AdaptiveBulkheadLimits;
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
 * A {@link BaseCachedProxySlice} fronted by a {@link RepoBulkhead} serves a
 * burst beyond its permits by letting the surplus wait for a permit. Only a
 * request that gets none within the wait bound is refused, with a 503
 * carrying {@code Retry-After} and {@code X-Pantera-Bulkhead-Overflow: true}.
 *
 * @since 2.2.0
 */
final class BaseCachedProxySliceBulkheadTest {

    @Test
    void burstBeyondThePermitsIsServedNotRefused() throws Exception {
        // 2026-11-17: the requests beyond the permits were refused on the
        // spot; a Maven Central proxy answered 17,000 of them 502 in 50
        // minutes while its upstream was healthy.
        final Storage storage = new InMemoryStorage();
        final SlowUpstream upstream = new SlowUpstream(Duration.ofMillis(100));
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "overflow-test-repo",
            AdaptiveBulkheadLimits.fixed(2, 100, Duration.ofSeconds(1)),
            ForkJoinPool.commonPool()
        );
        final BulkheadTestSlice slice = new BulkheadTestSlice(upstream, storage);
        slice.setBulkhead(bulkhead);

        final int fired = 12;
        @SuppressWarnings("unchecked")
        final CompletableFuture<Response>[] futures = new CompletableFuture[fired];
        for (int i = 0; i < fired; i++) {
            futures[i] = slice.response(
                new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0-" + i + ".jar"),
                Headers.EMPTY, Content.EMPTY
            );
        }
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

        int served = 0;
        for (final CompletableFuture<Response> future : futures) {
            if (future.get().status() == RsStatus.OK) {
                served += 1;
            }
        }
        MatcherAssert.assertThat(
            "every request of the burst is served",
            served, new IsEqual<>(fired)
        );
        MatcherAssert.assertThat(
            "each one reached the upstream",
            upstream.calls.get(), new IsEqual<>(fired)
        );
        MatcherAssert.assertThat(
            "never more of them at once than there are permits",
            upstream.peak.get() <= 2, new IsEqual<>(true)
        );
    }

    @Test
    void refusalAfterTheWaitBoundIsFiveOhThreeWithRetryAfter() throws Exception {
        final Storage storage = new InMemoryStorage();
        final SlowUpstream upstream = new SlowUpstream(Duration.ofMillis(1500));
        final RepoBulkhead bulkhead = new RepoBulkhead(
            "overflow-test-repo",
            AdaptiveBulkheadLimits.fixed(1, 100, Duration.ofSeconds(1)),
            ForkJoinPool.commonPool(),
            Duration.ofMillis(20)
        );
        final BulkheadTestSlice slice = new BulkheadTestSlice(upstream, storage);
        slice.setBulkhead(bulkhead);

        final CompletableFuture<Response> admitted = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0-a.jar"),
            Headers.EMPTY, Content.EMPTY
        );
        final Response refused = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0-b.jar"),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "a request that got no permit within the wait bound is answered 503",
            refused.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "with the bulkhead marker header",
            refused.headers().single("X-Pantera-Bulkhead-Overflow").getValue(),
            new IsEqual<>("true")
        );
        MatcherAssert.assertThat(
            "and Retry-After",
            refused.headers().single("Retry-After").getValue(),
            new IsEqual<>("1")
        );
        MatcherAssert.assertThat(
            "the request that holds the permit is served",
            admitted.get(30, TimeUnit.SECONDS).status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the upstream was asked for it alone",
            upstream.calls.get(), new IsEqual<>(1)
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

        /**
         * Calls in progress.
         */
        private final AtomicInteger running = new AtomicInteger();

        /**
         * Most calls in progress at one time.
         */
        private final AtomicInteger peak = new AtomicInteger();

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
            this.peak.accumulateAndGet(this.running.incrementAndGet(), Math::max);
            return CompletableFuture.supplyAsync(
                () -> ResponseBuilder.ok().body("ok".getBytes()).build(),
                CompletableFuture.delayedExecutor(this.delay.toMillis(), TimeUnit.MILLISECONDS)
            ).whenComplete((response, error) -> this.running.decrementAndGet());
        }
    }
}
