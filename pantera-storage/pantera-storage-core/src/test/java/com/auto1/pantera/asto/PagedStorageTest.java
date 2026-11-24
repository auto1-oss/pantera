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

import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.BenchmarkStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.PagedStorageVerification;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the paged listing and batch delete contract against the storages of
 * this module and their decorators.
 *
 * @since 2.2.14
 */
final class PagedStorageTest {

    /**
     * Temp dir.
     */
    @TempDir
    private Path temp;

    /**
     * In-memory storage.
     * @since 2.2.14
     */
    @Nested
    final class InMemory extends PagedStorageVerification {
        @Override
        protected Storage newStorage() {
            return new InMemoryStorage();
        }
    }

    /**
     * File storage.
     * @since 2.2.14
     */
    @Nested
    final class Files extends PagedStorageVerification {
        @Override
        protected Storage newStorage() {
            return new FileStorage(PagedStorageTest.this.temp.resolve("files"));
        }
    }

    /**
     * Sub storage of an in-memory storage that also holds keys of sibling
     * prefixes.
     * @since 2.2.14
     */
    @Nested
    final class SubOfInMemory extends PagedStorageVerification {
        @Override
        protected Storage newStorage() throws Exception {
            final Storage origin = new InMemoryStorage();
            this.save(origin, PagedStorageTest.neighbours());
            return new SubStorage(new Key.From("tenant/repo"), origin);
        }
    }

    /**
     * Sub storage of a file storage that also holds keys of sibling
     * prefixes.
     * @since 2.2.14
     */
    @Nested
    final class SubOfFiles extends PagedStorageVerification {
        @Override
        protected Storage newStorage() throws Exception {
            final Storage origin = new FileStorage(PagedStorageTest.this.temp.resolve("sub"));
            this.save(origin, PagedStorageTest.neighbours());
            return new SubStorage(new Key.From("tenant/repo"), origin);
        }
    }

    /**
     * Sub storage with the root prefix.
     * @since 2.2.14
     */
    @Nested
    final class SubOfRoot extends PagedStorageVerification {
        @Override
        protected Storage newStorage() {
            return new SubStorage(Key.ROOT, new InMemoryStorage());
        }
    }

    /**
     * A storage that implements neither operation and runs the defaults of
     * the interface.
     * @since 2.2.14
     */
    @Nested
    final class Defaults extends PagedStorageVerification {
        @Override
        protected Storage newStorage() {
            return new BenchmarkStorage(new InMemoryStorage());
        }
    }

    /**
     * A forwarding decorator.
     * @since 2.2.14
     */
    @Nested
    final class Wrapped extends PagedStorageVerification {
        @Override
        protected Storage newStorage() {
            return new Storage.Wrap(
                new FileStorage(PagedStorageTest.this.temp.resolve("wrapped"))
            ) {
            };
        }
    }

    /**
     * Keys next to the sub storage prefix that it must never show.
     * @return Key strings
     */
    private static java.util.List<String> neighbours() {
        return java.util.List.of(
            "tenant/repo-proxy/a.txt",
            "tenant/repo.old/a.txt",
            "tenant/rep/a.txt",
            "tenant/repo2/repo/a.txt",
            "tenant/zzz.txt",
            "other/tenant/repo/a.txt",
            "aaa.txt"
        );
    }
}
