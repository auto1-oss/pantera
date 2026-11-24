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
package com.auto1.pantera.docker.composite;

import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.fake.FakeLayers;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Assertions;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link MultiReadLayers}.
 *
 * @since 0.3
 */
final class MultiReadLayersTest {
    @ParameterizedTest
    @CsvSource({
        "empty,empty,false",
        "empty,full,true",
        "full,empty,true",
        "faulty,full,true",
        "full,faulty,true"
    })
    void shouldReturnExpectedValue(final String one, final String two, final boolean present) {
        MatcherAssert.assertThat(
            new MultiReadLayers(
                Arrays.asList(
                    new FakeLayers(one),
                    new FakeLayers(two)
                )
            ).get(new Digest.FromString("123"))
                .toCompletableFuture().join()
                .isPresent(),
            new IsEqual<>(present)
        );
    }

    @ParameterizedTest
    @CsvSource({
        "faulty,empty",
        "empty,faulty",
        "faulty,faulty"
    })
    void failsWhenNoneHasTheBlobAndOneCouldNotAnswer(final String one, final String two) {
        final MultiReadLayers layers = new MultiReadLayers(
            Arrays.asList(new FakeLayers(one), new FakeLayers(two))
        );
        // "Nobody has it" is only known when everybody answered.
        Assertions.assertThrows(
            CompletionException.class,
            () -> layers.get(new Digest.FromString("123")).toCompletableFuture().join()
        );
    }
}
