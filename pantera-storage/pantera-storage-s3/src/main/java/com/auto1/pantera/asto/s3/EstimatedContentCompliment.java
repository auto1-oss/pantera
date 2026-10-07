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
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.log.EcsLogger;
import io.reactivex.Flowable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Complements {@link Content} with size if size is unknown.
 *
 * <p>WS3.1: this used to spool the ENTIRE body to a temp file (unconditionally,
 * regardless of {@code limit}) before it could tell the caller anything about
 * the size. The rewrite buffers only up to a bounded threshold in memory and
 * never holds a whole large artifact on heap or on disk:
 * <ul>
 *   <li>When the caller can accept an unknown-size result ({@link
 *       #EstimatedContentCompliment(Content, long)} -- used when S3 multipart
 *       is enabled), buffering stops the moment {@code limit} bytes have been
 *       seen and the remainder streams straight through to the multipart
 *       uploader ({@link UnknownSizeProbe}) -- no disk spool at all.</li>
 *   <li>When the caller structurally needs a known size ({@link
 *       #EstimatedContentCompliment(Content)} -- multipart disabled, {@code
 *       putObject} needs an exact Content-Length), the first {@link
 *       #NO_MULTIPART_MEMORY_CAP} bytes stay in memory and only the excess is
 *       spilled to a temp file ({@link KnownSizeProbe}) -- bounded heap
 *       either way, at the cost of disk I/O only for the part that doesn't
 *       fit in memory.</li>
 * </ul>
 *
 * @since 0.1
 */
final class EstimatedContentCompliment {

    /**
     * Bound on how much of a size-unknown upload is held in RAM when the
     * caller cannot accept an unknown-size result (no multipart fallback):
     * beyond this, the remainder is spilled to a temp file so the exact
     * total size can still be learned without ever buffering the whole
     * body on heap.
     */
    private static final long NO_MULTIPART_MEMORY_CAP = 10 * 1024 * 1024;

    /** Chunk size used when streaming a spilled-overflow temp file back. */
    private static final int CHUNK_SIZE = 64 * 1024;

    /**
     * The original content.
     */
    private final Content original;

    /**
     * The limit.
     */
    private final long limit;

    /**
     * Whether the caller can accept an {@code Optional.empty()} (unknown)
     * size once {@code limit} is reached -- true only when S3 multipart is
     * enabled, since only multipart can upload a size-unknown body.
     */
    private final boolean allowUnknownSize;

    /**
     * Ctor. Used when multipart is enabled: once {@code limit} bytes have
     * been buffered, the result carries an unknown size and streams the
     * remainder live -- see {@link UnknownSizeProbe}.
     *
     * @param original Original content.
     * @param limit Content reading limit.
     */
    EstimatedContentCompliment(final Content original, final long limit) {
        this(original, limit, true);
    }

    /**
     * Ctor. Used when multipart is disabled: the caller structurally needs
     * a known size, so the result always resolves the exact total, spilling
     * only the excess over {@link #NO_MULTIPART_MEMORY_CAP} to disk -- see
     * {@link KnownSizeProbe}.
     *
     * @param original Original content.
     */
    EstimatedContentCompliment(final Content original) {
        this(original, NO_MULTIPART_MEMORY_CAP, false);
    }

    /**
     * Ctor. The single field-initializing constructor; the two public
     * ctors above delegate here.
     *
     * @param original Original content.
     * @param limit Content reading limit.
     * @param allowUnknownSize Whether an unknown-size result is acceptable.
     */
    private EstimatedContentCompliment(
        final Content original, final long limit, final boolean allowUnknownSize
    ) {
        this.original = original;
        this.limit = limit;
        this.allowUnknownSize = allowUnknownSize;
    }

    /**
     * Initialize future of Content.
     *
     * @return The future.
     */
    public CompletionStage<Content> estimate() {
        final CompletionStage<Content> res;
        if (this.original.size().isPresent()) {
            res = CompletableFuture.completedFuture(this.original);
        } else if (this.allowUnknownSize) {
            res = new UnknownSizeProbe(this.limit).subscribeTo(this.original);
        } else {
            res = new KnownSizeProbe(NO_MULTIPART_MEMORY_CAP).subscribeTo(this.original);
        }
        return res;
    }

    /**
     * Read {@code chan} in {@link #CHUNK_SIZE} chunks as a cold
     * {@link Flowable}.
     */
    private static Flowable<ByteBuffer> chunkedReader(final FileChannel chan) {
        return Flowable.generate(emitter -> {
            final ByteBuffer buf = ByteBuffer.allocate(CHUNK_SIZE);
            final int read = chan.read(buf);
            if (read < 0) {
                emitter.onComplete();
            } else {
                buf.flip();
                emitter.onNext(buf);
            }
        });
    }

    /**
     * Subscribes to a size-unknown {@link Content} exactly once and buffers
     * chunks in memory only until the cumulative size reaches {@code limit}
     * bytes (the S3 multipart threshold) or the upstream completes --
     * whichever happens first.
     *
     * <p>If the upstream completes first, the (small, bounded) buffered
     * prefix IS the whole body -- a known size is returned, no disk
     * involved. If {@code limit} is reached first, the buffered prefix is
     * combined with a live pass-through of the very same (already-open)
     * upstream subscription: this instance also implements {@link
     * Publisher} and {@link Subscription} so that whichever single
     * downstream subscribes later (the S3 SDK's multipart uploader) first
     * drains the prefix, then drives the live upstream directly via normal
     * backpressure. The whole body is therefore never held in memory or
     * spooled to disk, regardless of its total size.
     *
     * @since 2.3.0
     */
    private static final class UnknownSizeProbe
        implements Subscriber<ByteBuffer>, Publisher<ByteBuffer>, Subscription {

        /** Multipart-threshold bound for the in-memory probe phase. */
        private final long limit;

        /** Resolves once we know whether the result is known- or unknown-size. */
        private final CompletableFuture<Content> promise = new CompletableFuture<>();

        /** Items not yet delivered to the downstream (prefix, then live overflow). */
        private final Deque<ByteBuffer> buffered = new ArrayDeque<>();

        /** Cumulative bytes seen during the probe phase. */
        private long total;

        /** Whether the probe phase has concluded (known- or unknown-size decided). */
        private volatile boolean decided;

        /** Subscription to the original upstream content. */
        private Subscription upstream;

        /** The single real downstream subscriber, once attached. */
        private Subscriber<? super ByteBuffer> downstream;

        /** Outstanding demand signalled by the downstream, not yet satisfied. */
        private long requested;

        /** How much of {@link #requested} has already been asked of upstream. */
        private long upstreamRequested;

        /** Guards the drain loop so downstream signals are never concurrent. */
        private boolean draining;

        /** Whether the upstream has completed (post-decision). */
        private boolean upstreamDone;

        /** Upstream failure (post-decision), if any. */
        private Throwable upstreamError;

        /** Whether the downstream cancelled. */
        private boolean cancelled;

        UnknownSizeProbe(final long limit) {
            this.limit = limit;
        }

        CompletableFuture<Content> subscribeTo(final Content content) {
            content.subscribe(this);
            return this.promise;
        }

        // ---- Subscriber<ByteBuffer>: talking to the ORIGINAL upstream ----

        @Override
        public void onSubscribe(final Subscription s) {
            this.upstream = s;
            s.request(1);
        }

        @Override
        public void onNext(final ByteBuffer buf) {
            if (this.decided) {
                this.acceptLive(buf);
            } else {
                this.probe(buf);
            }
        }

        /** Probe-phase accounting: buffer, and decide once {@code limit} is crossed. */
        private void probe(final ByteBuffer buf) {
            this.buffered.addLast(buf);
            this.total += buf.remaining();
            if (this.total >= this.limit) {
                this.decided = true;
                this.promise.complete(new Content.From(Optional.empty(), this));
            } else {
                this.upstream.request(1);
            }
        }

        /** Post-decision: forward a live upstream item into the drain queue. */
        private void acceptLive(final ByteBuffer buf) {
            synchronized (this) {
                this.upstreamRequested = Math.max(0, this.upstreamRequested - 1);
                this.buffered.addLast(buf);
            }
            this.drain();
        }

        @Override
        public void onComplete() {
            if (this.decided) {
                synchronized (this) {
                    this.upstreamDone = true;
                }
                this.drain();
            } else {
                this.decided = true;
                this.promise.complete(
                    new Content.From(Optional.of(this.total), Flowable.fromIterable(this.buffered))
                );
            }
        }

        @Override
        public void onError(final Throwable ex) {
            if (this.decided) {
                synchronized (this) {
                    this.upstreamDone = true;
                    this.upstreamError = ex;
                }
                this.drain();
            } else {
                this.decided = true;
                this.promise.completeExceptionally(ex);
            }
        }

        // ---- Publisher<ByteBuffer>: the single real downstream attaches here ----

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
            synchronized (this) {
                this.downstream = subscriber;
            }
            subscriber.onSubscribe(this);
        }

        // ---- Subscription: downstream demand / cancel ----

        @Override
        public void request(final long n) {
            if (n > 0) {
                synchronized (this) {
                    this.requested = addCap(this.requested, n);
                }
                this.drain();
            }
        }

        @Override
        public void cancel() {
            synchronized (this) {
                this.cancelled = true;
            }
            if (this.upstream != null) {
                this.upstream.cancel();
            }
        }

        private static long addCap(final long a, final long b) {
            final long sum = a + b;
            return sum < 0L ? Long.MAX_VALUE : sum;
        }

        /**
         * Drain loop: computes the next action under the lock, then performs
         * it (downstream callback or upstream request) outside the lock, so
         * only one thread ever calls into {@code downstream} at a time and
         * calls are never nested inside the monitor.
         */
        private void drain() {
            synchronized (this) {
                if (this.draining) {
                    return;
                }
                this.draining = true;
            }
            boolean again = true;
            while (again) {
                again = this.drainStep();
            }
        }

        /** One iteration of the drain loop. @return {@code true} to keep looping. */
        private boolean drainStep() {
            final ByteBuffer item;
            final boolean complete;
            final Throwable error;
            final long requestUpstream;
            synchronized (this) {
                if (this.cancelled || this.downstream == null) {
                    this.draining = false;
                    return false;
                }
                if (this.requested > 0 && !this.buffered.isEmpty()) {
                    item = this.buffered.pollFirst();
                    this.requested--;
                    complete = false;
                    error = null;
                    requestUpstream = 0;
                } else if (this.buffered.isEmpty() && this.upstreamDone) {
                    item = null;
                    error = this.upstreamError;
                    complete = error == null;
                    requestUpstream = 0;
                    this.draining = false;
                } else {
                    item = null;
                    complete = false;
                    error = null;
                    final long need = this.requested - this.upstreamRequested;
                    requestUpstream = this.buffered.isEmpty() && !this.upstreamDone && need > 0 ? need : 0;
                    if (requestUpstream > 0) {
                        this.upstreamRequested += requestUpstream;
                    }
                    this.draining = false;
                }
            }
            return this.act(item, complete, error, requestUpstream);
        }

        private boolean act(
            final ByteBuffer item, final boolean complete, final Throwable error, final long requestUpstream
        ) {
            final boolean again;
            if (item != null) {
                this.downstream.onNext(item);
                again = true;
            } else if (complete) {
                this.downstream.onComplete();
                again = false;
            } else if (error != null) {
                this.downstream.onError(error);
                again = false;
            } else {
                if (requestUpstream > 0) {
                    this.upstream.request(requestUpstream);
                }
                again = false;
            }
            return again;
        }
    }

    /**
     * Subscribes to a size-unknown {@link Content} that MUST resolve to a
     * known total size -- used when multipart is disabled and {@code
     * putObject} therefore needs an exact Content-Length up front.
     *
     * <p>Buffers up to {@code capBytes} in memory. If the upstream completes
     * within that bound the whole (small) body is already in memory and no
     * disk I/O happens at all. If the bound is exceeded, only the excess is
     * spilled to a temp file: the exact size is still learned, but the heap
     * only ever holds {@code capBytes}, regardless of how large the
     * artifact actually is.
     *
     * @since 2.3.0
     */
    private static final class KnownSizeProbe implements Subscriber<ByteBuffer> {

        /** In-memory bound before spilling the remainder to disk. */
        private final long capBytes;

        /** Resolves with the fully-determined, known-size {@link Content}. */
        private final CompletableFuture<Content> promise = new CompletableFuture<>();

        /** In-memory prefix, always replayed first regardless of overflow. */
        private final List<ByteBuffer> prefix = new ArrayList<>();

        /** Cumulative bytes seen so far. */
        private long total;

        /** Subscription to the original upstream content. */
        private Subscription upstream;

        /** Temp file holding whatever exceeded {@link #capBytes}; null until needed. */
        private Path overflowFile;

        /** Open write channel onto {@link #overflowFile}; null until needed. */
        private FileChannel overflowChannel;

        KnownSizeProbe(final long capBytes) {
            this.capBytes = capBytes;
        }

        CompletableFuture<Content> subscribeTo(final Content content) {
            content.subscribe(this);
            return this.promise;
        }

        @Override
        public void onSubscribe(final Subscription s) {
            this.upstream = s;
            s.request(1);
        }

        @Override
        public void onNext(final ByteBuffer buf) {
            final int size = buf.remaining();
            try {
                if (this.overflowChannel != null || this.total + size > this.capBytes) {
                    this.writeOverflow(buf);
                } else {
                    this.prefix.add(buf);
                }
            } catch (final IOException ex) {
                this.failLocal(ex);
                return;
            }
            this.total += size;
            this.upstream.request(1);
        }

        private void writeOverflow(final ByteBuffer buf) throws IOException {
            if (this.overflowChannel == null) {
                this.overflowFile = Files.createTempFile("pantera-s3-estimate-", ".tmp");
                this.overflowChannel = FileChannel.open(this.overflowFile, StandardOpenOption.WRITE);
            }
            while (buf.hasRemaining()) {
                this.overflowChannel.write(buf);
            }
        }

        @Override
        public void onComplete() {
            if (this.overflowChannel == null) {
                this.promise.complete(
                    new Content.From(Optional.of(this.total), Flowable.fromIterable(this.prefix))
                );
                return;
            }
            try {
                this.overflowChannel.close();
            } catch (final IOException ex) {
                this.failLocal(ex);
                return;
            }
            this.promise.complete(new Content.From(Optional.of(this.total), this.combined()));
        }

        /** Prefix (in-memory) concatenated with the spilled overflow (on disk). */
        private Publisher<ByteBuffer> combined() {
            final Path file = this.overflowFile;
            return Flowable.concat(
                Flowable.fromIterable(this.prefix),
                Flowable.using(
                    () -> FileChannel.open(file, StandardOpenOption.READ),
                    EstimatedContentCompliment::chunkedReader,
                    chan -> {
                        chan.close();
                        Files.deleteIfExists(file);
                    }
                )
            );
        }

        @Override
        public void onError(final Throwable ex) {
            // Upstream failure: propagate as-is (do NOT wrap in
            // PanteraIOException -- this may not be an IO error at all).
            this.closeOverflowQuietly();
            this.promise.completeExceptionally(ex);
        }

        /** A LOCAL disk-write failure (our own temp-file spill), not an upstream error. */
        private void failLocal(final IOException ex) {
            this.upstream.cancel();
            this.closeOverflowQuietly();
            this.promise.completeExceptionally(new PanteraIOException(ex));
        }

        private void closeOverflowQuietly() {
            if (this.overflowChannel != null) {
                try {
                    this.overflowChannel.close();
                } catch (final IOException ex) {
                    EcsLogger.debug("com.auto1.pantera.asto.s3")
                        .message("Failed to close overflow channel during size-estimation cleanup")
                        .eventAction("resource_cleanup_failed")
                        .error(ex)
                        .field("log.source", "application")
                        .log();
                }
            }
            if (this.overflowFile != null) {
                try {
                    Files.deleteIfExists(this.overflowFile);
                } catch (final IOException ex) {
                    EcsLogger.debug("com.auto1.pantera.asto.s3")
                        .message("Failed to delete overflow temp file during size-estimation cleanup")
                        .eventAction("resource_cleanup_failed")
                        .error(ex)
                        .field("log.source", "application")
                        .log();
                }
            }
        }
    }
}
