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
package com.auto1.pantera.vertx;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Tests for {@link RequestBodyPublisher}. {@link Http2LikeStream} models the
 * contract of {@code Http2ServerRequest}: it must be driven from its context,
 * and {@code fetch}/{@code pause}/{@code handler} throw "Request has already
 * been read" once the end has been delivered — the end flag is set just before
 * the end handler runs, which is the window a foreign-thread {@code request(n)}
 * used to fall into.
 *
 * @since 2.2.11
 */
final class RequestBodyPublisherTest {

    private Vertx vertx;

    private Context context;

    private ExecutorService foreign;

    @BeforeEach
    void setUp() {
        this.vertx = Vertx.vertx();
        this.context = this.vertx.getOrCreateContext();
        this.foreign = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        this.foreign.shutdownNow();
        this.vertx.close();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void requestFromForeignThreadInsideEndWindowNeitherThrowsNorFetchesAfterEnd()
        throws Exception {
        final Http2LikeStream stream = this.onContext(() -> new Http2LikeStream(this.context));
        final RequestBodyPublisher body = this.onContext(
            () -> new RequestBodyPublisher(stream, this.context)
        );
        final Recorder rec = new Recorder();
        this.foreign.submit(() -> body.subscribe(rec)).get();
        final Subscription sub = rec.subscription();
        sub.request(1L);
        stream.awaitDemand();
        this.onContext(() -> stream.emit("abc"));
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        // End the body; while the end flag is already set but before the end
        // handler ran, a foreign thread (rio's writer) asks for more data.
        this.onContext(
            () -> stream.end(
                () -> {
                    try {
                        this.foreign.submit(() -> sub.request(8L)).get();
                    } catch (final Exception ex) {
                        thrown.set(ex.getCause() == null ? ex : ex.getCause());
                    }
                }
            )
        );
        rec.awaitTerminal();
        // Let the coalesced forwarding task scheduled by request(8) run.
        this.onContext(() -> null);
        MatcherAssert.assertThat(
            "request(n) must never throw on the subscriber's thread",
            thrown.get(), new IsNull<>()
        );
        MatcherAssert.assertThat(
            "no fetch may reach the stream after its end was delivered",
            stream.fetchesAfterEnd(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the body completes normally",
            rec.signals(), new IsEqual<>(List.of("next:abc", "complete"))
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void forwardsDemandAndDeliversChunksInOrder() throws Exception {
        final Http2LikeStream stream = this.onContext(() -> new Http2LikeStream(this.context));
        final RequestBodyPublisher body = this.onContext(
            () -> new RequestBodyPublisher(stream, this.context)
        );
        final Recorder rec = new Recorder();
        this.foreign.submit(() -> body.subscribe(rec)).get();
        rec.subscription().request(2L);
        stream.awaitDemand();
        this.onContext(
            () -> {
                stream.emit("a");
                stream.emit("b");
                stream.end(() -> { });
                return null;
            }
        );
        rec.awaitTerminal();
        MatcherAssert.assertThat(
            "both chunks then completion",
            rec.signals(), new IsEqual<>(List.of("next:a", "next:b", "complete"))
        );
        MatcherAssert.assertThat(
            "demand reaches the stream as one coalesced fetch",
            stream.fetched(), new IsEqual<>(2L)
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void bodyThatEndedBeforeHandlersWereInstalledFailsInsteadOfHanging() throws Exception {
        final Http2LikeStream stream = this.onContext(() -> new Http2LikeStream(this.context));
        final RequestBodyPublisher body = this.onContext(
            () -> new RequestBodyPublisher(stream, this.context)
        );
        this.onContext(() -> stream.end(() -> { }));
        final Recorder rec = new Recorder();
        this.foreign.submit(() -> body.subscribe(rec)).get();
        rec.awaitTerminal();
        MatcherAssert.assertThat(
            rec.error(), new IsInstanceOf(IllegalStateException.class)
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void secondSubscriberIsRejected() throws Exception {
        final Http2LikeStream stream = this.onContext(() -> new Http2LikeStream(this.context));
        final RequestBodyPublisher body = this.onContext(
            () -> new RequestBodyPublisher(stream, this.context)
        );
        body.subscribe(new Recorder());
        final Recorder second = new Recorder();
        body.subscribe(second);
        second.awaitTerminal();
        MatcherAssert.assertThat(
            second.error(), new IsInstanceOf(IllegalStateException.class)
        );
    }

    /**
     * Run {@code task} on the test context and wait for its result.
     * @param task Task
     * @param <T> Result type
     * @return Result
     * @throws Exception On failure
     */
    private <T> T onContext(final java.util.concurrent.Callable<T> task) throws Exception {
        final CompletableFuture<T> res = new CompletableFuture<>();
        this.context.runOnContext(
            ignored -> {
                try {
                    res.complete(task.call());
                } catch (final Exception ex) {
                    res.completeExceptionally(ex);
                }
            }
        );
        return res.get(10, TimeUnit.SECONDS);
    }

    /**
     * Fake request body following {@code Http2ServerRequest}'s rules.
     */
    private static final class Http2LikeStream implements ReadStream<Buffer> {

        private final Context ctx;

        private final CountDownLatch demanded;

        private Handler<Buffer> data;

        private Handler<Void> end;

        private boolean ended;

        private long fetched;

        private int lateFetches;

        Http2LikeStream(final Context ctx) {
            this.ctx = ctx;
            this.demanded = new CountDownLatch(1);
        }

        @Override
        public synchronized ReadStream<Buffer> exceptionHandler(final Handler<Throwable> handler) {
            return this;
        }

        @Override
        public synchronized ReadStream<Buffer> handler(final Handler<Buffer> handler) {
            if (handler != null) {
                this.checkEnded();
            }
            this.data = handler;
            return this;
        }

        @Override
        public synchronized ReadStream<Buffer> pause() {
            this.checkEnded();
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            return this.fetch(Long.MAX_VALUE);
        }

        @Override
        public synchronized ReadStream<Buffer> fetch(final long amount) {
            if (this.ended) {
                this.lateFetches += 1;
            }
            this.checkEnded();
            if (amount != Long.MAX_VALUE) {
                this.fetched += amount;
            }
            this.demanded.countDown();
            return this;
        }

        @Override
        public synchronized ReadStream<Buffer> endHandler(final Handler<Void> handler) {
            if (handler != null) {
                this.checkEnded();
            }
            this.end = handler;
            return this;
        }

        void awaitDemand() throws InterruptedException {
            this.demanded.await();
        }

        synchronized long fetched() {
            return this.fetched;
        }

        synchronized int fetchesAfterEnd() {
            return this.lateFetches;
        }

        Void emit(final String chunk) {
            this.onCtx();
            final Handler<Buffer> handler;
            synchronized (this) {
                handler = this.data;
            }
            handler.handle(Buffer.buffer(chunk));
            return null;
        }

        /**
         * Deliver the end like {@code Http2ServerRequest.handleEnd}: mark
         * ended, then (after {@code window}) invoke the end handler.
         * @param window Runs between marking ended and the end handler
         * @return Nothing
         */
        Void end(final Runnable window) {
            this.onCtx();
            final Handler<Void> handler;
            synchronized (this) {
                this.ended = true;
                handler = this.end;
            }
            window.run();
            if (handler != null) {
                handler.handle(null);
            }
            return null;
        }

        private void onCtx() {
            if (Vertx.currentContext() != this.ctx) {
                throw new IllegalStateException("stream driven off its context");
            }
        }

        private void checkEnded() {
            if (this.ended) {
                throw new IllegalStateException("Request has already been read");
            }
        }
    }

    /**
     * Recording subscriber.
     */
    private static final class Recorder implements Subscriber<ByteBuffer> {

        private final CompletableFuture<Subscription> sub = new CompletableFuture<>();

        private final List<String> log = new CopyOnWriteArrayList<>();

        private final CountDownLatch terminal = new CountDownLatch(1);

        private final AtomicReference<Throwable> err = new AtomicReference<>();

        @Override
        public void onSubscribe(final Subscription subscription) {
            this.sub.complete(subscription);
        }

        @Override
        public void onNext(final ByteBuffer item) {
            this.log.add("next:" + StandardCharsets.UTF_8.decode(item));
        }

        @Override
        public void onError(final Throwable throwable) {
            this.err.set(throwable);
            this.log.add("error");
            this.terminal.countDown();
        }

        @Override
        public void onComplete() {
            this.log.add("complete");
            this.terminal.countDown();
        }

        Subscription subscription() throws Exception {
            return this.sub.get(10, TimeUnit.SECONDS);
        }

        void awaitTerminal() throws InterruptedException {
            this.terminal.await();
        }

        List<String> signals() {
            return new ArrayList<>(this.log);
        }

        Throwable error() {
            return this.err.get();
        }
    }
}
