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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test for {@link ParsedCatalog}.
 *
 * @since 0.10
 */
class ParsedCatalogTest {

    @Test
    void parsesNames() {
        MatcherAssert.assertThat(
            new ParsedCatalog(
                () -> new Content.From("{\"repositories\":[\"one\",\"two\"]}".getBytes())
            ).repos().toCompletableFuture().join(),
            Matchers.contains("one", "two")
        );
    }

    @Test
    void parsesEmptyRepositories() {
        MatcherAssert.assertThat(
            new ParsedCatalog(
                () -> new Content.From("{\"repositories\":[]}".getBytes())
            ).repos().toCompletableFuture().join(),
            new IsEmptyCollection<>()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "[]", "123"})
    void failsParsingInvalid(final String json) {
        final ParsedCatalog catalog = new ParsedCatalog(() -> new Content.From(json.getBytes()));
        Assertions.assertThrows(
            Exception.class,
            () -> catalog.repos().toCompletableFuture().join()
        );
    }
}
