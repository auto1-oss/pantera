/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.s3;

import com.artipie.asto.ArtipieIOException;
import com.artipie.asto.Content;
import com.artipie.asto.FailedCompletionStage;
import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.asto.UnderLockOperation;
import com.artipie.asto.ValueNotFoundException;
import com.artipie.asto.lock.storage.StorageLock;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Publisher;
import io.reactivex.Flowable;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.ChecksumMode;

/**
 * Storage that holds data in S3 storage.
 *
 * @since 0.1
 * @todo #87:60min Do not await abort to complete if save() failed.
 *  In case uploading content fails inside {@link S3Storage#save(Key, Content)} method
 *  we are doing abort() for multipart upload.
 *  Also whole operation does not complete until abort() is complete.
 *  It would be better to finish save() operation right away and do abort() in background,
 *  but it makes testing the method difficult.
 */
@SuppressWarnings("PMD.TooManyMethods")
public final class S3Storage implements Storage {

    /**
     * Minimum content size to consider uploading it as multipart.
     */
    private static final long MIN_MULTIPART = 10 * 1024 * 1024;
    /**
     * Default minimum content size to consider uploading it as multipart.
     */
    private static final long DEFAULT_MIN_MULTIPART = 16 * 1024 * 1024;

    /**
     * S3 client.
     */
    private final S3AsyncClient client;

    /**
     * Bucket name.
     */
    private final String bucket;

    /**
     * Multipart allowed flag.
     */
    private final boolean multipart;

    /**
     * Minimum content size threshold for multipart (configurable).
     */
    private final long minmp;

    /**
     * Multipart part size in bytes.
     */
    private final int partsize;

    /**
     * Multipart upload concurrency.
     */
    private final int mpconc;

    /**
     * Checksum algorithm to use for uploads.
     */
    private final ChecksumAlgorithm checksum;

    /**
     * Server-side encryption type (null to omit).
     */
    private final ServerSideEncryption sse;

    /**
     * Optional KMS key id for SSE-KMS.
     */
    private final String kms;

    /**
     * Enable parallel download.
     */
    private final boolean parallelDownload;

    /**
     * Parallel download threshold.
     */
    private final long parallelThreshold;

    /**
     * Parallel download chunk size.
     */
    private final int parallelChunk;

    /**
     * Parallel download concurrency.
     */
    private final int parallelConc;

    /**
     * S3 storage identifier: endpoint of the storage S3 client and bucket id.
     */
    private final String id;

    /**
     * Ctor.
     *
     * @param client S3 client.
     * @param bucket Bucket name.
     * @param endpoint S3 client endpoint
     */
    public S3Storage(final S3AsyncClient client, final String bucket, final String endpoint) {
        this(
            client,
            bucket,
            true,
            endpoint,
            DEFAULT_MIN_MULTIPART,
            16 * 1024 * 1024,
            32,
            ChecksumAlgorithm.SHA256,
            null,
            null,
            false,
            64L * 1024 * 1024,
            8 * 1024 * 1024,
            16
        );
    }

    /**
     * Ctor.
     *
     * @param client S3 client.
     * @param bucket Bucket name.
     * @param multipart Multipart allowed flag.
     *  <code>true</code> - if multipart feature is allowed for larger blobs,
     *  <code>false</code> otherwise.
     * @param endpoint S3 client endpoint
     */
    public S3Storage(final S3AsyncClient client, final String bucket, final boolean multipart,
        final String endpoint) {
        this(
            client,
            bucket,
            multipart,
            endpoint,
            DEFAULT_MIN_MULTIPART,
            16 * 1024 * 1024,
            32,
            ChecksumAlgorithm.SHA256,
            null,
            null,
            false,
            64L * 1024 * 1024,
            8 * 1024 * 1024,
            16
        );
    }

