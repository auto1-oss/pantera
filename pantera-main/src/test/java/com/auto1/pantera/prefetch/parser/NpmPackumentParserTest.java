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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsEmptyCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link NpmPackumentParser} (Phase 13).
 *
 * @since 2.2.0
 */
final class NpmPackumentParserTest {

    @Test
    void emitsDirectDepPackumentCoordsForLatest() throws Exception {
        final Path packument = fixture("packument-simple.json");
        final List<Coordinate> deps = new NpmPackumentParser().parse(packument);
        // Latest is 4.21.0 → deps: accepts, body-parser, cookie. devDeps and
        // the older 4.20.0 entry must be ignored.
        MatcherAssert.assertThat(deps, Matchers.hasSize(3));
        MatcherAssert.assertThat(
            deps,
            Matchers.containsInAnyOrder(
                Coordinate.npmPackument("accepts"),
                Coordinate.npmPackument("body-parser"),
                Coordinate.npmPackument("cookie")
            )
        );
    }

    @Test
    void coordsArePackumentEcosystem() throws Exception {
        final Path packument = fixture("packument-simple.json");
        final List<Coordinate> deps = new NpmPackumentParser().parse(packument);
        MatcherAssert.assertThat(deps, Matchers.not(IsEmptyCollection.empty()));
        for (final Coordinate coord : deps) {
            MatcherAssert.assertThat(
                "packument coords must use NPM_PACKUMENT ecosystem",
                coord.ecosystem(), Matchers.is(Coordinate.Ecosystem.NPM_PACKUMENT)
            );
            MatcherAssert.assertThat(
                "packument coord version must be empty",
                coord.version(), Matchers.is("")
            );
        }
    }

    @Test
    void packumentCoordPathRendersBareName() throws Exception {
        final Path packument = fixture("packument-simple.json");
        final List<Coordinate> deps = new NpmPackumentParser().parse(packument);
        // Path of any unscoped packument coord should be just the name.
        final Coordinate accepts = deps.stream()
            .filter(c -> "accepts".equals(c.name()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(accepts.path(), Matchers.is("accepts"));
    }

    @Test
    void fallsBackToHighestStableWhenDistTagsMissing() throws Exception {
        final Path packument = fixture("packument-no-disttags.json");
        final List<Coordinate> deps = new NpmPackumentParser().parse(packument);
        // 2.5.3 is highest STABLE (3.0.0-beta.1 is prerelease → skipped).
        // Expected dep: new-dep (from 2.5.3).
        MatcherAssert.assertThat(deps, Matchers.hasSize(1));
        MatcherAssert.assertThat(
            deps.get(0), Matchers.is(Coordinate.npmPackument("new-dep"))
        );
    }

    @Test
    void scopedDepEmitsScopedPackumentCoord(@TempDir final Path tmp) throws Exception {
        final String json = "{\n"
            + "  \"name\": \"host\",\n"
            + "  \"dist-tags\": {\"latest\": \"1.0.0\"},\n"
            + "  \"versions\": {\n"
            + "    \"1.0.0\": {\n"
            + "      \"name\": \"host\",\n"
            + "      \"version\": \"1.0.0\",\n"
            + "      \"dependencies\": {\n"
            + "        \"@types/node\": \"^20.0.0\",\n"
            + "        \"plain\": \"1.0.0\"\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "}";
        final Path packument = tmp.resolve("scoped.json");
        Files.writeString(packument, json);
        final List<Coordinate> deps = new NpmPackumentParser().parse(packument);
        MatcherAssert.assertThat(deps, Matchers.hasSize(2));
        final Coordinate scoped = deps.stream()
            .filter(c -> "node".equals(c.name()))
            .findFirst().orElseThrow();
        MatcherAssert.assertThat(scoped.groupOrNamespace(), Matchers.is("@types"));
        MatcherAssert.assertThat(scoped.path(), Matchers.is("@types/node"));
    }

    @Test
    void emptyDepsReturnsEmptyList(@TempDir final Path tmp) throws Exception {
        final String json = "{\n"
            + "  \"name\": \"no-deps\",\n"
            + "  \"dist-tags\": {\"latest\": \"1.0.0\"},\n"
            + "  \"versions\": {\n"
            + "    \"1.0.0\": {\"name\": \"no-deps\", \"version\": \"1.0.0\"}\n"
            + "  }\n"
            + "}";
        final Path packument = tmp.resolve("nodeps.json");
        Files.writeString(packument, json);
        MatcherAssert.assertThat(
            new NpmPackumentParser().parse(packument),
            IsEmptyCollection.empty()
        );
    }

    @Test
    void malformedJsonReturnsEmptyList(@TempDir final Path tmp) throws Exception {
        final Path packument = tmp.resolve("malformed.json");
        Files.writeString(packument, "{not-valid-json");
        MatcherAssert.assertThat(
            new NpmPackumentParser().parse(packument),
            IsEmptyCollection.empty()
        );
    }

    @Test
    void missingFileReturnsEmptyList() {
        MatcherAssert.assertThat(
            new NpmPackumentParser().parse(Paths.get("/does/not/exist.json")),
            IsEmptyCollection.empty()
        );
    }

    private static Path fixture(final String name) throws Exception {
        final URL res = NpmPackumentParserTest.class.getClassLoader()
            .getResource("prefetch/npm/" + name);
        MatcherAssert.assertThat(
            "fixture missing on classpath: " + name, res, Matchers.notNullValue()
        );
        return Paths.get(res.toURI());
    }
}
