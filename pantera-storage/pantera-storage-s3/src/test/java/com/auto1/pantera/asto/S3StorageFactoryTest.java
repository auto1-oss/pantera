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

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;
import com.auto1.pantera.asto.s3.S3Storage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Test for Storages.
 */
public final class S3StorageFactoryTest {

    /**
     * Test for S3 storage factory.
     *
     */
    @Test
    void shouldCreateS3StorageConfigHasCredentials() {
        MatcherAssert.assertThat(
            StoragesLoader.STORAGES
                .newObject(
                    "s3",
                    new Config.YamlStorageConfig(
                        Yaml.createYamlMappingBuilder()
                            .add("region", "us-east-1")
                            .add("bucket", "aaa")
                            .add("endpoint", "http://localhost")
                            .add(
                                "credentials",
                                Yaml.createYamlMappingBuilder()
                                    .add("type", "basic")
                                    .add("accessKeyId", "foo")
                                    .add("secretAccessKey", "bar")
                                    .build()
                            )
                            .build()
                    )
                ),
            new IsInstanceOf(S3Storage.class)
        );
    }

    /**
     * Test for S3 storage factory.
     *
     */
    @Test
    void shouldCreateS3StorageConfigDoesNotHaveCredentials() {
        MatcherAssert.assertThat(
            StoragesLoader.STORAGES
                .newObject(
                    "s3",
                    new Config.YamlStorageConfig(
                        Yaml.createYamlMappingBuilder()
                            .add("region", "us-east-1")
                            .add("bucket", "aaa")
                            .add("endpoint", "http://localhost")
                            .build()
                    )
                ),
            new IsInstanceOf(S3Storage.class)
        );
    }
}
