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
import com.auto1.pantera.asto.ListPage;
import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.StorageThrottledException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The requests {@link S3Storage} sends for a paged listing, a batch delete
 * and an abort of multipart uploads, and what it makes of the answers.
 * Runs against a recording client: any call the storage is not expected to
 * make (a {@code HeadObject} per key, for one) fails the test, because the
 * client does not implement it.
 *
 * @since 2.2.14
 */
@SuppressWarnings("PMD.TooManyMethods")
final class S3BatchRequestsTest {

    @Test
    @Timeout(30)
    void deletesInQuietRequestsOfAtMostThousandKeys() throws Exception {
        final Recording client = new Recording();
        new S3Storage(client, "bkt", "test").deleteBatch(
            IntStream.range(0, 2500)
                .mapToObj(idx -> (Key) new Key.From("repo", String.format("k%05d", idx)))
                .collect(Collectors.toList())
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "2500 keys take three requests",
            client.deletes.stream()
                .map(req -> req.delete().objects().size()).collect(Collectors.toList()),
            new IsEqual<>(List.of(1000, 1000, 500))
        );
        MatcherAssert.assertThat(
            "every request is quiet and names the bucket",
            client.deletes.stream().allMatch(
                req -> Boolean.TRUE.equals(req.delete().quiet()) && "bkt".equals(req.bucket())
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "every key is sent exactly once",
            client.deletes.stream()
                .flatMap(req -> req.delete().objects().stream())
                .map(obj -> obj.key()).distinct().count(),
            new IsEqual<>(2500L)
        );
    }

    @Test
    @Timeout(30)
    void sendsNothingForEmptyBatch() throws Exception {
        final Recording client = new Recording();
        new S3Storage(client, "bkt", "test").deleteBatch(Collections.emptyList())
            .get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(client.deletes.size(), new IsEqual<>(0));
    }

    @Test
    @Timeout(30)
    void failsWithCountAndFirstKeysOnPerKeyErrors() {
        final Recording client = new Recording();
        client.deleted = req -> CompletableFuture.completedFuture(
            DeleteObjectsResponse.builder().errors(
                S3BatchRequestsTest.error("repo/a", "AccessDenied"),
                S3BatchRequestsTest.error("repo/c", "InternalError")
            ).build()
        );
        final Throwable cause = S3BatchRequestsTest.failure(
            new S3Storage(client, "bkt", "test").deleteBatch(
                List.of(new Key.From("repo/a"), new Key.From("repo/b"), new Key.From("repo/c"))
            )
        );
        MatcherAssert.assertThat(
            "a per-key error is an I/O failure",
            cause, new IsInstanceOf(PanteraIOException.class)
        );
        MatcherAssert.assertThat(
            "the message names the count, the keys and their codes",
            cause.getMessage(),
            new StringContains(
                "failed to delete 2 of 3 keys: repo/a (AccessDenied), repo/c (InternalError)"
            )
        );
    }

    @Test
    @Timeout(30)
    void treatsPerKeyNoSuchKeyAsDeleted() throws Exception {
        final Recording client = new Recording();
        client.deleted = req -> CompletableFuture.completedFuture(
            DeleteObjectsResponse.builder().errors(
                S3BatchRequestsTest.error("repo/a", "NoSuchKey")
            ).build()
        );
        new S3Storage(client, "bkt", "test").deleteBatch(List.of(new Key.From("repo/a")))
            .get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(client.deletes.size(), new IsEqual<>(1));
    }

    @Test
    @Timeout(30)
    void surfacesPerKeyThrottlingAsThrottledException() {
        for (final String code : List.of("SlowDown", "SlowDownWrite", "RequestLimitExceeded")) {
            final Recording client = new Recording();
            client.deleted = req -> CompletableFuture.completedFuture(
                DeleteObjectsResponse.builder().errors(
                    S3BatchRequestsTest.error("repo/a", "AccessDenied"),
                    S3BatchRequestsTest.error("repo/b", code)
                ).build()
            );
            MatcherAssert.assertThat(
                String.format("per-key code %s", code),
                S3BatchRequestsTest.failure(
                    new S3Storage(client, "bkt", "test").deleteBatch(
                        List.of(new Key.From("repo/a"), new Key.From("repo/b"))
                    )
                ),
                new IsInstanceOf(StorageThrottledException.class)
            );
        }
    }

    @Test
    @Timeout(30)
    void surfacesThrottledRequestAsThrottledExceptionAndStops() {
        final Recording client = new Recording();
        client.deleted = req -> CompletableFuture.failedFuture(
            S3BatchRequestsTest.s3Error(503, "SlowDown")
        );
        final Throwable cause = S3BatchRequestsTest.failure(
            new S3Storage(client, "bkt", "test").deleteBatch(
                IntStream.range(0, 1500)
                    .mapToObj(idx -> (Key) new Key.From("k", String.valueOf(idx)))
                    .collect(Collectors.toList())
            )
        );
        MatcherAssert.assertThat(
            "a 503 SlowDown answer asks the caller to back off",
            cause, new IsInstanceOf(StorageThrottledException.class)
        );
        MatcherAssert.assertThat(
            "the second chunk is not sent after the first was refused",
            client.deletes.size(), new IsEqual<>(1)
        );
    }

    @Test
    @Timeout(30)
    void doesNotTakeOtherFailureForThrottling() {
        final Recording client = new Recording();
        client.deleted = req -> CompletableFuture.failedFuture(
            S3BatchRequestsTest.s3Error(403, "AccessDenied")
        );
        MatcherAssert.assertThat(
            S3BatchRequestsTest.failure(
                new S3Storage(client, "bkt", "test").deleteBatch(List.of(new Key.From("a")))
            ),
            new IsInstanceOf(PanteraIOException.class)
        );
    }

    @Test
    @Timeout(30)
    void listsWithPathPrefixStartAfterAndLimit() throws Exception {
        final Recording client = new Recording();
        client.listed = req -> CompletableFuture.completedFuture(
            ListObjectsV2Response.builder()
                .contents(S3BatchRequestsTest.object("maven/b"), S3BatchRequestsTest.object("maven/c"))
                .isTruncated(true)
                .build()
        );
        final ListPage page = new S3Storage(client, "bkt", "test")
            .listPage(new Key.From("maven"), Optional.of("maven/a"), 2)
            .get(10, TimeUnit.SECONDS);
        final ListObjectsV2Request req = client.lists.get(0);
        MatcherAssert.assertThat(
            "the prefix ends with the delimiter, so maven-proxy/ cannot match",
            req.prefix(), new IsEqual<>("maven/")
        );
        MatcherAssert.assertThat(
            "the token is sent as StartAfter",
            req.startAfter(), new IsEqual<>("maven/a")
        );
        MatcherAssert.assertThat(
            "no continuation token is used",
            req.continuationToken(), new IsEqual<>(null)
        );
        MatcherAssert.assertThat(
            "the limit is sent as MaxKeys",
            req.maxKeys(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "one request fills the page",
            client.lists.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the page holds the keys and the last one as token",
            page,
            new IsEqual<>(
                new ListPage(
                    List.of(new Key.From("maven/b"), new Key.From("maven/c")),
                    Optional.of("maven/c")
                )
            )
        );
    }

    @Test
    @Timeout(30)
    void listsRootWithoutPrefixAndEndsWithoutToken() throws Exception {
        final Recording client = new Recording();
        client.listed = req -> CompletableFuture.completedFuture(
            ListObjectsV2Response.builder()
                .contents(S3BatchRequestsTest.object("a"))
                .isTruncated(false)
                .build()
        );
        final ListPage page = new S3Storage(client, "bkt", "test")
            .listPage(Key.ROOT, Optional.empty(), 5000)
            .get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "no prefix for the root",
            client.lists.get(0).prefix(), new IsEqual<>(null)
        );
        MatcherAssert.assertThat(
            "no StartAfter for the first page",
            client.lists.get(0).startAfter(), new IsEqual<>(null)
        );
        MatcherAssert.assertThat(
            "MaxKeys is capped at what S3 returns per answer",
            client.lists.get(0).maxKeys(), new IsEqual<>(1000)
        );
        MatcherAssert.assertThat(
            "the listing is finished",
            page, new IsEqual<>(new ListPage(List.of(new Key.From("a")), Optional.empty()))
        );
    }

    @Test
    @Timeout(30)
    void skipsKeysNoKeyCanNameAndKeepsAsking() throws Exception {
        final Recording client = new Recording();
        client.listed = req -> {
            final ListObjectsV2Response resp;
            if (req.startAfter() == null) {
                resp = ListObjectsV2Response.builder()
                    .contents(
                        S3BatchRequestsTest.object("repo/dir/"),
                        S3BatchRequestsTest.object("repo/dir//x")
                    )
                    .isTruncated(true)
                    .build();
            } else {
                resp = ListObjectsV2Response.builder()
                    .contents(S3BatchRequestsTest.object("repo/file"))
                    .isTruncated(false)
                    .build();
            }
            return CompletableFuture.completedFuture(resp);
        };
        final ListPage page = new S3Storage(client, "bkt", "test")
            .listPage(new Key.From("repo"), Optional.empty(), 2)
            .get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "placeholder and empty-segment keys are left out",
            page, new IsEqual<>(new ListPage(List.of(new Key.From("repo/file")), Optional.empty()))
        );
        MatcherAssert.assertThat(
            "the second request starts after the last raw key, skipped or not",
            client.lists.get(1).startAfter(), new IsEqual<>("repo/dir//x")
        );
    }

