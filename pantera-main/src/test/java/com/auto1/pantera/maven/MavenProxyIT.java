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
package com.auto1.pantera.maven;

import com.auto1.pantera.test.ContainerResultMatcher;
import com.auto1.pantera.test.TestDeployment;
import org.hamcrest.core.IsAnything;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Integration test for {@link com.auto1.pantera.maven.http.MavenProxySlice}.
 *
 * @since 0.11
 */
@DisabledOnOs(OS.WINDOWS)
final class MavenProxyIT {

    /**
     * Test deployments.
     */
    @RegisterExtension
    final TestDeployment containers = new TestDeployment(
        () -> TestDeployment.PanteraContainer.defaultDefinition()
            .withRepoConfig("maven/maven-proxy.yml", "my-maven")
            .withRepoConfig("maven/maven-proxy-port.yml", "my-maven-port")
            .withExposedPorts(8081),
        () -> new TestDeployment.ClientContainer("pantera/maven-tests:1.0")
            .withWorkingDirectory("/w")
    );

    @ParameterizedTest
    @CsvSource({
        "my-maven,maven/maven-settings.xml",
        "my-maven-port,maven/maven-settings-port.xml"
    })
    void shouldGetArtifactFromCentralAndSaveInCache(final String repo,
        final String settings) throws Exception {
        this.containers.putClasspathResourceToClient(settings, "/w/settings.xml");
        this.containers.assertExec(
            "Artifact wasn't downloaded",
            new ContainerResultMatcher(
                new IsEqual<>(0), new StringContains("BUILD SUCCESS")
            ),
            "mvn", "-s", "settings.xml", "dependency:get", "-Dartifact=args4j:args4j:2.32:jar"
        );
        this.containers.assertPanteraContent(
            "Artifact wasn't saved in cache",
            String.format("/var/pantera/data/%s/args4j/args4j/2.32/args4j-2.32.jar", repo),
            new IsAnything<>()
        );
    }

}
