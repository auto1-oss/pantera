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

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.log.EcsLogger;
import io.reactivex.Flowable;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * This cache implementation loads all the items from remote and caches it to storage. Content
 * is loaded from cache only if remote failed to return requested item.
 * @since 0.30
 */
public final class FromRemoteCache implements Cache {

    /**
     * Back-end storage.
     */
    private final Storage storage;

    /**
     * New remote cache.
     * @param storage Back-end storage for cache
     */
    public FromRemoteCache(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletionStage<Optional<? extends Content>> load(
        final Key key, final Remote remote, final CacheControl control
    ) {
        return remote.get().handle(
            (content, throwable) -> {
                final CompletionStage<Optional<? extends Content>> res;
                if (throwable == null && content.isPresent()) {
                    // Stream-through: deliver bytes to caller immediately while
                    // saving a copy to storage in the background.
                    // This avoids the save-then-read-back two-pass I/O penalty.
                    res = CompletableFuture.completedFuture(
                        Optional.of(teeContent(key, content.get(), this.storage))
                    );
                } else {
                    final Throwable error;
                    if (throwable == null) {
                        error = new PanteraIOException("Failed to load content from remote");
                    } else {
                        error = throwable;
                    }
                    res = new FromStorageCache(this.storage)
                        .load(key, new Remote.Failed(error), control);
                }
                return res;
            }
        ).thenCompose(Function.identity());
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
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
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
                            .log();
                    }
                }
            });
        return new Content.From(remote.size(), teed);
    }
}
