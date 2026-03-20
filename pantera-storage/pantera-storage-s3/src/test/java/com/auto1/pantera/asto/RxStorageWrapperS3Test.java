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

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.ext.ContentAs;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;
import com.auto1.pantera.asto.rx.RxStorage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Single;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tests for {@link RxStorageWrapper}.
 */
@Testcontainers
final class RxStorageWrapperS3Test {

    /**
     * S3Mock container.
     */
    @Container
    static final GenericContainer<?> S3_MOCK = new GenericContainer<>(
        DockerImageName.parse("adobe/s3mock:3.5.2")
    )
        .withExposedPorts(9090, 9191)
        .withEnv("initialBuckets", "test")
        .waitingFor(Wait.forHttp("/").forPort(9090));

    /**
     * S3 client.
     */
    private static AmazonS3 s3Client;

    /**
     * Bucket to use in tests.
     */
    private String bucket;

    /**
     * Original storage.
     */
    private Storage original;

    /**
     * Reactive wrapper of original storage.
     */
    private RxStorageWrapper wrapper;

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
        final String endpoint = String.format(
            "http://%s:%d",
            S3_MOCK.getHost(),
            S3_MOCK.getMappedPort(9090)
        );
        this.original = StoragesLoader.STORAGES
            .newObject(
                "s3",
                new Config.YamlStorageConfig(
                    Yaml.createYamlMappingBuilder()
                        .add("region", "us-east-1")
                        .add("bucket", this.bucket)
                        .add("endpoint", endpoint)
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
        this.wrapper = new RxStorageWrapper(this.original);
    }

    @Test
    void checksExistence() {
        final Key key = new Key.From("a");
        this.original.save(key, Content.EMPTY).join();
        MatcherAssert.assertThat(
            this.wrapper.exists(key).blockingGet(),
            new IsEqual<>(true)
        );
    }

    @Test
    void listsItemsByPrefix() {
        this.original.save(new Key.From("a/b/c"), Content.EMPTY).join();
        this.original.save(new Key.From("a/d"), Content.EMPTY).join();
        this.original.save(new Key.From("z"), Content.EMPTY).join();
        final Collection<String> keys = this.wrapper.list(new Key.From("a"))
            .blockingGet()
            .stream()
            .map(Key::string)
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            keys,
            new IsEqual<>(Arrays.asList("a/b/c", "a/d"))
        );
    }

    @Test
    void savesItems() {
        this.wrapper.save(
            new Key.From("foo/file1"), Content.EMPTY
        ).blockingAwait();
        this.wrapper.save(
            new Key.From("file2"), Content.EMPTY
        ).blockingAwait();
        final Collection<String> keys = this.original.list(Key.ROOT)
            .join()
            .stream()
            .map(Key::string)
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            keys,
            new IsEqual<>(Arrays.asList("file2", "foo/file1"))
        );
    }

    @Test
    void movesItems() {
        final Key source = new Key.From("foo/file1");
        final Key destination = new Key.From("bla/file2");
        final byte[] bvalue = "my file1 content"
            .getBytes(StandardCharsets.UTF_8);
        this.original.save(
            source, new Content.From(bvalue)
        ).join();
        this.original.save(
            destination, Content.EMPTY
        ).join();
        this.wrapper.move(source, destination).blockingAwait();
        MatcherAssert.assertThat(
            new BlockingStorage(this.original)
                .value(destination),
            new IsEqual<>(bvalue)
        );
    }

    @Test
    @SuppressWarnings("deprecation")
    void readsSize() {
        final Key key = new Key.From("file.txt");
        final String text = "my file content";
        this.original.save(
            key,
            new Content.From(
                text.getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        MatcherAssert.assertThat(
            this.wrapper.size(key).blockingGet(),
            new IsEqual<>((long) text.length())
        );
    }

    @Test
    void readsValue() {
        final Key key = new Key.From("a/z");
        final byte[] bvalue = "value to read"
            .getBytes(StandardCharsets.UTF_8);
        this.original.save(
            key, new Content.From(bvalue)
        ).join();
        MatcherAssert.assertThat(
            new Remaining(
                new Concatenation(
                    this.wrapper.value(key).blockingGet()
                ).single()
                    .blockingGet(),
                true
            ).bytes(),
            new IsEqual<>(bvalue)
        );
    }

    @Test
    void deletesItem() throws Exception {
        final Key key = new Key.From("key_to_delete");
        this.original.save(key, Content.EMPTY).join();
        this.wrapper.delete(key).blockingAwait();
        MatcherAssert.assertThat(
            this.original.exists(key).get(),
            new IsEqual<>(false)
        );
    }

    @Test
    void runsExclusively() {
        final Key key = new Key.From("exclusively_key");
        final Function<RxStorage, Single<Integer>> operation = sto -> Single.just(1);
        this.wrapper.exclusively(key, operation).blockingGet();
        MatcherAssert.assertThat(
            this.wrapper.exclusively(key, operation).blockingGet(),
            new IsEqual<>(1)
        );
    }
}
