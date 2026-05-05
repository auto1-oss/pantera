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
package com.auto1.pantera.adapters.npm;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.cache.CacheWriteCallbackRegistry;
import com.auto1.pantera.http.cache.CacheWriteEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link NpmCacheWriteBridge} — Phase 11 zero-copy passthrough.
 *
 * <p>Asserts:
 * <ol>
 *   <li>FileStorage path: bridge fires {@code CacheWriteEvent} with the
 *       storage-owned path and {@code callerOwnsSnapshot=false}; no
 *       {@code Files.value()} read-back, no temp-file is created.</li>
 *   <li>Non-FileStorage path: bridge falls back to materialise-temp-file
 *       behaviour, fires {@code callerOwnsSnapshot=true}.</li>
 *   <li>No-op shared callback: bridge skips entirely, no event fired.</li>
 * </ol>
 *
 * @since 2.2.0
 */
@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
final class NpmCacheWriteBridgeTest {

    private static final String REPO = "npm_proxy";
    private static final String ASSET = "express/-/express-4.21.0.tgz";

    @TempDir
    Path tmp;

    private CapturingCallback captured;

    @BeforeEach
    void setUp() {
        this.captured = new CapturingCallback();
        CacheWriteCallbackRegistry.instance().setSharedCallback(this.captured);
    }

    @AfterEach
    void tearDown() {
        CacheWriteCallbackRegistry.instance().clear();
    }

    @Test
    void fileStorage_firesEventWithStoragePath_andCallerOwnsSnapshotFalse() throws Exception {
        // Save a fake tarball directly through FileStorage so the on-disk
        // path that pathFor returns actually exists.
        final FileStorage storage = new FileStorage(this.tmp);
        final byte[] tarball = "fake-npm-tarball-bytes".getBytes();
        storage.save(new Key.From(ASSET), new Content.From(tarball)).join();

        final Path storageOwnedPath = storage.pathFor(new Key.From(ASSET))
            .orElseThrow();
        MatcherAssert.assertThat(
            "precondition: storage path must exist on disk after save",
            Files.exists(storageOwnedPath), Matchers.is(true)
        );

        final NpmCacheWriteBridge bridge = new NpmCacheWriteBridge(storage, REPO);
        bridge.hook().accept(ASSET);

        // The fast path is synchronous; one event MUST be captured.
        MatcherAssert.assertThat(
            "exactly one CacheWriteEvent must fire",
            this.captured.events.size(), Matchers.equalTo(1)
        );
        final CacheWriteEvent event = this.captured.events.get(0);
        MatcherAssert.assertThat(event.repoName(), Matchers.equalTo(REPO));
        MatcherAssert.assertThat(event.urlPath(), Matchers.equalTo(ASSET));
        MatcherAssert.assertThat(
            "bytesOnDisk must be the storage-owned path (zero-copy passthrough)",
            event.bytesOnDisk(), Matchers.equalTo(storageOwnedPath)
        );
        MatcherAssert.assertThat(
            "callerOwnsSnapshot must be false for storage-owned passthrough",
            event.callerOwnsSnapshot(), Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "sizeBytes must match the saved tarball length",
            event.sizeBytes(), Matchers.equalTo((long) tarball.length)
        );
        MatcherAssert.assertThat(
            "storage-owned path MUST still exist (not deleted by the bridge)",
            Files.exists(storageOwnedPath), Matchers.is(true)
        );
    }

    @Test
    void nonFileStorage_fallsBackToTempFile_andCallerOwnsSnapshotTrue() throws Exception {
        // Storage that does NOT expose pathFor — bridge must materialise
        // a temp file the legacy way.
        final byte[] tarball = "another-fake-tarball".getBytes();
        final FakeNonFsStorage storage = new FakeNonFsStorage(tarball);
        final NpmCacheWriteBridge bridge = new NpmCacheWriteBridge(storage, REPO);

        bridge.hook().accept(ASSET);
        // Fallback path completes asynchronously via the storage's value()
        // future; spin briefly until the event arrives.
        final long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (this.captured.events.isEmpty() && System.nanoTime() < deadlineNs) {
            Thread.sleep(5);
        }

        MatcherAssert.assertThat(
            "fallback path must fire exactly one CacheWriteEvent",
            this.captured.events.size(), Matchers.equalTo(1)
        );
        final CacheWriteEvent event = this.captured.events.get(0);
        MatcherAssert.assertThat(
            "fallback path uses caller-owned semantics",
            event.callerOwnsSnapshot(), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "fallback path materialises a temp file (NOT the storage path)",
            event.bytesOnDisk().toString(), Matchers.containsString("pantera-npm-prefetch")
        );
        MatcherAssert.assertThat(
            "fallback temp file is deleted by the bridge after the consumer returns",
            Files.exists(event.bytesOnDisk()), Matchers.is(false)
        );
    }

    @Test
    void noOpRegistry_skipsEventEntirely() {
        // Clear the registry so the shared callback is the no-op sentinel.
        CacheWriteCallbackRegistry.instance().clear();
        final FileStorage storage = new FileStorage(this.tmp);
        // Save so pathFor returns a real path — we want to verify the
        // bridge short-circuits BEFORE doing any work.
        storage.save(new Key.From(ASSET), new Content.From("x".getBytes())).join();
        final NpmCacheWriteBridge bridge = new NpmCacheWriteBridge(storage, REPO);

        bridge.hook().accept(ASSET);

        MatcherAssert.assertThat(
            "no event fires when registry callback is a no-op",
            this.captured.events.size(), Matchers.equalTo(0)
        );
    }

    // -----------------------------------------------------------------
    //  Test fakes
    // -----------------------------------------------------------------

    /** Captures every event for assertion. */
    private static final class CapturingCallback
            implements java.util.function.Consumer<CacheWriteEvent> {
        final List<CacheWriteEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void accept(final CacheWriteEvent event) {
            this.events.add(event);
        }
    }

    /**
     * Minimal Storage stub that returns a fixed byte array from value() and
     * inherits the default {@link Storage#pathFor} ({@code Optional.empty()})
     * — exercises the bridge's fallback path.
     */
    private static final class FakeNonFsStorage implements Storage {
        private final byte[] payload;
        private final AtomicBoolean valueCalled = new AtomicBoolean();

        FakeNonFsStorage(final byte[] payload) {
            this.payload = payload.clone();
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            // Not used on the fallback path (we go through value()).
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            this.valueCalled.set(true);
            return CompletableFuture.completedFuture(new Content.From(this.payload));
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

        @Override
        public Optional<Path> pathFor(final Key key) {
            // Explicitly opt out — exercises bridge fallback.
            return Optional.empty();
        }
    }
}
