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
package com.auto1.pantera.asto.s3;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.CachedBlobStorage;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@code S3StorageFactory}'s WS1.2 write-back config wiring (spec
 * {@code WS1-storage-for-scale.md} &sect;3.C, item D): {@code
 * cache.write-through} and every {@code cache.write-back-*} key, each
 * falling back to {@link CachedBlobStorage.WriteBackConfig#defaults()} per
 * field when unset -- mirroring {@link S3StorageFactoryCacheModeTest}'s
 * pattern.
 */
final class S3StorageFactoryWriteBackTest {

    @Test
    void writeThroughUnsetDefaultsToAsyncWriteBack(@TempDir final Path tmp) {
        final CachedBlobStorage storage = (CachedBlobStorage) S3StorageFactoryWriteBackTest.storage(
            tmp, cache -> cache
        );
        MatcherAssert.assertThat(storage.writeThrough(), new IsEqual<>(false));
    }

    @Test
    void writeThroughTrueOptsOutOfWriteBack(@TempDir final Path tmp) {
        final CachedBlobStorage storage = (CachedBlobStorage) S3StorageFactoryWriteBackTest.storage(
            tmp, cache -> cache.add("write-through", "true")
        );
        MatcherAssert.assertThat(storage.writeThrough(), new IsEqual<>(true));
    }

    @Test
    void writeThroughFalseExplicitStaysWriteBack(@TempDir final Path tmp) {
        final CachedBlobStorage storage = (CachedBlobStorage) S3StorageFactoryWriteBackTest.storage(
            tmp, cache -> cache.add("write-through", "false")
        );
        MatcherAssert.assertThat(storage.writeThrough(), new IsEqual<>(false));
    }

    @Test
    void writeBackTuningKeysAreAcceptedWithoutError(@TempDir final Path tmp) {
        // The write-back tuning knobs are internal to CachedBlobStorage (no
        // public accessors beyond writeThrough()) -- this test's job is to
        // prove the factory parses every key without throwing and still
        // produces a working CachedBlobStorage, not to re-assert their
        // values (covered at the CachedBlobStorage unit level).
        final CachedBlobStorage storage = (CachedBlobStorage) S3StorageFactoryWriteBackTest.storage(
            tmp,
            cache -> cache
                .add("write-back-queue-capacity", "64")
                .add("write-back-uploader-threads", "2")
                .add("write-back-max-retries", "3")
                .add("write-back-backoff-millis", "100")
                .add("write-back-max-backoff-millis", "5000")
                .add("write-back-retry-after-seconds", "10")
        );
        MatcherAssert.assertThat(storage.writeThrough(), new IsEqual<>(false));
    }

    private static Storage storage(
        final Path tmp, final java.util.function.UnaryOperator<YamlMappingBuilder> customize
    ) {
        YamlMappingBuilder cache = Yaml.createYamlMappingBuilder()
            .add("enabled", "true")
            .add("mode", "index")
            .add("path", tmp.toString());
        cache = customize.apply(cache);
        return StoragesLoader.STORAGES.newObject(
            "s3",
            new Config.YamlStorageConfig(
                Yaml.createYamlMappingBuilder()
                    .add("region", "us-east-1")
                    .add("bucket", "aaa")
                    .add("endpoint", "http://localhost")
                    .add("cache", cache.build())
                    .build()
            )
        );
    }
}
