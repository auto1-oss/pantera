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
package com.auto1.pantera.nuget;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.nuget.metadata.PackageId;
import com.auto1.pantera.nuget.metadata.Version;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Hash}.
 */
class HashTest {

    @Test
    void shouldSave() {
        final String id = "abc";
        final String version = "0.0.1";
        final Storage storage = new InMemoryStorage();
        new Hash(new Content.From("abc123".getBytes())).save(
            storage,
            new PackageIdentity(new PackageId(id), new Version(version))
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            storage.value(new Key.From(id, version, "abc.0.0.1.nupkg.sha512"))
                .join().asString(),
            Matchers.equalTo("xwtd2ev7b1HQnUEytxcMnSB1CnhS8AaA9lZY8DEOgQBW5nY8NMmgCw6UAHb1RJXBafwjAszrMSA5JxxDRpUH3A==")
        );
    }
}
