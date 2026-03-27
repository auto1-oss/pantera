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
package com.auto1.pantera.composer;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.test.TestResource;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JsonPackage}.
 *
 * @since 0.1
 */
class JsonPackageTest {

    /**
     * Example package read from 'minimal-package.json'.
     */
    private Package pack;

    @BeforeEach
    void init()  {
        this.pack = new JsonPackage(new TestResource("minimal-package.json").asBytes());
    }

    @Test
    void shouldExtractName() {
        MatcherAssert.assertThat(
            this.pack.name()
                .toCompletableFuture().join()
                .key().string(),
            new IsEqual<>("p2/vendor/package.json")
        );
    }

    @Test
    void shouldExtractVersion() {
        MatcherAssert.assertThat(
            this.pack.version(Optional.empty())
                .toCompletableFuture().join()
                .get(),
            new IsEqual<>("1.2.0")
        );
    }
}