    @Test
    @Timeout(30)
    void surfacesThrottledListingAsThrottledException() {
        final Recording client = new Recording();
        client.listed = req -> CompletableFuture.failedFuture(
            S3BatchRequestsTest.s3Error(503, "SlowDown")
        );
        MatcherAssert.assertThat(
            S3BatchRequestsTest.failure(
                new S3Storage(client, "bkt", "test").listPage(Key.ROOT, Optional.empty(), 10)
            ),
            new IsInstanceOf(StorageThrottledException.class)
        );
    }

    @Test
    @Timeout(30)
    void abortsUploadsUnderPathPrefixPageByPage() throws Exception {
        final Recording client = new Recording();
        client.uploads = req -> {
            final ListMultipartUploadsResponse resp;
            if (req.keyMarker() == null) {
                resp = ListMultipartUploadsResponse.builder()
                    .uploads(
                        S3BatchRequestsTest.upload("repo/a", "u1"),
                        S3BatchRequestsTest.upload("repo/a", "u2")
                    )
                    .isTruncated(true).nextKeyMarker("repo/a").nextUploadIdMarker("u2")
                    .build();
            } else {
                resp = ListMultipartUploadsResponse.builder()
                    .uploads(
                        S3BatchRequestsTest.upload("repo/b", "u3"),
                        S3BatchRequestsTest.upload("repo/gone", "u4")
                    )
                    .isTruncated(false)
                    .build();
            }
            return CompletableFuture.completedFuture(resp);
        };
        final Storage storage = new S3Storage(client, "bkt", "test");
        MatcherAssert.assertThat(
            "three uploads are aborted, the one already gone is not counted",
            storage.abortIncompleteUploads(new Key.From("repo")).get(10, TimeUnit.SECONDS),
            new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "the listing uses the path prefix",
            client.uploadLists.stream()
                .map(ListMultipartUploadsRequest::prefix).collect(Collectors.toList()),
            new IsEqual<>(List.of("repo/", "repo/"))
        );
        MatcherAssert.assertThat(
            "the second page continues at the markers of the first",
            Arrays.asList(
                client.uploadLists.get(1).keyMarker(), client.uploadLists.get(1).uploadIdMarker()
            ),
            new IsEqual<>(List.of("repo/a", "u2"))
        );
        MatcherAssert.assertThat(
            "every listed upload gets an abort",
            client.aborts.stream()
                .map(req -> req.key() + "#" + req.uploadId()).sorted()
                .collect(Collectors.toList()),
            new IsEqual<>(List.of("repo/a#u1", "repo/a#u2", "repo/b#u3", "repo/gone#u4"))
        );
    }

