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

import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.test.ContainerResultMatcher;
import com.auto1.pantera.test.TestDeployment;
import java.util.Map;
import org.cactoos.map.MapEntry;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.BindMode;

/**
 * Integration test for {@link com.auto1.pantera.maven.http.MavenProxySlice}.
 *
 * @since 0.11
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@DisabledOnOs(OS.WINDOWS)
final class MavenProxyAuthIT {

    /**
     * Test deployments.
     */
    @RegisterExtension
    final TestDeployment containers = new TestDeployment(
        Map.ofEntries(
            new MapEntry<>(
                "pantera",
                () -> new TestDeployment.PanteraContainer().withConfig("pantera_with_policy.yaml")
                    .withRepoConfig("maven/maven-with-perms.yml", "my-maven")
                    .withUser("security/users/alice.yaml", "alice")
            ),
            new MapEntry<>(
                "pantera-proxy",
                () -> TestDeployment.PanteraContainer.defaultDefinition()
                    .withRepoConfig("maven/maven-proxy-pantera.yml", "my-maven-proxy")
            )
        ),
        () -> new TestDeployment.ClientContainer("pantera/maven-tests:1.0")
            .withWorkingDirectory("/w")
            .withClasspathResourceMapping(
                "maven/maven-settings-proxy.xml", "/w/settings.xml", BindMode.READ_ONLY
            )
    );

    @Test
    void shouldGetDependency() throws Exception {
        this.containers.putResourceToPantera(
            "pantera",
            "com/auto1/pantera/helloworld/maven-metadata.xml",
            "/var/pantera/data/my-maven/com/pantera/helloworld/maven-metadata.xml"
        );
        MavenITCase.getResourceFiles("com/auto1/pantera/helloworld/0.1")
            .stream().map(item -> String.join("/", "com/auto1/pantera/helloworld/0.1", item))
            .forEach(
                item -> this.containers.putResourceToPantera(
                    item, String.join("/", "/var/pantera/data/my-maven", item)
                )
            );
        this.containers.assertExec(
            "Helloworld was not installed",
            new ContainerResultMatcher(
                new IsEqual<>(0),
                new StringContains("BUILD SUCCESS")
            ),
            "mvn", "-s", "settings.xml",
            "dependency:get", "-Dartifact=com.auto1.pantera:helloworld:0.1:jar"
        );
        this.containers.assertPanteraContent(
            "pantera-proxy",
            "Artifact was not cached in proxy",
            "/var/pantera/data/my-maven-proxy/com/pantera/helloworld/0.1/helloworld-0.1.jar",
            new IsEqual<>(
                new TestResource("com/auto1/pantera/helloworld/0.1/helloworld-0.1.jar").asBytes()
            )
        );
    }

}
