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

import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A {@link Content} carries the time it was last modified when its source
 * knows it, so that whoever serves the bytes does not ask the storage again.
 *
 * @since 2.2.12
 */
final class ContentLastModifiedTest {

    /**
     * Time the content was last modified.
     */
    private static final Instant MODIFIED = Instant.parse("2018-06-17T18:35:49Z");

    @Test
    void carriesTheTimeItWasBuiltWith() {
        MatcherAssert.assertThat(
            new Content.From(
                Optional.of(3L),
                Flowable.just(ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8))),
                Optional.of(ContentLastModifiedTest.MODIFIED)
            ).lastModified(),
            new IsEqual<>(Optional.of(ContentLastModifiedTest.MODIFIED))
        );
    }

    @Test
    void hasNoTimeUnlessGivenOne() {
        MatcherAssert.assertThat(
            new Content.From("abc".getBytes(StandardCharsets.UTF_8)).lastModified(),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void emptyContentHasNoTime() {
        MatcherAssert.assertThat(
            Content.EMPTY.lastModified(), new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void oneTimeContentKeepsTheTimeOfItsOriginal() {
        MatcherAssert.assertThat(
            new Content.OneTime(
                new Content.From(
                    Optional.of(3L),
                    Flowable.just(ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8))),
                    Optional.of(ContentLastModifiedTest.MODIFIED)
                )
            ).lastModified(),
            new IsEqual<>(Optional.of(ContentLastModifiedTest.MODIFIED))
        );
    }
}
