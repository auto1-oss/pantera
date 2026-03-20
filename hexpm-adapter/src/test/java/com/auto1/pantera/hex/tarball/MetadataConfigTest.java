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
package com.auto1.pantera.hex.tarball;

import com.auto1.pantera.hex.ResourceUtil;
import java.io.IOException;
import java.nio.file.Files;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link MetadataConfig}.
 * @since 0.1
 */
class MetadataConfigTest {
    /**
     * Metadata.config file.
     */
    private static MetadataConfig metadata;

    @BeforeAll
    static void setUp() throws IOException {
        MetadataConfigTest.metadata = new MetadataConfig(
            Files.readAllBytes(new ResourceUtil("metadata/metadata.config").asPath())
        );
    }

    @Test
    void readApp() {
        MatcherAssert.assertThat(
            MetadataConfigTest.metadata.app(),
            new StringContains("decimal")
        );
    }

    @Test
    void readVersion() {
        MatcherAssert.assertThat(
            MetadataConfigTest.metadata.version(),
            new StringContains("2.0.0")
        );
    }
}
