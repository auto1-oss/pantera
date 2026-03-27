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
package com.auto1.pantera;

import com.auto1.pantera.test.ContainerResultMatcher;
import com.auto1.pantera.test.TestDeployment;
import java.io.IOException;
import java.util.Map;
import org.cactoos.map.MapEntry;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Test for {@link VertxMain}.
 */
final class VertxMainITCase {

    /**
     * Test deployments.
     */
    @RegisterExtension
    final TestDeployment deployment = new TestDeployment(
        Map.ofEntries(
            new MapEntry<>(
                "pantera-config-key-present",
                () -> TestDeployment.PanteraContainer.defaultDefinition()
                    .withConfig("pantera-repo-config-key.yaml")
                    .withRepoConfig("binary/bin.yml", "my_configs/my-file")
            ),
            new MapEntry<>(
                "pantera-invalid-repo-config",
                () -> TestDeployment.PanteraContainer.defaultDefinition()
                    .withRepoConfig("invalid_repo.yaml", "my-file")
            )
        ),
        () -> new TestDeployment.ClientContainer("alpine:3.11")
            .withWorkingDirectory("/w")
    );

    @BeforeEach
    void setUp() throws IOException {
        this.deployment.assertExec(
            "Failed to install deps",
            new ContainerResultMatcher(),
            "apk", "add", "--no-cache", "curl"
        );
    }

    @Test
    void startsWhenNotValidRepoConfigsArePresent() throws IOException {
        this.deployment.putBinaryToPantera(
            "pantera-invalid-repo-config",
            "Hello world".getBytes(),
            "/var/pantera/data/my-file/item.txt"
        );
        this.deployment.assertExec(
            "Pantera started and responding 200",
            new ContainerResultMatcher(
                ContainerResultMatcher.SUCCESS,
                new StringContains("HTTP/1.1 404 Not Found")
            ),
            "curl", "-i", "-X", "GET",
            "http://pantera-invalid-repo-config:8080/my-file/item.txt"
        );
    }

    @Test
    void worksWhenRepoConfigsKeyIsPresent() throws IOException {
        this.deployment.putBinaryToPantera(
            "pantera-config-key-present",
            "Hello world".getBytes(),
            "/var/pantera/data/my-file/item.txt"
        );
        this.deployment.assertExec(
            "Pantera isn't started or not responding 200",
            new ContainerResultMatcher(
                ContainerResultMatcher.SUCCESS,
                new StringContains("HTTP/1.1 200 OK")
            ),
            "curl", "-i", "-X", "GET",
            "http://pantera-config-key-present:8080/my-file/item.txt"
        );
    }

}
