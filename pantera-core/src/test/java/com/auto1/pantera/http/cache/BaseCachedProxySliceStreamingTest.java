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
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BaseCachedProxySlice#streamingCacheWrite} —
 * T-P04 streaming tee helper.
 *
 * @since 2.2.0
 */
final class BaseCachedProxySliceStreamingTest {

    private static final Key KEY = new Key.From("foo/1.0/foo-1.0.tgz");

    private static final byte[] PAYLOAD =
        "T-P04 streaming-cache-write payload".getBytes(StandardCharsets.UTF_8);

    @Test
    void streamingCacheWriteTeesBytesAndPopulatesStorage() throws Exception {
        final Storage storage = new InMemoryStorage();
        final RecordingUpstream upstream = new RecordingUpstream();
        upstream.enqueue(
            ResponseBuilder.ok()
                .body(new Content.From(
                    Optional.of((long) PAYLOAD.length),
                    Flowable.just(ByteBuffer.wrap(PAYLOAD))
                ))
                .build()
        );
        final FakeStreamingSlice slice = new FakeStreamingSlice(upstream, storage);
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final Response response = slice.invoke(
            new RequestLine(RqMethod.GET, "/" + KEY.string()),
            Headers.EMPTY, KEY, gate
        ).join();
        final byte[] consumed = response.body().asBytesFuture().get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "client sees the upstream payload byte-for-byte",
            consumed, new IsEqual<>(PAYLOAD)
        );
        MatcherAssert.assertThat(
            "response status is 200",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        // The verificationOutcome resolves once the cache is durable;
        // wait for the gate which is wired to it.
        gate.get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "cache contains the primary after the leader gate fires",
            storage.exists(KEY).join(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "cache bytes match upstream byte-for-byte",
            storage.value(KEY).join().asBytes(), new IsEqual<>(PAYLOAD)
        );
    }

    @Test
    void streamingCacheWrite404PopulatesNegativeCacheAndReleasesGate() {
        final Storage storage = new InMemoryStorage();
        final RecordingUpstream upstream = new RecordingUpstream();
        upstream.enqueue(ResponseBuilder.notFound().build());
        final FakeStreamingSlice slice = new FakeStreamingSlice(upstream, storage);
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final Response response = slice.invoke(
            new RequestLine(RqMethod.GET, "/" + KEY.string()),
            Headers.EMPTY, KEY, gate
        ).join();
        MatcherAssert.assertThat(
            "404 propagates verbatim",
            response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "leader gate is released so followers do not park",
            gate.isDone(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "404 does not populate the cache",
            storage.exists(KEY).join(), new IsEqual<>(false)
        );
    }

    @Test
    void streamingCacheWrite5xxReleasesGateWithoutCachingBytes() {
        final Storage storage = new InMemoryStorage();
        final RecordingUpstream upstream = new RecordingUpstream();
        upstream.enqueue(
            ResponseBuilder.from(RsStatus.INTERNAL_ERROR)
                .body(Flowable.empty()).build()
        );
        final FakeStreamingSlice slice = new FakeStreamingSlice(upstream, storage);
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final Response response = slice.invoke(
            new RequestLine(RqMethod.GET, "/" + KEY.string()),
            Headers.EMPTY, KEY, gate
        ).join();
        MatcherAssert.assertThat(
            "5xx surfaces a 5xx-class error to the caller",
            response.status().code() >= 500, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "leader gate is released even on 5xx",
            gate.isDone(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "5xx does not populate the cache",
            storage.exists(KEY).join(), new IsEqual<>(false)
        );
    }

    @Test
    void streamingCacheWriteUpstreamExceptionReleasesGateAndReturns502() {
        final Storage storage = new InMemoryStorage();
        final RecordingUpstream upstream = new RecordingUpstream();
        upstream.failNext(new RuntimeException("simulated upstream IOException"));
        final FakeStreamingSlice slice = new FakeStreamingSlice(upstream, storage);
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final Response response = slice.invoke(
            new RequestLine(RqMethod.GET, "/" + KEY.string()),
            Headers.EMPTY, KEY, gate
        ).join();
        MatcherAssert.assertThat(
            "exception path returns 502 (Bad Gateway)",
            response.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "leader gate is released on exception",
            gate.isDone(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "exception does not populate the cache",
            storage.exists(KEY).join(), new IsEqual<>(false)
        );
    }

    /**
     * Concrete subclass that exposes the protected
     * {@link BaseCachedProxySlice#streamingCacheWrite} for direct
     * unit testing.
     */
    private static final class FakeStreamingSlice extends BaseCachedProxySlice {

        FakeStreamingSlice(final Slice upstream, final Storage storage) {
            super(
                upstream,
                buildCache(storage),
                "test-repo", "test", "https://upstream.example",
                Optional.of(storage),
                Optional.empty(),
                ProxyCacheConfig.defaults()
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }

        CompletableFuture<Response> invoke(
            final RequestLine line, final Headers headers, final Key key,
            final CompletableFuture<Void> leaderGate
        ) {
            return this.streamingCacheWrite(line, headers, key, leaderGate);
        }

        private static Cache buildCache(final Storage storage) {
            return new FromStorageCache(storage);
        }
    }

    /**
     * Upstream stub: returns canned responses in FIFO order or fails
     * with a canned throwable when {@link #failNext(Throwable)} was
     * called.
     */
    private static final class RecordingUpstream implements Slice {

        private final java.util.Queue<Response> canned = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();

        void enqueue(final Response response) {
            this.canned.add(response);
        }

        void failNext(final Throwable err) {
            this.failure.set(err);
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.calls.incrementAndGet();
            final Throwable err = this.failure.getAndSet(null);
            if (err != null) {
                return CompletableFuture.failedFuture(err);
            }
            final Response next = this.canned.poll();
            return CompletableFuture.completedFuture(
                next == null
                    ? ResponseBuilder.ok().body(Flowable.empty()).build()
                    : next
            );
        }
    }
}
