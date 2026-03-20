/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link RepoTypeNormalizer}.
 *
 * @since 1.20.13
 */
final class RepoTypeNormalizerTest {

    @ParameterizedTest
    @CsvSource({
        "docker-proxy,  docker",
        "npm-proxy,     npm",
        "maven-proxy,   maven",
        "go-proxy,      go",
        "maven,         maven",
        "docker,        docker",
        "file,          file",
        "go,            go"
    })
    void normalizesType(final String raw, final String expected) {
        MatcherAssert.assertThat(
            String.format("normalize('%s') should return '%s'", raw, expected),
            RepoTypeNormalizer.normalize(raw),
            Matchers.is(expected.trim())
        );
    }
}
