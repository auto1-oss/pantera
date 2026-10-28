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
import java.time.Duration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * SigV4 {@link Presigner} for S3 and S3-API-compatible stores, backed by the AWS
 * SDK's own {@link S3Presigner}.
 *
 * <p>Signing is purely local: {@link S3Presigner} computes the canonical request
 * and HMAC signature from the configured credentials/region/endpoint without
 * contacting the store, so {@link #presignGet(Key, long)} never performs network
 * I/O and is safe to call from latency-sensitive request handling.</p>
 *
 * <p>Named to avoid clashing with the AWS SDK's own {@code S3Presigner} class,
 * which it wraps and owns the lifecycle of (see {@link #close()}).</p>
 *
 * @since 2.3.0
 */
final class S3BlobPresigner implements Presigner, AutoCloseable {

    /**
     * AWS SDK presigner: computes SigV4 signatures locally, no network calls.
     */
    private final S3Presigner presigner;

    /**
     * Bucket name.
     */
    private final String bucket;

    /**
     * Ctor.
     *
     * @param presigner AWS SDK presigner, configured with region/endpoint/
     *  credentials/path-style matching the {@link S3Storage} it backs.
     * @param bucket Bucket name.
     */
    S3BlobPresigner(final S3Presigner presigner, final String bucket) {
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public URI presignGet(final Key key, final long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException(
                String.format("ttlSeconds must be positive, got %d", ttlSeconds)
            );
        }
        final PresignedGetObjectRequest presigned = this.presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(ttlSeconds))
                .getObjectRequest(
                    GetObjectRequest.builder()
                        .bucket(this.bucket)
                        .key(key.string())
                        .build()
                )
                .build()
        );
        return URI.create(presigned.url().toString());
    }

    @Override
    public void close() {
        this.presigner.close();
    }
}
