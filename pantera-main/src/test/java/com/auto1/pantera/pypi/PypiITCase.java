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

import com.auto1.pantera.test.ContainerResultMatcher;
import com.auto1.pantera.test.TestDeployment;
import java.io.IOException;
import org.cactoos.list.ListOf;
import org.hamcrest.Matchers;
import org.hamcrest.text.StringContainsInOrder;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Integration tests for Pypi repository.
 *
 * @since 0.12
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
final class PypiITCase {
    /**
     * Test deployments.
     *
     */
    @RegisterExtension
    final TestDeployment containers = new TestDeployment(
        () -> TestDeployment.PanteraContainer.defaultDefinition()
            .withRepoConfig("pypi-repo/pypi.yml", "my-python")
            .withRepoConfig("pypi-repo/pypi-port.yml", "my-python-port")
            .withUser("security/users/alice.yaml", "alice")
            .withRole("security/roles/readers.yaml", "readers")
            .withExposedPorts(8081),

        () -> new TestDeployment.ClientContainer("pantera/pypi-tests:1.0")
            .withWorkingDirectory("/var/pantera")
    );

    @ParameterizedTest
    @CsvSource("8080,my-python")
    //"8081,my-python-port" todo https://github.com/pantera/pantera/issues/1350
    void installPythonPackage(final String port, final String repo) throws IOException {
        final String meta = "pypi-repo/example-pckg/dist/panteratestpkg-0.0.3.tar.gz";
        this.containers.putResourceToPantera(
            meta,
            String.format("/var/pantera/data/%s/panteratestpkg/panteratestpkg-0.0.3.tar.gz", repo)
        );
        this.containers.assertExec(
            "Failed to install package",
            new ContainerResultMatcher(
                Matchers.equalTo(0),
                new StringContainsInOrder(
                    new ListOf<>(
                        String.format("Looking in indexes: http://pantera:%s/%s", port, repo),
                        "Collecting panteratestpkg",
                        String.format(
                            "  Downloading http://pantera:%s/%s/panteratestpkg/%s",
                            port, repo, "panteratestpkg-0.0.3.tar.gz"
                        ),
                        "Building wheels for collected packages: panteratestpkg",
                        "  Building wheel for panteratestpkg (setup.py): started",
                        String.format(
                            "  Building wheel for panteratestpkg (setup.py): %s",
                            "finished with status 'done'"
                        ),
                        "Successfully built panteratestpkg",
                        "Installing collected packages: panteratestpkg",
                        "Successfully installed panteratestpkg-0.0.3"
                    )
                )
            ),
            "python", "-m", "pip", "install", "--trusted-host", "pantera", "--index-url",
            String.format("http://pantera:%s/%s", port, repo),
            "panteratestpkg"
        );
    }

    @ParameterizedTest
    @CsvSource("8080,my-python")
    //"8081,my-python-port" todo https://github.com/pantera/pantera/issues/1350
    void canUpload(final String port, final String repo) throws Exception {
        this.containers.assertExec(
            "Failed to upload",
            new ContainerResultMatcher(
                Matchers.is(0),
                new StringContainsInOrder(
                    new ListOf<>(
                        "Uploading panteratestpkg-0.0.3.tar.gz", "100%"
                    )
                )
            ),
            "python3", "-m", "twine", "upload", "--repository-url",
            String.format("http://pantera:%s/%s/", port, repo),
            "-u", "alice", "-p", "123",
            "/w/example-pckg/dist/panteratestpkg-0.0.3.tar.gz"
        );
        this.containers.assertPanteraContent(
            "Bad content after upload",
            String.format("/var/pantera/data/%s/panteratestpkg/panteratestpkg-0.0.3.tar.gz", repo),
            Matchers.not("123".getBytes())
        );
    }
}
