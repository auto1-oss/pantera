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
package com.auto1.pantera.rpm;

import com.auto1.pantera.test.ContainerResultMatcher;
import com.auto1.pantera.test.TestDeployment;
import org.cactoos.list.ListOf;
import org.hamcrest.core.IsEqual;
import org.hamcrest.text.StringContainsInOrder;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.BindMode;

/**
 * IT case for RPM repository.
 * @since 0.12
 */
@DisabledOnOs(OS.WINDOWS)
public final class RpmITCase {

    /**
     * Test deployments.
     */
    @RegisterExtension
    final TestDeployment containers = new TestDeployment(
        () -> TestDeployment.PanteraContainer.defaultDefinition()
            .withRepoConfig("rpm/my-rpm.yml", "my-rpm")
            .withRepoConfig("rpm/my-rpm-port.yml", "my-rpm-port")
            .withExposedPorts(8081),
        () -> new TestDeployment.ClientContainer("pantera/rpm-tests-fedora:1.0")
            .withClasspathResourceMapping(
                "rpm/time-1.7-45.el7.x86_64.rpm", "/w/time-1.7-45.el7.x86_64.rpm",
                BindMode.READ_ONLY
            )
    );

    @ParameterizedTest
    @CsvSource({
        "8080,my-rpm",
        "8081,my-rpm-port"
    })
    void uploadsAndInstallsThePackage(final String port, final String repo) throws Exception {
        this.containers.putBinaryToClient(
            String.join(
                "\n", "[example]",
                "name=Example Repository",
                String.format("baseurl=http://pantera:%s/%s", port, repo),
                "enabled=1",
                "gpgcheck=0"
            ).getBytes(),
            "/etc/yum.repos.d/example.repo"
        );
        this.containers.assertExec(
            "Failed to upload rpm package",
            new ContainerResultMatcher(),
            "curl",
            String.format("http://pantera:%s/%s/time-1.7-45.el7.x86_64.rpm", port, repo),
            "--upload-file", "/w/time-1.7-45.el7.x86_64.rpm"
        );
        Thread.sleep(2000);
        this.containers.assertExec(
            "Failed to install time package",
            new ContainerResultMatcher(
                new IsEqual<>(0),
                new StringContainsInOrder(new ListOf<>("time-1.7-45.el7.x86_64", "Complete!"))
            ),
            "dnf", "-y", "repository-packages", "example", "install"
        );
    }
}
