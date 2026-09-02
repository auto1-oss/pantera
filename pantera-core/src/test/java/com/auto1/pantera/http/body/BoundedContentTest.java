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
package com.auto1.pantera.http.body;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.RequestBodyTooLargeException;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BoundedContent}: the limit is enforced on ACTUAL bytes
 * (independent of any declared size), tripping stops pulling upstream,
 * and a body within the limit passes through untouched.
 *
 * @since 2.2.9
 */
final class BoundedContentTest {

    @Test
    void failsOnceActualBytesExceedTheLimit() {
        // No declared size at all — the chunked case.
        final Content body = new Content.From(
            Flowable.range(0, 5).map(idx -> ByteBuffer.wrap(new byte[100]))
        );
        final CompletionException failure = Assertions.assertThrows(
            CompletionException.class,
            () -> new BoundedContent(body, 250L).asBytesFuture().join()
        );
        MatcherAssert.assertThat(
            "exceeding the limit must surface as RequestBodyTooLargeException",
            RequestBodyTooLargeException.isCause(failure), new IsEqual<>(true)
        );
    }

    @Test
    void stopsPullingUpstreamOnceTripped() {
        final AtomicInteger emitted = new AtomicInteger();
        final Content body = new Content.From(
            Flowable.range(0, 1000)
                .doOnNext(idx -> emitted.incrementAndGet())
                .map(idx -> ByteBuffer.wrap(new byte[100]))
        );
        Assertions.assertThrows(
            CompletionException.class,
            () -> new BoundedContent(body, 250L).asBytesFuture().join()
        );
        MatcherAssert.assertThat(
            "the upstream must be cancelled at the trip point, not drained to the end",
            emitted.get() < 1000, new IsEqual<>(true)
        );
    }

    @Test
    void passesABodyWithinTheLimitThrough() {
        final byte[] payload = "within the limit".getBytes();
        final Content body = new Content.From(payload);
        final byte[] out = new BoundedContent(body, 1024L).asBytesFuture().join();
        MatcherAssert.assertThat(
            "a body within the limit must be delivered unchanged",
            out, new IsEqual<>(payload)
        );
    }

    @Test
    void exactlyTheLimitIsAllowed() {
        final byte[] payload = new byte[64];
        final byte[] out = new BoundedContent(new Content.From(payload), 64L)
            .asBytesFuture().join();
        MatcherAssert.assertThat(
            "a body of exactly the limit is within it", out.length, new IsEqual<>(64)
        );
    }
}
