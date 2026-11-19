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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link VertxFileStorage#metadata(Key)} must report the real size: upload
 * slices read {@link Meta#OP_SIZE} after a save to emit the upload event,
 * and an empty meta turned every successful vertx-file upload into a 500.
 *
 * @since 2.2.9
 */
final class VertxFileStorageMetadataTest {

    private static final Vertx VERTX = Vertx.vertx();

    @TempDir
    private Path temp;

    @Test
    void metadataReportsTheStoredSize() {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        final Key key = new Key.From("com/acme/file.txt");
        storage.save(
            key, new Content.From("hello, world".getBytes(StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            storage.metadata(key).join().read(Meta.OP_SIZE),
            new IsEqual<>(Optional.of(12L))
        );
    }

    @Test
    void valueCarriesTheModificationTimeOfTheFile() throws Exception {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        final Key key = new Key.From("com/acme/tool-1.0.zip");
        storage.save(
            key, new Content.From("zip".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Instant modified = Instant.parse("2018-06-17T18:35:49Z");
        Files.setLastModifiedTime(this.temp.resolve(key.string()), FileTime.from(modified));
        final Content content = storage.value(key).join();
        MatcherAssert.assertThat(
            "the content is dated with the file's modification time",
            content.lastModified(), new IsEqual<>(Optional.of(modified))
        );
        MatcherAssert.assertThat(
            "the content is the file",
            new String(content.asBytesFuture().join(), StandardCharsets.UTF_8),
            new IsEqual<>("zip")
        );
    }

    @Test
    void metadataOfMissingKeyFailsWithValueNotFound() {
        final Storage storage = new VertxFileStorage(this.temp, VERTX);
        final CompletionException err = Assertions.assertThrows(
            CompletionException.class,
            () -> storage.metadata(new Key.From("missing.txt")).join()
        );
        MatcherAssert.assertThat(
            err.getCause(), new IsInstanceOf(ValueNotFoundException.class)
        );
    }
}
