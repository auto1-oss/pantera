/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.debian;

import com.auto1.pantera.test.ContainerResultMatcher;
import com.auto1.pantera.test.TestDeployment;
import org.cactoos.list.ListOf;
import org.hamcrest.core.IsEqual;
import org.hamcrest.text.StringContainsInOrder;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.BindMode;

/**
 * Debian integration test.
 * @since 0.15
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class DebianITCase {

    /**
     * Test deployments.
     */
    @RegisterExtension
    final TestDeployment containers = new TestDeployment(
        () -> TestDeployment.PanteraContainer.defaultDefinition()
            .withRepoConfig("debian/debian.yml", "my-debian")
            .withRepoConfig("debian/debian-port.yml", "my-debian-port")
            .withExposedPorts(8081),
        () -> new TestDeployment.ClientContainer("pantera/deb-tests:1.0")
            .withWorkingDirectory("/w")
            .withClasspathResourceMapping(
                "debian/aglfn_1.7-3_amd64.deb", "/w/aglfn_1.7-3_amd64.deb", BindMode.READ_ONLY
            )
    );

    @ParameterizedTest
    @CsvSource({
        "8080,my-debian",
        "8081,my-debian-port"
    })
    void pushAndInstallWorks(final String port, final String repo) throws Exception {
        this.containers.putBinaryToClient(
            String.format(
                "deb [trusted=yes] http://pantera:%s/%s %s main", port, repo, repo
            ).getBytes(),
            "/etc/apt/sources.list"
        );
        this.containers.assertExec(
            "Failed to upload deb package",
            new ContainerResultMatcher(),
            "curl", String.format("http://pantera:%s/%s/main/aglfn_1.7-3_amd64.deb", port, repo),
            "--upload-file", "/w/aglfn_1.7-3_amd64.deb"
        );
        this.containers.assertExec(
            "Apt-get update failed",
            new ContainerResultMatcher(),
            "apt-get", "update"
        );
        this.containers.assertExec(
            "Package was not downloaded and unpacked",
            new ContainerResultMatcher(
                new IsEqual<>(0),
                new StringContainsInOrder(new ListOf<>("Unpacking aglfn", "Setting up aglfn"))
            ),
            "apt-get", "install", "-y", "aglfn"
        );
    }
}
