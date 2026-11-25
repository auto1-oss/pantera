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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListPage;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.misc.BoundedFanOut;
import com.auto1.pantera.asto.test.PagedStorageVerification;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.MultipartUpload;

/**
 * Paged listing, batch delete and abort of multipart uploads of
 * {@link S3Storage} against the S3 mock the other tests of this module use.
 *
 * <p>The timeouts are no latency bounds: they turn a listing that never
 * ends into a failure.</p>
 *
 * @since 2.2.14
 */
@DisabledOnOs(OS.WINDOWS)
@Testcontainers
final class S3PagedStorageTest {

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
    private static S3AsyncClient client;

    @BeforeAll
    static void setUpClient() {
        S3PagedStorageTest.client = S3AsyncClient.builder()
            .forcePathStyle(true)
            .region(Region.of("us-east-1"))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar"))
            )
            .endpointOverride(
                URI.create(
                    String.format(
                        "http://%s:%d", S3_MOCK.getHost(), S3_MOCK.getMappedPort(9090)
                    )
                )
            )
            .serviceConfiguration(S3Configuration.builder().build())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build();
    }

    @AfterAll
    static void tearDownClient() {
        if (S3PagedStorageTest.client != null) {
            S3PagedStorageTest.client.close();
        }
    }

    @Test
    @Timeout(300)
    void deletesMoreKeysThanOneRequestHolds() throws Exception {
        final String bucket = S3PagedStorageTest.bucket();
        final Storage storage = new S3Storage(S3PagedStorageTest.client, bucket, "mock");
        final List<Key> keys = IntStream.range(0, 1100)
            .mapToObj(idx -> (Key) new Key.From("big", String.format("k%04d", idx)))
            .collect(Collectors.toList());
        new BoundedFanOut<Key>(
            16,
            key -> storage.save(key, new Content.From("x".getBytes(StandardCharsets.UTF_8)))
        ).run(keys).get(240, TimeUnit.SECONDS);
        storage.save(
            new Key.From("bigger/kept"), new Content.From("x".getBytes(StandardCharsets.UTF_8))
        ).get(30, TimeUnit.SECONDS);
        final List<Key> listed = new ArrayList<>(1100);
        Optional<String> token = Optional.empty();
        do {
            final ListPage page = storage.listPage(new Key.From("big"), token, 1500)
                .get(60, TimeUnit.SECONDS);
            listed.addAll(page.keys());
            token = page.next();
        } while (token.isPresent() && listed.size() < 5000);
        MatcherAssert.assertThat(
            "a page larger than one S3 answer is filled from several",
            listed, new IsEqual<>(keys)
        );
        storage.deleteBatch(keys).get(120, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "all 1100 keys are gone",
            storage.listPage(new Key.From("big"), Optional.empty(), 10)
                .get(60, TimeUnit.SECONDS),
            new IsEqual<>(new ListPage(List.of(), Optional.empty()))
        );
        MatcherAssert.assertThat(
            "a key of a sibling prefix stays",
            storage.exists(new Key.From("bigger/kept")).get(30, TimeUnit.SECONDS),
            new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(120)
    void abortsMultipartUploadsUnderPathPrefixOnly() throws Exception {
        final String bucket = S3PagedStorageTest.bucket();
        for (final String key : List.of("repo/a.bin", "repo/deep/b.bin", "repo-proxy/c.bin")) {
            S3PagedStorageTest.client.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build()
            ).get(30, TimeUnit.SECONDS);
        }
        final Storage storage = new S3Storage(S3PagedStorageTest.client, bucket, "mock");
        MatcherAssert.assertThat(
            "the two uploads inside repo/ are aborted",
            storage.abortIncompleteUploads(new Key.From("repo")).get(60, TimeUnit.SECONDS),
            new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the upload of the sibling prefix is still in progress",
            S3PagedStorageTest.client.listMultipartUploads(
                ListMultipartUploadsRequest.builder().bucket(bucket).build()
            ).get(30, TimeUnit.SECONDS).uploads().stream()
                .map(MultipartUpload::key).collect(Collectors.toList()),
            new IsEqual<>(List.of("repo-proxy/c.bin"))
        );
        MatcherAssert.assertThat(
            "a second run finds nothing to abort",
            storage.abortIncompleteUploads(new Key.From("repo")).get(60, TimeUnit.SECONDS),
            new IsEqual<>(0)
        );
    }

    /**
     * Create a bucket.
     * @return Its name
     */
    private static String bucket() {
        final String name = UUID.randomUUID().toString();
        S3PagedStorageTest.client.createBucket(
            CreateBucketRequest.builder().bucket(name).build()
        ).join();
        return name;
    }

    /**
     * The storage itself.
     * @since 2.2.14
     */
    @Nested
    final class Direct extends PagedStorageVerification {
        @Override
        protected Storage newStorage() {
            return new S3Storage(
                S3PagedStorageTest.client, S3PagedStorageTest.bucket(), "mock"
            );
        }
    }

    /**
     * A sub storage of it, next to keys of sibling prefixes.
     * @since 2.2.14
     */
    @Nested
    final class Sub extends PagedStorageVerification {
        @Override
        protected Storage newStorage() throws Exception {
            final Storage origin = new S3Storage(
                S3PagedStorageTest.client, S3PagedStorageTest.bucket(), "mock"
            );
            this.save(
                origin,
                List.of("tenant/repo-proxy/a.txt", "tenant/repo.old/a.txt", "tenant/zzz.txt")
            );
            return new SubStorage(new Key.From("tenant/repo"), origin);
        }
    }
}
