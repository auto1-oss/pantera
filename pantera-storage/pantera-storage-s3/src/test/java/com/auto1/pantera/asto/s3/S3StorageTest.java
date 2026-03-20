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
import com.amazonaws.services.s3.model.ListMultipartUploadsRequest;
import com.amazonaws.services.s3.model.MultipartUpload;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;
import com.google.common.io.ByteStreams;
import io.reactivex.Flowable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsEmptyIterable;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Tests for {@link S3Storage}.
 */
@DisabledOnOs(OS.WINDOWS)
@Testcontainers
class S3StorageTest {
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
    void shouldUploadObjectWhenSave() throws Exception {
        final byte[] data = "data2".getBytes();
        final String key = "a/b/c";
        this.storage().save(new Key.From(key), new Content.OneTime(new Content.From(data))).join();
        MatcherAssert.assertThat(this.download(key), Matchers.equalTo(data));
    }

    @Test
    @Timeout(5)
    void shouldUploadObjectWhenSaveContentOfUnknownSize() throws Exception {
        final byte[] data = "data?".getBytes();
        final String key = "unknown/size";
        this.storage().save(
            new Key.From(key),
            new Content.OneTime(new Content.From(data))
        ).join();
        MatcherAssert.assertThat(this.download(key), Matchers.equalTo(data));
    }

    @Test
    @Timeout(15)
    void shouldUploadObjectWhenSaveLargeContent() throws Exception {
        final int size = 20 * 1024 * 1024;
        final byte[] data = new byte[size];
        new Random().nextBytes(data);
        final String key = "big/data";
        this.storage().save(
            new Key.From(key),
            new Content.OneTime(new Content.From(data))
        ).join();
        MatcherAssert.assertThat(this.download(key), Matchers.equalTo(data));
    }

    @Test
    @Timeout(150)
    void shouldUploadLargeChunkedContent() throws Exception {
        final String key = "big/data";
        final int s3MinPartSize = 5 * 1024 * 1024;
        final int s3MinMultipart = 10 * 1024 * 1024;
        final int totalSize = s3MinMultipart * 2;
        final Random rnd = new Random();
        final byte[] sentData = new byte[totalSize];
        final Callable<Flowable<ByteBuffer>> getGenerator = () -> Flowable.generate(
            AtomicInteger::new,
            (counter, output) -> {
                final int sent = counter.get();
                final int sz = Math.min(totalSize - sent, rnd.nextInt(s3MinPartSize));
                counter.getAndAdd(sz);
                if (sent < totalSize) {
                    final byte[] data = new byte[sz];
                    rnd.nextBytes(data);
                    for (int i = 0, j = sent; i < data.length; ++i, ++j) {
                        sentData[j] = data[i];
                    }
                    output.onNext(ByteBuffer.wrap(data));
                } else {
                    output.onComplete();
                }
                return counter;
            });
        MatcherAssert.assertThat("Generator results mismatch",
            new Content.From(getGenerator.call()).asBytes(),
            Matchers.equalTo(sentData)
        );
        this.storage().save(new Key.From(key), new Content.From(getGenerator.call())).join();
        MatcherAssert.assertThat("Saved results mismatch (S3 client)",
            this.download(key), Matchers.equalTo(sentData)
        );
        MatcherAssert.assertThat("Saved results mismatch (S3Storage)",
            this.storage().value(new Key.From(key)).toCompletableFuture().get().asBytes(),
            Matchers.equalTo(sentData)
        );
    }

    @Test
    void shouldAbortMultipartUploadWhenFailedToReadContent() {
        this.storage().save(
            new Key.From("abort"),
            new Content.OneTime(new Content.From(Flowable.error(new IllegalStateException())))
        ).exceptionally(ignore -> null).join();
        final List<MultipartUpload> uploads = s3Client.listMultipartUploads(
            new ListMultipartUploadsRequest(this.bucket)
        ).getMultipartUploads();
        MatcherAssert.assertThat(uploads, new IsEmptyIterable<>());
    }

    @Test
    void shouldExistForSavedObject() throws Exception {
        final byte[] data = "content".getBytes();
        final String key = "some/existing/key";
        s3Client.putObject(this.bucket, key, new ByteArrayInputStream(data), new ObjectMetadata());
        final boolean exists = new BlockingStorage(this.storage())
            .exists(new Key.From(key));
        MatcherAssert.assertThat(
            exists,
            Matchers.equalTo(true)
        );
    }

