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
package com.auto1.pantera.http.log;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link UpstreamUrl}.
 *
 * @since 2.2.14
 */
final class UpstreamUrlTest {

    @ParameterizedTest
    @CsvSource({
        "https://repository.jboss.org/,/software/amazon/awssdk/a.pom,https://repository.jboss.org/software/amazon/awssdk/a.pom",
        "https://repository.jboss.org,/software/amazon/awssdk/a.pom,https://repository.jboss.org/software/amazon/awssdk/a.pom",
        "https://repo1.maven.org/maven2/,/org/acme/a.jar,https://repo1.maven.org/maven2/org/acme/a.jar",
        "https://repo1.maven.org/maven2,org/acme/a.jar,https://repo1.maven.org/maven2/org/acme/a.jar",
        "http://upstream//,//a,http://upstream/a"
    })
    void joinsRemoteAndPathWithOneSlash(
        final String remote, final String path, final String expected
    ) {
        MatcherAssert.assertThat(new UpstreamUrl(remote).of(path), new IsEqual<>(expected));
    }
}
