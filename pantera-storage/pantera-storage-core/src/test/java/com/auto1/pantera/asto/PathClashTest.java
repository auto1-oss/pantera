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
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PathClash}: a save whose path runs through an existing
 * file, at any depth, is classified as a path clash.
 *
 * @since 2.2.9
 */
final class PathClashTest {

    @TempDir
    private Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"dir1/a.txt/c.txt", "dir1/a.txt/d/e.txt", "dir1/a.txt/d/e/f/g.txt"})
    void saveUnderAnExistingFileAtAnyDepthIsAPathClash(final String path) {
        final Storage storage = new FileStorage(this.temp);
        storage.save(new Key.From("dir1/a.txt"), PathClashTest.body()).join();
        final CompletionException err = Assertions.assertThrows(
            CompletionException.class,
            () -> storage.save(new Key.From(path), PathClashTest.body()).join()
        );
        MatcherAssert.assertThat(
            "the failure must be classified as a path clash",
            new PathClash(err).cause().isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the existing file must be untouched",
            Files.isRegularFile(this.temp.resolve("dir1/a.txt")),
            new IsEqual<>(true)
        );
    }

    @Test
    void findsAClashDeepInTheCauseChain() {
        MatcherAssert.assertThat(
            new PathClash(
                new CompletionException(
                    new PanteraIOException(new NotDirectoryException("/data/a.txt"))
                )
            ).cause().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void recognisesAnExistingFileWhereADirectoryIsNeeded() {
        MatcherAssert.assertThat(
            new PathClash(new FileAlreadyExistsException("/data/a.txt")).cause().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void otherFailuresAreNotAClash() {
        MatcherAssert.assertThat(
            new PathClash(
                new CompletionException(new PanteraIOException(new AccessDeniedException("/x")))
            ).cause().isPresent(),
            new IsEqual<>(false)
        );
    }

    private static Content body() {
        return new Content.From("x".getBytes(StandardCharsets.UTF_8));
    }
}
