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
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.FailedCompletionStage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Test for {@link Remote.WithErrorHandling}.
 */
class RemoteWithErrorHandlingTest {

    @Test
    void returnsContentFromOrigin() {
        final byte[] bytes = "123".getBytes();
        Assertions.assertArrayEquals(
            bytes,
            new Remote.WithErrorHandling(
                () -> CompletableFuture.completedFuture(
                    Optional.of(new Content.From(bytes))
                )
            ).get().toCompletableFuture().join().orElseThrow().asBytes()
        );
    }

    @Test
    void returnsEmptyOnError() {
        MatcherAssert.assertThat(
            new Remote.WithErrorHandling(
                () -> new FailedCompletionStage<>(new ConnectException("Connection error"))
            ).get().toCompletableFuture().join().isPresent(),
            new IsEqual<>(false)
        );
    }
}
