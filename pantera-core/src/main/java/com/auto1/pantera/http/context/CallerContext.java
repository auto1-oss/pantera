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
package com.auto1.pantera.http.context;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.apache.logging.log4j.ThreadContext;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * The logging context ({@link ThreadContext}: {@code trace.id},
 * {@code client.ip}, ...) of the thread that created it, to be carried over
 * a future that is completed by a thread the request does not own.
 *
 * <p>A Valkey answer arrives on a Lettuce event loop, an upstream answer on
 * a Jetty client thread, a storage answer on a {@code pantera-io-*} thread.
 * Whatever is chained on such a future runs on that shared thread. Without
 * this class two things go wrong there:</p>
 * <ol>
 *   <li>a record logged by the continuation has no {@code trace.id}, because
 *       the thread never saw the request;</li>
 *   <li>a continuation that binds the request's context
 *       ({@code RequestContextHeaders.bindToMdc}) leaves it on the thread, and
 *       every later, unrelated record of that thread carries the trace id of
 *       a request it has nothing to do with.</li>
 * </ol>
 *
 * <p>{@link #resume(CompletionStage)} is the boundary: the returned future is
 * completed with the caller's context installed, so everything chained on it
 * synchronously logs under the caller's {@code trace.id}; when the chain
 * returns, the thread gets back exactly the context it had before, whatever
 * the continuations put into it. Use it where a future of a foreign thread
 * pool crosses back into request code.</p>
 *
 * <p>Unlike {@link ContextualExecutor} no thread hop is involved: the
 * continuation still runs on the completing thread.</p>
 *
 * @since 2.2.14
 */
public final class CallerContext {

    /**
     * Context of the creating thread.
     */
    private final Map<String, String> ctx;

    /**
     * Capture the context of the current thread.
     */
    public CallerContext() {
        this(ThreadContext.getImmutableContext());
    }

    /**
     * Ctor.
     * @param ctx Context to carry
     */
    private CallerContext(final Map<String, String> ctx) {
        this.ctx = ctx;
    }

    /**
     * Future that completes as {@code stage} does, with the captured context
     * installed on the completing thread while its dependents run and the
     * thread's own context restored afterwards.
     *
     * @param stage Future completed by a foreign thread
     * @param <T> Result type
     * @return Future to chain request code on
     */
    public <T> CompletableFuture<T> resume(final CompletionStage<T> stage) {
        final CompletableFuture<T> out = new CompletableFuture<>();
        stage.whenComplete(
            (val, err) -> this.run(
                () -> {
                    if (err == null) {
                        out.complete(val);
                    } else {
                        out.completeExceptionally(err);
                    }
                }
            )
        );
        return out;
    }

    /**
     * Subscriber that receives every signal of a body under the captured
     * context: a body streamed by a foreign thread (an upstream answer on a
     * Jetty thread) resumes request code on that thread with each chunk and
     * with its end.
     *
     * @param sub Subscriber of the request code
     * @param <T> Item type
     * @return Subscriber to hand to the foreign publisher
     */
    public <T> Subscriber<T> resume(final Subscriber<? super T> sub) {
        return new Subscriber<>() {
            @Override
            public void onSubscribe(final Subscription subscription) {
                CallerContext.this.run(() -> sub.onSubscribe(subscription));
            }

            @Override
            public void onNext(final T item) {
                CallerContext.this.run(() -> sub.onNext(item));
            }

            @Override
            public void onError(final Throwable err) {
                CallerContext.this.run(() -> sub.onError(err));
            }

            @Override
            public void onComplete() {
                CallerContext.this.run(sub::onComplete);
            }
        };
    }

    /**
     * Run the task on the current thread under the captured context, then
     * give the thread back the context it had.
     *
     * @param task Task to run
     */
    public void run(final Runnable task) {
        final Map<String, String> prior = ThreadContext.getImmutableContext();
        // On the caller's own thread there is nothing to install; a body
        // delivers every chunk through here, so the common case stays cheap.
        if (!prior.equals(this.ctx)) {
            CallerContext.install(this.ctx);
        }
        try {
            task.run();
        } finally {
            if (!prior.equals(ThreadContext.getImmutableContext())) {
                CallerContext.install(prior);
            }
        }
    }

    /**
     * Replace the context of the current thread.
     * @param map New context
     */
    private static void install(final Map<String, String> map) {
        ThreadContext.clearMap();
        if (!map.isEmpty()) {
            ThreadContext.putAll(map);
        }
    }
}
