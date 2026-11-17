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
package com.auto1.pantera.asto.fs;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.cache.OptimizedStorageCache;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The content a file storage returns carries the file's modification time,
 * read with the attributes the storage reads anyway.
 *
 * @since 2.2.12
 */
final class FileStorageLastModifiedTest {

    /**
     * Time the file was last written.
     */
    private static final Instant MODIFIED = Instant.parse("2018-06-17T18:35:49Z");

    /**
     * Stored object.
     */
    private static final Key KEY = new Key.From("repo", "org", "example", "tool-1.0.zip");

    /**
     * Storage.
     */
    private Storage storage;

    @BeforeEach
    void init(@TempDir final Path dir) throws Exception {
        this.storage = new FileStorage(dir);
        this.storage.save(
            FileStorageLastModifiedTest.KEY,
            new Content.From("zip".getBytes(StandardCharsets.UTF_8))
        ).get(30, TimeUnit.SECONDS);
        Files.setLastModifiedTime(
            dir.resolve(FileStorageLastModifiedTest.KEY.string()),
            FileTime.from(FileStorageLastModifiedTest.MODIFIED)
        );
    }

    @Test
    void valueCarriesTheModificationTime() throws Exception {
        MatcherAssert.assertThat(
            this.storage.value(FileStorageLastModifiedTest.KEY)
                .get(30, TimeUnit.SECONDS).lastModified(),
            new IsEqual<>(Optional.of(FileStorageLastModifiedTest.MODIFIED))
        );
    }

    @Test
    void optimizedValueCarriesTheModificationTime() throws Exception {
        MatcherAssert.assertThat(
            OptimizedStorageCache.optimizedValue(this.storage, FileStorageLastModifiedTest.KEY)
                .get(30, TimeUnit.SECONDS).lastModified(),
            new IsEqual<>(Optional.of(FileStorageLastModifiedTest.MODIFIED))
        );
    }

    @Test
    void optimizedValueOfScopedStorageCarriesTheModificationTime() throws Exception {
        MatcherAssert.assertThat(
            OptimizedStorageCache.optimizedValue(
                new SubStorage(new Key.From("repo"), this.storage),
                new Key.From("org", "example", "tool-1.0.zip")
            ).get(30, TimeUnit.SECONDS).lastModified(),
            new IsEqual<>(Optional.of(FileStorageLastModifiedTest.MODIFIED))
        );
    }
}
