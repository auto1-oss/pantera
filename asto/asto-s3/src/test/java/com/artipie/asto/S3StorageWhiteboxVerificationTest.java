/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto;

import com.artipie.asto.s3.S3Storage;
import com.artipie.asto.test.StorageWhiteboxVerification;
import java.net.URI;
import java.util.UUID;
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

/**
 * S3 storage verification test.
 *
 * @since 0.1
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@DisabledOnOs(OS.WINDOWS)
@Testcontainers
public final class S3StorageWhiteboxVerificationTest extends StorageWhiteboxVerification {

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

    @Override
    protected Storage newStorage() {
        final String endpoint = String.format(
            "http://%s:%d",
            S3_MOCK.getHost(),
            S3_MOCK.getMappedPort(9090)
        );
        final S3AsyncClient client = S3AsyncClient.builder()
            .forcePathStyle(true)
            .region(Region.of("us-east-1"))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("foo", "bar")
                )
            )
            .endpointOverride(URI.create(endpoint))
            .serviceConfiguration(
                S3Configuration.builder().build()
            )
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build();
        final String bucket = UUID.randomUUID().toString();
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).join();
        return new S3Storage(client, bucket, endpoint);
    }

}
