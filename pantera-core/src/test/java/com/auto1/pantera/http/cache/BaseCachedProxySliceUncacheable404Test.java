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
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Regression guard for the directory / uncacheable-path 404 &rarr; 502 defect.
 *
 * <p>On the {@code fetchDirect} path (taken when {@link BaseCachedProxySlice#isCacheable}
 * is false &mdash; e.g. a Gradle/Maven directory-listing GET with a trailing slash),
 * a 404 from upstream with negative-caching enabled subscribed the single-subscriber
 * upstream body <em>twice</em>: once to seed the negative cache, once to build the
 * 404 response. The second subscription threw
 * {@code IllegalStateException: ... is single-subscriber}, which the
 * {@code exceptionally} funnel turned into a 503 &mdash; and {@code RaceSlice}
 * (the maven-proxy wrapper) then surfaced that as a client-facing 502
 * ("All upstream remotes failed"). In production this was the single largest
 * source of proxy 502s.
 *
 * <p>The upstream body of a real proxy fetch is single-subscriber
 * ({@code JettyContentSourcePublisher}); this test uses a single-subscriber fake so
 * the double-subscribe surfaces the same way it does in production. Unit tests that
 * used re-subscribable in-memory bodies could never catch it.
 */
final class BaseCachedProxySliceUncacheable404Test {

    @Test
    @Timeout(10)
    @DisplayName("uncacheable-path 404 with single-subscriber body + negative cache returns 404, not 502/503")
    void uncacheable404WithSingleSubscriberBodyReturns404() throws Exception {
        final Slice upstream = (line, headers, content) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.notFound()
                    .body(new SingleSubscriberContent())
                    .build()
            );
        final Response resp = newSlice(upstream).response(
            new RequestLine(RqMethod.GET, "/org/springframework/spring-aspects/"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertThat(
            "a directory 404 must stay a 404 — never collapse to a bad-gateway",
            resp.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    private static TestSlice newSlice(final Slice upstream) {
        final Storage storage = new InMemoryStorage();
        return new TestSlice(upstream, storage);
    }

    /**
     * Storage-backed subclass whose paths are all <em>uncacheable</em>, so every
     * request takes the {@code fetchDirect} branch &mdash; the code path that carried
     * the double-subscribe defect. {@link ProxyCacheConfig#defaults()} leaves negative
     * caching enabled, which is the precondition for the bug.
     */
    private static final class TestSlice extends BaseCachedProxySlice {
        TestSlice(final Slice upstream, final Storage storage) {
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
            return false;
        }
    }

    /**
     * Empty body that permits exactly one subscription and throws on the second,
     * mirroring {@code JettyContentSourcePublisher}'s contract for a real upstream
     * response body.
     */
    private static final class SingleSubscriberContent implements Content {
        private final AtomicBoolean subscribed = new AtomicBoolean(false);

        @Override
        public Optional<Long> size() {
            return Optional.empty();
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> sub) {
            if (!this.subscribed.compareAndSet(false, true)) {
                throw new IllegalStateException("single-subscriber (test fake)");
            }
            sub.onSubscribe(new Subscription() {
                @Override
                public void request(final long n) {
                    sub.onComplete();
                }

                @Override
                public void cancel() {
                    // no-op
                }
            });
        }
    }
}
