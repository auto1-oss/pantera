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
package com.auto1.pantera.importer;

import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Imported files are recorded under the coordinates a native publish of the
 * same format records (R41, R42).
 *
 * @since 2.2.9
 */
final class ImportCoordinatesTest {

    @ParameterizedTest
    @CsvSource({
        "maven,com/qa/rvimporter/rvart/1.0.0/rvart-1.0.0.jar,com.qa.rvimporter.rvart,1.0.0",
        "maven,/com/qa/rvimporter/rvart/1.0.0/rvart-1.0.0.pom,com.qa.rvimporter.rvart,1.0.0",
        "gradle,org/x/lib/2.1/lib-2.1.module,org.x.lib,2.1",
        "file,dir/a.txt,dir.a.txt,UNKNOWN",
        "file,x.txt,x.txt,UNKNOWN",
        "file,tools/app-1.2.3.tar.gz,tools.app-1.2.3.tar.gz,1.2.3",
        "npm,@scope/pkg/-/pkg-1.0.0.tgz,@scope/pkg,1.0.0",
        "npm,lodash/-/lodash-4.17.21.tgz,lodash,4.17.21",
        "go,github.com/!burnt!sushi/toml/@v/v1.3.2.zip,github.com/BurntSushi/toml,1.3.2",
        "pypi,My_Pkg/0.5.0/My_Pkg-0.5.0.tar.gz,my-pkg,0.5.0",
        "helm,chart-1.0.0.tgz,chart-1.0.0.tgz,UNKNOWN"
    })
    void recordsNativeCoordinates(
        final String type, final String path, final String name, final String version
    ) {
        MatcherAssert.assertThat(
            new ImportCoordinates(type, path).value()
                .map(coords -> coords.name() + '@' + coords.version()),
            new IsEqual<>(Optional.of(name + '@' + version))
        );
    }

    @ParameterizedTest
    @CsvSource({
        "maven,com/qa/rvart/maven-metadata.xml",
        "maven,com/qa/rvart/1.0.0/rvart-1.0.0.jar.sha1",
        "maven,com/qa/rvart/1.0.0/rvart-1.0.0-sources.jar",
        "maven,com/qa/rvart/1.0.0/rvart-1.0.0.jar.asc",
        "npm,lodash/meta.json",
        "go,example.com/mod/@v/v1.0.0.mod",
        "go,example.com/mod/@v/list"
    })
    void companionFilesAreNotRecordedAsArtifacts(final String type, final String path) {
        MatcherAssert.assertThat(
            new ImportCoordinates(type, path).value().isPresent(),
            new IsEqual<>(false)
        );
    }
}
