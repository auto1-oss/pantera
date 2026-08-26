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
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.rx.RxFuture;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.asto.log.EcsLogger;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cache implementation that tries to obtain items from storage cache,
 * validates it and returns if valid. If item is not present in storage or is not valid,
 * it is loaded from remote.
 * @since 0.24
 */
public final class FromStorageCache implements Cache {

    /**
     * Back-end storage.
     */
    private final Storage storage;

    /**
     * New storage cache.
     * @param storage Back-end storage for cache
     */
    public FromStorageCache(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletionStage<Optional<? extends Content>> load(final Key key, final Remote remote,
        final CacheControl control) {
        final RxStorageWrapper rxsto = new RxStorageWrapper(this.storage);
        return rxsto.exists(key)
            .filter(exists -> exists)
            .flatMapSingleElement(
                // Use non-blocking RxFuture.single instead of blocking SingleInterop.fromFuture
                exists -> RxFuture.single(
                    // Use optimized content retrieval for validation (100-1000x faster for FileStorage)
                    control.validate(key, () -> OptimizedStorageCache.optimizedValue(this.storage, key).thenApply(Optional::of))
                )
            )
            .filter(valid -> valid)
            .<Optional<? extends Content>>flatMapSingleElement(
                // Use optimized content retrieval for cache hit (100-1000x faster for FileStorage)
                // Use non-blocking RxFuture.single instead of blocking SingleInterop.fromFuture
                ignore -> RxFuture.single(
                    OptimizedStorageCache.optimizedValue(this.storage, key)
                ).map(Optional::of)
            )
            .onErrorResumeNext(err -> {
                // TOCTOU: file was deleted between exists() and open().
                // Treat as a cache miss so switchIfEmpty triggers a fresh upstream fetch.
                // Swallow only the vanished-file family, walking the FULL cause
                // chain: FileStorage.value/metadata wrap the NoSuchFileException as
                // ValueNotFoundException -> IOException -> NoSuchFileException
                // (optionally under a CompletionException). A shallow top-level /
                // one-level check missed that real production shape and leaked a
                // spurious error (npm/pypi "No value for key" -> 404). All other
                // errors propagate.
                final boolean isToctou = isVanishedEntry(err);
                if (isToctou) {
                    EcsLogger.warn("com.auto1.pantera.asto.cache")
                        .message("Cache TOCTOU: file vanished between exists and open, falling through to upstream")
                        .eventCategory("file")
                        .eventAction("cache_toctou_recovered")
                        .eventOutcome("success")
                        .field("file.path", key.string())
                        .field("log.source", "application")
                        .log();
                    return Maybe.empty();
                }
                return Maybe.error(err);
            })
            .doOnError(err ->
                EcsLogger.warn("com.auto1.pantera.asto")
                    .message("Failed to read cached item: " + key.string())
                    .eventCategory("database")
                    .eventAction("cache_read")
                    .eventOutcome("failure")
                    .error(err)
                    .field("log.source", "application")
                    .log()
            )
            .onErrorComplete()
            .switchIfEmpty(
                // LAZY on purpose: Single.defer(...) postpones calling remote.get()
                // until this fallback is actually subscribed, which RxJava's
                // switchIfEmpty only does when the cache-hit chain above completed
                // empty (a genuine miss). Without the defer, remote.get() -- a
                // plain method-call argument to switchIfEmpty -- would be evaluated
                // eagerly while *constructing* the chain, i.e. on every load(), so a
                // side-effecting Remote (an upstream HTTP fetch) fired even on a
                // cache hit. Use non-blocking RxFuture.single, not blocking
                // SingleInterop.fromFuture.
                Single.defer(() -> RxFuture.single(remote.get()).flatMap(
                    content -> {
                        final Single<Optional<? extends Content>> res;
                        if (content.isPresent()) {
                            // Stream-through: deliver bytes to caller immediately while
                            // saving a copy to storage in the background.
                            // This avoids the save-then-read-back two-pass I/O penalty.
                            res = Single.just(
                                Optional.of(teeContent(key, content.get(), this.storage))
                            );
                        } else {
                            res = Single.fromCallable(Optional::empty);
                        }
                        return res;
                    }
                ))
            ).to(SingleInterop.get());
    }

    /**
     * Whether a cache-read error is the "entry vanished under us" family that
     * must be treated as a cache miss (fall through to upstream) rather than
     * propagated. Walks the whole cause chain because FileStorage wraps the
     * underlying {@link NoSuchFileException} inside a
     * {@link ValueNotFoundException} (itself possibly under a
     * {@code CompletionException}), so a shallow top-level / one-level check
     * misses the real production shape.
     *
     * @param err Error thrown while validating or reading the cached entry.
     * @return {@code true} if any cause is a NoSuchFile / ValueNotFound.
     */
    private static boolean isVanishedEntry(final Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof NoSuchFileException
                || cause instanceof ValueNotFoundException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Create a tee-Content that forwards bytes to the caller while accumulating
     * them for background storage save.
     *
     * @param key Storage key for caching
     * @param remote Remote content to tee
     * @param sto Storage to save to
     * @return Content that streams to caller and saves to storage
     */
    private static Content teeContent(final Key key, final Content remote, final Storage sto) {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final AtomicBoolean saveFired = new AtomicBoolean(false);
        final Flowable<ByteBuffer> teed = Flowable.fromPublisher(remote)
            .doOnNext(buf -> {
                final ByteBuffer copy = buf.asReadOnlyBuffer();
                final byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                buffer.write(bytes);
            })
            .doOnComplete(() -> {
                if (saveFired.compareAndSet(false, true)) {
                    try {
                        sto.save(key, new Content.From(buffer.toByteArray()))
                            .whenComplete((ignored, err) -> {
                                if (err != null) {
                                    EcsLogger.warn("com.auto1.pantera.asto.cache")
                                        .message(String.format("Stream-through: failed to save to cache for key '%s'", key.string()))
                                        .eventCategory("database")
                                        .eventAction("stream_through_save")
                                        .eventOutcome("failure")
                                        .error(err)
                                        .field("log.source", "application")
                                        .log();
                                }
                            });
                    } catch (final Exception ex) {
                        EcsLogger.warn("com.auto1.pantera.asto.cache")
                            .message(String.format("Stream-through: exception initiating save for key '%s'", key.string()))
                            .eventCategory("database")
                            .eventAction("stream_through_save")
                            .eventOutcome("failure")
                            .error(ex)
                            .field("log.source", "application")
                            .log();
                    }
                }
            });
        return new Content.From(remote.size(), teed);
    }
}
