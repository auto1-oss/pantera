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
import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.StorageThrottledException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;

/**
 * Deletes keys with the S3 multi-object delete: {@code DeleteObjects}
 * requests of at most 1000 keys, in quiet mode, one request after another.
 * No request is spent on checking whether a key exists; S3 reports a key
 * that is not there as deleted.
 *
 * @since 2.2.14
 */
final class S3BatchDelete {

    /**
     * Most keys S3 accepts in one request.
     */
    private static final int MAX_KEYS = 1000;

    /**
     * Failed keys named in the error message at most.
     */
    private static final int NAMED = 5;

    /**
     * Per-key error codes that say the key was already gone.
     */
    private static final Set<String> ABSENT = Set.of("NoSuchKey", "NoSuchVersion");

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
    S3BatchDelete(final S3AsyncClient client, final String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * Delete the keys.
     * @param keys Keys
     * @return Completion; fails with {@link StorageThrottledException} when
     *  S3 asks to slow down, with {@link PanteraIOException} naming the
     *  number of keys that failed and the first few of them otherwise
     */
    CompletableFuture<Void> delete(final Collection<Key> keys) {
        final Set<String> distinct = new LinkedHashSet<>(keys.size() * 2);
        for (final Key key : keys) {
            distinct.add(key.string());
        }
        return this.chunk(new ArrayList<>(distinct), 0);
    }

    /**
     * Delete the keys from a position on, one request at a time.
     * @param keys All keys
     * @param from Position of the first key not yet sent
     * @return Completion
     */
    private CompletableFuture<Void> chunk(final List<String> keys, final int from) {
        final CompletableFuture<Void> res;
        if (from >= keys.size()) {
            res = CompletableFuture.completedFuture(null);
        } else {
            final int upto = Math.min(keys.size(), from + S3BatchDelete.MAX_KEYS);
            final List<String> part = keys.subList(from, upto);
            res = this.client.deleteObjects(
                DeleteObjectsRequest.builder()
                    .bucket(this.bucket)
                    .delete(
                        Delete.builder()
                            .quiet(true)
                            .objects(
                                part.stream()
                                    .map(key -> ObjectIdentifier.builder().key(key).build())
                                    .collect(Collectors.toList())
                            )
                            .build()
                    )
                    .build()
            ).handle(
                (resp, err) -> {
                    if (err != null) {
                        throw new S3Failure(err).translated(
                            String.format("multi-object delete of %d keys", part.size())
                        );
                    }
                    S3BatchDelete.check(resp, part.size());
                    return resp;
                }
            ).thenCompose(ignored -> this.chunk(keys, upto));
        }
        return res;
    }

    /**
     * Fail on the per-key errors of an answer.
     * @param resp Answer
     * @param sent Number of keys in the request
     */
    private static void check(final DeleteObjectsResponse resp, final int sent) {
        final List<S3Error> errors = new ArrayList<>(0);
        if (resp != null && resp.hasErrors()) {
            for (final S3Error error : resp.errors()) {
                if (!S3BatchDelete.ABSENT.contains(String.valueOf(error.code()))) {
                    errors.add(error);
                }
            }
        }
        if (!errors.isEmpty()) {
            final String msg = String.format(
                "S3 failed to delete %d of %d keys: %s",
                errors.size(), sent,
                errors.stream()
                    .limit(S3BatchDelete.NAMED)
                    .map(error -> String.format("%s (%s)", error.key(), error.code()))
                    .collect(Collectors.joining(", "))
            );
            final S3Failure codes = new S3Failure(null);
            if (errors.stream().anyMatch(error -> codes.throttling(error.code()))) {
                throw new StorageThrottledException(msg);
            }
            throw new PanteraIOException(msg);
        }
    }
}
