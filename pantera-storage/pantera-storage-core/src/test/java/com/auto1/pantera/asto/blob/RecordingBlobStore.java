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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory, invocation-counting {@link BlobStore} fake -- the direct
 * {@code BlobStore}-level analogue of {@code FakeS3AsyncClient} (WS1.0,
 * {@code pantera-storage-s3}), used here so {@link CachedBlobStorage} tests
 * can assert "zero blob-store round trips on a hit" / "exactly one GET for
 * N concurrent cold callers" without any S3 SDK or Docker dependency.
 *
 * <p>{@link #gateGet(CountDownLatch, CountDownLatch)} lets a test line up N
 * concurrent callers deterministically around a single {@link #get(Key)}
 * invocation (latches, not wall-clock sleeps -- CLAUDE.md testing doctrine).</p>
 *
 * @since 2.3.0
 */
final class RecordingBlobStore implements BlobStore {

    /**
     * In-memory object store: key -&gt; bytes.
     */
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    private final AtomicInteger existsCalls = new AtomicInteger();
    private final AtomicInteger headCalls = new AtomicInteger();
    private final AtomicInteger getCalls = new AtomicInteger();
    private final AtomicInteger putCalls = new AtomicInteger();
    private final AtomicInteger deleteCalls = new AtomicInteger();
    private final AtomicInteger listCalls = new AtomicInteger();

    /**
     * Counted down the moment a {@link #get(Key)} invocation actually starts
     * running (as opposed to having merely been called) -- optional, set via
     * {@link #gateGet(CountDownLatch, CountDownLatch)}.
     */
    private volatile CountDownLatch enteredGetGate;

    /**
     * Awaited before a {@link #get(Key)} invocation returns -- optional, set
     * via {@link #gateGet(CountDownLatch, CountDownLatch)}.
     */
    private volatile CountDownLatch releaseGetGate;

    /**
     * Counted down the moment a {@link #put(Key, Content)} invocation
     * actually starts running -- optional, set via {@link
     * #gatePut(CountDownLatch, CountDownLatch)}. Used by the WS1.2
     * write-back tests to deterministically observe "the uploader picked
     * this key up" and to hold a write-back admission permit open
     * indefinitely (never counting down {@code release}) to simulate a
     * saturated queue or a crash-before-drain.
     */
    private volatile CountDownLatch enteredPutGate;

    /**
     * Awaited before a {@link #put(Key, Content)} invocation completes --
     * optional, set via {@link #gatePut(CountDownLatch, CountDownLatch)}.
     */
    private volatile CountDownLatch releasePutGate;

    void seed(final String key, final byte[] data) {
        this.objects.put(key, data);
    }

    /**
     * Gate the next (and only the next single-flighted) {@link #get(Key)}
     * call: it counts down {@code entered} the instant it starts, then
     * blocks until {@code release} counts down to zero. Lets a test prove
     * that N concurrent callers all lined up before the one real fetch is
     * allowed to complete.
     *
     * @param entered Counted down when {@code get()} starts running.
     * @param release Awaited before {@code get()} returns.
     */
    void gateGet(final CountDownLatch entered, final CountDownLatch release) {
        this.enteredGetGate = entered;
        this.releaseGetGate = release;
    }

    /**
     * Gate every subsequent {@link #put(Key, Content)} call: each counts
     * down {@code entered} the instant it starts, then blocks until {@code
     * release} counts down to zero. A test that never counts down {@code
     * release} simulates a write-back upload that never confirms (queue
     * saturation, or a crash-before-drain that leaves the entry {@code
     * PENDING_WRITE}).
     *
     * @param entered Counted down every time a gated {@code put()} starts running.
     * @param release Awaited before a gated {@code put()} completes.
     */
    void gatePut(final CountDownLatch entered, final CountDownLatch release) {
        this.enteredPutGate = entered;
        this.releasePutGate = release;
    }

    int existsCalls() {
        return this.existsCalls.get();
    }

    int headCalls() {
        return this.headCalls.get();
    }

    int getCalls() {
        return this.getCalls.get();
    }

    int putCalls() {
        return this.putCalls.get();
    }

    int deleteCalls() {
        return this.deleteCalls.get();
    }

    int listCalls() {
        return this.listCalls.get();
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        this.existsCalls.incrementAndGet();
        return CompletableFuture.completedFuture(this.objects.containsKey(key.string()));
    }

    @Override
    public CompletableFuture<? extends Meta> head(final Key key) {
        this.headCalls.incrementAndGet();
        final byte[] data = this.objects.get(key.string());
        final CompletableFuture<Meta> result;
        if (data == null) {
            result = CompletableFuture.failedFuture(new ValueNotFoundException(key));
        } else {
            result = CompletableFuture.completedFuture(new SizeMeta(data.length));
        }
        return result;
    }

    @Override
    public CompletableFuture<Content> get(final Key key) {
        this.getCalls.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            final CountDownLatch entered = this.enteredGetGate;
            if (entered != null) {
                entered.countDown();
            }
            final CountDownLatch release = this.releaseGetGate;
            if (release != null) {
                RecordingBlobStore.awaitUninterruptibly(release);
            }
            final byte[] data = this.objects.get(key.string());
            if (data == null) {
                throw new CompletionException(new ValueNotFoundException(key));
            }
            return new Content.From(data);
        });
    }

    @Override
    public CompletableFuture<Void> put(final Key key, final Content content) {
        this.putCalls.incrementAndGet();
        return content.asBytesFuture().thenCompose(bytes -> CompletableFuture.<Void>supplyAsync(() -> {
            final CountDownLatch entered = this.enteredPutGate;
            if (entered != null) {
                entered.countDown();
            }
            final CountDownLatch release = this.releasePutGate;
            if (release != null) {
                RecordingBlobStore.awaitUninterruptibly(release);
            }
            this.objects.put(key.string(), bytes);
            return null;
        }));
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        this.deleteCalls.incrementAndGet();
        final CompletableFuture<Void> result;
        if (this.objects.remove(key.string()) == null) {
            result = CompletableFuture.failedFuture(new ValueNotFoundException(key));
        } else {
            result = CompletableFuture.completedFuture(null);
        }
        return result;
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key prefix) {
        this.listCalls.incrementAndGet();
        final String raw = prefix.string();
        final List<Key> matches = new ArrayList<>();
        for (final String candidate : this.objects.keySet()) {
            if (raw.isEmpty() || candidate.equals(raw) || candidate.startsWith(raw)) {
                matches.add(new Key.From(candidate));
            }
        }
        return CompletableFuture.completedFuture(matches);
    }

    private static void awaitUninterruptibly(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Minimal {@link Meta} exposing only {@link Meta#OP_SIZE}, mirroring
     * what a real backend's HEAD-equivalent would carry for this fake.
     */
    private static final class SizeMeta implements Meta {

        private final long size;

        SizeMeta(final long size) {
            this.size = size;
        }

        @Override
        public <T> T read(final ReadOperator<T> opr) {
            final Map<String, String> raw = new HashMap<>();
            Meta.OP_SIZE.put(raw, this.size);
            return opr.take(raw);
        }
    }
}
