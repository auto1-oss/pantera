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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exploit-regression test: {@link VertxFileStorage} must not let a key with
 * parent segments resolve to a path outside its configured root. Before 2.2.9
 * {@code path(key)} was a bare {@code Paths.get(dir, key)} with no
 * normalization or containment (unlike {@code FileStorage.keyPath}), so a
 * {@code type: vertx-file} repository could read/write/delete arbitrary
 * process-accessible files via a traversal key.
 *
 * @since 2.2.9
 */
final class VertxFileStorageContainmentTest {

    private static final Vertx VERTX = Vertx.vertx();

    @TempDir
    private Path temp;

    @Test
    void rejectsSaveThatEscapesRoot() throws Exception {
        final Path outside = this.temp.getParent().resolve("vfs-escape-marker.txt");
        Files.deleteIfExists(outside);
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        Assertions.assertThrows(
            CompletionException.class,
            () -> storage.save(
                new Key.From("../vfs-escape-marker.txt"),
                new Content.From("pwned".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            ).join(),
            "a save with a parent-segment key must fail, not escape the root"
        );
        MatcherAssert.assertThat(
            "no file may be written outside the storage root",
            Files.exists(outside), new IsEqual<>(false)
        );
    }

    @Test
    void containedKeyStillWorks() {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        storage.save(
            new Key.From("com/acme/file.txt"),
            new Content.From("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            "a normal contained key must still resolve and store",
            storage.exists(new Key.From("com/acme/file.txt")).join(), new IsEqual<>(true)
        );
    }
}
