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
package com.auto1.pantera.http.filter;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.http.Headers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Test;
import org.llorllale.cactoos.matchers.IsTrue;

/**
 * Test for {@link GlobFilter}.
 */
class GlobFilterTest {
    /**
     * Request path.
     */
    private static final String PATH = "/mvnrepo/com/pantera/inner/0.1/inner-0.1.pom";

    @Test
    void checkInstanceTypeReturnedByLoader() {
        MatcherAssert.assertThat(
            new FilterFactoryLoader().newObject(
                "glob",
                Yaml.createYamlMappingBuilder()
                    .add(
                        "filter",
                        "**/*"
                    ).build()
            ),
            new IsInstanceOf(GlobFilter.class)
        );
    }

    @Test
    void anythingMatchesFilter() {
        final Filter filter = new FilterFactoryLoader().newObject(
            "glob",
            Yaml.createYamlMappingBuilder()
                .add(
                    "filter",
                    "**/*"
                ).build()
        );
        MatcherAssert.assertThat(
            filter.check(
                FiltersTestUtil.get(GlobFilterTest.PATH),
                Headers.EMPTY
            ),
            new IsTrue()
        );
    }

    @Test
    void packagePrefixFilter() {
        final Filter filter = new FilterFactoryLoader().newObject(
            "glob",
            Yaml.createYamlMappingBuilder()
                .add("filter", "**/com/pantera/**/*").build()
        );
        MatcherAssert.assertThat(
            filter.check(
                FiltersTestUtil.get(GlobFilterTest.PATH),
                Headers.EMPTY
            ),
            new IsTrue()
        );
    }

    @Test
    void matchByFileExtensionFilter() {
        final Filter filter = new FilterFactoryLoader().newObject(
            "glob",
            Yaml.createYamlMappingBuilder()
                .add("filter", "**/com/pantera/**/*.pom").build()
        );
        MatcherAssert.assertThat(
            filter.check(
                FiltersTestUtil.get(GlobFilterTest.PATH),
                Headers.EMPTY
            ),
            new IsTrue()
        );
        MatcherAssert.assertThat(
            filter.check(
                FiltersTestUtil.get(GlobFilterTest.PATH.replace(".pom", ".zip")),
                Headers.EMPTY
            ),
            IsNot.not(new IsTrue())
        );
    }
}
