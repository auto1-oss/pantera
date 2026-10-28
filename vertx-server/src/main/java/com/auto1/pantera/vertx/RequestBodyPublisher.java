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

import com.auto1.pantera.http.log.EcsLogger;
import io.netty.buffer.ByteBuf;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Reactive-streams view of an inbound HTTP request body that confines every
 * interaction with the Vert.x {@link ReadStream} to the request's own context.
 *
 * <p>Replaces {@code HttpServerRequest#toFlowable()} on the streaming upload
 * path. That adapter forwards {@code Subscription.request(n)} straight into
 * {@code ReadStream.fetch(n)} on whatever thread the subscriber calls from —
 * for a file-storage upload, a rio writer thread. On HTTP/2,
 * {@code Http2ServerRequest.fetch} throws {@code IllegalStateException("Request
 * has already been read")} once the body's end has been delivered, and the
 * adapter only stops forwarding demand after its end handler ran: a writer
 * thread calling {@code request(n)} in between gets the exception thrown into
 * its own task (violating Reactive Streams §3.6/§3.16). rio's write loop dies
 * on it with its "running" flag still set, so the queued completion is never
 * processed, the save never completes, no response is sent and the client
 * waits until the connection idle timeout. HTTP/1.1's {@code fetch} has no such
 * check, which is why only HTTP/2 uploads hung.</p>
 *
 * <p>Here {@code request(n)} only accumulates demand and schedules a single
 * coalesced task on the request context; handler installation, {@code fetch},
 * cancellation and every downstream signal run on that context, serialized
 * with the stream's own end/reset delivery. A fetch can therefore never be
 * issued after the body ended, and {@code request}/{@code cancel} never throw
 * on the caller's thread.</p>
 *
 * <p>Single-use: one subscriber per request body.</p>
 *
 * @since 2.2.11
 */
final class RequestBodyPublisher implements Publisher<ByteBuffer> {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.vertx";

    /**
     * Request body stream.
     */
    private final ReadStream<Buffer> stream;

    /**
     * Context the stream delivers its events on.
     */
    private final Context context;

    /**
     * Guards the single subscription.
     */
    private final AtomicBoolean subscribed;

    /**
     * Ctor. Must be called on {@code context}: the stream is paused at once
     * so no body bytes are delivered before a subscriber asks for them.
     * @param stream Request body stream
     * @param context Context the stream delivers its events on
     */
    RequestBodyPublisher(final ReadStream<Buffer> stream, final Context context) {
        this.stream = Objects.requireNonNull(stream, "stream must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.subscribed = new AtomicBoolean();
        stream.pause();
    }

    @Override
    public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        if (!this.subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(new Rejected());
            subscriber.onError(
                new IllegalStateException("Request body allows only a single subscriber")
            );
            return;
        }
        final BodySubscription sub = new BodySubscription(subscriber);
        subscriber.onSubscribe(sub);
        sub.onContext(sub::install);
    }

    /**
     * Copy a Vert.x buffer into a fresh heap {@link ByteBuffer}.
     * @param buffer Vert.x buffer
     * @return Independent, flipped byte buffer
     */
    private static ByteBuffer copy(final Buffer buffer) {
        final ByteBuf netty = buffer.getByteBuf();
        final ByteBuffer nio = ByteBuffer.allocate(netty.readableBytes());
        netty.getBytes(netty.readerIndex(), nio);
        nio.flip();
        return nio;
    }

    /**
     * Subscription handed to a rejected second subscriber.
     */
    private static final class Rejected implements Subscription {
        @Override
        public void request(final long amount) {
            // Already terminated with an error.
        }

        @Override
        public void cancel() {
            // Already terminated with an error.
        }
    }

    /**
     * The one subscription. Fields other than {@link #demand} and
     * {@link #scheduled} are confined to the request context.
     */
    private final class BodySubscription implements Subscription {

        /**
         * Downstream subscriber.
         */
        private final Subscriber<? super ByteBuffer> downstream;

        /**
         * Demand requested by the subscriber and not yet passed to fetch.
         */
        private final AtomicLong demand;

        /**
         * Whether a demand-forwarding task is already queued on the context.
         */
        private final AtomicBoolean scheduled;

        /**
         * Stream handlers installed (context-confined).
         */
        private boolean installed;

        /**
         * Terminated or cancelled (context-confined).
         */
        private boolean done;

        /**
         * Ctor.
         * @param downstream Downstream subscriber
         */
        BodySubscription(final Subscriber<? super ByteBuffer> downstream) {
            this.downstream = downstream;
            this.demand = new AtomicLong();
            this.scheduled = new AtomicBoolean();
        }

        @Override
        public void request(final long amount) {
            if (amount <= 0L) {
                this.onContext(
                    () -> this.fail(
                        new IllegalArgumentException(
                            "Reactive Streams §3.9: request amount must be positive, got " + amount
                        )
                    )
                );
                return;
            }
            this.demand.accumulateAndGet(
                amount, (cur, add) -> cur + add < 0L ? Long.MAX_VALUE : cur + add
            );
            if (this.scheduled.compareAndSet(false, true)) {
                this.onContext(
                    () -> {
                        this.scheduled.set(false);
                        this.forward();
                    }
                );
            }
        }

        @Override
        public void cancel() {
            this.onContext(
                () -> {
                    if (!this.done) {
                        this.done = true;
                        this.release();
                    }
                }
            );
        }

        /**
         * Install the stream handlers, then forward any demand that arrived
         * before they were in place. Runs on the context.
         */
        void install() {
            if (this.done) {
                return;
            }
            try {
                RequestBodyPublisher.this.stream.handler(this::next);
                RequestBodyPublisher.this.stream.endHandler(ignored -> this.complete());
                RequestBodyPublisher.this.stream.exceptionHandler(this::fail);
            } catch (final IllegalStateException ex) {
                // The stream already ended (e.g. the client reset it) before
                // the subscriber got here: fail instead of waiting forever.
                this.fail(ex);
                return;
            }
            this.installed = true;
            this.forward();
        }

        /**
         * Run {@code task} on the request context: inline when already there,
         * otherwise queued. Never throws on the caller's thread.
         * @param task Context-confined task
         */
        void onContext(final Runnable task) {
            if (RequestBodyPublisher.this.context.equals(Vertx.currentContext())) {
                task.run();
                return;
            }
            try {
                RequestBodyPublisher.this.context.runOnContext(ignored -> task.run());
            } catch (final RuntimeException ex) {
                // Event loop shut down: the connection is gone with it.
                EcsLogger.debug(LOGGER)
                    .message("Request body signal dropped: request context no longer accepts tasks")
                    .eventCategory("web")
                    .eventAction("request_body_signal")
                    .eventOutcome("failure")
                    .error(ex)
                    .field("log.source", "application")
                    .log();
            }
        }

        /**
         * Pass accumulated demand to the stream. Runs on the context.
         */
        private void forward() {
            if (this.done || !this.installed) {
                return;
            }
            final long amount = this.demand.getAndSet(0L);
            if (amount == 0L) {
                return;
            }
            try {
                RequestBodyPublisher.this.stream.fetch(amount);
            } catch (final IllegalStateException ex) {
                // Unreachable while confined to the context (end and reset
                // are delivered here and mark the subscription done first);
                // kept so a Vert.x change cannot reintroduce a silent hang.
                this.fail(ex);
            }
        }

        /**
         * Body chunk. Runs on the context.
         * @param buffer Chunk
         */
        private void next(final Buffer buffer) {
            if (!this.done) {
                this.downstream.onNext(RequestBodyPublisher.copy(buffer));
            }
        }

        /**
         * Body end. Runs on the context.
         */
        private void complete() {
            if (!this.done) {
                this.done = true;
                this.detach();
                this.downstream.onComplete();
            }
        }

        /**
         * Body failure. Runs on the context.
         * @param err Failure
         */
        private void fail(final Throwable err) {
            if (!this.done) {
                this.done = true;
                this.release();
                this.downstream.onError(err);
            }
        }

        /**
         * Detach from the stream and let any remaining body bytes drain so
         * the connection is not left paused (cancel or failure before the
         * end). Runs on the context.
         */
        @SuppressWarnings("PMD.EmptyCatchBlock")
        private void release() {
            this.detach();
            try {
                RequestBodyPublisher.this.stream.resume();
            } catch (final IllegalStateException ignored) {
                // HTTP/2 refuses resume once the body ended or was reset:
                // nothing left to drain.
            }
        }

        /**
         * Remove this subscription's handlers from the stream. Runs on the
         * context.
         */
        private void detach() {
            final ReadStream<Buffer> src = RequestBodyPublisher.this.stream;
            src.handler(null);
            src.endHandler(null);
            src.exceptionHandler(null);
        }
    }
}
