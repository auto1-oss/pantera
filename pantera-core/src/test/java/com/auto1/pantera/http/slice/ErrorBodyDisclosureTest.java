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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the error-body contract for artifact serving: a 500 names the failed
 * operation and nothing else.
 *
 * <p>Exception messages routinely carry absolute filesystem paths — the real
 * leak that prompted this was {@code cannot create directory /var/pantera/data}
 * reaching a docker client. Detail belongs in the ERROR log.</p>
 */
final class ErrorBodyDisclosureTest {

    /**
     * Stand-in for internal detail that must never reach a client.
     */
    private static final String INTERNAL = "/var/pantera/data/must-not-leak";

    @Test
    @DisplayName("StorageArtifactSlice 500 body carries no exception detail")
    void storageArtifactSliceHidesExceptionDetail() {
        final Storage failing = new FailingStorage();
        final StorageArtifactSlice slice = new StorageArtifactSlice(failing);
        final Response response = slice.response(
            new RequestLine("GET", "/some/artifact.jar"),
            Headers.EMPTY,
            com.auto1.pantera.asto.Content.EMPTY
        ).join();
        final String body = bodyOf(response);
        MatcherAssert.assertThat(
            "a storage failure must answer 500",
            response.status(), new IsEqual<>(RsStatus.INTERNAL_ERROR)
        );
        MatcherAssert.assertThat(
            "body must not disclose the internal path from the exception message",
            body, new IsNot<>(new StringContains(INTERNAL))
        );
        MatcherAssert.assertThat(
            "body must still name the failed operation",
            body, new StringContains("Failed to serve artifact")
        );
    }

    private static String bodyOf(final Response response) {
        return new String(
            response.body().asBytesFuture().join(), StandardCharsets.UTF_8
        );
    }

    /**
     * Storage whose reads fail with an exception carrying an internal path.
     * Only the methods the slice touches are meaningful.
     */
    private static final class FailingStorage implements Storage {

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<com.auto1.pantera.asto.Content> value(final Key key) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(String.format("cannot read %s", INTERNAL))
            );
        }

        @Override
        public CompletableFuture<java.util.Collection<Key>> list(final Key prefix) {
            return CompletableFuture.completedFuture(java.util.List.of());
        }

        @Override
        public CompletableFuture<Void> save(
            final Key key, final com.auto1.pantera.asto.Content content
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<? extends com.auto1.pantera.asto.Meta> metadata(final Key key) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(String.format("cannot stat %s", INTERNAL))
            );
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key,
            final Function<Storage, CompletionStage<T>> operation
        ) {
            return operation.apply(this);
        }
    }
}
