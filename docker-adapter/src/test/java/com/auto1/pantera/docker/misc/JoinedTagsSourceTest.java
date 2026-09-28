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
import com.auto1.pantera.docker.fake.FullTagsManifests;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import wtf.g4s8.hamcrest.json.JsonContains;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.JsonValueIs;
import wtf.g4s8.hamcrest.json.StringIsJson;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests for {@link JoinedTagsSource}.
 */
final class JoinedTagsSourceTest {

    @Test
    void joinsTags() {
        final int limit = 3;
        final String name = "my-test";
        MatcherAssert.assertThat(
            new JoinedTagsSource(
                name,
                Stream.of(
                    "{\"tags\":[\"one\",\"two\"]}",
                    "{\"tags\":[\"one\",\"three\",\"four\"]}"
                ).map(
                    json -> new FullTagsManifests(() -> new Content.From(json.getBytes()))
                ).collect(Collectors.toList()),
                Pagination.from("four", limit)
            ).tags().thenCompose(
                tags -> tags.json().asStringFuture()
            ).toCompletableFuture().join(),
            new StringIsJson.Object(
                Matchers.allOf(
                    new JsonHas("name", new JsonValueIs(name)),
                    new JsonHas(
                        "tags",
                        new JsonContains(
                            new JsonValueIs("one"), new JsonValueIs("three"), new JsonValueIs("two")
                        )
                    )
                )
            )
        );
    }

    @Test
    void marksListingIncompleteWhenASourceFails() {
        MatcherAssert.assertThat(
            new JoinedTagsSource(
                "my-test",
                java.util.List.of(
                    new FullTagsManifests(() -> new Content.From("{\"tags\":[]}".getBytes())),
                    new com.auto1.pantera.docker.fake.FaultyGetManifests()
                ),
                Pagination.empty()
            ).tags().join().complete(),
            new org.hamcrest.core.IsEqual<>(false)
        );
    }

    @Test
    void marksListingCompleteWhenAllSourcesAnswer() {
        MatcherAssert.assertThat(
            new JoinedTagsSource(
                "my-test",
                java.util.List.of(
                    new FullTagsManifests(() -> new Content.From("{\"tags\":[]}".getBytes()))
                ),
                Pagination.empty()
            ).tags().join().complete(),
            new org.hamcrest.core.IsEqual<>(true)
        );
    }

    /**
     * T06: the joined listing knows the name when any source holds it.
     */
    @Test
    void knowsNameWhenAnySourceHoldsIt() {
        MatcherAssert.assertThat(
            new JoinedTagsSource(
                "my-test",
                java.util.List.of(
                    new FullTagsManifests(new UnknownTags()),
                    new FullTagsManifests(() -> new Content.From("{\"tags\":[]}".getBytes()))
                ),
                Pagination.from("zzz", 2)
            ).tags().join().known(),
            new org.hamcrest.core.IsEqual<>(true)
        );
    }

    /**
     * T06: the joined listing does not know the name when no source that
     * answered holds it (a failed source proves nothing either way).
     */
    @Test
    void doesNotKnowNameWhenNoSourceHoldsIt() {
        MatcherAssert.assertThat(
            new JoinedTagsSource(
                "my-test",
                java.util.List.of(
                    new FullTagsManifests(new UnknownTags()),
                    new com.auto1.pantera.docker.fake.FaultyGetManifests()
                ),
                Pagination.from("zzz", 2)
            ).tags().join().known(),
            new org.hamcrest.core.IsEqual<>(false)
        );
    }

    /**
     * Tags of a source that does not hold the name.
     */
    private static final class UnknownTags implements com.auto1.pantera.docker.Tags {
        @Override
        public Content json() {
            return new Content.From("{\"tags\":[]}".getBytes());
        }

        @Override
        public boolean known() {
            return false;
        }
    }
}
