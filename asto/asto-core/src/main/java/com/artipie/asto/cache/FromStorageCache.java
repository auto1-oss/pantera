/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.cache;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.rx.RxStorageWrapper;
import com.artipie.asto.log.EcsLogger;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Single;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

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
                exists -> SingleInterop.fromFuture(
                    // Use optimized content retrieval for validation (100-1000x faster for FileStorage)
                    control.validate(key, () -> OptimizedStorageCache.optimizedValue(this.storage, key).thenApply(Optional::of))
                )
            )
            .filter(valid -> valid)
            .<Optional<? extends Content>>flatMapSingleElement(
                // Use optimized content retrieval for cache hit (100-1000x faster for FileStorage)
                ignore -> Single.fromFuture(
                    OptimizedStorageCache.optimizedValue(this.storage, key)
                ).map(Optional::of)
            )
            .doOnError(err ->
                EcsLogger.warn("com.artipie.asto")
                    .message("Failed to read cached item: " + key.string())
                    .eventCategory("cache")
                    .eventAction("cache_read")
                    .eventOutcome("failure")
                    .error(err)
                    .log()
            )
            .onErrorComplete()
            .switchIfEmpty(
                SingleInterop.fromFuture(remote.get()).flatMap(
                    content -> {
                        final Single<Optional<? extends Content>> res;
                        if (content.isPresent()) {
                            // CRITICAL: Don't call content.get() twice - it's a OneTimePublisher!
                            // Save content as-is (size will be computed during save if needed)
                            final Content remoteContent = content.get();
                            res = rxsto.save(key, remoteContent)
                            // Read back saved content (optimization happens during cache hits above)
                            .andThen(rxsto.value(key)).map(Optional::of);
                        } else {
                            res = Single.fromCallable(Optional::empty);
                        }
                        return res;
                    }
                )
            ).to(SingleInterop.get());
    }
}
