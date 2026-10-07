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
 * The artifact API refuses paths inside the storage lock namespace the same
 * way it refuses traversal: before any storage call.
 */
final class ArtifactHandlerReservedPathTest {

    @ParameterizedTest
    @CsvSource({
        "/.pantera-locks/com/acme/lib,true",
        ".pantera-locks,true",
        "/com/acme/.pantera-locks/x/uuid,true",
        "/com/acme/lib/1.0/lib-1.0.jar,false",
        "/pantera-locks/x,false",
        "/.pantera-locks-not/x,false",
        "/,false"
    })
    void recognisesTheLockNamespace(final String path, final boolean reserved) {
        MatcherAssert.assertThat(ArtifactHandler.reservedPath(path), new IsEqual<>(reserved));
    }
}
