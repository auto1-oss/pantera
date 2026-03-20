/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.s3;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;
import java.io.ByteArrayInputStream;
import java.util.Random;
import java.util.UUID;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Functional test for parallel range downloads.
 */
@DisabledOnOs(OS.WINDOWS)
@Testcontainers
final class S3ParallelDownloadTest {
    @Container
    static final GenericContainer<?> S3_MOCK = new GenericContainer<>(
        DockerImageName.parse("adobe/s3mock:3.5.2")
    )
        .withExposedPorts(9090, 9191)
        .withEnv("initialBuckets", "test")
        .waitingFor(Wait.forHttp("/").forPort(9090));

    private static AmazonS3 s3Client;

    private String bucket;

    @BeforeAll
    static void setUpClient() {
        final String endpoint = String.format(
            "http://%s:%d",
            S3_MOCK.getHost(),
            S3_MOCK.getMappedPort(9090)
        );
        s3Client = AmazonS3ClientBuilder.standard()
            .withEndpointConfiguration(
                new AwsClientBuilder.EndpointConfiguration(endpoint, "us-east-1")
            )
            .withCredentials(
                new AWSStaticCredentialsProvider(
                    new BasicAWSCredentials("foo", "bar")
                )
            )
            .withPathStyleAccessEnabled(true)
            .build();
    }

    @AfterAll
    static void tearDownClient() {
        if (s3Client != null) {
            s3Client.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        this.bucket = UUID.randomUUID().toString();
        s3Client.createBucket(this.bucket);
    }

    @Test
    void downloadsLargeObjectInParallel() throws Exception {
        final int size = 32 * 1024 * 1024;
        final byte[] data = new byte[size];
        new Random().nextBytes(data);
        final String key = "large-parallel";
        s3Client.putObject(
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
                        .add("endpoint", String.format(
                            "http://%s:%d",
                            S3_MOCK.getHost(),
                            S3_MOCK.getMappedPort(9090)
                        ))
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

