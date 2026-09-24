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

import com.auto1.pantera.http.cache.NegativeCacheKey;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@link PackageName}: one package, every producer's spelling.
 *
 * @since 2.2.9
 */
final class PackageNameTest {

    @Test
    void mavenCoordinateMatchesGroupAndProxySpellings() {
        final PackageName pkg = new PackageName("maven", "com.example:foo");
        MatcherAssert.assertThat(
            "the group resolver's dotted name matches",
            pkg.matches(new NegativeCacheKey("g", "maven-group", "com.example.foo", "1.0/foo-1.0.jar")),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the proxy's URL-form name matches",
            pkg.matches(new NegativeCacheKey("p", "maven-proxy", "com/example/foo", "1.0")),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a different artifact does not",
            pkg.matches(new NegativeCacheKey("p", "maven-proxy", "com/example/foobar", "1.0")),
            new IsEqual<>(false)
        );
    }

    @Test
    void pypiNameMatchesWheelAndNormalisedSpellings() {
        final PackageName pkg = new PackageName("pypi", "Typing_Extensions");
        MatcherAssert.assertThat(
            "the wheel filename spelling matches",
            pkg.matches(new NegativeCacheKey("p", "pypi-proxy", "typing_extensions", "4.0.0")),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the normalised group spelling matches",
            pkg.matches(new NegativeCacheKey("g", "pypi-group", "typing-extensions", "4.0.0/x.whl")),
            new IsEqual<>(true)
        );
    }

    @Test
    void repoTypeRestrictsTheFamily() {
        MatcherAssert.assertThat(
            new PackageName("npm", "lodash").matches(
                new NegativeCacheKey("p", "pypi-proxy", "lodash", "1.0")
            ),
            new IsEqual<>(false)
        );
    }

    @Test
    void noRepoTypeMatchesEveryFamily() {
        MatcherAssert.assertThat(
            new PackageName(null, "lodash").matches(
                new NegativeCacheKey("p", "npm-proxy", "lodash", "1.0")
            ),
            new IsEqual<>(true)
        );
    }

    @Test
    void storedFormsCoverTheCooldownSpellings() {
        MatcherAssert.assertThat(
            "maven: typed, dotted and path forms",
            new PackageName("maven", "com.example:foo").storedForms(),
            new IsEqual<>(new java.util.LinkedHashSet<>(
                java.util.List.of("com.example:foo", "com.example.foo", "com/example/foo")
            ))
        );
        MatcherAssert.assertThat(
            "pypi: typed and normalised forms",
            new PackageName("pypi", "My_Pkg").storedForms(),
            new IsEqual<>(new java.util.LinkedHashSet<>(java.util.List.of("My_Pkg", "my-pkg")))
        );
    }
}