    /**
     * Ctor with extended options.
     *
     * @param client S3 client.
     * @param bucket Bucket name.
     * @param multipart Allow multipart uploads.
     * @param endpoint S3 client endpoint (for identifier only).
     * @param minmp Multipart threshold in bytes.
     * @param partsize Multipart part size in bytes.
     * @param mpconc Multipart upload concurrency.
     * @param checksum Upload checksum algorithm.
     * @param sse Server-side encryption type (or null).
     * @param kms KMS key id (optional, for SSE-KMS).
     * @param parallelDownload Enable parallel downloads.
     * @param parallelThreshold Threshold for parallel downloads.
     * @param parallelChunk Chunk size for parallel downloads.
     * @param parallelConc Concurrency for parallel downloads.
     */
    public S3Storage(
        final S3AsyncClient client,
        final String bucket,
        final boolean multipart,
        final String endpoint,
        final long minmp,
        final int partsize,
        final int mpconc,
        final ChecksumAlgorithm checksum,
        final ServerSideEncryption sse,
        final String kms,
        final boolean parallelDownload,
        final long parallelThreshold,
        final int parallelChunk,
        final int parallelConc
    ) {
        this.client = client;
        this.bucket = bucket;
        this.multipart = multipart;
        this.minmp = minmp;
        this.partsize = partsize;
        this.mpconc = mpconc;
        this.checksum = checksum;
        this.sse = sse;
        this.kms = kms;
        this.parallelDownload = parallelDownload;
        this.parallelThreshold = parallelThreshold;
        this.parallelChunk = parallelChunk;
        this.parallelConc = parallelConc;
        this.id = String.format("S3: %s %s", endpoint, this.bucket);
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        final CompletableFuture<Boolean> exists = new CompletableFuture<>();
        this.client.headObject(
            HeadObjectRequest.builder()
                .bucket(this.bucket)
                .key(key.string())
                .build()
        ).handle(
            (response, throwable) -> {
                if (throwable == null) {
                    exists.complete(true);
                } else if (throwable.getCause() instanceof NoSuchKeyException) {
                    exists.complete(false);
                } else {
                    exists.completeExceptionally(new ArtipieIOException(throwable));
                }
                return response;
            }
        );
        return exists;
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key prefix) {
        return this.client.listObjects(
            ListObjectsRequest.builder()
                .bucket(this.bucket)
                .prefix(prefix.string())
                .build()
        ).thenApply(
            response -> response.contents()
                .stream()
                .map(S3Object::key)
                .map(Key.From::new)
                .collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        final CompletionStage<Content> result;
        // Don't wrap in OneTime - AWS SDK needs to retry on throttling/errors
        if (this.multipart) {
            result = new EstimatedContentCompliment(content, S3Storage.MIN_MULTIPART)
                .estimate();
        } else {
            result = new EstimatedContentCompliment(content).estimate();
        }
        return result.thenCompose(
            estimated -> {
                final CompletionStage<Void> res;
                if (this.isMultipartRequired(estimated))
                {
                    res = this.putMultipart(key, estimated);
                } else {
                    res = this.put(key, estimated);
                }
                return res;
            }
        ).toCompletableFuture();
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        return this.client.copyObject(
            CopyObjectRequest.builder()
                .sourceBucket(this.bucket)
                .sourceKey(source.string())
                .destinationBucket(this.bucket)
                .destinationKey(destination.string())
                .build()
        ).thenCompose(
            copied -> this.client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(source.string())
                    .build()
            ).thenCompose(
                deleted -> CompletableFuture.allOf()
            )
        );
    }

    @Override
    public CompletableFuture<? extends Meta> metadata(final Key key) {
        return this.client.headObject(
            HeadObjectRequest.builder()
                .bucket(this.bucket)
                .key(key.string())
                .build()
        ).thenApply(S3HeadMeta::new).handle(
            new InternalExceptionHandle<>(
                NoSuchKeyException.class,
                cause -> new ValueNotFoundException(key, cause)
            )
        ).thenCompose(Function.identity());
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        final CompletableFuture<Content> promise = new CompletableFuture<>();
        if (this.parallelDownload) {
            this.client.headObject(
                HeadObjectRequest.builder().bucket(this.bucket).key(key.string()).build()
            ).whenComplete((head, err) -> {
                if (err == null && head.contentLength() != null
                    && head.contentLength() >= this.parallelThreshold) {
                    final long size = head.contentLength();
                    final int chunks = (int) Math.max(1, (size + this.parallelChunk - 1) / this.parallelChunk);
                    final Flowable<ByteBuffer> stream = Flowable
                        .range(0, chunks)
                        .concatMapEager(
                            idx -> Flowable.fromPublisher(
                                this.rangePublisher(key,
                                    idx * (long) this.parallelChunk,
                                    Math.min(size - 1, (idx + 1L) * (long) this.parallelChunk - 1)
                                )
                            ),
                            this.parallelConc,
                            this.parallelConc
                        );
                    promise.complete(new Content.From(Optional.of(size), stream));
                } else {
                    this.client.getObject(
                        GetObjectRequest.builder()
                            .bucket(this.bucket)
                            .key(key.string())
                            .checksumMode(ChecksumMode.ENABLED)
                            .build(),
                        new ResponseAdapter(promise)
                    );
                }
            });
        } else {
            this.client.getObject(
                GetObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(key.string())
                    .build(),
                new ResponseAdapter(promise)
            );
        }
        return promise
            .handle(
                new InternalExceptionHandle<>(
                    NoSuchKeyException.class,
                    cause -> new ValueNotFoundException(key, cause)
                )
            )
            .thenCompose(Function.identity())
            .thenApply(Content.OneTime::new);
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        return this.exists(key).thenCompose(
            exists -> {
                final CompletionStage<Void> deleted;
                if (exists) {
                    deleted = this.client.deleteObject(
                        DeleteObjectRequest.builder()
                            .bucket(this.bucket)
                            .key(key.string())
                            .build()
                    ).thenCompose(
                        response -> CompletableFuture.allOf()
                    );
                } else {
                    deleted = new FailedCompletionStage<>(
                        new ArtipieIOException(String.format("Key does not exist: %s", key))
                    );
                }
                return deleted;
            }
        );
    }

    @Override
    public <T> CompletionStage<T> exclusively(
        final Key key,
        final Function<Storage, CompletionStage<T>> operation
    ) {
        return new UnderLockOperation<>(new StorageLock(this, key), operation).perform(this);
    }

