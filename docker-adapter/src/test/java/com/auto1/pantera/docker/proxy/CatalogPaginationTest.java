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
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.docker.misc.Pagination;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link Pagination}.
 */
class CatalogPaginationTest {

    @ParameterizedTest
    @CsvSource({
        ",0x7fffffff,/v2/_catalog",
        "some/image,0x7fffffff,/v2/_catalog?last=some/image",
        ",10,/v2/_catalog?n=10",
        "my-alpine,20,/v2/_catalog?n=20&last=my-alpine"
    })
    void shouldBuildPathString(String repo, int limit, String uri) {
        Pagination p = new Pagination(repo, limit);
        MatcherAssert.assertThat(
            URLDecoder.decode(p.uriWithPagination("/v2/_catalog"), StandardCharsets.UTF_8),
            Matchers.is(uri)
        );
    }
}
