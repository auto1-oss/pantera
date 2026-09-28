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
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exploit-regression test: {@link DiskCacheStorage} must not let a cache key
 * with parent segments resolve to a file outside its per-storage namespace
 * root. Before 2.2.9 {@code filePath}/{@code metaPath} were a bare
 * {@code nsRoot().resolve(key)} with no containment, so a traversal key could
 * overwrite or delete files outside the cache directory (S3 disk-cache repos).
 *
 * @since 2.2.9
 */
final class DiskCacheStorageContainmentTest {

    @Test
    void traversalKeyFailsClosed(@TempDir final Path tmp) throws Exception {
        try (DiskCacheStorage cache = new DiskCacheStorage(
            new InMemoryStorage(), tmp, 1024 * 1024, DiskCacheStorage.Policy.LRU,
            0, 90, 80, false
        )) {
            Assertions.assertThrows(
                PanteraIOException.class,
                () -> cache.value(new Key.From("../../../../../../etc/passwd")),
                "a traversal cache key must fail closed, not resolve outside the namespace root"
            );
        }
    }
}
