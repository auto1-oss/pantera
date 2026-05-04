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
package com.auto1.pantera.prefetch.parser;

import com.auto1.pantera.prefetch.Coordinate;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsEmptyCollection;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link MavenPomParser}.
 * @since 2.2.0
 */
class MavenPomParserTest {

    @Test
    void extractsRuntimeDependenciesOnly() throws Exception {
        final URL res = this.getClass().getClassLoader().getResource("prefetch/poms/simple.pom");
        MatcherAssert.assertThat("simple.pom fixture missing on classpath", res, Matchers.notNullValue());
        final Path pom = Paths.get(res.toURI());
        final List<Coordinate> deps = new MavenPomParser().parse(pom);
        MatcherAssert.assertThat(
            deps,
            Matchers.contains(
                Matchers.equalTo(Coordinate.maven("com.google.guava", "guava", "33.4.0-jre"))
            )
        );
    }

    @Test
    void returnsEmptyOnMalformedPom() {
        final Path missing = Paths.get("/this/path/does/not/exist.pom");
        final List<Coordinate> deps = new MavenPomParser().parse(missing);
        MatcherAssert.assertThat(deps, new IsEmptyCollection<>());
    }

    @Test
    void extractsTopLevelDepsAndIgnoresDependencyManagement() {
        final Path pom = Paths.get("src/test/resources/prefetch/poms/with-dep-mgmt.pom");
        final List<Coordinate> coords = new MavenPomParser().parse(pom);
        MatcherAssert.assertThat(coords, Matchers.hasSize(1));
        MatcherAssert.assertThat(coords, Matchers.contains(
            Coordinate.maven("com.google.guava", "guava", "33.4.0-jre")));
        // explicit negative assertion
        MatcherAssert.assertThat(coords.toString(), Matchers.not(Matchers.containsString("managed-thing")));
        MatcherAssert.assertThat(coords.toString(), Matchers.not(Matchers.containsString("spring-boot-dependencies")));
    }

    @Test
    void returnsEmptyOnMalformedXml() {
        final Path pom = Paths.get("src/test/resources/prefetch/poms/malformed.pom");
        final List<Coordinate> deps = new MavenPomParser().parse(pom);
        MatcherAssert.assertThat(deps, new IsEmptyCollection<>());
    }
}
