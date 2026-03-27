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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.rpm.Digest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.apache.commons.codec.digest.DigestUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link AstoChecksumAndSize}.
 * @since 1.9
 */
class AstoChecksumAndSizeTest {

    @Test
    void savesChecksumAndSize() {
        final Storage asto = new InMemoryStorage();
        final Charset charset = StandardCharsets.UTF_8;
        final String item = "storage_item";
        final byte[] bfirst = item.getBytes(charset);
        final BlockingStorage blsto = new BlockingStorage(asto);
        blsto.save(new Key.From(item), bfirst);
        final Digest dgst = Digest.SHA256;
        new AstoChecksumAndSize(asto, dgst).calculate(new Key.From(item))
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            new String(
                blsto.value(new Key.From(String.format("%s.%s", item, dgst.name()))), charset
            ),
            new IsEqual<>(String.format("%s %s", DigestUtils.sha256Hex(bfirst), bfirst.length))
        );
    }

}
