/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class MetadataHeuristicsTest {

    @Test
    void extractsMavenCoordinates() {
        final ArtifactMetadata metadata = MetadataHeuristics.extract(
            "maven",
            Path.of("com/example/app/1.0/app-1.0.jar"),
            Path.of("app-1.0.jar")
        );
        Assertions.assertEquals("app", metadata.artifact().orElseThrow());
        Assertions.assertEquals("1.0", metadata.version().orElseThrow());
    }

    @Test
    void extractsNpmCoordinates() {
        final ArtifactMetadata metadata = MetadataHeuristics.extract(
            "npm",
            Path.of("@scope/pkg/-/pkg-2.1.0.tgz"),
            Path.of("pkg-2.1.0.tgz")
        );
        Assertions.assertEquals("pkg", metadata.artifact().orElseThrow());
        Assertions.assertEquals("2.1.0", metadata.version().orElseThrow());
    }
}
