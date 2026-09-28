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
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.asto.Content;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;

/**
 * Test for {@link ParsedTags}.
 */
class ParsedTagsTest {

    @Test
    void parsesTags() {
        MatcherAssert.assertThat(
            new ArrayList<>(new ParsedTags(
                () -> new Content.From("{\"tags\":[\"one\",\"two\"]}".getBytes())
            ).tags().toCompletableFuture().join()),
            Matchers.contains("one", "two")
        );
    }

    @Test
    void parsesName() {
        MatcherAssert.assertThat(
            new ParsedTags(
                () -> new Content.From("{\"name\":\"foo\"}".getBytes())
            ).repo().toCompletableFuture().join(),
            new IsEqual<>("foo")
        );
    }

    @Test
    void parsesEmptyTags() {
        MatcherAssert.assertThat(
            new ParsedTags(
                () -> new Content.From("{\"tags\":[]}".getBytes())
            ).tags().toCompletableFuture().join(),
            new IsEmptyCollection<>()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "[]", "123", "{\"name\":\"foo\"}"})
    void failsParsingTagsFromInvalid(final String json) {
        final ParsedTags catalog = new ParsedTags(() -> new Content.From(json.getBytes()));
        Assertions.assertThrows(
            Exception.class,
            () -> catalog.tags().toCompletableFuture().join()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "[]", "123", "{\"tags\":[]}"})
    void failsParsingRepoFromInvalid(final String json) {
        final ParsedTags catalog = new ParsedTags(() -> new Content.From(json.getBytes()));
        Assertions.assertThrows(
            Exception.class,
            () -> catalog.repo().toCompletableFuture().join()
        );
    }
}
