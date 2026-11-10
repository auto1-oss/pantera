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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blob.Presigner;
import java.net.URI;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Proves {@link S3BlobPresigner} issues a valid, correctly-shaped SigV4 presigned
 * GET URL entirely offline -- signing is local (spec WS1 &sect;B2: "zero
 * blob-store round trip"), so no S3/MinIO server is needed to verify the URL
 * structure and signature query parameters.
 */
@Timeout(10)
final class S3BlobPresignerTest {

    @Test
    void presignsPathStyleUrlWithSigV4QueryParams() {
        final Presigner presigner = S3BlobPresignerTest.presigner(true);
        final URI url = presigner.presignGet(new Key.From("group/artifact/1.0/artifact-1.0.jar"), 900);
        MatcherAssert.assertThat("path-style: bucket is a path segment, not a subdomain",
            url.getHost(), new IsEqual<>("localhost"));
        MatcherAssert.assertThat(url.getPath(),
            new StringStartsWith("/my-bucket/group/artifact/1.0/artifact-1.0.jar"));
        MatcherAssert.assertThat(url.getQuery(), new StringContains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
        MatcherAssert.assertThat(url.getQuery(), new StringContains("X-Amz-Expires=900"));
        MatcherAssert.assertThat(url.getQuery(), new StringContains("X-Amz-Signature="));
        MatcherAssert.assertThat(url.getQuery(), new StringContains("X-Amz-Credential="));
    }

    @Test
    void presignsVirtualHostedStyleUrlWhenPathStyleDisabled() {
        final Presigner presigner = S3BlobPresignerTest.presigner(false);
        final URI url = presigner.presignGet(new Key.From("a/b"), 900);
        MatcherAssert.assertThat(
            "virtual-hosted-style: bucket is prepended as a subdomain",
            url.getHost(),
            new IsEqual<>("my-bucket.localhost")
        );
        MatcherAssert.assertThat(url.getPath(), new StringStartsWith("/a/b"));
    }

    @Test
    void rejectsNonPositiveTtl() {
        final Presigner presigner = S3BlobPresignerTest.presigner(true);
        try {
            presigner.presignGet(new Key.From("k"), 0);
            throw new AssertionError("expected IllegalArgumentException for ttlSeconds=0");
        } catch (final IllegalArgumentException expected) {
            MatcherAssert.assertThat(expected.getMessage(), new StringContains("ttlSeconds"));
        }
        try {
            presigner.presignGet(new Key.From("k"), -5);
            throw new AssertionError("expected IllegalArgumentException for negative ttlSeconds");
        } catch (final IllegalArgumentException expected) {
            MatcherAssert.assertThat(expected.getMessage(), new StringContains("ttlSeconds"));
        }
    }

    @Test
    void closeDelegatesToUnderlyingSdkPresignerExactlyOnce() {
        final RecordingSdkPresigner sdk = new RecordingSdkPresigner();
        final S3BlobPresigner blob = new S3BlobPresigner(sdk, "my-bucket");
        blob.close();
        MatcherAssert.assertThat("close() delegates exactly once", sdk.closeCalls, new IsEqual<>(1));
    }

    /**
     * Minimal {@link S3Presigner} fake used only to prove
     * {@link S3BlobPresigner#close()} delegates -- an invocation-count check
     * rather than relying on unspecified post-close SDK behavior.
     */
    private static final class RecordingSdkPresigner implements S3Presigner {

        private int closeCalls;

        @Override
        public void close() {
            this.closeCalls += 1;
        }

        @Override
        public software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest presignGetObject(
            final software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest request
        ) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest presignPutObject(
            final software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest request
        ) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public software.amazon.awssdk.services.s3.presigner.model.PresignedDeleteObjectRequest presignDeleteObject(
            final software.amazon.awssdk.services.s3.presigner.model.DeleteObjectPresignRequest request
        ) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public software.amazon.awssdk.services.s3.presigner.model.PresignedCreateMultipartUploadRequest
            presignCreateMultipartUpload(
                final software.amazon.awssdk.services.s3.presigner.model.CreateMultipartUploadPresignRequest request
            ) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest presignUploadPart(
            final software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest request
        ) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public software.amazon.awssdk.services.s3.presigner.model.PresignedCompleteMultipartUploadRequest
            presignCompleteMultipartUpload(
                final software.amazon.awssdk.services.s3.presigner.model.CompleteMultipartUploadPresignRequest request
            ) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public software.amazon.awssdk.services.s3.presigner.model.PresignedAbortMultipartUploadRequest
            presignAbortMultipartUpload(
                final software.amazon.awssdk.services.s3.presigner.model.AbortMultipartUploadPresignRequest request
            ) {
            throw new UnsupportedOperationException("not needed for this test");
        }
    }

    private static Presigner presigner(final boolean pathStyle) {
        final S3Presigner sdk = S3Presigner.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create("http://localhost:9000"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAFAKE", "secretFake"))
            )
            .build();
        return new S3BlobPresigner(sdk, "my-bucket");
    }
}
