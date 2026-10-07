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
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@code S3StorageFactory}'s {@code cache.mode} selection (WS1.1
 * &sect;3, spec {@code WS1-storage-for-scale.md}): {@code cache.mode: index}
 * opts a repository into {@link CachedBlobStorage}; the unset/{@code disk}
 * default keeps selecting {@link DiskCacheStorage} unchanged, so existing
 * repositories are unaffected until they opt in.
 */
final class S3StorageFactoryCacheModeTest {

    @Test
    void cacheModeIndexSelectsCachedBlobStorage(@TempDir final Path tmp) {
        final Storage storage = S3StorageFactoryCacheModeTest.storage(tmp, "index");
        MatcherAssert.assertThat(storage, new IsInstanceOf(CachedBlobStorage.class));
    }

    @Test
    void cacheModeUnsetKeepsDefaultDiskCacheStorage(@TempDir final Path tmp) {
        final Storage storage = S3StorageFactoryCacheModeTest.storage(tmp, null);
        MatcherAssert.assertThat(storage, new IsInstanceOf(DiskCacheStorage.class));
    }

    @Test
    void cacheModeDiskExplicitlyKeepsDiskCacheStorage(@TempDir final Path tmp) {
        final Storage storage = S3StorageFactoryCacheModeTest.storage(tmp, "disk");
        MatcherAssert.assertThat(storage, new IsInstanceOf(DiskCacheStorage.class));
    }

    @Test
    void noCacheBlockReturnsPlainS3Storage() {
        final Storage storage = StoragesLoader.STORAGES.newObject(
            "s3",
            new Config.YamlStorageConfig(
                Yaml.createYamlMappingBuilder()
                    .add("region", "us-east-1")
                    .add("bucket", "aaa")
                    .add("endpoint", "http://localhost")
                    .build()
            )
        );
        MatcherAssert.assertThat(storage, new IsInstanceOf(S3Storage.class));
    }

    private static Storage storage(final Path tmp, final String mode) {
        YamlMappingBuilder cache = Yaml.createYamlMappingBuilder()
            .add("enabled", "true")
            .add("path", tmp.toString());
        if (mode != null) {
            cache = cache.add("mode", mode);
        }
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
