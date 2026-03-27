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
package com.auto1.pantera.rpm.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.apache.commons.io.IOUtils;

/**
 * Test for {@link AstoArchive}.
 * @since 1.9
 */
class AstoArchiveTest {

    @Test
    void gzipsItem() throws IOException {
        final Storage asto = new InMemoryStorage();
        final Key.From key = new Key.From("test");
        final String val = "some text";
        asto.save(key, new Content.From(val.getBytes())).join();
        new AstoArchive(asto).gzip(key).toCompletableFuture().join();
        MatcherAssert.assertThat(
            IOUtils.readLines(
                new GZIPInputStream(new ByteArrayInputStream(new BlockingStorage(asto).value(key))),
                StandardCharsets.UTF_8
            ),
            Matchers.contains(val)
        );
    }

}
