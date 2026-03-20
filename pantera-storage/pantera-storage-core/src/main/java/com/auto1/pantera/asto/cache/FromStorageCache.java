/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.rx.RxFuture;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.asto.log.EcsLogger;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.Single;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
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
            .doOnError(err ->
                EcsLogger.warn("com.auto1.pantera.asto")
                    .message("Failed to read cached item: " + key.string())
                    .eventCategory("cache")
                    .eventAction("cache_read")
                    .eventOutcome("failure")
                    .error(err)
                    .log()
            )
            .onErrorComplete()
            .switchIfEmpty(
                // Use non-blocking RxFuture.single instead of blocking SingleInterop.fromFuture
                RxFuture.single(remote.get()).flatMap(
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
                )
            ).to(SingleInterop.get());
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
                                        .eventCategory("cache")
                                        .eventAction("stream_through_save")
                                        .eventOutcome("failure")
                                        .error(err)
                                        .log();
                                }
                            });
                    } catch (final Exception ex) {
                        EcsLogger.warn("com.auto1.pantera.asto.cache")
                            .message(String.format("Stream-through: exception initiating save for key '%s'", key.string()))
                            .eventCategory("cache")
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
