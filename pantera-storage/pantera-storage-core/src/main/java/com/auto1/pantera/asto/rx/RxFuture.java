/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.rx;

import io.reactivex.Maybe;
import io.reactivex.Single;
import java.util.concurrent.CompletionStage;

/**
 * Non-blocking conversion utilities from CompletionStage to RxJava 2 types.
 * 
 * <p>CRITICAL: These methods are non-blocking, unlike {@code Single.fromFuture()}
 * and {@code SingleInterop.fromFuture()} which block the calling thread waiting
 * for the future to complete. Blocking in thread pools causes thread starvation
 * and deadlocks under high concurrency.
 *
 * @since 1.0
 */
public final class RxFuture {

    private RxFuture() {
        // Utility class
    }

    /**
     * Convert a CompletionStage to Single without blocking.
     * This is the non-blocking alternative to SingleInterop.fromFuture().
     *
     * @param stage The completion stage
     * @param <T> Result type
     * @return Single that completes when the stage completes
     */
    public static <T> Single<T> single(final CompletionStage<T> stage) {
        return Single.create(emitter -> 
            stage.whenComplete((result, error) -> {
                if (emitter.isDisposed()) {
                    return;
                }
                if (error != null) {
                    emitter.onError(error);
                } else {
                    emitter.onSuccess(result);
                }
            })
        );
    }

    /**
     * Convert a CompletionStage to Maybe without blocking.
     * This is the non-blocking alternative to Maybe.fromFuture().
     *
     * @param stage The completion stage
     * @param <T> Result type
     * @return Maybe that completes when the stage completes
     */
    public static <T> Maybe<T> maybe(final CompletionStage<T> stage) {
        return Maybe.create(emitter ->
            stage.whenComplete((result, error) -> {
                if (emitter.isDisposed()) {
                    return;
                }
                if (error != null) {
                    emitter.onError(error);
                } else if (result != null) {
                    emitter.onSuccess(result);
                } else {
                    emitter.onComplete();
                }
            })
        );
    }
}
