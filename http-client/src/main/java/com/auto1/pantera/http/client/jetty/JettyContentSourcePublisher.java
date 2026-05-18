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
package com.auto1.pantera.http.client.jetty;

import com.auto1.pantera.http.log.EcsLogger;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Reactive-Streams {@link Publisher} that bridges Jetty's pull-based,
 * refcounted {@link Content.Source} (the HTTP/2 / HTTP/1.1 response body
 * coming from upstream) into Pantera's {@code Publisher<ByteBuffer>}
 * contract.
 *
 * <h2>Design</h2>
 *
 * <p>An <b>eager pre-drain + bounded staging buffer</b> hybrid:
 *
 * <ul>
 *   <li>The Jetty I/O thread calls {@link #primeOnIoThread()} immediately
 *       after {@code onResponseContentSource} fires. The bridge starts
 *       pulling chunks from the source on that thread, <b>copies each
 *       chunk into a heap {@link ByteBuffer}</b>, releases the pooled
 *       Jetty buffer back to the {@code ArrayByteBufferPool}, and parks
 *       the heap copy in {@link #staged}.</li>
 *   <li>Releasing the pooled buffer immediately serves two crucial
 *       purposes for HTTP/1.1 keep-alive and HTTP/2 flow control:
 *       <ol>
 *         <li>The connection is freed for reuse as soon as the body
 *             is fully read — without this, downstream code that ignores
 *             the body forces the HttpClient pool to open a fresh
 *             connection on every request (the leak-test pathology).</li>
 *         <li>{@code WINDOW_UPDATE} flows back to the H2 peer once the
 *             pooled buffer is released, keeping the receive window
 *             open.</li>
 *       </ol></li>
 *   <li>Staging is capped at {@link #MAX_STAGED} chunks. When the cap is
 *       reached the bridge stops calling {@link Content.Source#read()}
 *       and waits for the subscriber to drain the queue — this is the
 *       backpressure boundary. Subscriber-side demand re-enters
 *       {@link #drainSourceToStaging()} when staging falls below the
 *       cap.</li>
 *   <li>When a downstream subscriber attaches it consumes from
 *       {@link #staged} first, then from new source chunks. Demand from
 *       the subscriber is honoured exactly per Reactive-Streams §3.9
 *       semantics.</li>
 *   <li>If no subscriber attaches at all (caller violates the
 *       must-consume contract) {@link #discardIfUnsubscribed} drains
 *       and releases any remaining source chunks, clears the staging
 *       queue, and marks the publisher consumed so a subscribe that
 *       arrives later fails fast with {@link IllegalStateException}.</li>
 *   <li>{@link Subscription#cancel} propagates back to
 *       {@link Content.Source#fail} and {@link Response#abort} so the
 *       upstream stream terminates cleanly with
 *       {@code RST_STREAM(CANCEL)}.</li>
 *   <li>{@link EOFException} from {@link Content.Source#read()} — the
 *       Jetty-internal signal for an upstream mid-body RST — is re-wrapped
 *       as {@link UpstreamStreamResetException} so the response layer
 *       can render it as 502 rather than 500.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 *
 * <p>The staging-side drain ({@link #drainSourceToStaging()}) is invoked
 * exclusively by Jetty (initially via {@link #primeOnIoThread}, then via
 * {@link Content.Source#demand}); Jetty guarantees these callbacks are
 * serialised. Subscriber-side delivery runs on whichever thread calls
 * {@link Subscription#request} and is serialised by {@link #delivering}.
 * The two sides communicate through {@link #staged} (a concurrent queue)
 * and {@link #stagingPaused} / {@link #done} flags.
 *
 * <p>The bridge is single-subscriber by design (matching the Jetty
 * source's single-consumer contract). A second {@link #subscribe} call
 * fails the new subscriber with an {@link IllegalStateException}.
 *
 * @since 2.2.0
 */
final class JettyContentSourcePublisher implements Publisher<ByteBuffer> {

    /**
     * Maximum number of pre-drained heap copies held in {@link #staged}.
     * When reached, the bridge stops reading from the source and waits
     * for the subscriber to drain the queue. Sized to absorb a small
     * burst of fast chunks without paying re-arming overhead per chunk;
     * 64 chunks at the typical Jetty 4 KiB receive size is ~256 KiB —
     * well below the 1 MiB default H2 stream window so we never starve
     * the upstream send.
     */
    static final int MAX_STAGED = 64;

    /** Logger name. */
    private static final String LOGGER = "com.auto1.pantera.http.client";

    /** Jetty content source — the HTTP response body. */
    private final Content.Source source;

    /** Jetty response handle — exposed so cancel can abort it. */
    private final Response response;

    /**
     * Pre-drained heap copies awaiting a subscriber. Read on the I/O
     * thread (staging side), drained on the subscriber thread.
     */
    private final Queue<ByteBuffer> staged = new ConcurrentLinkedDeque<>();

    /**
     * Set when the source has emitted its terminal chunk (isLast or
     * failure). Both sides observe it: staging side sets it after the
     * final read, subscriber side checks it after draining staging to
     * decide whether to call {@code onComplete}/{@code onError}.
     */
    private volatile boolean done;

    /** Non-null when the source produced an error chunk. */
    private volatile Throwable failure;

    /** Set when staging hit the cap and is waiting for the subscriber. */
    private final AtomicBoolean stagingPaused = new AtomicBoolean();

    /** Current subscriber, or {@code null} until {@link #subscribe} fires. */
    private final AtomicReference<Subscriber<? super ByteBuffer>>
        subscriber = new AtomicReference<>();

    /**
     * Sentinel inserted into {@link #subscriber} when the publisher is
     * discarded without ever having a real subscriber. Prevents a
     * late {@link #subscribe} from racing the discard.
     */
    private static final Subscriber<ByteBuffer> DISCARDED_SENTINEL =
        new Subscriber<>() {
            @Override public void onSubscribe(final Subscription s) {
                // no-op
            }
            @Override public void onNext(final ByteBuffer b) {
                // no-op
            }
            @Override public void onError(final Throwable t) {
                // no-op
            }
            @Override public void onComplete() {
                // no-op
            }
        };

    /** Outstanding demand from the subscriber. */
    private final AtomicLong demand = new AtomicLong();

    /** Set when the subscriber cancelled — staging halts, source aborts. */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** Set after {@code onComplete}/{@code onError} have fired. */
    private final AtomicBoolean terminal = new AtomicBoolean();

    /** CAS serialiser for the subscriber-side delivery loop. */
    private final AtomicBoolean delivering = new AtomicBoolean();

    /**
     * @param source Jetty {@link Content.Source} for the response body.
     * @param response Jetty {@link Response} — used by cancel() to abort
     *                 the in-flight upstream stream.
     */
    JettyContentSourcePublisher(final Content.Source source, final Response response) {
        this.source = Objects.requireNonNull(source, "source");
        this.response = Objects.requireNonNull(response, "response");
    }

    /**
     * Kick off eager pre-draining on the Jetty I/O thread. Must be
     * invoked synchronously from {@code onResponseContentSource} so the
     * staging loop runs before the I/O thread returns to Jetty — that
     * gives HTTP/1.1 keep-alive a chance to reclaim the connection
     * before downstream may even subscribe.
     */
    void primeOnIoThread() {
        this.drainSourceToStaging();
    }

    @Override
    public void subscribe(final Subscriber<? super ByteBuffer> sub) {
        Objects.requireNonNull(sub, "subscriber");
        if (!this.subscriber.compareAndSet(null, sub)) {
            sub.onSubscribe(NoopSubscription.INSTANCE);
            sub.onError(new IllegalStateException(
                "JettyContentSourcePublisher is single-subscriber"
            ));
            return;
        }
        sub.onSubscribe(new StagedSubscription());
    }

    /**
     * Mark the publisher as consumed without anyone subscribing, drain
     * any remaining source chunks (so the pooled buffers go back to the
     * pool and the connection becomes reusable), and discard the heap
     * copies still in {@link #staged}.
     *
     * <p>Idempotent: a real subscriber already present wins the CAS and
     * this is a no-op; a previous discard also wins the CAS the second
     * time and is a no-op.
     */
    void discardIfUnsubscribed() {
        if (!this.subscriber.compareAndSet(null, DISCARDED_SENTINEL)) {
            return;
        }
        this.cancelled.set(true);
        this.staged.clear();
        Content.Source.consumeAll(this.source, Callback.NOOP);
    }

    /**
     * Pre-drain loop. Runs on the Jetty I/O thread (first via
     * {@link #primeOnIoThread}, subsequently via
     * {@link Content.Source#demand}). Reads chunks, copies each into a
     * heap {@link ByteBuffer}, releases the pooled chunk back to the
     * pool, and parks the copy in {@link #staged}. Stops when:
     *
     * <ul>
     *   <li>the source is exhausted ({@code isLast} or failure) — sets
     *       {@link #done} and {@link #failure} as appropriate, then
     *       notifies the subscriber;</li>
     *   <li>the source has no chunk currently ready ({@code read()}
     *       returns {@code null}) — registers
     *       {@link Content.Source#demand} so we resume when bytes
     *       arrive;</li>
     *   <li>{@link #staged} reaches {@link #MAX_STAGED} — sets
     *       {@link #stagingPaused} and lets the subscriber drain before
     *       we resume.</li>
     * </ul>
     */
    private void drainSourceToStaging() {
        while (!this.cancelled.get()) {
            if (this.staged.size() >= MAX_STAGED) {
                this.stagingPaused.set(true);
                return;
            }
            final Content.Chunk chunk;
            try {
                chunk = this.source.read();
            } catch (final RuntimeException ex) {
                final EOFException eof = unwrapEof(ex);
                if (eof != null) {
                    this.failure = new UpstreamStreamResetException(
                        this.upstreamUrl(), eof
                    );
                } else {
                    this.failure = ex;
                }
                this.done = true;
                this.deliverToSubscriber();
                return;
            }
            if (chunk == null) {
                this.source.demand(this::drainSourceToStaging);
                return;
            }
            if (Content.Chunk.isFailure(chunk)) {
                final Throwable cause = chunk.getFailure();
                final boolean last = chunk.isLast();
                try {
                    chunk.release();
                } catch (final RuntimeException ignored) {
                    // best-effort; chunk lifecycle is Jetty's
                }
                if (last) {
                    final EOFException eof = unwrapEof(cause);
                    if (eof != null) {
                        this.failure = new UpstreamStreamResetException(
                            this.upstreamUrl(), eof
                        );
                    } else {
                        this.failure = cause;
                    }
                    this.done = true;
                    this.deliverToSubscriber();
                    return;
                }
                continue;
            }
            this.staged.add(copyToHeap(chunk));
            final boolean last = chunk.isLast();
            try {
                chunk.release();
            } catch (final RuntimeException ignored) {
                // best-effort; chunk lifecycle is Jetty's
            }
            if (last) {
                this.done = true;
                this.deliverToSubscriber();
                return;
            }
            this.deliverToSubscriber();
        }
    }

    /**
     * Drain {@link #staged} to the subscriber up to the outstanding
     * {@link #demand}. CAS-serialised — at most one thread runs the
     * loop at a time. After releasing the lock we re-check both demand
     * and staging to absorb any signals raced in while the lock was
     * held; without this re-check a late {@code request(n)} or staging
     * arrival would be silently lost.
     */
    private void deliverToSubscriber() {
        if (this.subscriber.get() == null || this.subscriber.get() == DISCARDED_SENTINEL) {
            return;
        }
        if (!this.delivering.compareAndSet(false, true)) {
            return;
        }
        try {
            this.deliverLoop();
        } finally {
            this.delivering.set(false);
        }
        if (this.shouldDrainAgain()) {
            this.deliverToSubscriber();
        }
    }

    private void deliverLoop() {
        while (!this.terminal.get() && !this.cancelled.get()) {
            if (this.demand.get() <= 0L) {
                return;
            }
            final ByteBuffer next = this.staged.poll();
            if (next == null) {
                // Resume the staging side if it had paused — it'll see
                // the queue empty and start filling it again. Without
                // this we'd stall forever on long bodies.
                if (this.stagingPaused.compareAndSet(true, false)) {
                    this.source.demand(this::drainSourceToStaging);
                }
                if (this.done && this.staged.isEmpty()) {
                    this.fireTerminal();
                }
                return;
            }
            this.demand.decrementAndGet();
            try {
                this.subscriber.get().onNext(next);
            } catch (final RuntimeException ex) {
                this.cancelled.set(true);
                this.fireOnError(ex);
                return;
            }
            // Resume staging if it had paused — we just freed a slot.
            if (this.staged.size() < MAX_STAGED
                && this.stagingPaused.compareAndSet(true, false)) {
                this.source.demand(this::drainSourceToStaging);
            }
        }
    }

    private boolean shouldDrainAgain() {
        return !this.terminal.get()
            && !this.cancelled.get()
            && this.demand.get() > 0L
            && (!this.staged.isEmpty() || this.done);
    }

    private void fireTerminal() {
        if (!this.terminal.compareAndSet(false, true)) {
            return;
        }
        if (this.failure != null) {
            try {
                this.subscriber.get().onError(this.failure);
            } catch (final RuntimeException ex) {
                this.logOnErrorViolation(ex);
            }
        } else {
            try {
                this.subscriber.get().onComplete();
            } catch (final RuntimeException ignored) {
                // §2.13 — terminal callbacks must not throw.
            }
        }
    }

    private void fireOnError(final Throwable cause) {
        if (!this.terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            this.source.fail(cause);
        } catch (final RuntimeException ignored) {
            // best-effort
        }
        try {
            this.subscriber.get().onError(cause);
        } catch (final RuntimeException ex) {
            this.logOnErrorViolation(ex);
        }
    }

    private void logOnErrorViolation(final Throwable cause) {
        EcsLogger.warn(LOGGER)
            .message("Subscriber.onError threw — §2.13 violation")
            .eventCategory("web")
            .eventAction("http_response_read")
            .eventOutcome("failure")
            .error(cause)
            .field("log.source", "application")
            .log();
    }

    private String upstreamUrl() {
        try {
            return this.response.getRequest().getURI().toString();
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /**
     * Per-subscriber {@link Subscription}. Just a thin façade over the
     * publisher's demand counter and cancellation flag — all of the
     * delivery logic lives in {@link #deliverToSubscriber} so it can be
     * re-entered from both subscriber and staging sides.
     */
    private final class StagedSubscription implements Subscription {

        @Override
        public void request(final long count) {
            if (JettyContentSourcePublisher.this.terminal.get()
                || JettyContentSourcePublisher.this.cancelled.get()) {
                return;
            }
            if (count <= 0L) {
                JettyContentSourcePublisher.this.fireOnError(
                    new IllegalArgumentException(
                        "Reactive Streams §3.9: request(" + count + ") must be > 0"
                    )
                );
                return;
            }
            JettyContentSourcePublisher.this.demand.updateAndGet(prev -> {
                final long sum = prev + count;
                return sum < 0L ? Long.MAX_VALUE : sum;
            });
            JettyContentSourcePublisher.this.deliverToSubscriber();
        }

        @Override
        public void cancel() {
            if (JettyContentSourcePublisher.this.cancelled.compareAndSet(false, true)
                && JettyContentSourcePublisher.this.terminal.compareAndSet(false, true)) {
                final CancellationException reason =
                    new CancellationException("downstream subscriber cancelled");
                JettyContentSourcePublisher.this.staged.clear();
                try {
                    JettyContentSourcePublisher.this.source.fail(reason);
                } catch (final RuntimeException ignored) {
                    // best-effort; Jetty may already be in terminal state
                }
                try {
                    JettyContentSourcePublisher.this.response.abort(reason);
                } catch (final RuntimeException ignored) {
                    // best-effort
                }
            }
        }
    }

    /**
     * Walk the cause chain looking for an {@link EOFException}.
     * Jetty wraps the H2 RST_STREAM signal in different unchecked
     * exception types depending on context, so we identify by the
     * presence of {@link EOFException} anywhere in the chain.
     * Returns the first {@link EOFException} found, or {@code null}.
     */
    private static EOFException unwrapEof(final Throwable error) {
        Throwable cursor = error;
        int hops = 0;
        while (cursor != null && hops < 8) {
            if (cursor instanceof EOFException eof) {
                return eof;
            }
            cursor = cursor.getCause();
            hops += 1;
        }
        return null;
    }

    /**
     * Copy a Jetty pooled chunk into a heap {@link ByteBuffer}. The
     * pooled chunk is released by the caller immediately after this
     * returns so the connection becomes reusable and the H2 layer can
     * send {@code WINDOW_UPDATE}.
     */
    private static ByteBuffer copyToHeap(final Content.Chunk chunk) {
        final int len = chunk.remaining();
        final ByteBuffer out = ByteBuffer.allocate(len);
        out.put(chunk.getByteBuffer().duplicate());
        out.flip();
        return out;
    }

    /**
     * Singleton no-op subscription used to terminate the multi-subscribe
     * error path without dangling a real {@link Subscription}.
     */
    private static final class NoopSubscription implements Subscription {
        static final NoopSubscription INSTANCE = new NoopSubscription();
        @Override public void request(final long n) {
            // no-op
        }
        @Override public void cancel() {
            // no-op
        }
    }
}
