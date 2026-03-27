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
package com.auto1.pantera.composer;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.composer.http.Archive;
import org.cactoos.set.SetOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.JsonObject;
import java.util.Optional;

/**
 * Tests for {@link AstoRepository#addArchive(Archive, Content)}.
 */
final class AstoRepositoryAddArchiveTest {
    /**
     * Storage used in tests.
     */
    private Storage storage;

    /**
     * Example package read from 'minimal-package.json'.
     */
    private Content archive;

    /**
     * Archive name.
     */
    private Archive.Name name;

    @BeforeEach
    void init() {
        final String zip = "log-1.1.3.zip";
        this.storage = new InMemoryStorage();
        this.archive = new Content.From(
            new TestResource(zip).asBytes()
        );
        this.name = new Archive.Name(zip, "1.1.3");
    }

    @Test
    void shouldAddPackageToAll() {
        this.saveZipArchive();
        // With Satis layout, check p2/psr/log.json instead of packages.json
        final JsonObject p2File = this.storage.value(new Key.From("p2/psr/log.json"))
            .join()
            .asJsonObject();
        MatcherAssert.assertThat(
            p2File.getJsonObject("packages")
                .getJsonObject("psr/log")
                .keySet(),
            new IsEqual<>(new SetOf<>(this.name.version()))
        );
    }

    @Test
    void shouldAddPackageToAllWhenOtherVersionExists() {
        // Save existing version to p2/psr/log.json (Satis layout)
        new BlockingStorage(this.storage).save(
            new Key.From("p2/psr/log.json"),
            "{\"packages\":{\"psr/log\":{\"1.1.2\":{}}}}".getBytes()
        );
        this.saveZipArchive();
        // Read from p2/psr/log.json
        final JsonObject p2File = this.storage.value(new Key.From("p2/psr/log.json"))
            .join()
            .asJsonObject();
        MatcherAssert.assertThat(
            p2File.getJsonObject("packages")
                .getJsonObject("psr/log")
                .keySet(),
            new IsEqual<>(new SetOf<>("1.1.2", this.name.version()))
        );
    }

    @Test
    void shouldAddArchive() {
        this.saveZipArchive();
        Assertions.assertTrue(
            this.storage.exists(new Key.From("artifacts", this.name.full()))
                .toCompletableFuture().join()
        );
    }

    private void saveZipArchive() {
        new AstoRepository(this.storage, Optional.of("http://pantera:8080/"))
            .addArchive(
                new Archive.Zip(this.name),
                this.archive
            ).join();
    }

    private JsonObject packages(final Key key) {
        return this.storage.value(key).join()
            .asJsonObject()
            .getJsonObject("packages");
    }
}