    @Test
    void shouldListKeysInOrder() throws Exception {
        final byte[] data = "some data!".getBytes();
        Arrays.asList(
            new Key.From("1"),
            new Key.From("a", "b", "c", "1"),
            new Key.From("a", "b", "2"),
            new Key.From("a", "z"),
            new Key.From("z")
        ).forEach(
            key -> s3Client.putObject(
                this.bucket,
                key.string(),
                new ByteArrayInputStream(data),
                new ObjectMetadata()
            )
        );
        final Collection<String> keys = new BlockingStorage(this.storage())
            .list(new Key.From("a", "b"))
            .stream()
            .map(Key::string)
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            keys,
            Matchers.equalTo(Arrays.asList("a/b/2", "a/b/c/1"))
        );
    }

    @Test
    void shouldGetObjectWhenLoad() throws Exception {
        final byte[] data = "data".getBytes();
        final String key = "some/key";
        s3Client.putObject(this.bucket, key, new ByteArrayInputStream(data), new ObjectMetadata());
        final byte[] value = new BlockingStorage(this.storage())
            .value(new Key.From(key));
        MatcherAssert.assertThat(
            value,
            new IsEqual<>(data)
        );
    }

    @Test
    void shouldCopyObjectWhenMoved() throws Exception {
        final byte[] original = "something".getBytes();
        final String source = "source";
        s3Client.putObject(
            this.bucket,
            source, new ByteArrayInputStream(original),
            new ObjectMetadata()
        );
        final String destination = "destination";
        new BlockingStorage(this.storage()).move(
            new Key.From(source),
            new Key.From(destination)
        );
        try (S3Object s3Object = s3Client.getObject(this.bucket, destination)) {
            MatcherAssert.assertThat(
                ByteStreams.toByteArray(s3Object.getObjectContent()),
                new IsEqual<>(original)
            );
        }
    }

    @Test
    void shouldDeleteOriginalObjectWhenMoved() throws Exception {
        final String source = "src";
        s3Client.putObject(
            this.bucket,
            source,
            new ByteArrayInputStream("some data".getBytes()),
            new ObjectMetadata()
        );
        new BlockingStorage(this.storage()).move(
            new Key.From(source),
            new Key.From("dest")
        );
        MatcherAssert.assertThat(
            s3Client.doesObjectExist(this.bucket, source),
            new IsEqual<>(false)
        );
    }

    @Test
    void shouldDeleteObject() throws Exception {
        final byte[] data = "to be deleted".getBytes();
        final String key = "to/be/deleted";
        s3Client.putObject(this.bucket, key, new ByteArrayInputStream(data), new ObjectMetadata());
        new BlockingStorage(this.storage()).delete(new Key.From(key));
        MatcherAssert.assertThat(
            s3Client.doesObjectExist(this.bucket, key),
            new IsEqual<>(false)
        );
    }

    @Test
    void readMetadata() throws Exception {
        final String key = "random/data";
        s3Client.putObject(
            this.bucket, key,
            new ByteArrayInputStream("random data".getBytes()), new ObjectMetadata()
        );
        final Meta meta = this.storage().metadata(new Key.From(key)).join();
        MatcherAssert.assertThat(
            "size",
            meta.read(Meta.OP_SIZE).get(),
            new IsEqual<>(11L)
        );
        MatcherAssert.assertThat(
            "MD5",
            meta.read(Meta.OP_MD5).get(),
            new IsEqual<>("3e58b24739a19c3e2e1b21bac818c6cd")
        );
    }

    @Test
    void returnsIdentifier() {
        MatcherAssert.assertThat(
            this.storage().identifier(),
            Matchers.stringContainsInOrder(
                "S3", S3_MOCK.getHost(), String.valueOf(S3_MOCK.getMappedPort(9090)), this.bucket
            )
        );
    }

    @Test
    @Timeout(60)
    void shouldListMoreThan1000Objects() throws Exception {
        final int total = 1050;
        final String prefix = "many/";
        final ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(1);
        for (int idx = 0; idx < total; idx += 1) {
            s3Client.putObject(
                this.bucket,
                String.format("%s%04d", prefix, idx),
                new ByteArrayInputStream(new byte[]{1}),
                meta
            );
        }
        final Collection<Key> keys = this.storage()
            .list(new Key.From("many")).join();
        MatcherAssert.assertThat(
            "should list all objects beyond S3 1000 page limit",
            keys.size(),
            new IsEqual<>(total)
        );
    }

    @Test
    @Timeout(60)
    void shouldListMoreThan1000ObjectsWithDelimiter() throws Exception {
        final int total = 1050;
        final ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(1);
        for (int idx = 0; idx < total; idx += 1) {
            s3Client.putObject(
                this.bucket,
                String.format("multi/sub%04d/file.txt", idx),
                new ByteArrayInputStream(new byte[]{1}),
                meta
            );
        }
        final ListResult result = this.storage()
            .list(new Key.From("multi"), "/").join();
        MatcherAssert.assertThat(
            "should list all directory prefixes beyond S3 page limit",
            result.directories().size(),
            new IsEqual<>(total)
        );
    }

    private byte[] download(final String key) throws IOException {
        try (S3Object s3Object = s3Client.getObject(this.bucket, key)) {
            return ByteStreams.toByteArray(s3Object.getObjectContent());
        }
    }

    private Storage storage() {
        final String endpoint = String.format(
            "http://%s:%d",
            S3_MOCK.getHost(),
            S3_MOCK.getMappedPort(9090)
        );
        return StoragesLoader.STORAGES
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
}
