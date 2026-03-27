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
import java.util.Map;
import org.cactoos.map.MapEntry;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration test for files proxy.
 *
 * @since 0.11
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@DisabledOnOs(OS.WINDOWS)
final class FileProxyAuthIT {

    /**
     * Test deployments.
     */
    @RegisterExtension
    final TestDeployment containers = new TestDeployment(
        Map.ofEntries(
            new MapEntry<>(
                "pantera",
                () -> TestDeployment.PanteraContainer.defaultDefinition()
                    .withRepoConfig("binary/bin.yml", "my-bin")
                    .withUser("security/users/alice.yaml", "alice")
            ),
            new MapEntry<>(
                "pantera-proxy",
                () -> TestDeployment.PanteraContainer.defaultDefinition()
                    .withRepoConfig("binary/bin-proxy.yml", "my-bin-proxy")
                    .withRepoConfig("binary/bin-proxy-cache.yml", "my-bin-proxy-cache")
                    .withRepoConfig("binary/bin-proxy-port.yml", "my-bin-proxy-port")
                    .withExposedPorts(8081)
            )
        ),
        () -> new TestDeployment.ClientContainer("pantera/file-tests:1.0")
            .withWorkingDirectory("/w")
    );

    @ParameterizedTest
    @ValueSource(strings = {"8080/my-bin-proxy", "8081/my-bin-proxy-port"})
    void shouldGetFileFromOrigin(final String repo) throws Exception {
        final byte[] data = "Hello world!".getBytes();
        this.containers.putBinaryToPantera(
            "pantera", data,
            "/var/pantera/data/my-bin/foo/bar.txt"
        );
        this.containers.assertExec(
            "File was not downloaded",
            new ContainerResultMatcher(
                new IsEqual<>(0), new StringContains("HTTP/1.1 200 OK")
            ),
            "curl", "-i", "-X", "GET", String.format("http://pantera-proxy:%s/foo/bar.txt", repo)
        );
    }

    @Test
    void cachesDataWhenCacheIsSet() throws IOException {
        final byte[] data = "Hello world!".getBytes();
        this.containers.putBinaryToPantera(
            "pantera", data,
            "/var/pantera/data/my-bin/foo/bar.txt"
        );
        this.containers.assertExec(
            "File was not downloaded",
            new ContainerResultMatcher(
                new IsEqual<>(0), new StringContains("HTTP/1.1 200 OK")
            ),
            "curl", "-i", "-X", "GET", "http://pantera-proxy:8080/my-bin-proxy-cache/foo/bar.txt"
        );
        this.containers.assertPanteraContent(
            "pantera-proxy", "Proxy cached data",
            "/var/pantera/data/my-bin-proxy-cache/foo/bar.txt",
            new IsEqual<>(data)
        );
    }
}
