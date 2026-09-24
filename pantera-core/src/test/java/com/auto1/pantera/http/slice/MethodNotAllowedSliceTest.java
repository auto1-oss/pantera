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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;

/**
 * Test for {@link MethodNotAllowedSlice}.
 *
 * @since 2.2.9
 */
final class MethodNotAllowedSliceTest {

    @Test
    void refusesWithTheAllowedMethodsAndDrainsTheBody() {
        final DeclaredHugeBody body = new DeclaredHugeBody();
        final Response resp = new MethodNotAllowedSlice("GET, HEAD").response(
            RequestLine.from("PUT /repo/pkg HTTP/1.1"), Headers.EMPTY, body
        ).join();
        MatcherAssert.assertThat(
            "the method is refused",
            resp.status(), new IsEqual<>(RsStatus.METHOD_NOT_ALLOWED)
        );
        MatcherAssert.assertThat(
            "the refusal names the allowed methods",
            resp.headers().values("Allow"), new IsEqual<>(List.of("GET, HEAD"))
        );
        MatcherAssert.assertThat(
            "the refusal must not materialise the body (resource-dos F31)",
            body.materialised.get(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the refusal still consumes the request body",
            body.subscribed.get(), new IsEqual<>(true)
        );
    }

    /**
     * Request body that declares ~2 GB but carries a few bytes, and records
     * whether it was materialised with {@code asBytesFuture()} or drained.
     */
    private static final class DeclaredHugeBody implements Content {

        private final AtomicBoolean materialised = new AtomicBoolean();

        private final AtomicBoolean subscribed = new AtomicBoolean();

        @Override
        public Optional<Long> size() {
            return Optional.of(2_000_000_000L);
        }

        @Override
        public CompletableFuture<byte[]> asBytesFuture() {
            this.materialised.set(true);
            return Content.super.asBytesFuture();
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
            this.subscribed.set(true);
            Flowable.just(
                ByteBuffer.wrap("ten  bytes".getBytes(StandardCharsets.UTF_8))
            ).subscribe(subscriber);
        }
    }
}
