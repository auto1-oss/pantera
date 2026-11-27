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
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A client that cancels its subscription while the end of the body is
 * still on its way, as a response writer that has written every byte may,
 * does not take the copy away from a save that has already started: the
 * body is stored.
 *
 * @since 2.2.15
 */
final class StreamThroughCacheCancelRaceTest {

    @Test
    @Timeout(60)
    void cancelAfterTheSaveStartedLeavesTheCopyToTheSave(@TempDir final Path dir)
        throws Exception {
        final CountDownLatch saving = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        final Storage storage = new HeldSave(new FileStorage(dir), saving, cancelled);
        final Key key = new Key.From("packages", "archive.tar.bz2");
        final byte[] data = new byte[1024 * 1024];
        new Random(7).nextBytes(data);
        final Content served = new StreamThroughCache(storage).load(
            key,
            () -> CompletableFuture.completedFuture(Optional.of(new Content.From(data))),
            CacheControl.Standard.ALWAYS
        ).toCompletableFuture().get(30, TimeUnit.SECONDS).orElseThrow();
        final AtomicReference<Subscription> sub = new AtomicReference<>();
        final ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            // The body is read on its own thread; its end starts the save,
            // which the storage holds until the client has cancelled.
            reader.submit(() -> served.subscribe(new Reads(sub)));
            MatcherAssert.assertThat(
                "the save started", saving.await(30, TimeUnit.SECONDS), new IsEqual<>(true)
            );
            sub.get().cancel();
            cancelled.countDown();
            // The save follows the stream: wait for it, do not time it.
            for (int idx = 0; idx < 400 && !storage.exists(key).join(); idx += 1) {
                TimeUnit.MILLISECONDS.sleep(25L);
            }
            MatcherAssert.assertThat(
                "the body is stored", storage.value(key).join().asBytes(), new IsEqual<>(data)
            );
        } finally {
            reader.shutdownNow();
        }
    }

    /**
     * Storage whose save waits, once it was asked for, until the client
     * cancelled.
     *
     * @since 2.2.15
     */
    private static final class HeldSave extends Storage.Wrap {

        /**
         * Counted down when a save is asked for.
         */
        private final CountDownLatch saving;

        /**
         * Awaited before the save goes on.
         */
        private final CountDownLatch cancelled;

        HeldSave(
            final Storage delegate, final CountDownLatch saving, final CountDownLatch cancelled
        ) {
            super(delegate);
            this.saving = saving;
            this.cancelled = cancelled;
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            this.saving.countDown();
            try {
                this.cancelled.await(30, TimeUnit.SECONDS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return super.save(key, content);
        }
    }

    /**
     * Subscriber that reads everything and keeps its subscription.
     *
     * @since 2.2.15
     */
    private static final class Reads implements Subscriber<ByteBuffer> {

        /**
         * Where the subscription is kept.
         */
        private final AtomicReference<Subscription> sub;

        Reads(final AtomicReference<Subscription> sub) {
            this.sub = sub;
        }

        @Override
        public void onSubscribe(final Subscription subscription) {
            this.sub.set(subscription);
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(final ByteBuffer item) {
            // The bytes are not needed.
        }

        @Override
        public void onError(final Throwable err) {
            // The test reads the storage, not the stream.
        }

        @Override
        public void onComplete() {
            // The test reads the storage, not the stream.
        }
    }
}
