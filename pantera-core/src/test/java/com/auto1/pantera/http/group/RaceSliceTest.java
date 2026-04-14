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
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.SliceSimple;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Test case for {@link RaceSlice}.
 */
final class RaceSliceTest {

    @Test
    @Timeout(1)
    void returnsFirstSuccessResponseInParallel() {
        // Parallel race strategy: fastest success wins, not first in order
        final String expects = "ok-50";  // This is the FASTEST success (50ms)
        Response response = new RaceSlice(
            slice(RsStatus.NOT_FOUND, "not-found-250", Duration.ofMillis(250)),
            slice(RsStatus.NOT_FOUND, "not-found-50", Duration.ofMillis(50)),
            slice(RsStatus.OK, "ok-150", Duration.ofMillis(150)),  // Slower success
            slice(RsStatus.NOT_FOUND, "not-found-200", Duration.ofMillis(200)),
            slice(RsStatus.OK, expects, Duration.ofMillis(50)),  // FASTEST success - wins!
            slice(RsStatus.OK, "ok-never", Duration.ofDays(1))
        ).response(new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY).join();

        Assertions.assertEquals(RsStatus.OK, response.status());
        Assertions.assertEquals(expects, response.body().asString());
    }

    @Test
    void returnsNotFoundIfAllFails() {
        Response res = new RaceSlice(
            slice(RsStatus.NOT_FOUND, "not-found-140", Duration.ofMillis(250)),
            slice(RsStatus.NOT_FOUND, "not-found-10", Duration.ofMillis(50)),
            slice(RsStatus.NOT_FOUND, "not-found-110", Duration.ofMillis(200))
        ).response(new RequestLine(RqMethod.GET, "/foo"), Headers.EMPTY, Content.EMPTY).join();

        Assertions.assertEquals(RsStatus.NOT_FOUND, res.status());
    }

    @Test
    @Timeout(1)
    void returnsNotFoundIfSomeFailsWithException() {
        Slice s = (line, headers, body) -> CompletableFuture.failedFuture(new IllegalStateException());

        Assertions.assertEquals(RsStatus.NOT_FOUND,
            new RaceSlice(s)
                .response(new RequestLine(RqMethod.GET, "/faulty/path"), Headers.EMPTY, Content.EMPTY)
                .join().status());
    }

    private static Slice slice(RsStatus status, String body, Duration delay) {
        return new SliceWithDelay(
            new SliceSimple(ResponseBuilder.from(status).textBody(body).build()), delay
        );
    }

    /**
     * Slice testing decorator to add delay before sending request to origin slice.
     */
    private static final class SliceWithDelay extends Slice.Wrap {

        /**
         * Add delay for slice.
         * @param origin Origin slice
         * @param delay Delay duration
         */
        SliceWithDelay(final Slice origin, final Duration delay) {
            super((line, headers, body) -> CompletableFuture.runAsync(
                () -> {
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (final InterruptedException ignore) {
                        Thread.currentThread().interrupt();
                    }
                }
            ).thenCompose(none -> origin.response(line, headers, body)));
        }
    }
}
