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
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.ContentIs;
import com.jcabi.log.Logger;
import hu.akarnokd.rxjava2.interop.CompletableInterop;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link FromStorageCache}.
 *
 * @since 0.24
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class FromStorageCacheTest {

    /**
     * Storage for tests.
     */
    private final Storage storage = new InMemoryStorage();

    @Test
    void loadsFromCache() throws Exception {
        final Key key = new Key.From("key1");
        final byte[] data = "hello1".getBytes();
        new BlockingStorage(this.storage).save(key, data);
        MatcherAssert.assertThat(
            new FromStorageCache(this.storage).load(
                key,
                new Remote.Failed(new IllegalStateException("Failing remote 1")),
                CacheControl.Standard.ALWAYS
            ).toCompletableFuture().get().get(),
            new ContentIs(data)
        );
    }

    @Test
    void savesToCacheFromRemote() throws Exception {
        final Key key = new Key.From("key2");
        final byte[] data = "hello2".getBytes();
        final FromStorageCache cache = new FromStorageCache(this.storage);
        final Content load = cache.load(
            key,
            () -> CompletableFuture.supplyAsync(() -> Optional.of(new Content.From(data))),
            CacheControl.Standard.ALWAYS
        ).toCompletableFuture().get().get();
        MatcherAssert.assertThat(
            "Cache returned broken remote content",
            load, new ContentIs(data)
        );
        MatcherAssert.assertThat(
            "Cache didn't save remote content locally",
            cache.load(
                key,
                new Remote.Failed(new IllegalStateException("Failing remote 1")),
                CacheControl.Standard.ALWAYS
            ).toCompletableFuture().get().get(),
            new ContentIs(data)
        );
    }

    @Test
    void dontCacheFailedRemote() throws Exception {
        final Key key = new Key.From("key3");
        final AtomicInteger cnt = new AtomicInteger();
        new FromStorageCache(this.storage).load(
            key,
            () -> CompletableFuture.supplyAsync(
                () -> Optional.of(
                    new Content.From(
                        Flowable.generate(
                            emitter -> {
                                if (cnt.incrementAndGet() < 3) {
                                    emitter.onNext(ByteBuffer.allocate(4));
                                } else {
                                    emitter.onError(new Exception("Error!"));
                                }
                            }
                        )
                    )
                )
            ),
            CacheControl.Standard.ALWAYS
        ).exceptionally(
            err -> {
                Logger.info(this, "Handled error: %s", err.getMessage());
                return null;
            }
        ).toCompletableFuture().get();
        MatcherAssert.assertThat(
            new BlockingStorage(this.storage).exists(key), Matchers.is(false)
        );
    }

    @Test
    void toctouWrappedNoSuchFileTriggersUpstreamFetch() throws Exception {
        // Storage that claims key exists but throws RuntimeException(NoSuchFileException)
        // from value() — mirrors what OptimizedStorageCache.getFileSystemContent() produces
        // when the file is deleted between the exists() check and the file open.
        final Key key = new Key.From("toctou-wrapped");
        final byte[] remoteData = "remote-bytes".getBytes();
        final AtomicBoolean remoteCalled = new AtomicBoolean(false);
        final Storage toctouStorage = new Storage.Wrap(new InMemoryStorage()) {
            @Override
            public CompletableFuture<Boolean> exists(final Key k) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Content> value(final Key k) {
                return CompletableFuture.failedFuture(
                    new RuntimeException(
                        "Failed to read file: " + k.string(),
                        new NoSuchFileException(k.string())
                    )
                );
            }

            @Override
            public CompletableFuture<? extends Meta> metadata(final Key k) {
                return CompletableFuture.failedFuture(
                    new RuntimeException(
                        "Failed to read file: " + k.string(),
                        new NoSuchFileException(k.string())
                    )
                );
            }
        };
        final Remote remote = () -> {
            remoteCalled.set(true);
            return CompletableFuture.completedFuture(
                Optional.of(new Content.From(remoteData))
            );
        };
        final Optional<? extends Content> result = new FromStorageCache(toctouStorage)
            .load(key, remote, CacheControl.Standard.ALWAYS)
            .toCompletableFuture()
            .get();
        MatcherAssert.assertThat(
            "Remote supplier must be called when TOCTOU eviction is detected",
            remoteCalled.get(), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Result must contain remote content (not empty)",
            result.isPresent(), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Content must match remote data",
            result.get(), new ContentIs(remoteData)
        );
    }

    @Test
    void toctouDirectNoSuchFileTriggersUpstreamFetch() throws Exception {
        // Storage that claims key exists but throws NoSuchFileException directly from value().
        // Covers the err instanceof NoSuchFileException branch.
        final Key key = new Key.From("toctou-direct");
        final byte[] remoteData = "remote-direct".getBytes();
        final AtomicBoolean remoteCalled = new AtomicBoolean(false);
        final Storage toctouStorage = new Storage.Wrap(new InMemoryStorage()) {
            @Override
            public CompletableFuture<Boolean> exists(final Key k) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Content> value(final Key k) {
                return CompletableFuture.failedFuture(
                    new NoSuchFileException(k.string())
                );
            }

            @Override
            public CompletableFuture<? extends Meta> metadata(final Key k) {
                return CompletableFuture.failedFuture(
                    new NoSuchFileException(k.string())
                );
            }
        };
        final Remote remote = () -> {
            remoteCalled.set(true);
            return CompletableFuture.completedFuture(
                Optional.of(new Content.From(remoteData))
            );
        };
        final Optional<? extends Content> result = new FromStorageCache(toctouStorage)
            .load(key, remote, CacheControl.Standard.ALWAYS)
            .toCompletableFuture()
            .get();
        MatcherAssert.assertThat(
            "Remote supplier must be called when direct NoSuchFileException is detected",
            remoteCalled.get(), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Result must contain remote content (not empty)",
            result.isPresent(), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Content must match remote data",
            result.get(), new ContentIs(remoteData)
        );
    }

    @Test
    void toctouNonFileErrorStillPropagates() throws Exception {
        // A non-TOCTOU error (e.g. permission denied) must NOT be swallowed;
        // the existing .onErrorComplete() handler converts it to an empty Optional,
        // which triggers the upstream fetch.  The key assertion is that the original
        // error does NOT propagate all the way to the caller (the cache's onErrorComplete
        // handles it), and the remote IS called as fallback.
        // This documents the existing behaviour so a future refactor doesn't regress it.
        final Key key = new Key.From("toctou-other-error");
        final byte[] remoteData = "remote-fallback".getBytes();
        final AtomicBoolean remoteCalled = new AtomicBoolean(false);
        final Storage permissionDeniedStorage = new Storage.Wrap(new InMemoryStorage()) {
            @Override
            public CompletableFuture<Boolean> exists(final Key k) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Content> value(final Key k) {
                return CompletableFuture.failedFuture(
                    new java.io.IOException("Permission denied")
                );
            }

            @Override
            public CompletableFuture<? extends Meta> metadata(final Key k) {
                return CompletableFuture.failedFuture(
                    new java.io.IOException("Permission denied")
                );
            }
        };
        final Remote remote = () -> {
            remoteCalled.set(true);
            return CompletableFuture.completedFuture(
                Optional.of(new Content.From(remoteData))
            );
        };
        // Non-TOCTOU error propagates through .onErrorResumeNext (not swallowed),
        // then hits .doOnError + .onErrorComplete, giving an empty Maybe that triggers switchIfEmpty.
        final Optional<? extends Content> result = new FromStorageCache(permissionDeniedStorage)
            .load(key, remote, CacheControl.Standard.ALWAYS)
            .toCompletableFuture()
            .get();
        MatcherAssert.assertThat(
            "Remote supplier must be called after non-TOCTOU error is handled by onErrorComplete",
            remoteCalled.get(), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Result must contain remote content",
            result.isPresent(), Matchers.is(true)
        );
    }

    @Test
    void processMultipleRequestsSimultaneously() throws Exception {
        final FromStorageCache cache = new FromStorageCache(this.storage);
        final Key key = new Key.From("key4");
        final int count = 100;
        final CountDownLatch latch = new CountDownLatch(
            Runtime.getRuntime().availableProcessors() - 1
        );
        final byte[] data = "data".getBytes();
        final Remote remote =
            () -> CompletableFuture
                .runAsync(
                    () -> {
                        latch.countDown();
                        try {
                            latch.await();
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                    })
                .thenApply(nothing -> ByteBuffer.wrap(data))
                .thenApply(Flowable::just)
                .thenApply(Content.From::new)
                .thenApply(Optional::of);
        Observable.range(0, count).flatMapCompletable(
            num -> com.auto1.pantera.asto.rx.RxFuture.single(cache.load(key, remote, CacheControl.Standard.ALWAYS))
                .flatMapCompletable(
                    pub -> CompletableInterop.fromFuture(
                        this.storage.save(new Key.From("out", num.toString()), pub.get())
                    )
                )
        ).blockingAwait();
        for (int num = 0; num < count; ++num) {
            MatcherAssert.assertThat(
                new BlockingStorage(this.storage).value(new Key.From("out", String.valueOf(num))),
                Matchers.equalTo(data)
            );
        }
    }
}
