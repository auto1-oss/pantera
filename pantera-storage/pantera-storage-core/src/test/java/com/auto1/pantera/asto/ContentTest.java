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
package com.auto1.pantera.asto;

import hu.akarnokd.rxjava2.interop.MaybeInterop;
import io.reactivex.Flowable;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.Test;

/**
 * Test cases for {@link Content}.
 *
 * @since 0.24
 */
final class ContentTest {

    @Test
    void emptyHasNoChunks() {
        MatcherAssert.assertThat(
            Flowable.fromPublisher(Content.EMPTY)
                .singleElement()
                .to(MaybeInterop.get())
                .toCompletableFuture()
                .join(),
            new IsNull<>()
        );
    }

    @Test
    void emptyHasZeroSize() {
        MatcherAssert.assertThat(
            Content.EMPTY.size(),
            new IsEqual<>(Optional.of(0L))
        );
    }
}
