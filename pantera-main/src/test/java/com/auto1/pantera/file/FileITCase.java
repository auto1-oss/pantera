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
package com.auto1.pantera.file;

import com.auto1.pantera.test.ContainerResultMatcher;
import com.auto1.pantera.test.TestDeployment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.hamcrest.Matchers;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Integration test for binary repo.
 * @since 0.18
 */
final class FileITCase {

    /**
     * Deployment for tests.
     */
    @RegisterExtension
    final TestDeployment deployment = new TestDeployment(
        () -> TestDeployment.PanteraContainer.defaultDefinition()
            .withRepoConfig("binary/bin.yml", "bin")
            .withRepoConfig("binary/bin-port.yml", "bin-port")
            .withExposedPorts(8081),
        () -> new TestDeployment.ClientContainer("pantera/file-tests:1.0")
            .withWorkingDirectory("/w")
    );

    @ParameterizedTest
    @CsvSource({
        "8080,bin",
        "8081,bin-port"
    })
    void canDownload(final String port, final String repo) throws Exception {
        final byte[] target = new byte[]{0, 1, 2, 3};
        this.deployment.putBinaryToPantera(
            target, String.format("/var/pantera/data/%s/target", repo)
        );
        this.deployment.assertExec(
            "Failed to download artifact",
            new ContainerResultMatcher(ContainerResultMatcher.SUCCESS),
            "curl", "-X", "GET", String.format("http://pantera:%s/%s/target", port, repo)
        );
    }

    @ParameterizedTest
    @CsvSource({
        "8080,bin",
        "8081,bin-port"
    })
    void canUpload(final String port, final String repo) throws Exception {
        this.deployment.assertExec(
            "Failed to upload",
            new ContainerResultMatcher(ContainerResultMatcher.SUCCESS),
            "curl", "-X", "PUT", "--data-binary", "123",
            String.format("http://pantera:%s/%s/target", port, repo)
        );
        this.deployment.assertPanteraContent(
            "Bad content after upload",
            String.format("/var/pantera/data/%s/target", repo),
            Matchers.equalTo("123".getBytes())
        );
    }

    @Test
    void repoWithPortIsNotAvailableByDefaultPort() throws IOException {
        this.deployment.assertExec(
            "Failed to upload",
            new ContainerResultMatcher(
                ContainerResultMatcher.SUCCESS, new StringContains("HTTP/1.1 404 Not Found")
            ),
            "curl", "-i", "-X", "PUT", "--data-binary", "123", "http://pantera:8080/bin-port/target"
        );
        this.deployment.putBinaryToPantera(
            "target".getBytes(StandardCharsets.UTF_8), "/var/pantera/data/bin-port/target"
        );
        this.deployment.assertExec(
            "Failed to download artifact",
            new ContainerResultMatcher(
                ContainerResultMatcher.SUCCESS, new StringContains("not found")
            ),
            "curl", "-X", "GET", "http://pantera:8080/bin-port/target"
        );
    }
}
