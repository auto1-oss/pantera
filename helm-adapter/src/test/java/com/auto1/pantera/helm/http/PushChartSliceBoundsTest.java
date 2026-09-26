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
package com.auto1.pantera.helm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the unbounded Helm chart upload buffer
 * (resource-dos F45, upload half).
 *
 * <p>Before 2.2.9 {@code PushChartSlice} collected the whole request body
 * with {@code Flowable.toList()} into one contiguous array (then copied it
 * again) with no bound whatsoever, so an authenticated writer could push
 * an arbitrarily large "chart" straight into the heap. The upload must be
 * metered and refused with 413 once it exceeds the chart cap — without
 * consuming the rest of the body.</p>
 *
 * @since 2.2.9
 */
final class PushChartSliceBoundsTest {

    @Test
    void uploadExceedingTheChartCapIsRejectedWithoutBufferingItAll() {
        final InMemoryStorage storage = new InMemoryStorage();
        final AtomicLong emitted = new AtomicLong();
        // 64 chunks of 1 KiB against a 4 KiB cap.
        final Content body = new Content.From(
            Flowable.range(0, 64)
                .doOnNext(idx -> emitted.addAndGet(1024L))
                .map(idx -> ByteBuffer.wrap(new byte[1024]))
        );
        final Response response = new PushChartSlice(
            storage, Optional.empty(), "helm", 4096L
        ).response(
            new RequestLine(RqMethod.POST, "/"), Headers.EMPTY, body
        ).join();
        MatcherAssert.assertThat(
            "a chart upload above the cap must be refused with 413",
            response.status().code(), new IsEqual<>(413)
        );
        MatcherAssert.assertThat(
            "the slice must stop consuming the body at the cap, not buffer all of it",
            emitted.get() < 64L * 1024L, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "nothing may be written to storage for a refused upload",
            storage.list(Key.ROOT).join().isEmpty(), new IsEqual<>(true)
        );
    }
}
