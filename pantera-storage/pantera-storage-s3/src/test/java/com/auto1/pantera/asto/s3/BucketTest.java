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
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.ListMultipartUploadsRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.io.ByteStreams;
import java.net.URI;
import java.util.UUID;
import org.hamcrest.MatcherAssert;
import org.hamcrest.collection.IsEmptyIterable;
import org.hamcrest.core.IsEqual;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * Tests for {@link Bucket}.
 *
 * @since 0.1
 */
@DisabledOnOs(OS.WINDOWS)
@Testcontainers
class BucketTest {

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
     * Bucket name to use in tests.
     */
    private String name;

    /**
     * Bucket instance being tested.
     */
    private Bucket bucket;

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
        this.name = UUID.randomUUID().toString();
        s3Client.createBucket(this.name);
        final String endpoint = String.format(
            "http://%s:%d",
            S3_MOCK.getHost(),
            S3_MOCK.getMappedPort(9090)
        );
        this.bucket = new Bucket(
            S3AsyncClient.builder()
                .forcePathStyle(true)
                .region(Region.of("us-east-1"))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar"))
                )
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(
                    S3Configuration.builder().build()
                )
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build(),
            this.name
        );
    }

    @Test
    void shouldUploadPartAndCompleteMultipartUpload() throws Exception {
        final String key = "multipart";
        final String id = s3Client.initiateMultipartUpload(
            new InitiateMultipartUploadRequest(this.name, key)
        ).getUploadId();
        final byte[] data = "data".getBytes();
        this.bucket.uploadPart(
            UploadPartRequest.builder()
                .key(key)
                .uploadId(id)
                .partNumber(1)
                .contentLength((long) data.length)
                .build(),
            AsyncRequestBody.fromPublisher(AsyncRequestBody.fromBytes(data))
        ).thenCompose(
            uploaded -> this.bucket.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                    .key(key)
                    .uploadId(id)
                    .multipartUpload(
                        CompletedMultipartUpload.builder()
                            .parts(CompletedPart.builder()
                                .partNumber(1)
                                .eTag(uploaded.eTag())
                                .build()
                            )
                            .build()
                    )
                    .build()
            )
        ).join();
        final byte[] downloaded;
        try (S3Object s3Object = s3Client.getObject(this.name, key)) {
            downloaded = ByteStreams.toByteArray(s3Object.getObjectContent());
        }
        MatcherAssert.assertThat(downloaded, new IsEqual<>(data));
    }

    @Test
    void shouldAbortMultipartUploadWhenFailedToReadContent() {
        final String key = "abort";
        final String id = s3Client.initiateMultipartUpload(
            new InitiateMultipartUploadRequest(this.name, key)
        ).getUploadId();
        final byte[] data = "abort_test".getBytes();
        this.bucket.uploadPart(
            UploadPartRequest.builder()
                .key(key)
                .uploadId(id)
                .partNumber(1)
                .contentLength((long) data.length)
                .build(),
            AsyncRequestBody.fromPublisher(AsyncRequestBody.fromBytes(data))
        ).thenCompose(
            ignore -> this.bucket.abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                    .key(key)
                    .uploadId(id)
                    .build()
            )
        ).join();
        MatcherAssert.assertThat(
            s3Client.listMultipartUploads(
                new ListMultipartUploadsRequest(this.name)
            ).getMultipartUploads(),
            new IsEmptyIterable<>()
        );
    }
}
