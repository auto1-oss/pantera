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
import com.auto1.pantera.asto.test.PagedStorageVerification;
import io.vertx.reactivex.core.Vertx;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Paged listing and batch delete of {@link VertxFileStorage}.
 *
 * @since 2.2.14
 */
final class VertxFilePagedStorageTest {

    /**
     * Vert.x.
     */
    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Temp dir.
     */
    @TempDir
    private Path temp;

    @AfterAll
    static void tearDown() {
        VertxFilePagedStorageTest.VERTX.close();
    }

    @Test
    @Timeout(60)
    void batchDeleteRemovesDirectoriesLeftEmptyAndKeepsTheRest() throws Exception {
        final Storage storage = new VertxFileStorage(this.temp, VertxFilePagedStorageTest.VERTX);
        for (final String key : List.of(
            "repo/a/b/one.txt", "repo/a/b/two.txt", "repo/a/kept.txt", "sibling/kept.txt"
        )) {
            storage.save(
                new Key.From(key), new Content.From(key.getBytes(StandardCharsets.UTF_8))
            ).get(30, TimeUnit.SECONDS);
        }
        storage.deleteBatch(
            List.of(
                new Key.From("repo/a/b/one.txt"),
                new Key.From("repo/a/b/two.txt"),
                new Key.From("repo/a/b/never-there.txt")
            )
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the emptied directory is gone",
            Files.exists(this.temp.resolve("repo/a/b")), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "its parent still holds a file and stays",
            Files.exists(this.temp.resolve("repo/a/kept.txt")), new IsEqual<>(true)
        );
        storage.deleteBatch(
            List.of(new Key.From("repo/a/kept.txt"), new Key.From("sibling/kept.txt"))
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the storage root stays",
            Files.isDirectory(this.temp), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the repository directory does not",
            Files.exists(this.temp.resolve("repo")), new IsEqual<>(false)
        );
    }

    /**
     * The storage itself.
     * @since 2.2.14
     */
    @Nested
    final class Direct extends PagedStorageVerification {
        @Override
        protected Storage newStorage() {
            return new VertxFileStorage(
                VertxFilePagedStorageTest.this.temp.resolve("direct"),
                VertxFilePagedStorageTest.VERTX
            );
        }
    }

    /**
     * A sub storage of it, next to keys of sibling prefixes.
     * @since 2.2.14
     */
    @Nested
    final class Sub extends PagedStorageVerification {
        @Override
        protected Storage newStorage() throws Exception {
            final Storage origin = new VertxFileStorage(
                VertxFilePagedStorageTest.this.temp.resolve("sub"),
                VertxFilePagedStorageTest.VERTX
            );
            this.save(
                origin,
                List.of("tenant/repo-proxy/a.txt", "tenant/repo.old/a.txt", "tenant/zzz.txt")
            );
            return new SubStorage(new Key.From("tenant/repo"), origin);
        }
    }
}
