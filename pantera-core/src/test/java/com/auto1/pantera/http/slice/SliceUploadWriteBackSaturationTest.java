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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.WriteBackSaturatedException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.fault.FaultTranslator;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SliceUpload}'s WS1.6 hosted-upload backpressure mapping
 * (spec {@code WS1-storage-for-scale.md} &sect;3.C, the WS1.2-deferred
 * behaviour): a {@link WriteBackSaturatedException} from the storage's
 * {@code save()} must surface as {@code 503 Service Unavailable} +
 * {@code Retry-After}, via the existing central {@link FaultTranslator}
 * policy -- every other failure must propagate unchanged (regression guard).
 */
final class SliceUploadWriteBackSaturationTest {

    @Test
    void writeBackSaturationMapsTo503WithRetryAfter() {
        final Slice slice = new SliceUpload(new SaturatedSaveStorage(new WriteBackSaturatedException("k", 7L)));

        final Response response = slice.response(
            new RequestLine(RqMethod.PUT, "/some/artifact.jar"), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(response.status().code(), new IsEqual<>(503));
        final String retryAfter = SliceUploadWriteBackSaturationTest.header(response, "Retry-After");
        MatcherAssert.assertThat(retryAfter, new IsEqual<>("7"));
        final String fault = SliceUploadWriteBackSaturationTest.header(response, FaultTranslator.HEADER_FAULT);
        MatcherAssert.assertThat(fault, new IsEqual<>("overload:write_back_queue"));
    }

    @Test
    void writeBackSaturationWrappedInCompletionExceptionStillMapsTo503() {
        // storage.save() futures typically surface exceptions already wrapped
        // in a CompletionException by the CompletableFuture chain -- the
        // mapping must unwrap through that, not just a bare cause.
        final Slice slice = new SliceUpload(
            new SaturatedSaveStorage(new CompletionException(new WriteBackSaturatedException("k", 3L)))
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.PUT, "/some/artifact.jar"), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(response.status().code(), new IsEqual<>(503));
        MatcherAssert.assertThat(SliceUploadWriteBackSaturationTest.header(response, "Retry-After"), new IsEqual<>("3"));
    }

    @Test
    void anyOtherSaveFailurePropagatesUnchanged() {
        final Slice slice = new SliceUpload(new SaturatedSaveStorage(new IllegalStateException("boom")));

        final CompletableFuture<Response> future = slice.response(
            new RequestLine(RqMethod.PUT, "/some/artifact.jar"), Headers.EMPTY, Content.EMPTY
        );

        MatcherAssert.assertThat("a non-saturation failure must not be swallowed into a response", future.isCompletedExceptionally(), new IsEqual<>(true));
    }

    private static String header(final Response response, final String name) {
        return response.headers().stream()
            .filter(h -> name.equalsIgnoreCase(h.getKey()))
            .map(java.util.Map.Entry::getValue)
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing header: " + name));
    }

    /** {@link Storage} fake whose {@code save()} always fails with the given cause. */
    private static final class SaturatedSaveStorage implements Storage {
        private final Throwable failure;

        SaturatedSaveStorage(final Throwable failure) {
            this.failure = failure;
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            return CompletableFuture.failedFuture(this.failure);
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
            return CompletableFuture.completedFuture(new ListResult.Simple(List.of(), List.of()));
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key, final Function<Storage, CompletionStage<T>> operation
        ) {
            return operation.apply(this);
        }
    }
}
