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
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsEmptyCollection;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link NpmPackageParser}.
 * @since 2.2.0
 */
class NpmPackageParserTest {

    @Test
    void extractsRuntimeDependenciesOnly() throws Exception {
        final Path tgz = fixture();
        final NpmMetadataLookup lookup = (name, range) -> {
            if ("left-pad".equals(name) && "1.3.0".equals(range)) {
                return Optional.of("1.3.0");
            }
            if ("lodash".equals(name) && "^4.17.0".equals(range)) {
                return Optional.of("4.17.21");
            }
            return Optional.empty();
        };
        final List<Coordinate> deps = new NpmPackageParser(lookup).parse(tgz);
        MatcherAssert.assertThat(deps, Matchers.hasSize(2));
        MatcherAssert.assertThat(
            deps,
            Matchers.containsInAnyOrder(
                Coordinate.npm("left-pad", "1.3.0"),
                Coordinate.npm("lodash", "4.17.21")
            )
        );
    }

    @Test
    void skipsDevDependencies() throws Exception {
        final Path tgz = fixture();
        // Identity lookup — resolves any range to itself, so jest WOULD be
        // included if devDependencies were read. It must NOT be.
        final NpmMetadataLookup lookup = (name, range) -> Optional.of(range);
        final List<Coordinate> deps = new NpmPackageParser(lookup).parse(tgz);
        MatcherAssert.assertThat(
            deps.toString(),
            Matchers.not(Matchers.containsString("jest"))
        );
    }

    @Test
    void skipsRangesNotResolvable() throws Exception {
        final Path tgz = fixture();
        final NpmMetadataLookup lookup = (name, range) -> Optional.empty();
        final List<Coordinate> deps = new NpmPackageParser(lookup).parse(tgz);
        MatcherAssert.assertThat(deps, new IsEmptyCollection<>());
    }

    private static Path fixture() throws Exception {
        final URL res = NpmPackageParserTest.class.getClassLoader()
            .getResource("prefetch/npm/simple-1.0.0.tgz");
        MatcherAssert.assertThat(
            "simple-1.0.0.tgz fixture missing on classpath", res, Matchers.notNullValue()
        );
        return Paths.get(res.toURI());
    }
}
