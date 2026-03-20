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
package com.auto1.pantera.docker.asto;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * Test case for {@code AstoUploads.uploadKey}.
 */
public final class UploadKeyTest {

    @Test
    public void shouldBuildExpectedString() {
        final String name = "test";
        final String uuid = UUID.randomUUID().toString();
        MatcherAssert.assertThat(
            Layout.upload(name, uuid).string(),
            Matchers.equalTo(
                String.format("repositories/%s/_uploads/%s", name, uuid)
            )
        );
    }
}
