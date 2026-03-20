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
package com.auto1.pantera.asto;

import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link MetaCommon}.
 * @since 1.11
 */
final class MetaCommonTest {

    @Test
    void readsSize() {
        final Storage storage = new InMemoryStorage();
        final Key key = new Key.From("key");
        final String data = "012004407";
        storage.save(
            key,
            new Content.From(data.getBytes(StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "Gets value size from metadata",
            new MetaCommon(storage.metadata(key).join()).size(),
            new IsEqual<>(Long.valueOf(data.length()))
        );
    }

}
