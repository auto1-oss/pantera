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
package com.auto1.pantera.asto.blob;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.metrics.BlobStoreMetricsCollector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MeteredBlobStore} (WS1.6, spec {@code
 * WS1-storage-for-scale.md} &sect;3.G): proves the decorator records the
 * right bounded backend/operation/outcome dimensions via a recording {@link
 * BlobStoreMetricsCollector.Recorder} fake -- never against a real
 * MeterRegistry (that bridge, {@code BlobStoreMetricsRecorder}, is a thin,
 * un-branching forward and is covered by this seam contract instead) -- and
 * that the {@link BlobStoreMetricsCollector#isInitialized}-equivalent guard
 * (no recorder installed) never throws.
 */
final class MeteredBlobStoreTest {

    @AfterEach
    void resetRecorder() {
        // Static registry -- must not leak a fake recorder into other test
        // classes sharing this JVM.
        BlobStoreMetricsCollector.setRecorder(null);
    }

    @Test
    void noRecorderInstalledIsANoOp() {
        BlobStoreMetricsCollector.setRecorder(null);
        final MeteredBlobStore metered = new MeteredBlobStore(new FakeS3Store());
        final byte[] result = metered.get(new Key.From("a")).join().asBytesFuture().join();
        MatcherAssert.assertThat(result, new IsEqual<>("a-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void successfulGetRecordsSuccessOutcomeUnderS3Backend() {
        final RecordingRecorder recorder = new RecordingRecorder();
        BlobStoreMetricsCollector.setRecorder(recorder);
        final MeteredBlobStore metered = new MeteredBlobStore(new FakeS3Store());
        metered.get(new Key.From("a")).join();
        MatcherAssert.assertThat("exactly one recorded call", recorder.calls.size(), new IsEqual<>(1));
        final Call call = recorder.calls.get(0);
        MatcherAssert.assertThat("backend kind", call.backend, new IsEqual<>("s3"));
        MatcherAssert.assertThat("operation", call.operation, new IsEqual<>("get"));
        MatcherAssert.assertThat("outcome", call.outcome, new IsEqual<>(BlobStoreMetricsCollector.OUTCOME_SUCCESS));
    }

    @Test
    void everyBlobStoreVerbIsRecordedUnderItsOwnOperationName() {
        final RecordingRecorder recorder = new RecordingRecorder();
        BlobStoreMetricsCollector.setRecorder(recorder);
        final MeteredBlobStore metered = new MeteredBlobStore(new FakeS3Store());
        final Key key = new Key.From("a");
        metered.exists(key).join();
        metered.head(key).join();
        metered.get(key).join();
        metered.put(key, new Content.From("x".getBytes(StandardCharsets.UTF_8))).join();
        metered.delete(key).join();
        metered.list(Key.ROOT).join();
        metered.list(Key.ROOT, "/").join();
        final List<String> operations = recorder.calls.stream().map(c -> c.operation).toList();
        MatcherAssert.assertThat(
            operations,
            new IsEqual<>(List.of("exists", "head", "get", "put", "delete", "list", "list"))
        );
    }

    @Test
    void throttlingSignalIsClassifiedAsThrottledNotError() {
        final RecordingRecorder recorder = new RecordingRecorder();
        BlobStoreMetricsCollector.setRecorder(recorder);
        final MeteredBlobStore metered = new MeteredBlobStore(
            new ThrowingStore(new RuntimeException("SlowDown: please reduce your request rate"))
        );
        final CompletableFuture<Content> result = metered.get(new Key.From("a"));
        MatcherAssert.assertThat(result.isCompletedExceptionally(), new IsEqual<>(true));
        MatcherAssert.assertThat(recorder.calls.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            recorder.calls.get(0).outcome, new IsEqual<>(BlobStoreMetricsCollector.OUTCOME_THROTTLED)
        );
    }

    @Test
    void genericFailureIsClassifiedAsError() {
        final RecordingRecorder recorder = new RecordingRecorder();
        BlobStoreMetricsCollector.setRecorder(recorder);
        final MeteredBlobStore metered = new MeteredBlobStore(
            new ThrowingStore(new ValueNotFoundException(new Key.From("a")))
        );
        metered.get(new Key.From("a"));
        MatcherAssert.assertThat(recorder.calls.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            recorder.calls.get(0).outcome, new IsEqual<>(BlobStoreMetricsCollector.OUTCOME_ERROR)
        );
    }

    @Test
    void identifierAndCloseDelegateToUnderlyingStore() throws Exception {
        final CloseCountingStore delegate = new CloseCountingStore();
        final MeteredBlobStore metered = new MeteredBlobStore(delegate);
        MatcherAssert.assertThat(metered.identifier(), new IsEqual<>("fake-closeable"));
        metered.close();
        MatcherAssert.assertThat(delegate.closed, new IsEqual<>(1));
    }

    /** Recording {@link BlobStoreMetricsCollector.Recorder} test fake. */
    private static final class RecordingRecorder implements BlobStoreMetricsCollector.Recorder {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public void recordOperation(
            final String backend, final String operation, final String outcome, final long durationNs
        ) {
            this.calls.add(new Call(backend, operation, outcome));
        }
    }

    private record Call(String backend, String operation, String outcome) {
    }

    /** Minimal successful {@link BlobStore} fake whose class name implies backend "s3". */
    private static final class FakeS3Store implements BlobStore {
        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<? extends Meta> head(final Key key) {
            return CompletableFuture.completedFuture(MeteredBlobStoreTest.sizeMeta(7));
        }

        @Override
        public CompletableFuture<Content> get(final Key key) {
            return CompletableFuture.completedFuture(
                new Content.From("a-bytes".getBytes(StandardCharsets.UTF_8))
            );
        }

        @Override
        public CompletableFuture<Void> put(final Key key, final Content content) {
            return content.asBytesFuture().thenApply(bytes -> null);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /** {@link BlobStore} fake whose every call fails with a fixed cause. */
    private static final class ThrowingStore implements BlobStore {
        private final RuntimeException failure;

        ThrowingStore(final RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return CompletableFuture.failedFuture(this.failure);
        }

        @Override
        public CompletableFuture<? extends Meta> head(final Key key) {
            return CompletableFuture.failedFuture(this.failure);
        }

        @Override
        public CompletableFuture<Content> get(final Key key) {
            return CompletableFuture.failedFuture(this.failure);
        }

        @Override
        public CompletableFuture<Void> put(final Key key, final Content content) {
            return CompletableFuture.failedFuture(this.failure);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return CompletableFuture.failedFuture(this.failure);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return CompletableFuture.failedFuture(this.failure);
        }
    }

    /** {@link BlobStore} + {@link AutoCloseable} fake proving close()/identifier() delegate. */
    private static final class CloseCountingStore implements BlobStore, AutoCloseable {
        private int closed;

        @Override
        public void close() {
            this.closed++;
        }

        @Override
        public String identifier() {
            return "fake-closeable";
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<? extends Meta> head(final Key key) {
            return CompletableFuture.failedFuture(new ValueNotFoundException(key));
        }

        @Override
        public CompletableFuture<Content> get(final Key key) {
            return CompletableFuture.failedFuture(new ValueNotFoundException(key));
        }

        @Override
        public CompletableFuture<Void> put(final Key key, final Content content) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private static Meta sizeMeta(final long size) {
        return new Meta() {
            @Override
            public <T> T read(final ReadOperator<T> opr) {
                final Map<String, String> raw = new HashMap<>();
                Meta.OP_SIZE.put(raw, size);
                return opr.take(raw);
            }
        };
    }
}