    /**
     * Cause of a failed future.
     * @param future Future expected to fail
     * @return Cause
     */
    private static Throwable failure(final CompletableFuture<?> future) {
        return Assertions.assertThrows(
            ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS)
        ).getCause();
    }

    /**
     * Per-key error.
     * @param key Key
     * @param code Code
     * @return Error
     */
    private static S3Error error(final String key, final String code) {
        return S3Error.builder().key(key).code(code).message(code).build();
    }

    /**
     * Listed object.
     * @param key Key
     * @return Object
     */
    private static S3Object object(final String key) {
        return S3Object.builder().key(key).build();
    }

    /**
     * Listed upload.
     * @param key Key
     * @param upload Upload id
     * @return Upload
     */
    private static MultipartUpload upload(final String key, final String upload) {
        return MultipartUpload.builder().key(key).uploadId(upload).build();
    }

    /**
     * Service failure.
     * @param status HTTP status
     * @param code Error code
     * @return Exception
     */
    private static S3Exception s3Error(final int status, final String code) {
        return (S3Exception) S3Exception.builder()
            .statusCode(status)
            .message(code)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(code).build())
            .build();
    }

    /**
     * Client that records the requests it supports and fails on any other.
     * @since 2.2.14
     */
    private static final class Recording implements S3AsyncClient {

        /**
         * Delete requests.
         */
        private final List<DeleteObjectsRequest> deletes = new CopyOnWriteArrayList<>();

        /**
         * List requests.
         */
        private final List<ListObjectsV2Request> lists = new CopyOnWriteArrayList<>();

        /**
         * Upload list requests.
         */
        private final List<ListMultipartUploadsRequest> uploadLists =
            new CopyOnWriteArrayList<>();

        /**
         * Abort requests.
         */
        private final List<AbortMultipartUploadRequest> aborts = new CopyOnWriteArrayList<>();

        /**
         * Answer to a delete.
         */
        private Function<DeleteObjectsRequest, CompletableFuture<DeleteObjectsResponse>> deleted =
            req -> CompletableFuture.completedFuture(DeleteObjectsResponse.builder().build());

        /**
         * Answer to a listing.
         */
        private Function<ListObjectsV2Request, CompletableFuture<ListObjectsV2Response>> listed =
            req -> CompletableFuture.completedFuture(
                ListObjectsV2Response.builder().contents(new ArrayList<>(0)).build()
            );

        /**
         * Answer to an upload listing.
         */
        private Function<ListMultipartUploadsRequest,
            CompletableFuture<ListMultipartUploadsResponse>> uploads =
            req -> CompletableFuture.completedFuture(
                ListMultipartUploadsResponse.builder().build()
            );

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
            // nothing to release
        }

        @Override
        public CompletableFuture<DeleteObjectsResponse> deleteObjects(
            final DeleteObjectsRequest req
        ) {
            this.deletes.add(req);
            return this.deleted.apply(req);
        }

        @Override
        public CompletableFuture<ListObjectsV2Response> listObjectsV2(
            final ListObjectsV2Request req
        ) {
            this.lists.add(req);
            return this.listed.apply(req);
        }

        @Override
        public CompletableFuture<ListMultipartUploadsResponse> listMultipartUploads(
            final ListMultipartUploadsRequest req
        ) {
            this.uploadLists.add(req);
            return this.uploads.apply(req);
        }

        @Override
        public CompletableFuture<AbortMultipartUploadResponse> abortMultipartUpload(
            final AbortMultipartUploadRequest req
        ) {
            this.aborts.add(req);
            final CompletableFuture<AbortMultipartUploadResponse> res;
            if (req.key().endsWith("gone")) {
                res = CompletableFuture.failedFuture(
                    NoSuchUploadException.builder().message("gone").statusCode(404).build()
                );
            } else {
                res = CompletableFuture.completedFuture(
                    AbortMultipartUploadResponse.builder().build()
                );
            }
            return res;
        }
    }
}
