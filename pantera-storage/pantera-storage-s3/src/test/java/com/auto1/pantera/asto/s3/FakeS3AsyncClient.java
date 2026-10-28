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

import io.reactivex.Flowable;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.StorageClass;

/**
 * In-memory, invocation-counting {@link S3AsyncClient} fake.
 *
 * <p>Every {@link S3AsyncClient} operation method is a {@code default} method on
 * the interface that throws {@link UnsupportedOperationException} unless
 * overridden (only {@code serviceName()}/{@code close()} are truly abstract) --
 * this is the AWS SDK's own supported pattern for lightweight test doubles, so this
 * fake overrides only the handful of operations {@link S3Storage} calls, with no
 * network, no Testcontainers, and per-operation call counters for the "invocation
 * count, not wall clock" testing doctrine (see CLAUDE.md).</p>
 *
 * <p>Note: {@link #putObject(PutObjectRequest, AsyncRequestBody)} drains the
 * request body with a blocking {@code Flowable} collect. That is fine here -- this
 * is synchronous test glue standing in for a real client, not production code
 * subject to the Vert.x event-loop-never-blocks rule.</p>
 *
 * @since 2.3.0
 */
final class FakeS3AsyncClient implements S3AsyncClient {

    /**
     * In-memory object store: key -> bytes.
     */
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    /**
     * Number of {@code headObject} calls.
     */
    private final AtomicInteger headCalls = new AtomicInteger();

    /**
     * Number of {@code getObject} calls.
     */
    private final AtomicInteger getCalls = new AtomicInteger();

    /**
     * Number of {@code putObject} calls.
     */
    private final AtomicInteger putCalls = new AtomicInteger();

    /**
     * Number of {@code deleteObject} calls.
     */
    private final AtomicInteger deleteCalls = new AtomicInteger();

    /**
     * Number of {@code listObjectsV2} calls.
     */
    private final AtomicInteger listCalls = new AtomicInteger();

    /**
     * Storage class captured from the most recent {@code putObject} request.
     */
    private volatile StorageClass lastStorageClass;

    int headCalls() {
        return this.headCalls.get();
    }

    int getCalls() {
        return this.getCalls.get();
    }

    int putCalls() {
        return this.putCalls.get();
    }

    int deleteCalls() {
        return this.deleteCalls.get();
    }

    int listCalls() {
        return this.listCalls.get();
    }

    StorageClass lastStorageClass() {
        return this.lastStorageClass;
    }

    boolean contains(final String key) {
        return this.objects.containsKey(key);
    }

    @Override
    public String serviceName() {
        return "s3";
    }

    @Override
    public void close() {
        // no resources to release in the fake
    }

    @Override
    public CompletableFuture<HeadObjectResponse> headObject(final HeadObjectRequest request) {
        this.headCalls.incrementAndGet();
        final byte[] data = this.objects.get(request.key());
        final CompletableFuture<HeadObjectResponse> result = new CompletableFuture<>();
        if (data == null) {
            result.completeExceptionally(new CompletionException(FakeS3AsyncClient.notFound(request.key())));
        } else {
            result.complete(
                HeadObjectResponse.builder()
                    .contentLength((long) data.length)
                    .eTag("\"fake-etag\"")
                    .build()
            );
        }
        return result;
    }

    @Override
    public <ReturnT> CompletableFuture<ReturnT> getObject(
        final GetObjectRequest request,
        final AsyncResponseTransformer<GetObjectResponse, ReturnT> transformer
    ) {
        this.getCalls.incrementAndGet();
        final byte[] data = this.objects.get(request.key());
        final CompletableFuture<ReturnT> promise = transformer.prepare();
        if (data == null) {
            transformer.exceptionOccurred(new CompletionException(FakeS3AsyncClient.notFound(request.key())));
        } else {
            transformer.onResponse(GetObjectResponse.builder().contentLength((long) data.length).build());
            transformer.onStream(SdkPublisher.adapt(Flowable.just(ByteBuffer.wrap(data))));
        }
        return promise;
    }

    @Override
    public CompletableFuture<PutObjectResponse> putObject(
        final PutObjectRequest request,
        final AsyncRequestBody body
    ) {
        this.putCalls.incrementAndGet();
        this.lastStorageClass = request.storageClass();
        final ByteArrayOutputStream out = Flowable.fromPublisher(body)
            .collectInto(new ByteArrayOutputStream(), (stream, buffer) -> {
                final byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                stream.write(chunk, 0, chunk.length);
            })
            .blockingGet();
        this.objects.put(request.key(), out.toByteArray());
        return CompletableFuture.completedFuture(PutObjectResponse.builder().build());
    }

    @Override
    public CompletableFuture<DeleteObjectResponse> deleteObject(final DeleteObjectRequest request) {
        this.deleteCalls.incrementAndGet();
        this.objects.remove(request.key());
        return CompletableFuture.completedFuture(DeleteObjectResponse.builder().build());
    }

    @Override
    public CompletableFuture<ListObjectsV2Response> listObjectsV2(final ListObjectsV2Request request) {
        this.listCalls.incrementAndGet();
        final String prefix = request.prefix() == null ? "" : request.prefix();
        final List<S3Object> found = this.objects.keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .sorted()
            .map(key -> S3Object.builder().key(key).build())
            .collect(Collectors.toList());
        return CompletableFuture.completedFuture(
            ListObjectsV2Response.builder().contents(found).isTruncated(false).build()
        );
    }

    private static NoSuchKeyException notFound(final String key) {
        return NoSuchKeyException.builder().message("not found: " + key).build();
    }
}
