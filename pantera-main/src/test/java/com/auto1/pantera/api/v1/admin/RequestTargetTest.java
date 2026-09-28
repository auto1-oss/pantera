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
package com.auto1.pantera.api.v1.admin;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestTarget.Parser}.
 *
 * @since 2.2.9
 */
final class RequestTargetTest {

    /**
     * Parser over a small topology.
     */
    private final RequestTarget.Parser parser = new RequestTarget.Parser(
        new FakeTopology().proxy("npm_proxy", "npm-proxy").group("npm_group", "npm-group", "npm_proxy")
    );

    @Test
    void resolvesAFullClientUrlWithPrefixAndApiSegment() {
        final RequestTarget target = this.parser
            .parse("http://localhost:8081/test_prefix/api/npm_group/@scope%2Fpkg/-/pkg-1.0.0.tgz")
            .orElseThrow();
        MatcherAssert.assertThat("repository", target.repo().name(), new IsEqual<>("npm_group"));
        MatcherAssert.assertThat(
            "raw path keeps the encoding",
            target.rawPath(), new IsEqual<>("/@scope%2Fpkg/-/pkg-1.0.0.tgz")
        );
        MatcherAssert.assertThat(
            "decoded path",
            target.path(), new IsEqual<>("/@scope/pkg/-/pkg-1.0.0.tgz")
        );
    }

    @Test
    void resolvesARepoRelativePath() {
        final RequestTarget target = this.parser.parse("/npm_proxy/lodash").orElseThrow();
        MatcherAssert.assertThat("repository", target.repo().name(), new IsEqual<>("npm_proxy"));
        MatcherAssert.assertThat("path", target.path(), new IsEqual<>("/lodash"));
    }

    @Test
    void keepsTheTrailingSlashOfAnIndexPath() {
        MatcherAssert.assertThat(
            new RequestTarget.Parser(new FakeTopology().proxy("pypi_proxy", "pypi-proxy"))
                .parse("pypi_proxy/simple/requests/").orElseThrow().path(),
            new IsEqual<>("/simple/requests/")
        );
    }

    @Test
    void reportsNoTargetForAnUnknownRepository() {
        MatcherAssert.assertThat(
            this.parser.parse("/nope/lodash").isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void rejectsAnEmptyUrl() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> this.parser.parse(" "));
    }
}
