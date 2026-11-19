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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The content a disk-cached object store returns carries the remote object's
 * modification time, on a miss and on a hit, so a download is dated without a
 * second request to the object store.
 *
 * @since 2.2.12
 */
final class DiskCacheStorageLastModifiedTest {

    /**
     * Time the remote object was last modified.
     */
    private static final Instant MODIFIED = Instant.parse("2018-06-17T18:35:49Z");

    /**
     * Cached object.
     */
    private static final Key KEY = new Key.From("org/example/tool-1.0.zip");

    /**
     * Object bytes.
     */
    private static final byte[] DATA = "zip bytes".getBytes(StandardCharsets.UTF_8);

    /**
     * Remote storage.
     */
    private DatedRemote remote;

    /**
     * Cache directory.
     */
    private Path dir;

    /**
     * Storage under test.
     */
    private DiskCacheStorage cache;

    @BeforeEach
    void init(@TempDir final Path tmp) {
        this.dir = tmp;
        this.remote = new DatedRemote(DiskCacheStorageLastModifiedTest.MODIFIED);
        this.remote.save(
            DiskCacheStorageLastModifiedTest.KEY,
            new Content.From(DiskCacheStorageLastModifiedTest.DATA)
        ).join();
        this.cache = new DiskCacheStorage(
            this.remote, tmp, 1024 * 1024, DiskCacheStorage.Policy.LRU,
            0, 90, 80, false
        );
    }

    @AfterEach
    void close() {
        this.cache.close();
    }

    @Test
    // The bound only turns a hung read into a failure.
    @Timeout(30)
    void missCarriesTheRemoteModificationTime() throws Exception {
        final Content miss = this.cache.value(DiskCacheStorageLastModifiedTest.KEY)
            .get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the content is dated",
            miss.lastModified(),
            new IsEqual<>(Optional.of(DiskCacheStorageLastModifiedTest.MODIFIED))
        );
        MatcherAssert.assertThat(
            "the bytes are the remote object",
            miss.asBytesFuture().get(10, TimeUnit.SECONDS),
            new IsEqual<>(DiskCacheStorageLastModifiedTest.DATA)
        );
    }

    @Test
    @Timeout(30)
    void hitCarriesTheRemoteModificationTimeWithoutAskingTheRemote() throws Exception {
        final Content hit = this.hit();
        final int before = this.remote.requests();
        MatcherAssert.assertThat(
            "the cached content is dated",
            hit.lastModified(),
            new IsEqual<>(Optional.of(DiskCacheStorageLastModifiedTest.MODIFIED))
        );
        MatcherAssert.assertThat(
            "the bytes are the cached object",
            hit.asBytesFuture().get(10, TimeUnit.SECONDS),
            new IsEqual<>(DiskCacheStorageLastModifiedTest.DATA)
        );
        MatcherAssert.assertThat(
            "a dated hit makes no request to the remote",
            this.cache.value(DiskCacheStorageLastModifiedTest.KEY)
                .get(10, TimeUnit.SECONDS).lastModified().isPresent()
                && this.remote.requests() == before,
            new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(30)
    void entryCachedWithoutTheTimeLearnsItOnce() throws Exception {
        this.hit().asBytesFuture().get(10, TimeUnit.SECONDS);
        // An entry as an earlier version wrote it: no modification time.
        final Path meta;
        try (Stream<Path> files = Files.walk(this.dir)) {
            meta = files.filter(file -> file.toString().endsWith(".meta")).findFirst().orElseThrow();
        }
        final int before = this.remote.metadataRequests();
        // The cache updates the sidecar after every hit, off the calling
        // thread: an update still pending from an earlier read puts the time
        // back. Remove it again until a hit sees the entry without it.
        Content undated = null;
        final long patience = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (undated == null && System.nanoTime() < patience) {
            final List<String> lines = Files.readAllLines(meta).stream()
                .filter(line -> !line.startsWith("lastModified")).toList();
            Files.write(meta, lines);
            final Content hit = this.cache.value(DiskCacheStorageLastModifiedTest.KEY)
                .get(10, TimeUnit.SECONDS);
            if (hit.lastModified().isEmpty()) {
                undated = hit;
            } else {
                hit.asBytesFuture().get(10, TimeUnit.SECONDS);
                TimeUnit.MILLISECONDS.sleep(10);
            }
        }
        MatcherAssert.assertThat(
            "a hit on such an entry is not dated",
            undated == null, new IsEqual<>(false)
        );
        undated.asBytesFuture().get(10, TimeUnit.SECONDS);
        // The time is learned in the background: poll for the dated hit.
        Optional<Instant> learned = Optional.empty();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (learned.isEmpty() && System.nanoTime() < deadline) {
            final Content next = this.cache.value(DiskCacheStorageLastModifiedTest.KEY)
                .get(10, TimeUnit.SECONDS);
            learned = next.lastModified();
            next.asBytesFuture().get(10, TimeUnit.SECONDS);
            if (learned.isEmpty()) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
        }
        MatcherAssert.assertThat(
            "later hits are dated",
            learned, new IsEqual<>(Optional.of(DiskCacheStorageLastModifiedTest.MODIFIED))
        );
        final int learning = this.remote.metadataRequests();
        this.cache.value(DiskCacheStorageLastModifiedTest.KEY)
            .get(10, TimeUnit.SECONDS).asBytesFuture().get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the remote was asked for the time at least once",
            learning > before, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "once learned, the remote is not asked again",
            this.remote.metadataRequests(), new IsEqual<>(learning)
        );
    }

    /**
     * A content served from the disk cache: reads until one is served
     * without a read of the remote object (the entry is written after the
     * first read was consumed).
     * @return Content of a cache hit
     * @throws Exception On error
     */
    private Content hit() throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            final int before = this.remote.valueRequests();
            final Content content = this.cache.value(DiskCacheStorageLastModifiedTest.KEY)
                .get(10, TimeUnit.SECONDS);
            if (this.remote.valueRequests() == before) {
                return content;
            }
            content.asBytesFuture().get(10, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new IllegalStateException("the object was never served from the disk cache");
    }

    /**
     * Remote storage that dates its objects, as an object store does, and
     * counts the requests made to it.
     */
    private static final class DatedRemote extends Storage.Wrap {

        /**
         * Time every object was last modified.
         */
        private final Instant modified;

        /**
         * Object reads.
         */
        private final AtomicInteger values;

        /**
         * Metadata reads.
         */
        private final AtomicInteger metas;

        DatedRemote(final Instant modified) {
            super(new InMemoryStorage());
            this.modified = modified;
            this.values = new AtomicInteger();
            this.metas = new AtomicInteger();
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            this.values.incrementAndGet();
            return super.value(key).thenApply(
                content -> new Content.From(content.size(), content, Optional.of(this.modified))
            );
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            this.metas.incrementAndGet();
            return super.metadata(key).thenApply(
                meta -> new Meta() {
                    @Override
                    public <T> T read(final ReadOperator<T> opr) {
                        final Map<String, String> raw = new HashMap<>();
                        Meta.OP_SIZE.put(raw, meta.read(Meta.OP_SIZE).orElseThrow());
                        Meta.OP_UPDATED_AT.put(raw, DatedRemote.this.modified);
                        return opr.take(raw);
                    }
                }
            );
        }

        int valueRequests() {
            return this.values.get();
        }

        int metadataRequests() {
            return this.metas.get();
        }

        int requests() {
            return this.values.get() + this.metas.get();
        }
    }
}
