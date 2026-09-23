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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.ContentIs;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link GoUploadSlice}: version immutability and {@code @v/list}
 * consistency under concurrent publishes.
 *
 * @since 2.2.9
 */
final class GoUploadSliceTest {

    /**
     * Module path used by the tests.
     */
    private static final String MODULE = "example.com/upload/mod";

    /**
     * Pool that completes storage operations off the caller thread.
     */
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        this.pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        this.pool.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mod", "zip"})
    void rejectsRepublishWithDifferentContent(final String ext) {
        final Storage storage = new InMemoryStorage();
        final GoUploadSlice slice = new GoUploadSlice(storage, "go-local", Optional.empty());
        final String path = String.format("%s/@v/v1.0.0.%s", MODULE, ext);
        GoUploadSliceTest.put(slice, path, "first");
        MatcherAssert.assertThat(
            "second publish of a version with different bytes must conflict",
            GoUploadSliceTest.put(slice, path, "second"),
            new RsHasStatus(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the originally published bytes must be kept",
            storage.value(new Key.From(path)).join(),
            new ContentIs("first".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void infoIsReplaceableUntilZipIsPublished() {
        final Storage storage = new InMemoryStorage();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final GoUploadSlice slice = new GoUploadSlice(storage, "go-local", Optional.of(events));
        final String info = MODULE + "/@v/v1.0.0.info";
        final String second = "{\"Version\":\"v1.0.0\",\"Time\":\"2026-01-02T00:00:00Z\"}";
        GoUploadSliceTest.put(
            slice, info, "{\"Version\":\"v1.0.0\",\"Time\":\"2026-01-01T00:00:00Z\"}"
        );
        GoUploadSliceTest.put(slice, MODULE + "/@v/v1.0.0.mod", "module " + MODULE);
        MatcherAssert.assertThat(
            "a retried publish may replace .info while the zip is missing",
            GoUploadSliceTest.put(slice, info, second),
            new RsHasStatus(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "the replacement .info is stored",
            storage.value(new Key.From(info)).join(),
            new ContentIs(second.getBytes(StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "the zip then publishes",
            GoUploadSliceTest.put(slice, MODULE + "/@v/v1.0.0.zip", "zip"),
            new RsHasStatus(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "the completed publish is recorded once",
            events.size(),
            new IsEqual<>(1)
        );
    }

    @Test
    void rejectsDifferentInfoOnceZipIsPublished() {
        final Storage storage = new InMemoryStorage();
        final GoUploadSlice slice = new GoUploadSlice(storage, "go-local", Optional.empty());
        final String info = MODULE + "/@v/v1.0.0.info";
        GoUploadSliceTest.put(slice, info, "{\"Version\":\"v1.0.0\",\"Time\":\"t1\"}");
        GoUploadSliceTest.put(slice, MODULE + "/@v/v1.0.0.zip", "zip");
        MatcherAssert.assertThat(
            "a different .info for a fully published version must conflict",
            GoUploadSliceTest.put(slice, info, "{\"Version\":\"v1.0.0\",\"Time\":\"t2\"}"),
            new RsHasStatus(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "an identical .info is an idempotent retry",
            GoUploadSliceTest.put(slice, info, "{\"Version\":\"v1.0.0\",\"Time\":\"t1\"}"),
            new RsHasStatus(RsStatus.CREATED)
        );
    }

    @Test
    void acceptsIdenticalRepublishWithoutNewEvent() {
        final Storage storage = new InMemoryStorage();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final GoUploadSlice slice = new GoUploadSlice(storage, "go-local", Optional.of(events));
        final String path = MODULE + "/@v/v1.0.0.zip";
        MatcherAssert.assertThat(
            "first publish is created",
            GoUploadSliceTest.put(slice, path, "same"),
            new RsHasStatus(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "identical re-publish is idempotent",
            GoUploadSliceTest.put(slice, path, "same"),
            new RsHasStatus(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "identical re-publish is not a new publish event",
            events.size(),
            new IsEqual<>(1)
        );
    }

    @Test
    @Timeout(60)
    void concurrentPublishesKeepEveryVersionInList() {
        final Storage storage = new AsyncStorage(new InMemoryStorage(), this.pool);
        final GoUploadSlice slice = new GoUploadSlice(storage, "go-local", Optional.empty());
        final int count = 40;
        final List<CompletableFuture<Response>> uploads = IntStream.rangeClosed(1, count)
            .mapToObj(
                idx -> slice.response(
                    new RequestLine("PUT", String.format("%s/@v/v1.0.%d.zip", MODULE, idx)),
                    Headers.EMPTY,
                    new Content.From(("zip-" + idx).getBytes(StandardCharsets.UTF_8))
                )
            ).collect(Collectors.toList());
        uploads.forEach(CompletableFuture::join);
        final Set<String> listed = new HashSet<>(
            Arrays.asList(
                new String(
                    storage.value(new Key.From(MODULE + "/@v/list")).join().asBytes(),
                    StandardCharsets.UTF_8
                ).split("\n")
            )
        );
        final Set<String> expected = IntStream.rangeClosed(1, count)
            .mapToObj(idx -> "v1.0." + idx)
            .collect(Collectors.toSet());
        MatcherAssert.assertThat(listed, new IsEqual<>(expected));
    }

    @Test
    void listRecoversVersionsMissingFromStaleListFile() {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From(MODULE + "/@v/v1.0.1.zip"),
            new Content.From("old".getBytes(StandardCharsets.UTF_8))
        ).join();
        storage.save(
            new Key.From(MODULE + "/@v/list"),
            new Content.From("v1.0.0\n".getBytes(StandardCharsets.UTF_8))
        ).join();
        final GoUploadSlice slice = new GoUploadSlice(storage, "go-local", Optional.empty());
        GoUploadSliceTest.put(slice, MODULE + "/@v/v1.0.2.zip", "new");
        MatcherAssert.assertThat(
            new String(
                storage.value(new Key.From(MODULE + "/@v/list")).join().asBytes(),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>("v1.0.0\nv1.0.1\nv1.0.2\n")
        );
    }

    /**
     * Upload a text body.
     * @param slice Slice
     * @param path Path
     * @param body Body
     * @return Response
     */
    private static Response put(final Slice slice, final String path, final String body) {
        return slice.response(
            new RequestLine("PUT", path),
            Headers.EMPTY,
            new Content.From(body.getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    /**
     * Storage decorator that completes every operation on a thread pool, so
     * concurrent uploads interleave like they do on a real storage.
     *
     * @since 2.2.9
     */
    private static final class AsyncStorage implements Storage {

        /**
         * Origin.
         */
        private final Storage origin;

        /**
         * Pool.
         */
        private final ExecutorService pool;

        AsyncStorage(final Storage origin, final ExecutorService pool) {
            this.origin = origin;
            this.pool = pool;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return this.later(() -> this.origin.exists(key));
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return this.later(() -> this.origin.list(prefix));
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            return this.later(() -> this.origin.save(key, content));
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return this.later(() -> this.origin.move(source, destination));
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return this.later(() -> this.origin.metadata(key));
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return this.later(() -> this.origin.value(key));
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return this.later(() -> this.origin.delete(key));
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key, final Function<Storage, CompletionStage<T>> operation
        ) {
            return this.origin.exclusively(key, operation);
        }

        /**
         * Run the operation on the pool and hop its completion back to the pool.
         * @param op Operation
         * @param <T> Result type
         * @return Future completed on a pool thread
         */
        private <T> CompletableFuture<T> later(final Supplier<CompletableFuture<T>> op) {
            return CompletableFuture.supplyAsync(op, this.pool)
                .thenCompose(Function.identity())
                .thenApplyAsync(Function.identity(), this.pool);
        }
    }
}
