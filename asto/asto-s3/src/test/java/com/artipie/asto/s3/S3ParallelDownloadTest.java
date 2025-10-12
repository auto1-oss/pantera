/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.s3;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amihaiemil.eoyaml.Yaml;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.blocking.BlockingStorage;
import com.artipie.asto.factory.Config;
import com.artipie.asto.factory.StoragesLoader;
import java.io.ByteArrayInputStream;
import java.util.Random;
import java.util.UUID;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional test for parallel range downloads.
 */
@DisabledOnOs(OS.WINDOWS)
final class S3ParallelDownloadTest {
    @RegisterExtension
    static final S3MockExtension MOCK = S3MockExtension.builder()
        .withSecureConnection(false)
        .build();

    private String bucket;

    @BeforeEach
    void setUp(final AmazonS3 client) {
        this.bucket = UUID.randomUUID().toString();
        client.createBucket(this.bucket);
    }

    @Test
    void downloadsLargeObjectInParallel(final AmazonS3 client) throws Exception {
        final int size = 32 * 1024 * 1024;
        final byte[] data = new byte[size];
        new Random().nextBytes(data);
        final String key = "large-parallel";
        client.putObject(
            this.bucket,
            key,
            new ByteArrayInputStream(data),
            new ObjectMetadata()
        );
        final Storage st = this.storage(true);
        final byte[] got = new BlockingStorage(st).value(new Key.From(key));
        MatcherAssert.assertThat(got, Matchers.equalTo(data));
    }

    private Storage storage(final boolean parallel) {
        return StoragesLoader.STORAGES
            .newObject(
                "s3",
                new Config.YamlStorageConfig(
                    Yaml.createYamlMappingBuilder()
                        .add("region", "us-east-1")
                        .add("bucket", this.bucket)
                        .add("endpoint", String.format("http://localhost:%d", MOCK.getHttpPort()))
                        .add("parallel-download", String.valueOf(parallel))
                        .add("parallel-download-min-bytes", "1")
                        .add("parallel-download-chunk-bytes", String.valueOf(1 * 1024 * 1024))
                        .add("parallel-download-concurrency", "4")
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
            );
    }
}

