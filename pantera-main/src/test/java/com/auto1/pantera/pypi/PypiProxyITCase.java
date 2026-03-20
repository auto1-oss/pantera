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
package com.auto1.pantera.pypi;

import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.test.ContainerResultMatcher;
import com.auto1.pantera.test.TestDeployment;
import java.util.Map;
import org.cactoos.map.MapEntry;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Test to pypi proxy.
 * @since 0.12
 * @todo #1500:30min Build and publish pantera/pantera-tests Docker image
 *  This test requires pantera/pantera-tests:1.0-SNAPSHOT image which is not available.
 *  Need to create Dockerfile and publish to Docker Hub or use local build.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@EnabledOnOs({OS.LINUX, OS.MAC})
@Disabled("Requires pantera/pantera-tests:1.0-SNAPSHOT Docker image")
public final class PypiProxyITCase {

    /**
     * Test deployments.
     */
    @RegisterExtension
    final TestDeployment containers = new TestDeployment(
        Map.ofEntries(
            new MapEntry<>(
                "pantera",
                () -> TestDeployment.PanteraContainer.defaultDefinition()
                    .withRepoConfig("pypi-proxy/pypi.yml", "my-pypi")
                    .withUser("security/users/alice.yaml", "alice")
            ),
            new MapEntry<>(
                "pantera-proxy",
                () -> TestDeployment.PanteraContainer.defaultDefinition()
                    .withRepoConfig("pypi-proxy/pypi-proxy.yml", "my-pypi-proxy")
            )
        ),
        () -> new TestDeployment.ClientContainer("pantera/pypi-tests:1.0")
            .withWorkingDirectory("/w")
    );

    @Test
    void installFromProxy() throws Exception {
        final byte[] data = new TestResource("pypi-repo/alarmtime-0.1.5.tar.gz").asBytes();
        this.containers.putBinaryToPantera(
            "pantera", data,
            "/var/pantera/data/my-pypi/alarmtime/alarmtime-0.1.5.tar.gz"
        );
        this.containers.assertExec(
            "Package was not installed",
            new ContainerResultMatcher(
                new IsEqual<>(0),
                Matchers.containsString("Successfully installed alarmtime-0.1.5")
            ),
            "pip", "install", "--no-deps", "--trusted-host", "pantera-proxy",
            "--index-url", "http://alice:123@pantera-proxy:8080/my-pypi-proxy/", "alarmtime"
        );
        this.containers.assertPanteraContent(
            "pantera-proxy",
            "/var/pantera/data/my-pypi-proxy/alarmtime/alarmtime-0.1.5.tar.gz",
            new IsEqual<>(data)
        );
    }

}
