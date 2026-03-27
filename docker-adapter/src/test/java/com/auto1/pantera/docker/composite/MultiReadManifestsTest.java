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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.fake.FakeManifests;
import com.auto1.pantera.docker.fake.FullTagsManifests;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.Pagination;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import wtf.g4s8.hamcrest.json.JsonContains;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.JsonValueIs;
import wtf.g4s8.hamcrest.json.StringIsJson;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tests for {@link MultiReadManifests}.
 */
final class MultiReadManifestsTest {

    @ParameterizedTest
    @CsvSource({
        "empty,empty,",
        "empty,full,two",
        "full,empty,one",
        "faulty,full,two",
        "full,faulty,one",
        "faulty,empty,",
        "empty,faulty,",
        "full,full,one"
    })
    void shouldReturnExpectedValue(
        final String origin,
        final String cache,
        final String expected
    ) {
        final MultiReadManifests manifests = new MultiReadManifests(
            "test",
            Arrays.asList(
                new FakeManifests(origin, "one"),
                new FakeManifests(cache, "two")
            )
        );
        MatcherAssert.assertThat(
            manifests.get(ManifestReference.from("ref"))
                .toCompletableFuture().join()
                .map(Manifest::digest)
                .map(Digest::hex),
            new IsEqual<>(Optional.ofNullable(expected))
        );
    }

    @Test
    void loadsTagsFromManifests() {
        final int limit = 3;
        final String name = "tags-test";
        MatcherAssert.assertThat(
            new MultiReadManifests(
                "tags-test",
                Arrays.asList(
                    new FullTagsManifests(
                        () -> new Content.From("{\"tags\":[\"one\",\"three\",\"four\"]}".getBytes())
                    ),
                    new FullTagsManifests(
                        () -> new Content.From("{\"tags\":[\"one\",\"two\"]}".getBytes())
                    )
                )
            ).tags(Pagination.from("four", limit)).thenCompose(
                tags -> tags.json().asStringFuture()
            ).join(),
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
}
