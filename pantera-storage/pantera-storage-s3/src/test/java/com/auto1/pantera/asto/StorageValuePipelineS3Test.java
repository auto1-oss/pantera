/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;
import com.auto1.pantera.asto.misc.UncheckedIOFunc;
import com.auto1.pantera.asto.s3.S3Storage;
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.asto.streams.StorageValuePipeline;
import com.auto1.pantera.asto.test.ReadWithDelaysStorage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.apache.commons.io.IOUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Test for {@link StorageValuePipeline} backed by {@link S3Storage}.
 * Uses Testcontainers to run S3Mock in isolated Docker container,
 * avoiding Jetty 11/12 classpath conflicts.
 */
@Testcontainers
public class StorageValuePipelineS3Test {

    /**
     * S3Mock container running in Docker.
     * Isolated from our Jetty 12.1.4 classpath.
     */
    @Container
    static final GenericContainer<?> S3_MOCK = new GenericContainer<>(
        DockerImageName.parse("adobe/s3mock:3.5.2")
    )
        .withExposedPorts(9090, 9191)
        .withEnv("initialBuckets", "test")
        .waitingFor(Wait.forHttp("/").forPort(9090));

    /**
     * S3 client for test setup.
     */
    private static AmazonS3 s3Client;

    /**
     * Bucket to use in tests.
     */
    private String bucket;

    /**
     * Test storage.
     */
    private Storage asto;

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
        asto = StoragesLoader.STORAGES
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
    }

    @Test
    void processesExistingItemAndReturnsResult() {
        final Key key = new Key.From("test.txt");
        final Charset charset = StandardCharsets.US_ASCII;
        this.asto.save(key, new Content.From("five\nsix\neight".getBytes(charset))).join();
        MatcherAssert.assertThat(
            "Resulting lines count should be 4",
            new StorageValuePipeline<Integer>(this.asto, key).processWithResult(
                (input, out) -> {
                    try {
                        final List<String> list = IOUtils.readLines(input.get(), charset);
                        list.add(2, "seven");
                        IOUtils.writeLines(list, "\n", out, charset);
                        return list.size();
                    } catch (final IOException err) {
                        throw new ArtipieIOException(err);
                    }
                }
            ).toCompletableFuture().join(),
            new IsEqual<>(4)
        );
        MatcherAssert.assertThat(
            "Storage item was not updated",
            new String(new BlockingStorage(this.asto).value(key), charset),
            new IsEqual<>("five\nsix\nseven\neight\n")
        );
    }

    @Test
    void processesExistingItemAndReturnsResultWithThen() {
        final Key key = new Key.From("test.txt");
        final Charset charset = StandardCharsets.US_ASCII;

        MatcherAssert.assertThat(
            "Resulting lines count should be 4",
            this.asto.save(key, new Content.From("five\nsix\neight".getBytes(charset))).thenCompose(unused -> {
                return new StorageValuePipeline<Integer>(this.asto, key).processWithResult(
                    (input, out) -> {
                        try {
                            final List<String> list = IOUtils.readLines(input.get(), charset);
                            list.add(2, "seven");
                            IOUtils.writeLines(list, "\n", out, charset);
                            return list.size();
                        } catch (final IOException err) {
                            throw new ArtipieIOException(err);
                        }
                    }
                );
            }).join(),
            new IsEqual<>(4)
        );
        MatcherAssert.assertThat(
            "Storage item was not updated",
            new String(new BlockingStorage(this.asto).value(key), charset),
            new IsEqual<>("five\nsix\nseven\neight\n")
        );
    }

    @ParameterizedTest
    @CsvSource({
        "key_from,key_to",
        "key_from,key_from"
    })
    void processesExistingLargeSizeItem(
        final String read, final String write
    ) {
        final int size = 1024 * 1024;
        final int bufsize = 128;
        final byte[] data = new byte[size];
        new Random().nextBytes(data);
        final Key kfrom = new Key.From(read);
        final Key kto = new Key.From(write);
        this.asto.save(kfrom, new Content.From(data)).join();
        new StorageValuePipeline<String>(new ReadWithDelaysStorage(this.asto), kfrom, kto)
            .processWithResult(
                (input, out) -> {
                    final byte[] buffer = new byte[bufsize];
                    try {
                        final InputStream stream = input.get();
                        while (stream.read(buffer) != -1) {
                            IOUtils.write(buffer, out);
                            out.flush();
                        }
                        new Random().nextBytes(buffer);
                        IOUtils.write(buffer, out);
                        out.flush();
                    } catch (final IOException err) {
                        throw new ArtipieIOException(err);
                    }
                    return "res";
                }
            ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            new BlockingStorage(this.asto).value(kto).length,
            new IsEqual<>(size + bufsize)
        );
    }

    @Test
    void testContentAsStream() {
        final Charset charset = StandardCharsets.UTF_8;
        final Key kfrom = new Key.From("kfrom");
        this.asto.save(kfrom, new Content.From("one\ntwo\nthree".getBytes(charset))).join();
        final List<String> res = this.asto.value(kfrom).thenCompose(
            content -> new ContentAsStream<List<String>>(content)
                .process(new UncheckedIOFunc<>(
                    input -> org.apache.commons.io.IOUtils.readLines(input, charset)
                ))).join();
        MatcherAssert.assertThat(res, Matchers.contains("one", "two", "three"));
    }
}
