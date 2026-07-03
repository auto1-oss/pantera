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
import org.cactoos.map.MapEntry;
import org.cactoos.map.MapOf;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration test for maven proxy with multiple remotes.
 *
 * @since 0.12
 */
@DisabledOnOs(OS.WINDOWS)
final class MavenMultiProxyIT {

    /**
     * Test deployments.
     */
    @RegisterExtension
    final TestDeployment containers = new TestDeployment(
        new MapOf<>(
            new MapEntry<>(
                "pantera",
                () -> TestDeployment.PanteraContainer.defaultDefinition()
                    .withRepoConfig("maven/maven-multi-proxy.yml", "my-maven")
                    .withRepoConfig("maven/maven-multi-proxy-port.yml", "my-maven-port")
                    .withExposedPorts(8081)
            ),
            new MapEntry<>(
                "pantera-empty",
                () -> TestDeployment.PanteraContainer.defaultDefinition()
                    .withRepoConfig("maven/maven.yml", "empty-maven")
            ),
            new MapEntry<>(
                "pantera-origin",
                () -> TestDeployment.PanteraContainer.defaultDefinition()
                    .withRepoConfig("maven/maven.yml", "origin-maven")
            )
        ),
        () -> new TestDeployment.ClientContainer("pantera/maven-tests:1.0")
            .withWorkingDirectory("/w")
    );

    @ParameterizedTest
    @ValueSource(strings = {
        "maven/maven-settings.xml",
        "maven/maven-settings-port.xml"
    })
    void shouldGetDependency(final String settings) throws Exception {
        this.containers.putClasspathResourceToClient(settings, "/w/settings.xml");
        this.containers.putResourceToPantera(
            "pantera-origin",
            "com/auto1/pantera/helloworld/maven-metadata.xml",
            "/var/pantera/data/origin-maven/com/pantera/helloworld/maven-metadata.xml"
        );
        MavenITCase.getResourceFiles("com/auto1/pantera/helloworld/0.1")
            .stream().map(item -> String.join("/", "com/auto1/pantera/helloworld/0.1", item))
            .forEach(
                item -> this.containers.putResourceToPantera(
                    "pantera-origin", item, String.join("/", "/var/pantera/data/origin-maven", item)
                )
            );
        this.containers.assertExec(
            "Artifact wasn't downloaded",
            new ContainerResultMatcher(
                new IsEqual<>(0), new StringContains("BUILD SUCCESS")
            ),
            "mvn", "-s", "settings.xml", "dependency:get",
            "-Dartifact=com.auto1.pantera:helloworld:0.1:jar"
        );
    }

}
