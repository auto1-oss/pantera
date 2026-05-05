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
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsEmptyCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link NpmCompositeParser} — Phase 13 routing of npm cache-write
 * events to the right sub-parser based on file shape.
 *
 * @since 2.2.0
 */
final class NpmCompositeParserTest {

    @Test
    void routesGzipMagicToTarballParser() throws Exception {
        final Path tarball = tarballFixture();
        final NpmMetadataLookup lookup = (name, range) -> Optional.of(range);
        final List<Coordinate> deps = new NpmCompositeParser(lookup).parse(tarball);
        // simple-1.0.0.tgz fixture has runtime deps (left-pad, lodash) — see
        // NpmPackageParserTest for the canonical assertions. Here we only need
        // to confirm we're routing into the tarball parser (deps come back
        // as NPM ecosystem with versions, not packument coords).
        MatcherAssert.assertThat(deps, Matchers.not(IsEmptyCollection.empty()));
        for (final Coordinate coord : deps) {
            MatcherAssert.assertThat(
                "tarball parser emits NPM (versioned) coords",
                coord.ecosystem(), Matchers.is(Coordinate.Ecosystem.NPM)
            );
        }
    }

    @Test
    void routesJsonToPackumentParser() throws Exception {
        final Path packument = packumentFixture();
        // Lookup is irrelevant for the packument parser — it doesn't resolve
        // ranges. Pass an always-empty lookup to make sure the packument
        // parser is the one being invoked (the tarball parser would return
        // empty when the lookup never resolves).
        final NpmMetadataLookup lookup = (name, range) -> Optional.empty();
        final List<Coordinate> deps = new NpmCompositeParser(lookup).parse(packument);
        MatcherAssert.assertThat(deps, Matchers.not(IsEmptyCollection.empty()));
        for (final Coordinate coord : deps) {
            MatcherAssert.assertThat(
                "packument parser emits NPM_PACKUMENT coords",
                coord.ecosystem(), Matchers.is(Coordinate.Ecosystem.NPM_PACKUMENT)
            );
        }
    }

    @Test
    void emptyFileReturnsEmptyList(@TempDir final Path tmp) throws Exception {
        // Empty file: first byte read returns -1 (EOF), so isGzip() returns
        // false and dispatch goes to the packument parser, which then fails
        // JSON parse and returns empty.
        final Path empty = tmp.resolve("empty.json");
        Files.write(empty, new byte[0]);
        MatcherAssert.assertThat(
            new NpmCompositeParser((n, r) -> Optional.empty()).parse(empty),
            IsEmptyCollection.empty()
        );
    }

    @Test
    void missingFileReturnsEmptyList() {
        MatcherAssert.assertThat(
            new NpmCompositeParser((n, r) -> Optional.empty())
                .parse(Paths.get("/does/not/exist.bin")),
            IsEmptyCollection.empty()
        );
    }

    private static Path tarballFixture() throws Exception {
        final URL res = NpmCompositeParserTest.class.getClassLoader()
            .getResource("prefetch/npm/simple-1.0.0.tgz");
        MatcherAssert.assertThat(
            "tarball fixture missing", res, Matchers.notNullValue()
        );
        return Paths.get(res.toURI());
    }

    private static Path packumentFixture() throws Exception {
        final URL res = NpmCompositeParserTest.class.getClassLoader()
            .getResource("prefetch/npm/packument-simple.json");
        MatcherAssert.assertThat(
            "packument fixture missing", res, Matchers.notNullValue()
        );
        return Paths.get(res.toURI());
    }
}
