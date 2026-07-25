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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

/**
 * Test for {@link PingSlice}: {@code npm ping}'s registry-liveness contract.
 */
final class PingSliceTest {

    @Test
    void answersOkWithEmptyJsonBody() {
        MatcherAssert.assertThat(
            new PingSlice().response(
                new RequestLine(RqMethod.GET, "/-/ping"), Headers.EMPTY, Content.EMPTY
            ).join().status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void consumesRequestBodyPublisher() {
        final AtomicInteger subscribed = new AtomicInteger();
        final Publisher<java.nio.ByteBuffer> tracked = subscriber -> {
            subscribed.incrementAndGet();
            subscriber.onSubscribe(new Subscription() {
                @Override
                public void request(final long n) {
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                }
            });
        };
        new PingSlice().response(
            new RequestLine(RqMethod.GET, "/-/ping"), Headers.EMPTY, new Content.From(tracked)
        ).join();
        MatcherAssert.assertThat(subscribed.get(), new IsEqual<>(1));
    }
}
