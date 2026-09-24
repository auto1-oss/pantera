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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Storage#deleteEmptyDirectories(Key)}: a removed repository must
 * leave no empty (possibly hidden) working directories on disk.
 *
 * @since 2.2.9
 */
final class FileStorageEmptyDirectoriesTest {

    @TempDir
    private Path temp;

    @Test
    void removesEmptyHiddenTreesOfTheSubtreeOnly() throws Exception {
        final Storage storage = new FileStorage(this.temp);
        storage.save(new Key.From("conda/a.txt"), FileStorageEmptyDirectoriesTest.body()).join();
        storage.save(new Key.From("conda2/b.txt"), FileStorageEmptyDirectoriesTest.body()).join();
        Files.createDirectories(this.temp.resolve("conda/.upload/noarch"));
        Files.createDirectories(this.temp.resolve("conda2/.add"));
        storage.delete(new Key.From("conda/a.txt")).join();
        storage.deleteEmptyDirectories(new Key.From("conda")).join();
        MatcherAssert.assertThat(
            "the repository directory must be gone",
            Files.exists(this.temp.resolve("conda")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a sibling repository's empty directory is outside the subtree",
            Files.isDirectory(this.temp.resolve("conda2/.add")), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the storage root must stay",
            Files.isDirectory(this.temp), new IsEqual<>(true)
        );
    }

    @Test
    void keepsDirectoriesThatHoldFiles() throws Exception {
        final Storage storage = new FileStorage(this.temp);
        storage.save(new Key.From("rpm/sub/a.rpm"), FileStorageEmptyDirectoriesTest.body()).join();
        Files.createDirectories(this.temp.resolve("rpm/.add"));
        storage.deleteEmptyDirectories(new Key.From("rpm")).join();
        MatcherAssert.assertThat(
            "the empty directory must be gone",
            Files.exists(this.temp.resolve("rpm/.add")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the file must stay",
            Files.exists(this.temp.resolve("rpm/sub/a.rpm")), new IsEqual<>(true)
        );
    }

    @Test
    void isANoOpForStoragesWithoutDirectories() {
        final Storage storage = new InMemoryStorage();
        final Key key = new Key.From("repo/a.txt");
        storage.save(key, FileStorageEmptyDirectoriesTest.body()).join();
        storage.deleteEmptyDirectories(new Key.From("repo")).join();
        MatcherAssert.assertThat(storage.exists(key).join(), new IsEqual<>(true));
    }

    @Test
    void subStorageScopesTheSubtree() throws Exception {
        final Storage storage = new SubStorage(new Key.From("root"), new FileStorage(this.temp));
        Files.createDirectories(this.temp.resolve("root/repo/.tmp/x"));
        Files.createDirectories(this.temp.resolve("repo/keep"));
        storage.deleteEmptyDirectories(new Key.From("repo")).join();
        MatcherAssert.assertThat(
            "the prefixed subtree must be pruned",
            Files.exists(this.temp.resolve("root/repo")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the unprefixed directory of the same name must stay",
            Files.isDirectory(this.temp.resolve("repo/keep")), new IsEqual<>(true)
        );
    }

    @Test
    void neverUnlinksASymlinkedDirectoryWhoseTargetHoldsFiles() throws Exception {
        final Path root = Files.createDirectories(this.temp.resolve("root"));
        final Path volume = Files.createDirectories(this.temp.resolve("volume/repo"));
        Files.write(volume.resolve("kept.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(root.resolve("repo"), volume);
        final Storage storage = new FileStorage(root);
        storage.save(new Key.From("repo/sub/a.txt"), FileStorageEmptyDirectoriesTest.body()).join();
        storage.delete(new Key.From("repo/sub/a.txt")).join();
        storage.deleteEmptyDirectories(new Key.From("repo")).join();
        storage.deleteEmptyDirectories(Key.ROOT).join();
        MatcherAssert.assertThat(
            "the symlinked repository directory must stay",
            Files.isSymbolicLink(root.resolve("repo")), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the target's files must stay reachable through the storage",
            storage.exists(new Key.From("repo/kept.txt")).join(), new IsEqual<>(true)
        );
    }

    @Test
    void neverUnlinksASymlinkedDirectoryInsideThePrunedSubtree() throws Exception {
        final Path root = Files.createDirectories(this.temp.resolve("root"));
        final Path volume = Files.createDirectories(this.temp.resolve("volume/big"));
        Files.write(volume.resolve("kept.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("repo/.add"));
        Files.createSymbolicLink(root.resolve("repo/big"), volume);
        final Storage storage = new FileStorage(root);
        storage.deleteEmptyDirectories(new Key.From("repo")).join();
        MatcherAssert.assertThat(
            "the symlink inside the subtree must stay",
            Files.isSymbolicLink(root.resolve("repo/big")), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the empty directory beside it must be gone",
            Files.exists(root.resolve("repo/.add")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the target's files must stay reachable through the storage",
            storage.exists(new Key.From("repo/big/kept.txt")).join(), new IsEqual<>(true)
        );
    }

    private static Content body() {
        return new Content.From("x".getBytes(StandardCharsets.UTF_8));
    }
}
