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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Tests for {@link RangeSlice}.
 *
 * <p>The origin body is emitted in many small chunks and the body is
 * consumed with bounded demand (one buffer at a time), the way the Vert.x
 * response writer consumes it. A range that starts past the first chunk
 * must neither hang nor over-read.</p>
 *
 * @since 2.2.9
 */
final class RangeSliceTest {

    /**
     * Chunk size of the fake origin body.
     */
    private static final int CHUNK = 1024;

    /**
     * Number of chunks in the fake origin body.
     */
    private static final int CHUNKS = 64;

    @ParameterizedTest
    @CsvSource({
        "bytes=0-9,0,9",
        "bytes=1023-1032,1023,1032",
        "bytes=1024-1033,1024,1033",
        "bytes=10240-10249,10240,10249",
        "bytes=5000-,5000,65535",
        "bytes=65530-65535,65530,65535"
    })
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void servesRangeWithBoundedDemand(final String range, final long start,
        final long end) throws Exception {
        final byte[] data = RangeSliceTest.data();
        final Response rsp = new RangeSlice(new ChunkedSlice(data, new AtomicBoolean()))
            .response(
                new RequestLine(RqMethod.GET, "/file.bin"),
                Headers.from("Range", range),
                Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "status is 206",
            rsp.status(), new IsEqual<>(RsStatus.PARTIAL_CONTENT)
        );
        MatcherAssert.assertThat(
            "body is exactly the requested range",
            RangeSliceTest.drain(rsp).get(20, TimeUnit.SECONDS),
            new IsEqual<>(Arrays.copyOfRange(data, (int) start, (int) end + 1))
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cancelsUpstreamOnceRangeIsServed() throws Exception {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Response rsp = new RangeSlice(
            new ChunkedSlice(RangeSliceTest.data(), cancelled)
        ).response(
            new RequestLine(RqMethod.GET, "/file.bin"),
            Headers.from("Range", "bytes=2048-2057"),
            Content.EMPTY
        ).join();
        RangeSliceTest.drain(rsp).get(20, TimeUnit.SECONDS);
        MatcherAssert.assertThat(cancelled.get(), new IsEqual<>(true));
    }

    @ParameterizedTest
    @CsvSource({
        "bytes=65000-70000,65000,65535",
        "bytes=-10,65526,65535",
        "bytes=-100000,0,65535"
    })
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void clampsEndAndServesSuffixRanges(final String range, final long start,
        final long end) throws Exception {
        final byte[] data = RangeSliceTest.data();
        final Response rsp = new RangeSlice(new ChunkedSlice(data, new AtomicBoolean()))
            .response(
                new RequestLine(RqMethod.GET, "/file.bin"),
                Headers.from("Range", range),
                Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "status is 206",
            rsp.status(), new IsEqual<>(RsStatus.PARTIAL_CONTENT)
        );
        MatcherAssert.assertThat(
            "Content-Range is clamped to the representation",
            rsp.headers().values("Content-Range").get(0),
            new IsEqual<>(String.format("bytes %d-%d/%d", start, end, data.length))
        );
        MatcherAssert.assertThat(
            "body is the clamped range",
            RangeSliceTest.drain(rsp).get(20, TimeUnit.SECONDS),
            new IsEqual<>(Arrays.copyOfRange(data, (int) start, (int) end + 1))
        );
    }

    @Test
    void startPastEndIsNotSatisfiable() {
        final Response rsp = new RangeSlice(
            new ChunkedSlice(RangeSliceTest.data(), new AtomicBoolean())
        ).response(
            new RequestLine(RqMethod.GET, "/file.bin"),
            Headers.from("Range", "bytes=65536-"),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            rsp.status(), new IsEqual<>(RsStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        );
    }

    /**
     * Test data: CHUNKS * CHUNK bytes with a position-dependent pattern.
     * @return Bytes
     */
    private static byte[] data() {
        final byte[] res = new byte[RangeSliceTest.CHUNK * RangeSliceTest.CHUNKS];
        for (int idx = 0; idx < res.length; idx += 1) {
            res[idx] = (byte) (idx * 31 + idx / 251);
        }
        return res;
    }

    /**
     * Drain a response body requesting one buffer at a time.
     * @param rsp Response
     * @return Future with the body bytes
     */
    private static CompletableFuture<byte[]> drain(final Response rsp) {
        final CompletableFuture<byte[]> res = new CompletableFuture<>();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        rsp.body().subscribe(
            new Subscriber<ByteBuffer>() {
                private Subscription sub;

                @Override
                public void onSubscribe(final Subscription subscription) {
                    this.sub = subscription;
                    subscription.request(1);
                }

                @Override
                public void onNext(final ByteBuffer buf) {
                    final byte[] arr = new byte[buf.remaining()];
                    buf.get(arr);
                    out.write(arr, 0, arr.length);
                    this.sub.request(1);
                }

                @Override
                public void onError(final Throwable err) {
                    res.completeExceptionally(err);
                }

                @Override
                public void onComplete() {
                    res.complete(out.toByteArray());
                }
            }
        );
        return res;
    }

    /**
     * Origin slice serving a body in fixed-size chunks.
     * @since 2.2.9
     */
    private static final class ChunkedSlice implements com.auto1.pantera.http.Slice {

        /**
         * Body bytes.
         */
        private final byte[] data;

        /**
         * Set when the body publisher is cancelled.
         */
        private final AtomicBoolean cancelled;

        /**
         * Ctor.
         * @param data Body bytes
         * @param cancelled Cancellation flag
         */
        ChunkedSlice(final byte[] data, final AtomicBoolean cancelled) {
            this.data = data;
            this.cancelled = cancelled;
        }

        @Override
        public CompletableFuture<Response> response(final RequestLine line,
            final Headers headers, final Content body) {
            final Flowable<ByteBuffer> chunks = Flowable.range(0, RangeSliceTest.CHUNKS)
                .map(
                    num -> ByteBuffer.wrap(
                        this.data, num * RangeSliceTest.CHUNK, RangeSliceTest.CHUNK
                    ).slice()
                )
                .doOnCancel(() -> this.cancelled.set(true));
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Length", String.valueOf(this.data.length))
                    .body(new Content.From(chunks))
                    .build()
            );
        }
    }
}
