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
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ReservedPathSlice}.
 */
final class ReservedPathSliceTest {

    @ParameterizedTest
    @ValueSource(
        strings = {
            "/files/.pantera-locks/a/b/uuid",
            "/files/.pantera-locks",
            "/files/.pantera-locks/",
            "/.pantera-locks/x",
            "/maven/com/acme/.pantera-locks/lib-1.0.jar"
        }
    )
    void refusesTheLockNamespaceWithoutReachingTheOrigin(final String path) {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger drained = new AtomicInteger();
        final Response resp = new ReservedPathSlice(
            (line, headers, body) -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
        ).response(
            new RequestLine(RqMethod.PUT, path), Headers.EMPTY,
            new Content() {
                private final Content inner = new Content.From(
                    "payload".getBytes(StandardCharsets.UTF_8)
                );

                @Override
                public java.util.Optional<Long> size() {
                    return this.inner.size();
                }

                @Override
                public void subscribe(final org.reactivestreams.Subscriber<? super java.nio.ByteBuffer> sub) {
                    this.inner.subscribe(sub);
                }

                @Override
                public CompletableFuture<Void> discard() {
                    drained.incrementAndGet();
                    return this.inner.discard();
                }
            }
        ).join();
        MatcherAssert.assertThat("answers 404", resp.status(), new IsEqual<>(RsStatus.NOT_FOUND));
        MatcherAssert.assertThat("origin is not reached", calls.get(), new IsEqual<>(0));
        MatcherAssert.assertThat("body is drained", drained.get(), new IsEqual<>(1));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "/files/pantera-locks/a",
            "/files/.pantera-locks-not/a",
            "/files/x.pantera-locks",
            "/files/docs/locks.txt"
        }
    )
    void passesEverythingElseThrough(final String path) {
        MatcherAssert.assertThat(
            new ReservedPathSlice(
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.ok().build()
                )
            ).response(new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY)
                .join().status(),
            new IsEqual<>(RsStatus.OK)
        );
    }
}
