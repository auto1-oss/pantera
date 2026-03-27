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

import com.google.common.base.Splitter;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import wtf.g4s8.hamcrest.json.JsonContains;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.JsonValueIs;
import wtf.g4s8.hamcrest.json.StringIsJson;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Tests for {@link TagsPage}.
 */
final class TagsPageTest {

    @ParameterizedTest
    @CsvSource({
        ",,1;2;3;4;5",
        "2,,3;4;5",
        "7,,''",
        ",2,1;2",
        "2,2,3;4"
    })
    void shouldSupportPaging(final String from, final Integer limit, final String result) {
        final String repo = "my-alpine";
        MatcherAssert.assertThat(
            new TagsPage(
                repo,
                Arrays.asList("3", "1", "2", "4", "5", "4"),
                Pagination.from(from, limit)
            ).json().asString(),
            new StringIsJson.Object(
                Matchers.allOf(
                    new JsonHas("name", new JsonValueIs(repo)),
                    new JsonHas(
                        "tags",
                        new JsonContains(
                            StreamSupport.stream(
                                Splitter.on(";").omitEmptyStrings().split(result).spliterator(),
                                false
                            ).map(JsonValueIs::new).collect(Collectors.toList())
                        )
                    )
                )
            )
        );
    }
}
