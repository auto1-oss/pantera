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
package com.auto1.pantera.pypi.meta;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PypiIndexCleanup}.
 * @since 2.2.9
 */
final class PypiIndexCleanupTest {

    @Test
    void removesStaleIndexesAndOrphanSidecarsOnly() {
        final InMemoryStorage storage = new InMemoryStorage();
        final BlockingStorage blocking = new BlockingStorage(storage);
        blocking.save(new Key.From("pkg", "0.0.1", "pkg-0.0.1.tar.gz"), new byte[]{1});
        blocking.save(new Key.From(".pypi", "pkg", "pkg.html"), new byte[]{1});
        blocking.save(new Key.From(".pypi", "pkg", "pkg.json"), new byte[]{1});
        blocking.save(new Key.From(".pypi", "simple.html"), new byte[]{1});
        blocking.save(new Key.From(".pypi", "other", "other.html"), new byte[]{1});
        blocking.save(new Key.From(".pypi", "metadata", "pkg", "pkg-0.0.1.tar.gz.json"), new byte[]{1});
        blocking.save(new Key.From(".pypi", "metadata", "pkg", "pkg-0.0.2.tar.gz.json"), new byte[]{1});
        new PypiIndexCleanup(storage).afterDelete("pkg/0.0.2").join();
        MatcherAssert.assertThat(
            blocking.list(new Key.From(".pypi")).stream().map(Key::string).sorted().toList(),
            new IsEqual<>(
                List.of(".pypi/metadata/pkg/pkg-0.0.1.tar.gz.json", ".pypi/other/other.html")
            )
        );
    }
}
