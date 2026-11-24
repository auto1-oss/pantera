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
import com.auto1.pantera.asto.misc.BoundedFanOut;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;

/**
 * Aborts the multipart uploads in progress under a path prefix. Their parts
 * are stored and billed but no object listing shows them, so deleting every
 * key of a prefix leaves them behind.
 *
 * @since 2.2.14
 */
final class S3UploadAbort {

    /**
     * Aborts in flight at most.
     */
    private static final int WIDTH = 8;

    /**
     * S3 client.
     */
    private final S3AsyncClient client;

    /**
     * Bucket name.
     */
    private final String bucket;

    /**
     * Ctor.
     * @param client S3 client
     * @param bucket Bucket name
     */
    S3UploadAbort(final S3AsyncClient client, final String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * Abort the uploads under the prefix.
     * @param prefix Path prefix
     * @return Number of uploads aborted
     */
    CompletableFuture<Integer> abort(final Key prefix) {
        final String str = prefix.string();
        final String inside;
        if (str.isEmpty()) {
            inside = str;
        } else {
            inside = str + Key.DELIMITER;
        }
        final AtomicInteger aborted = new AtomicInteger();
        return this.pages(inside, null, null, aborted)
            .thenApply(ignored -> aborted.get())
            .exceptionallyCompose(
                err -> CompletableFuture.failedFuture(
                    new S3Failure(err).translated(
                        String.format("abort of multipart uploads under '%s'", str)
                    )
                )
            );
    }

    /**
     * List one page of uploads, abort them, go on with the next page.
     * @param inside Request prefix, with trailing delimiter or empty
     * @param key Key marker of the page, or null
     * @param upload Upload id marker of the page, or null
     * @param aborted Counter of aborted uploads
     * @return Completion
     */
    private CompletableFuture<Void> pages(
        final String inside, final String key, final String upload,
        final AtomicInteger aborted
    ) {
        final ListMultipartUploadsRequest.Builder req = ListMultipartUploadsRequest.builder()
            .bucket(this.bucket);
        if (!inside.isEmpty()) {
            req.prefix(inside);
        }
        if (key != null) {
            req.keyMarker(key);
        }
        if (upload != null) {
            req.uploadIdMarker(upload);
        }
        return this.client.listMultipartUploads(req.build()).thenCompose(
            resp -> new BoundedFanOut<MultipartUpload>(
                S3UploadAbort.WIDTH, item -> this.abortOne(item, aborted)
            ).run(resp.uploads()).thenCompose(
                ignored -> {
                    final CompletableFuture<Void> next;
                    if (Boolean.TRUE.equals(resp.isTruncated())
                        && resp.nextKeyMarker() != null && !resp.nextKeyMarker().isEmpty()) {
                        next = this.pages(
                            inside, resp.nextKeyMarker(), resp.nextUploadIdMarker(), aborted
                        );
                    } else {
                        next = CompletableFuture.completedFuture(null);
                    }
                    return next;
                }
            )
        );
    }

    /**
     * Abort one upload; one that is already gone is not an error and is
     * not counted.
     * @param upload Upload
     * @param aborted Counter of aborted uploads
     * @return Completion
     */
    private CompletableFuture<Void> abortOne(
        final MultipartUpload upload, final AtomicInteger aborted
    ) {
        return this.client.abortMultipartUpload(
            AbortMultipartUploadRequest.builder()
                .bucket(this.bucket)
                .key(upload.key())
                .uploadId(upload.uploadId())
                .build()
        ).handle(
            (resp, err) -> {
                final CompletableFuture<Void> res;
                if (err == null) {
                    aborted.incrementAndGet();
                    res = CompletableFuture.completedFuture(null);
                } else if (S3UploadAbort.gone(err)) {
                    res = CompletableFuture.completedFuture(null);
                } else {
                    res = CompletableFuture.failedFuture(err);
                }
                return res;
            }
        ).thenCompose(res -> res);
    }

    /**
     * Whether an abort failed because the upload no longer exists.
     * @param err Failure
     * @return True if so
     */
    private static boolean gone(final Throwable err) {
        Throwable cur = err;
        boolean res = false;
        int depth = 0;
        while (cur != null && !res && depth < 16) {
            res = cur instanceof NoSuchUploadException;
            cur = cur.getCause();
            depth += 1;
        }
        return res;
    }
}
