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
package com.auto1.pantera.conda;

import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.test.ContainerResultMatcher;
import com.auto1.pantera.test.TestDeployment;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Conda IT case.
 * @since 0.23
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
public final class CondaAuthITCase {

    /**
     * Test deployments.
     */
    @RegisterExtension
    final TestDeployment containers = new TestDeployment(
        () -> TestDeployment.PanteraContainer.defaultDefinition()
            .withUser("security/users/alice.yaml", "alice")
            .withRepoConfig("conda/conda-auth.yml", "my-conda"),
        () -> new TestDeployment.ClientContainer("pantera/conda-tests:1.0")
    );

    @Test
    void canUploadToPantera() throws IOException {
        this.containers.putClasspathResourceToClient("conda/condarc", "/w/.condarc");
        this.moveCondarc();
        this.containers.assertExec(
            "Failed to set anaconda upload url",
            new ContainerResultMatcher(),
            "anaconda", "config", "--set", "url", "http://pantera:8080/my-conda/", "-s"
        );
        this.containers.assertExec(
            "Failed to set anaconda upload flag",
            new ContainerResultMatcher(),
            "conda", "config", "--set", "anaconda_upload", "yes"
        );
        this.containers.assertExec(
            "Login was not successful",
            new ContainerResultMatcher(),
            "anaconda", "login", "--username", "alice", "--password", "123"
        );
        this.containers.assertExec(
            "Package was not uploaded successfully",
            new ContainerResultMatcher(
                new IsEqual<>(0),
                Matchers.allOf(
                    new StringContains("Using Anaconda API: http://pantera:8080/my-conda/"),
                    new StringContains("Uploading file \"alice/example-package/0.0.1/linux-64/example-package-0.0.1-0.tar.bz2\""),
                    new StringContains("Upload complete")
                )
            ),
            "conda", "build", "--output-folder", "/w/conda-out/", "/w/example-project/conda/"
        );
        this.containers.assertPanteraContent(
            "Package was not uploaded to pantera",
            "/var/pantera/data/my-conda/linux-64/example-package-0.0.1-0.tar.bz2",
            new IsNot<>(new IsNull<>())
        );
        this.containers.assertPanteraContent(
            "Package was not uploaded to pantera",
            "/var/pantera/data/my-conda/linux-64/repodata.json",
            new IsNot<>(new IsNull<>())
        );
    }

    @Test
    void canInstall() throws IOException {
        this.containers.putClasspathResourceToClient("conda/condarc-auth", "/w/.condarc");
        this.moveCondarc();
        this.containers.putBinaryToPantera(
            new TestResource("conda/packages.json").asBytes(),
            "/var/pantera/data/my-conda/linux-64/repodata.json"
        );
        this.containers.putBinaryToPantera(
            new TestResource("conda/snappy-1.1.3-0.tar.bz2").asBytes(),
            "/var/pantera/data/my-conda/linux-64/snappy-1.1.3-0.tar.bz2"
        );
        this.containers.assertExec(
            "Package snappy-1.1.3-0 was not installed successfully",
            new ContainerResultMatcher(
                new IsEqual<>(0),
                Matchers.allOf(
                    new StringContains("http://pantera:8080/my-conda"),
                    new StringContains("linux-64::snappy-1.1.3-0")
                )
            ),
            "conda", "install", "--verbose", "-y", "snappy"
        );
    }

    private void moveCondarc() throws IOException {
        this.containers.assertExec(
            "Failed to move condarc to /root", new ContainerResultMatcher(),
            "mv", "/w/.condarc", "/root/"
        );
    }
}
