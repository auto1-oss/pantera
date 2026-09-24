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
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Tests for {@link AstoCatalog}.
 */
final class AstoCatalogTest {

    /**
     * Tag keys.
     */
    private Collection<Key> keys;

    @BeforeEach
    void setUp() {
        this.keys = Stream.of("my-alpine", "test", "bar", "busybox")
            .map(name -> new Key.From("foo", name, "_manifests", "tags", "1", "current", "link"))
            .collect(Collectors.toList());
    }

    @ParameterizedTest
    @CsvSource({
        ",,bar;busybox;my-alpine;test",
        "busybox,,my-alpine;test",
        "xyz,,''",
        ",2,bar;busybox",
        "bar,2,busybox;my-alpine"
    })
    void shouldSupportPaging(final String from, final Integer limit, final String result) {
        MatcherAssert.assertThat(
            new AstoCatalog(
                new Key.From("foo"),
                this.keys,
                Pagination.from(from, limit)
            ).json().asJsonObject(),
            new JsonHas(
                "repositories",
                new JsonContains(
                    StreamSupport.stream(
                        Splitter.on(";").omitEmptyStrings().split(result).spliterator(),
                        false
                    ).map(JsonValueIs::new).collect(Collectors.toList())
                )
            )
        );
    }

    /**
     * R30: an image name is every path segment up to its {@code _manifests}
     * folder, not just the first one. Listing only the first segment named
     * images that do not exist ({@code team}) and hid the real ones.
     */
    @Test
    void listsNestedImageNamesInFull() {
        MatcherAssert.assertThat(
            new AstoCatalog(
                new Key.From("repositories"),
                Stream.of(
                    "repositories/team/nested/img/_manifests/tags/1/current/link",
                    "repositories/team/nested/img/_manifests/revisions/sha256/ab/link",
                    "repositories/ayd/test/_manifests/tags/2.0.0/current/link",
                    "repositories/ayd/test/_layers/sha256/cd/link",
                    "repositories/alpine/_manifests/tags/3/current/link"
                ).map(Key.From::new).collect(Collectors.toList()),
                Pagination.empty()
            ).json().asString(),
            new IsEqual<>("{\"repositories\":[\"alpine\",\"ayd/test\",\"team/nested/img\"]}")
        );
    }

    /**
     * A name with only an unfinished upload holds no image: it is not listed.
     */
    @Test
    void skipsNamesWithoutManifests() {
        MatcherAssert.assertThat(
            new AstoCatalog(
                new Key.From("repositories"),
                Stream.of(
                    "repositories/pending/img/_uploads/0f1e/data",
                    "repositories/done/_manifests/tags/1/current/link"
                ).map(Key.From::new).collect(Collectors.toList()),
                Pagination.empty()
            ).json().asString(),
            new IsEqual<>("{\"repositories\":[\"done\"]}")
        );
    }
}
