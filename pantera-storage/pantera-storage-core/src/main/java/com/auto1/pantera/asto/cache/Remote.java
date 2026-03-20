/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.log.EcsLogger;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Async {@link java.util.function.Supplier} of {@link java.util.concurrent.CompletionStage}
 * with {@link Optional} of {@link Content}. It's a {@link FunctionalInterface}.
 *
 * @since 0.32
 */
@FunctionalInterface
public interface Remote extends Supplier<CompletionStage<Optional<? extends Content>>> {

    /**
     * Empty remote.
     */
    Remote EMPTY = () -> CompletableFuture.completedFuture(Optional.empty());

    @Override
    CompletionStage<Optional<? extends Content>> get();

    /**
     * Implementation of {@link Remote} that handle all possible errors and returns
     * empty {@link Optional} if any exception happened.
     * @since 0.32
     */
    class WithErrorHandling implements Remote {

        /**
         * Origin.
         */
        private final Remote origin;

        /**
         * Ctor.
         * @param origin Origin
         */
        public WithErrorHandling(final Remote origin) {
            this.origin = origin;
        }

        @Override
        public CompletionStage<Optional<? extends Content>> get() {
            return this.origin.get().handle(
                (content, throwable) -> {
                    final Optional<? extends Content> res;
                    if (throwable == null) {
                        res = content;
                    } else {
                        EcsLogger.error("com.auto1.pantera.asto")
                            .message("Remote content retrieval failed")
                            .eventCategory("cache")
                            .eventAction("remote_get")
                            .eventOutcome("failure")
                            .error(throwable)
                            .log();
                        res = Optional.empty();
                    }
                    return res;
                }
            );
        }
    }

    /**
     * Failed remote.
     * @since 0.32
     */
    final class Failed implements Remote {

        /**
         * Failure cause.
         */
        private final Throwable reason;

        /**
         * Ctor.
         * @param reason Failure cause
         */
        public Failed(final Throwable reason) {
            this.reason = reason;
        }

        @Override
        public CompletionStage<Optional<? extends Content>> get() {
            final CompletableFuture<Optional<? extends Content>> res = new CompletableFuture<>();
            res.completeExceptionally(this.reason);
            return res;
        }
    }
}
