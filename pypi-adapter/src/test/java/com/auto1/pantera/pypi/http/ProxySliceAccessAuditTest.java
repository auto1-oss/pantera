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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * The {@code artifact_access} audit record of a pypi proxy serve carries the
 * artifact's real size (R43: a cache miss recorded 0 because the chunked
 * upstream body did not know its size, while the matching publish record
 * carried it).
 *
 * @since 2.2.9
 */
final class ProxySliceAccessAuditTest {

    @Test
    void usesTheServedContentSize() {
        MatcherAssert.assertThat(
            ProxySlice.accessSize(new Content.From(new byte[12]), Headers.EMPTY),
            new IsEqual<>(12L)
        );
    }

    @Test
    void fallsBackToTheUpstreamContentLength() {
        MatcherAssert.assertThat(
            ProxySlice.accessSize(
                new Content.From(Flowable.just(ByteBuffer.wrap(new byte[3]))),
                Headers.from("content-length", "12823")
            ),
            new IsEqual<>(12_823L)
        );
    }

    @Test
    void isZeroOnlyWhenNothingKnowsTheSize() {
        MatcherAssert.assertThat(
            ProxySlice.accessSize(
                new Content.From(Flowable.just(ByteBuffer.wrap(new byte[3]))), null
            ),
            new IsEqual<>(0L)
        );
    }
}
