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
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.docker.misc.Pagination;
import com.google.common.base.Splitter;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import wtf.g4s8.hamcrest.json.JsonContains;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.JsonValueIs;

import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Tests for {@link AstoTags}.
 */
final class AstoTagsTest {

    /**
     * Repository name used in tests.
     */
    private String name;

    /**
     * Tag keys.
     */
    private Collection<Key> keys;

    @BeforeEach
    void setUp() {
        this.name = "test";
        this.keys = Stream.of("foo/1.0", "foo/0.1-rc", "foo/latest", "foo/0.1")
            .map(Key.From::new)
            .collect(Collectors.toList());
    }

    @ParameterizedTest
    @CsvSource({
        ",,0.1;0.1-rc;1.0;latest",
        "0.1-rc,,1.0;latest",
        "xyz,,''",
        ",2,0.1;0.1-rc",
        "0.1,2,0.1-rc;1.0"
    })
    void shouldSupportPaging(final String from, final Integer limit, final String result) {
        MatcherAssert.assertThat(
            new AstoTags(
                this.name,
                new Key.From("foo"),
                this.keys,
                Pagination.from(from, limit)
            ).json().asJsonObject(),
            new JsonHas(
                "tags",
                new JsonContains(
                    StreamSupport.stream(
                        Splitter.on(";").omitEmptyStrings().split(result).spliterator(),
                        false
                    ).map(JsonValueIs::new).collect(Collectors.toList())
                )
            )
        );
    }
}
