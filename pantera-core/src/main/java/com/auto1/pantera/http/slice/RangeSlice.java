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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RangeSpec;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.log.EcsLogger;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Slice decorator that adds HTTP Range request support for GET requests.
 * Enables resumable downloads of large artifacts.
 * 
 * <p>Supports a single byte range: {@code bytes=start-end} (end clamped to
 * the last byte), {@code bytes=start-} and the suffix form {@code bytes=-N}.</p>
 * <p>Returns 206 Partial Content with Content-Range header</p>
 * <p>Returns 416 Range Not Satisfiable when the first byte is past the end</p>
 * 
 * @since 1.0
 */
public final class RangeSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Constructor.
     * @param origin Origin slice to wrap
     */
    public RangeSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Only handle GET requests
        if (!"GET".equalsIgnoreCase(line.method().value())) {
            return origin.response(line, headers, body);
        }

        // Check for Range header
        final Optional<String> rangeHeader = headers.stream()
            .filter(h -> "Range".equalsIgnoreCase(h.getKey()))
            .map(h -> h.getValue())
            .findFirst();

        if (rangeHeader.isEmpty()) {
            // No range request - pass through
            return origin.response(line, headers, body);
        }

        // Parse range
        final Optional<RangeSpec> range = RangeSpec.parse(rangeHeader.get());
        if (range.isEmpty()) {
            // Invalid range syntax - ignore and return full content
            return origin.response(line, headers, body);
        }

        // Get full response first to determine content length
        return origin.response(line, headers, body).thenApply(resp -> {
            // Only process successful responses
            if (resp.status().code() != 200) {
                return resp;
            }

            // Try to get content length from headers
            final Optional<Long> contentLength = resp.headers().stream()
                .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
                .map(h -> h.getValue())
                .map(Long::parseLong)
                .findFirst();

            if (contentLength.isEmpty()) {
                // Cannot determine size - return full content
                return resp;
            }

            final long fileSize = contentLength.get();
            final RangeSpec rangeSpec = range.get();

            // Validate range
            if (!rangeSpec.isValid(fileSize)) {
                // Range not satisfiable
                return ResponseBuilder.rangeNotSatisfiable()
                    .header("Content-Range", "bytes */" + fileSize)
                    .build();
            }

            // Create partial content response
            final long rangeLength = rangeSpec.length(fileSize);
            final Content partialContent = skipAndLimit(
                resp.body(),
                rangeSpec.start(fileSize),
                rangeLength
            );

            return ResponseBuilder.partialContent()
                .header("Content-Range", rangeSpec.toContentRange(fileSize))
                .header("Content-Length", String.valueOf(rangeLength))
                .header("Accept-Ranges", "bytes")
                .body(partialContent)
                .build();
        });
    }

    /**
     * Skip bytes and limit content length.
     *
     * @param content Original content
     * @param skip Number of bytes to skip
     * @param limit Number of bytes to return after skip
     * @return Limited content
     */
    private static Content skipAndLimit(
        final Content content,
        final long skip,
        final long limit
    ) {
        return new Content.From(
            limit,
            new RangeLimitPublisher(content, skip, limit)
        );
    }

    /**
     * Publisher that skips and limits bytes.
     */
    private static final class RangeLimitPublisher implements Publisher<ByteBuffer> {
        private final Publisher<ByteBuffer> upstream;
        private final long skip;
        private final long limit;

        RangeLimitPublisher(final Publisher<ByteBuffer> upstream, final long skip, final long limit) {
            this.upstream = upstream;
            this.skip = skip;
            this.limit = limit;
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> downstream) {
            this.upstream.subscribe(new RangeLimitSubscriber(downstream, this.skip, this.limit));
        }
    }

    /**
     * Subscriber that implements skip/limit logic with correct demand accounting.
     *
     * <p>Every upstream buffer consumes one unit of downstream demand. A buffer
     * that is skipped entirely is therefore replaced by an extra
     * {@code request(1)} upstream, otherwise the stream stalls once the range
     * starts past the first chunk. When the limit is reached the upstream is
     * cancelled (closing the file / connection) and downstream completes.</p>
     */
    private static final class RangeLimitSubscriber
        implements Subscriber<ByteBuffer>, Subscription {
        private final Subscriber<? super ByteBuffer> downstream;
        private final long skip;
        private final long limit;
        private final AtomicLong skipped = new AtomicLong(0);
        private final AtomicLong emitted = new AtomicLong(0);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicReference<Subscription> upstream = new AtomicReference<>();

        RangeLimitSubscriber(
            final Subscriber<? super ByteBuffer> downstream,
            final long skip,
            final long limit
        ) {
            this.downstream = downstream;
            this.skip = skip;
            this.limit = limit;
        }

        @Override
        public void onSubscribe(final Subscription subscription) {
            if (!this.upstream.compareAndSet(null, subscription)) {
                subscription.cancel();
                return;
            }
            this.downstream.onSubscribe(this);
            if (this.limit <= 0 && !this.completed.getAndSet(true)) {
                subscription.cancel();
                this.downstream.onComplete();
            }
        }

        @Override
        public void request(final long num) {
            if (!this.completed.get()) {
                this.upstream.get().request(num);
            }
        }

        @Override
        public void cancel() {
            this.completed.set(true);
            this.upstream.get().cancel();
        }

        @Override
        public void onNext(final ByteBuffer buffer) {
            if (this.completed.get()) {
                return;
            }
            final long toSkip = Math.min(
                this.skip - this.skipped.get(), (long) buffer.remaining()
            );
            if (toSkip > 0) {
                this.skipped.addAndGet(toSkip);
                buffer.position((int) (buffer.position() + toSkip));
            }
            if (!buffer.hasRemaining()) {
                // Whole buffer skipped: it consumed downstream demand without
                // producing anything, so ask upstream for its replacement.
                this.upstream.get().request(1);
                return;
            }
            final long remaining = this.limit - this.emitted.get();
            if (buffer.remaining() >= remaining) {
                final ByteBuffer limited = buffer.duplicate();
                limited.limit((int) (limited.position() + remaining));
                this.emitted.addAndGet(remaining);
                this.completed.set(true);
                this.downstream.onNext(limited);
                this.upstream.get().cancel();
                this.downstream.onComplete();
            } else {
                this.emitted.addAndGet(buffer.remaining());
                this.downstream.onNext(buffer);
            }
        }

        @Override
        public void onError(final Throwable error) {
            if (!this.completed.getAndSet(true)) {
                this.downstream.onError(error);
            } else {
                EcsLogger.warn("com.auto1.pantera.http")
                    .message("Error after range stream completion (state: completed)")
                    .eventCategory("web")
                    .eventAction("range_stream_error")
                    .eventOutcome("failure")
                    .field("error.message", error.getMessage())
                    .field("log.source", "application")
                    .log();
            }
        }

        @Override
        public void onComplete() {
            if (!this.completed.getAndSet(true)) {
                this.downstream.onComplete();
            }
        }
    }
}
