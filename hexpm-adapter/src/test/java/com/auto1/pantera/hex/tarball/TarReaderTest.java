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
import org.apache.commons.compress.utils.IOUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link TarReader}.
 * @since 0.1
 */
class TarReaderTest {
    @Test
    void readHexPackageName() throws IOException {
        final byte[] content = IOUtils.toByteArray(
            Files.newInputStream(new ResourceUtil("tarballs/decimal-2.0.0.tar").asPath())
        );
        MatcherAssert.assertThat(
            new TarReader(content)
                .readEntryContent("metadata.config")
                .isPresent(),
            new IsEqual<>(true)
        );
    }

}
