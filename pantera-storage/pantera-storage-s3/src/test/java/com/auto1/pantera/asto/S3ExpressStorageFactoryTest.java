/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
 * Test for S3 Express One Zone storage factory.
 */
public final class S3ExpressStorageFactoryTest {

    /**
     * Test for S3 Express storage factory with credentials.
     */
    @Test
    void shouldCreateS3ExpressStorageConfigHasCredentials() {
        MatcherAssert.assertThat(
            StoragesLoader.STORAGES
                .newObject(
                    "s3-express",
                    new Config.YamlStorageConfig(
                        Yaml.createYamlMappingBuilder()
                            .add("region", "us-east-1")
                            .add("bucket", "my-bucket--usw2-az1--x-s3")
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
     * Test for S3 Express storage factory without credentials.
     */
    @Test
    void shouldCreateS3ExpressStorageConfigDoesNotHaveCredentials() {
        MatcherAssert.assertThat(
            StoragesLoader.STORAGES
                .newObject(
                    "s3-express",
                    new Config.YamlStorageConfig(
                        Yaml.createYamlMappingBuilder()
                            .add("region", "us-east-1")
                            .add("bucket", "my-bucket--usw2-az1--x-s3")
                            .add("endpoint", "http://localhost")
                            .build()
                    )
                ),
            new IsInstanceOf(S3Storage.class)
        );
    }

    /**
     * Test for S3 Express storage factory with all optional settings.
     */
    @Test
    void shouldCreateS3ExpressStorageWithAllSettings() {
        MatcherAssert.assertThat(
            StoragesLoader.STORAGES
                .newObject(
                    "s3-express",
                    new Config.YamlStorageConfig(
                        Yaml.createYamlMappingBuilder()
                            .add("region", "us-west-2")
                            .add("bucket", "analytics-bucket--usw2-az1--x-s3")
                            .add("endpoint", "http://localhost:9000")
                            .add("multipart", "true")
                            .add("multipart-min-size", "32MB")
                            .add("part-size", "8MB")
                            .add("multipart-concurrency", "16")
                            .add("checksum", "SHA256")
                            .add("parallel-download", "true")
                            .add("parallel-download-min-size", "64MB")
                            .add("parallel-download-chunk-size", "8MB")
                            .add("parallel-download-concurrency", "8")
                            .add(
                                "credentials",
                                Yaml.createYamlMappingBuilder()
                                    .add("type", "basic")
                                    .add("accessKeyId", "test")
                                    .add("secretAccessKey", "test")
                                    .build()
                            )
                            .build()
                    )
                ),
            new IsInstanceOf(S3Storage.class)
        );
    }
}
