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

import com.auto1.pantera.asto.fs.VertxFileStorage;
import io.vertx.reactivex.core.Vertx;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Directory-tree behaviour of {@link VertxFileStorage}: a save under an
 * existing file is a path clash (not an opaque Vert.x failure), and deletes
 * leave no empty directories behind.
 *
 * @since 2.2.9
 */
final class VertxFileStorageTreeTest {

    private static final Vertx VERTX = Vertx.vertx();

    @TempDir
    private Path temp;

    @Test
    void saveUnderAnExistingFileFailsAsPathClash() {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        storage.save(new Key.From("docs/a.txt"), VertxFileStorageTreeTest.body()).join();
        final CompletionException err = Assertions.assertThrows(
            CompletionException.class,
            () -> storage.save(
                new Key.From("docs/a.txt/c.txt"), VertxFileStorageTreeTest.body()
            ).join()
        );
        MatcherAssert.assertThat(
            "the failure must carry the NIO path-clash exception",
            VertxFileStorageTreeTest.hasCause(err, FileAlreadyExistsException.class),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the existing file must be untouched",
            storage.exists(new Key.From("docs/a.txt")).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void deleteRemovesTheDirectoriesItLeftEmpty() {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        storage.save(new Key.From("repo/docs/x/y.txt"), VertxFileStorageTreeTest.body()).join();
        storage.save(new Key.From("other/keep.txt"), VertxFileStorageTreeTest.body()).join();
        storage.delete(new Key.From("repo/docs/x/y.txt")).join();
        MatcherAssert.assertThat(
            "the emptied repository directory must be gone",
            Files.exists(this.temp.resolve("repo")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the empty temp directory must be gone",
            Files.exists(this.temp.resolve(".tmp")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "another directory with files must stay",
            Files.exists(this.temp.resolve("other/keep.txt")), new IsEqual<>(true)
        );
    }

    @Test
    void deleteEmptyDirectoriesRemovesEmptyHiddenTrees() throws Exception {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        storage.save(new Key.From("repo/a.txt"), VertxFileStorageTreeTest.body()).join();
        storage.save(new Key.From("repo2/b.txt"), VertxFileStorageTreeTest.body()).join();
        Files.createDirectories(this.temp.resolve("repo/.upload/noarch"));
        storage.delete(new Key.From("repo/a.txt")).join();
        storage.deleteEmptyDirectories(new Key.From("repo")).join();
        MatcherAssert.assertThat(
            "the repository directory must be gone",
            Files.exists(this.temp.resolve("repo")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a sibling repository must be untouched",
            Files.exists(this.temp.resolve("repo2/b.txt")), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the storage root must stay",
            Files.isDirectory(this.temp), new IsEqual<>(true)
        );
    }

    @Test
    void deleteEmptyDirectoriesKeepsDirectoriesWithFiles() throws Exception {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        storage.save(new Key.From("repo/sub/a.txt"), VertxFileStorageTreeTest.body()).join();
        Files.createDirectories(this.temp.resolve("repo/empty"));
        storage.deleteEmptyDirectories(new Key.From("repo")).join();
        try (Stream<Path> files = Files.list(this.temp.resolve("repo"))) {
            MatcherAssert.assertThat(
                files.map(p -> p.getFileName().toString()).toList(),
                new IsEqual<>(List.of("sub"))
            );
        }
    }

    @Test
    void deleteNeverUnlinksASymlinkedRepositoryDirectory() throws Exception {
        final Path root = Files.createDirectories(this.temp.resolve("root"));
        final Path volume = Files.createDirectories(this.temp.resolve("volume/repo"));
        Files.write(volume.resolve("kept.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(root.resolve("repo"), volume);
        final Storage storage = new VertxFileStorage(root, VERTX);
        storage.save(new Key.From("repo/sub/a.txt"), VertxFileStorageTreeTest.body()).join();
        storage.delete(new Key.From("repo/sub/a.txt")).join();
        MatcherAssert.assertThat(
            "the symlinked repository directory must survive a sibling delete",
            Files.isSymbolicLink(root.resolve("repo")), new IsEqual<>(true)
        );
        storage.deleteEmptyDirectories(new Key.From("repo")).join();
        MatcherAssert.assertThat(
            "the symlinked repository directory must survive a prune",
            Files.isSymbolicLink(root.resolve("repo")), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the target's files must stay reachable through the storage",
            storage.exists(new Key.From("repo/kept.txt")).join(), new IsEqual<>(true)
        );
    }

    private static Content body() {
        return new Content.From("x".getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasCause(final Throwable err, final Class<?> type) {
        Throwable cur = err;
        boolean found = false;
        while (cur != null && !found) {
            found = type.isInstance(cur);
            cur = cur.getCause();
        }
        return found;
    }
}
