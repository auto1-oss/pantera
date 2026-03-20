/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.proxy;

import com.auto1.pantera.asto.rx.RxFuture;
import com.auto1.pantera.npm.proxy.model.NpmAsset;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import io.reactivex.Maybe;
import io.vertx.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Decorate a {@link NpmRemote} with a {@link CircuitBreaker}.
 * @since 0.7
 * @todo #16:30min Wrap new instances of HttpNpmRemote with this class
 *  in NpmProxy. But first ensure that HttpNpmRemote throws an exception
 *  in case the request fails (it should still return Maybe for 404
 *  though). Also see https://vertx.io/docs/vertx-circuit-breaker/java/
 *  for configuring the CircuitBreaker.
 * @todo #16:30min Add a test to ensure it works as expected. The most simple
 *  is to provide a Fake version of NpmRemote that can be setup to either fail
 *  or work as expected by the contract of NpmRemote. Be careful about the fact
 *  that the expected behaviour is beasically: empty if the asset/package is not
 *  present and an exception if there is an error. See also the todo above or
 *  HttpNpmRemote if it had been solved already.
 */
public final class CircuitBreakerNpmRemote implements NpmRemote {

    /**
     * NPM Remote.
     */
    private final NpmRemote wrapped;

    /**
     * Circuit Breaker.
     */
    private final CircuitBreaker breaker;

    /**
     * Ctor.
     * @param wrapped Wrapped remote
     * @param breaker Circuit breaker
     */
    public CircuitBreakerNpmRemote(final NpmRemote wrapped, final CircuitBreaker breaker) {
        this.wrapped = wrapped;
        this.breaker = breaker;
    }

    @Override
    public void close() throws IOException {
        this.wrapped.close();
        this.breaker.close();
    }

    @Override
    public Maybe<NpmPackage> loadPackage(final String name) {
        // Use non-blocking RxFuture.maybe instead of blocking Maybe.fromFuture
        return RxFuture.maybe(
            this.breaker.<Maybe<NpmPackage>>executeWithFallback(
                future -> future.complete(this.wrapped.loadPackage(name)),
                exception -> Maybe.empty()
            ).toCompletionStage()
        ).flatMap(m -> m);
    }

    @Override
    public Maybe<NpmAsset> loadAsset(final String path, final Path tmp) {
        // Use non-blocking RxFuture.maybe instead of blocking Maybe.fromFuture
        return RxFuture.maybe(
            this.breaker.<Maybe<NpmAsset>>executeWithFallback(
                future -> future.complete(this.wrapped.loadAsset(path, tmp)),
                exception -> Maybe.empty()
            ).toCompletionStage()
        ).flatMap(m -> m);
    }
}