    @Override
    public String identifier() {
        return this.id;
    }

    /**
     * Uploads content using put request.
     *
     * @param key Object key.
     * @param content Object content to be uploaded.
     * @return Completion stage which is completed when response received from S3.
     */
    private CompletableFuture<Void> put(final Key key, final Content content) {
        final PutObjectRequest.Builder req = PutObjectRequest.builder()
            .bucket(this.bucket)
            .key(key.string());
        if (this.sse != null) {
            req.serverSideEncryption(this.sse);
            if (this.sse == ServerSideEncryption.AWS_KMS && this.kms != null) {
                req.ssekmsKeyId(this.kms);
            }
        }
        // Stream directly without buffering entire content in memory
        // This reduces memory usage from 3x file size to streaming buffers only
        if (this.checksum != null && this.checksum != ChecksumAlgorithm.SHA256) {
            req.checksumAlgorithm(this.checksum);
        }
        // For SHA256, AWS SDK will calculate it during streaming
        return this.client.putObject(
            req.build(),
            new ContentBody(content)
        ).thenApply(ignored -> null);
    }

    /**
     * Save multipart.
     *
     * @param key The key of value to be saved.
     * @param updated The estimated content.
     * @return The future.
     */
    private CompletableFuture<Void> putMultipart(final Key key, final Content updated) {
        final CreateMultipartUploadRequest.Builder mpreq = CreateMultipartUploadRequest.builder()
            .bucket(this.bucket)
            .key(key.string());
        if (this.sse != null) {
            mpreq.serverSideEncryption(this.sse);
            if (this.sse == ServerSideEncryption.AWS_KMS && this.kms != null) {
                mpreq.ssekmsKeyId(this.kms);
            }
        }
        if (this.checksum == ChecksumAlgorithm.SHA256) {
            mpreq.checksumAlgorithm(this.checksum);
        }
        return this.client.createMultipartUpload(mpreq.build()).thenApply(
            created -> new MultipartUpload(
                new Bucket(this.client, this.bucket),
                key,
                created.uploadId(),
                this.partsize,
                this.mpconc,
                this.checksum
            )
        ).thenCompose(
            upload -> upload.upload(updated).handle(
                (ignored, throwable) -> {
                    final CompletionStage<Void> finished;
                    if (throwable == null) {
                        finished = upload.complete();
                    } else {
                        final CompletableFuture<Void> promise =
                            new CompletableFuture<>();
                        finished = promise;
                        upload.abort().whenComplete(
                            (ignore, ex) -> promise.completeExceptionally(
                                new ArtipieIOException(throwable)
                            )
                        );
                    }
                    return finished;
                }
            ).thenCompose(Function.identity())
        );
    }

    /**
     * Checks if multipart save is required for provided Content.
     * @param content Content with input data.
     * @return true, if Content requires multipart processing.
     */
    private boolean isMultipartRequired(final Content content) {
        return this.multipart && (
            content.size().isEmpty() ||
                content.size().filter(x -> x >= this.minmp).isPresent()
        );
    }

    private Publisher<ByteBuffer> rangePublisher(final Key key, final long start, final long end) {
        final CompletableFuture<Content> res = new CompletableFuture<>();
        this.client.getObject(
            GetObjectRequest.builder()
                .bucket(this.bucket)
                .key(key.string())
                .range(String.format("bytes=%d-%d", start, end))
                .build(),
            new ResponseAdapter(res)
        );
        return res.thenApply(c -> (Publisher<ByteBuffer>) c).join();
    }


    /**
     * {@link AsyncRequestBody} created from {@link Content}.
     *
     * @since 0.16
     */
    private static class ContentBody implements AsyncRequestBody {

        /**
         * Data source for request body.
         */
        private final Content source;

        /**
         * Ctor.
         *
         * @param source Data source for request body.
         */
        ContentBody(final Content source) {
            this.source = source;
        }

        @Override
        public Optional<Long> contentLength() {
            return this.source.size();
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
            this.source.subscribe(subscriber);
        }
    }

    /**
     * Adapts {@link AsyncResponseTransformer} to {@link CompletableFuture}.
     *
     * @since 0.15
     */
    private static class ResponseAdapter
        implements AsyncResponseTransformer<GetObjectResponse, Content> {

        /**
         * Promise of response body.
         */
        private final CompletableFuture<Content> promise;

        /**
         * Content length received in response.
         */
        private Long length;

        /**
         * Ctor.
         *
         * @param promise Promise of response body.
         */
        ResponseAdapter(final CompletableFuture<Content> promise) {
            this.promise = promise;
        }

        @Override
        public CompletableFuture<Content> prepare() {
            return this.promise;
        }

        @Override
        public void onResponse(final GetObjectResponse response) {
            this.length = response.contentLength();
        }

        @Override
        public void onStream(final SdkPublisher<ByteBuffer> publisher) {
            this.promise.complete(new Content.From(Optional.ofNullable(this.length), publisher));
        }

        @Override
        public void exceptionOccurred(final Throwable throwable) {
            this.promise.completeExceptionally(throwable);
        }
    }
}
