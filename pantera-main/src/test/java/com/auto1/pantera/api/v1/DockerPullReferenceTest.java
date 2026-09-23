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
package com.auto1.pantera.api.v1;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link DockerPullReference}.
 * @since 2.2.9
 */
final class DockerPullReferenceTest {

    @ParameterizedTest
    @CsvSource({
        "docker/registry/v2/repositories/qa/app/_manifests/tags/0.0.2/current/link,docker_local/qa/app:0.0.2",
        "docker/registry/v2/repositories/qa/app/_manifests/tags/0.0.2,docker_local/qa/app:0.0.2",
        "docker/registry/v2/repositories/qa/app/_manifests/revisions/sha256/abc/link,docker_local/qa/app@sha256:abc",
        "docker/registry/v2/repositories/qa/app,docker_local/qa/app:<tag>",
        "docker/registry/v2/blobs/sha256/ab/abc/data,docker_local/<image>:<tag>"
    })
    void includesTheRepositoryAndTheTag(final String path, final String expected) {
        // B80: the command left out the repository name and the tag.
        MatcherAssert.assertThat(
            new DockerPullReference("docker_local", path).value(), new IsEqual<>(expected)
        );
    }
}
