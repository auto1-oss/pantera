/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.s3;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Merging;
import com.artipie.asto.Splitting;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.reactivestreams.Publisher;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;

/**
 * Multipart upload of S3 object.
 *
 * @since 0.1
 */
final class MultipartUpload {

    /**
     * Minimum part size.
     * See <a href="https://docs.aws.amazon.com/AmazonS3/latest/dev/qfacts.html">
     * Amazon S3 multipart upload limits</a>
     */
    private static final int MIN_PART_SIZE = 5 * 1024 * 1024;

    /**
     * Bucket.
     */
    private final Bucket bucket;

    /**
     * S3 object key.
     */
    private final Key key;

    /**
     * ID of this upload.
     */
    private final String id;

    /**
     * Uploaded parts.
     */
    private final List<UploadedPart> parts;

    /**
     * Configured part size.
     */
    private final int partsize;

    /**
     * Max concurrent part uploads.
     */
    private final int concurrency;

    /**
     * Checksum algorithm to use (may be null or unsupported for parts).
     */
    private final ChecksumAlgorithm checksum;

    /**
     * Ctor.
     *
     * @param bucket Bucket.
     * @param key S3 object key.
     * @param id ID of this upload.
     * @param partsize Part size in bytes.
     * @param concurrency Max concurrent uploads.
     * @param checksum Checksum algorithm.
     */
    MultipartUpload(final Bucket bucket, final Key key, final String id,
        final int partsize, final int concurrency, final ChecksumAlgorithm checksum) {
        this.bucket = bucket;
        this.key = key;
        this.id = id;
        this.parts = new CopyOnWriteArrayList<>();
        this.partsize = Math.max(partsize, MultipartUpload.MIN_PART_SIZE);
        this.concurrency = Math.max(1, concurrency);
        this.checksum = checksum;
    }

    /**
     * Uploads all content by parts.
     * Note that content part must be at least MultipartUpload.MIN_PART_SIZE except last part.
     * Note that we send one request with chunk data at time. We shouldn't send all chunks/requests in parallel,
     * since may overload request pool of the S3 client or limits of the server.
     *
     * @param content Object content to be uploaded in parts.
     * @return Completion stage which is completed when responses received from S3 for all parts.
     */
    public CompletionStage<Void> upload(final Content content) {
        final AtomicInteger counter = new AtomicInteger();
        return new Merging(this.partsize, this.partsize * 2).mergeFlow(
            Flowable.fromPublisher(content).concatMap(
                buffer -> Flowable.fromPublisher(
                    new Splitting(buffer, this.partsize).publisher()
                )
            )
        ).flatMap(payload -> {
            final int pnum = counter.incrementAndGet();
            return Flowable.fromFuture(
                this.uploadPart(pnum, payload).thenApply(
                    response -> {
                        this.parts.add(new UploadedPart(pnum, response.eTag(), response.checksumSHA256()));
                        return 1;
                    }
                ).toCompletableFuture()
            );
        }, this.concurrency)
            .count()
            .to(SingleInterop.get())
            .thenApply(count -> (Void) null);
    }

    /**
     * Completes the upload.
     *
     * @return Completion stage which is completed when success response received from S3.
     */
    public CompletionStage<Void> complete() {
        return this.bucket.completeMultipartUpload(
            CompleteMultipartUploadRequest.builder()
                .key(this.key.string())
                .uploadId(this.id)
                .multipartUpload(
                    CompletedMultipartUpload.builder()
                        .parts(
                            this.parts.stream()
                                .sorted(Comparator.comparingInt(p -> p.pnum))
                                .map(
                                    UploadedPart::completedPart
                                ).collect(Collectors.toList())
                        ).build()
                )
                .build()
        ).thenApply(ignored -> null);
    }

    /**
     * Aborts the upload.
     *
     * @return Completion stage which is completed when success response received from S3.
     */
    public CompletionStage<Void> abort() {
        return this.bucket.abortMultipartUpload(
            AbortMultipartUploadRequest.builder()
                .key(this.key.string())
                .uploadId(this.id)
                .build()
        ).thenApply(ignored -> null);
    }

    /**
     * Uploads part.
     *
     * @param part Part number.
     * @param content Part content to be uploaded.
     * @return Completion stage which is completed when success response received from S3.
     */
    private CompletionStage<UploadPartResponse> uploadPart(final int part, final ByteBuffer payload) {
        final long length = payload.remaining();
        final UploadPartRequest.Builder req = UploadPartRequest.builder()
            .key(this.key.string())
            .uploadId(this.id)
            .partNumber(part)
            .contentLength(length);
        if (this.checksum == ChecksumAlgorithm.SHA256) {
            final ByteBuffer dup = payload.asReadOnlyBuffer();
            final byte[] arr = new byte[dup.remaining()];
            dup.get(arr);
            final String b64 = base64Sha256(arr);
            req.checksumSHA256(b64);
        }
        final Publisher<ByteBuffer> body = Flowable.just(payload);
        return this.bucket.uploadPart(req.build(), AsyncRequestBody.fromPublisher(body));
    }

    private static String base64Sha256(final byte[] data) {
        try {
            final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(data);
            return java.util.Base64.getEncoder().encodeToString(digest);
        } catch (final Exception err) {
            throw new IllegalStateException(err);
        }
    }

    /**
     * Uploaded part.
     * @since 1.12.0
     */
    private static class UploadedPart {
        /**
         * Part's number.
         */
        private final int pnum;

        /**
         * Entity tag for the uploaded object.
         */
        private final String tag;

        /**
         * SHA256 checksum for the part (may be null).
         */
        private final String checksum;

        /**
         * Ctor.
         *
         * @param pnum Part's number.
         * @param tag Entity tag for the uploaded object.
         * @param checksum SHA256 checksum (may be null).
         */
        UploadedPart(final int pnum, final String tag, final String checksum) {
            this.pnum = pnum;
            this.tag = tag;
            this.checksum = checksum;
        }

        /**
         * Builds {@code CompletedPart}.
         *
         * @return CompletedPart.
         */
        CompletedPart completedPart() {
            final CompletedPart.Builder builder = CompletedPart.builder()
                .partNumber(this.pnum)
                .eTag(this.tag);
            if (this.checksum != null) {
                builder.checksumSHA256(this.checksum);
            }
            return builder.build();
        }
    }
}
