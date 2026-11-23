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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FileStorage#deleteBatch(java.util.Collection)}: the directories a
 * batch leaves empty go with it, nothing else does.
 *
 * @since 2.2.14
 */
final class FileStorageDeleteBatchTest {

    /**
     * Storage root.
     */
    @TempDir
    private Path temp;

    @Test
    @Timeout(60)
    void removesDirectoriesLeftEmptyAndKeepsRootAndSiblings() throws Exception {
        final Storage storage = new FileStorage(this.temp);
        for (final String key : List.of(
            "repo/com/acme/lib/1.0/lib-1.0.jar",
            "repo/com/acme/lib/1.0/lib-1.0.pom",
            "repo/com/acme/lib/2.0/lib-2.0.jar",
            "repo/com/other/kept.jar",
            "sibling/kept.txt"
        )) {
            storage.save(new Key.From(key), FileStorageDeleteBatchTest.body()).join();
        }
        storage.deleteBatch(
            List.of(
                new Key.From("repo/com/acme/lib/1.0/lib-1.0.jar"),
                new Key.From("repo/com/acme/lib/1.0/lib-1.0.pom"),
                new Key.From("repo/com/acme/lib/2.0/lib-2.0.jar")
            )
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the emptied directories are gone up to the first one that holds something",
            Files.exists(this.temp.resolve("repo/com/acme")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a directory that still holds a file stays",
            Files.exists(this.temp.resolve("repo/com/other/kept.jar")), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a sibling tree stays",
            Files.exists(this.temp.resolve("sibling/kept.txt")), new IsEqual<>(true)
        );
        storage.deleteBatch(
            List.of(new Key.From("repo/com/other/kept.jar"), new Key.From("sibling/kept.txt"))
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the storage root stays when the last file is gone",
            Files.isDirectory(this.temp), new IsEqual<>(true)
        );
        try (java.util.stream.Stream<Path> left = Files.list(this.temp)) {
            MatcherAssert.assertThat(
                "nothing is left in the storage root",
                left.count(), new IsEqual<>(0L)
            );
        }
    }

    @Test
    @Timeout(60)
    void doesNotRemoveDirectoryNamedByKey() throws Exception {
        final Storage storage = new FileStorage(this.temp);
        storage.save(new Key.From("repo/dir/a.txt"), FileStorageDeleteBatchTest.body()).join();
        Files.createDirectories(this.temp.resolve("repo/hollow"));
        storage.deleteBatch(
            List.of(new Key.From("repo/dir"), new Key.From("repo/hollow"), new Key.From("repo"))
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a key that names a directory deletes no file",
            Files.exists(this.temp.resolve("repo/dir/a.txt")), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "nor an empty directory: a key is a file",
            Files.isDirectory(this.temp.resolve("repo/hollow")), new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(60)
    void finishesCleanupOfInterruptedBatchWhenRepeated() throws Exception {
        final Storage storage = new FileStorage(this.temp);
        Files.createDirectories(this.temp.resolve("repo/a/b/c"));
        storage.save(new Key.From("keep/x.txt"), FileStorageDeleteBatchTest.body()).join();
        storage.deleteBatch(List.of(new Key.From("repo/a/b/c/gone.txt")))
            .get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            Files.exists(this.temp.resolve("repo")), new IsEqual<>(false)
        );
    }

    @Test
    @Timeout(60)
    void rejectsKeyOutsideStorageAndDeletesNothing() throws Exception {
        final Path inner = this.temp.resolve("storage");
        final Storage storage = new FileStorage(inner);
        storage.save(new Key.From("a.txt"), FileStorageDeleteBatchTest.body()).join();
        Files.write(this.temp.resolve("outside.txt"), new byte[]{1});
        final Throwable err = org.junit.jupiter.api.Assertions.assertThrows(
            Exception.class,
            () -> storage.deleteBatch(
                List.of(new Key.From("a.txt"), new Key.From("../outside.txt"))
            ).get(30, TimeUnit.SECONDS)
        );
        MatcherAssert.assertThat(
            "the batch fails",
            err.getCause(), new org.hamcrest.core.IsInstanceOf(PanteraIOException.class)
        );
        MatcherAssert.assertThat(
            "the file outside the storage stays",
            Files.exists(this.temp.resolve("outside.txt")), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no key of a rejected batch is deleted",
            Files.exists(inner.resolve("a.txt")), new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(60)
    void prunesInsideSubStorageButNotItsOrigin() throws Exception {
        final Storage origin = new FileStorage(this.temp);
        origin.save(new Key.From("repo-proxy/a.txt"), FileStorageDeleteBatchTest.body()).join();
        final Storage sub = new SubStorage(new Key.From("repo"), origin);
        sub.save(new Key.From("x/y/a.txt"), FileStorageDeleteBatchTest.body()).join();
        sub.deleteBatch(List.of(new Key.From("x/y/a.txt"), new Key.From("a.txt")))
            .get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the key of the sub storage is deleted, with its directories",
            Files.exists(this.temp.resolve("repo")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the same relative key outside the sub storage stays",
            Files.exists(this.temp.resolve("repo-proxy/a.txt")), new IsEqual<>(true)
        );
    }

    /**
     * Content of a test file.
     * @return Content
     */
    private static Content body() {
        return new Content.From("data".getBytes(StandardCharsets.UTF_8));
    }
}
