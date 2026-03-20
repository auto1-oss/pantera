/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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

/**
 * Slice decorator that adds HTTP Range request support for GET requests.
 * Enables resumable downloads of large artifacts.
 * 
 * <p>Supports byte ranges in format: Range: bytes=start-end</p>
 * <p>Returns 206 Partial Content with Content-Range header</p>
 * <p>Returns 416 Range Not Satisfiable if invalid</p>
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
                rangeSpec.start(),
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
     * CRITICAL: Properly consumes upstream publisher to prevent connection leaks.
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
            new RangeLimitPublisher(content, skip, limit)
        );
    }

    /**
     * Publisher that skips and limits bytes.
     * Ensures upstream is fully consumed to prevent connection leaks.
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
     * Subscriber that implements skip/limit logic.
     * CRITICAL: Consumes all upstream data (even after limit) to prevent leaks.
     */
    private static final class RangeLimitSubscriber implements Subscriber<ByteBuffer> {
        private final Subscriber<? super ByteBuffer> downstream;
        private final long skip;
        private final long limit;
        private final AtomicLong skipped = new AtomicLong(0);
        private final AtomicLong emitted = new AtomicLong(0);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private Subscription upstream;

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
            this.upstream = subscription;
            this.downstream.onSubscribe(subscription);
        }

        @Override
        public void onNext(final ByteBuffer buffer) {
            if (this.completed.get()) {
                // Already completed downstream - just consume and discard
                // CRITICAL: Must consume to prevent connection leak
                return;
            }

            final int bufferSize = buffer.remaining();
            final long currentSkipped = this.skipped.get();
            final long currentEmitted = this.emitted.get();

            // Still skipping?
            if (currentSkipped < this.skip) {
                final long toSkip = Math.min(this.skip - currentSkipped, bufferSize);
                this.skipped.addAndGet(toSkip);

                if (toSkip >= bufferSize) {
                    // Skip entire buffer - consume and request more
                    return;
                }

                // Skip part of buffer
                buffer.position((int) (buffer.position() + toSkip));
            }

            // Reached limit?
            if (currentEmitted >= this.limit) {
                // Mark as completed but keep consuming upstream
                if (!this.completed.getAndSet(true)) {
                    this.downstream.onComplete();
                }
                // CRITICAL: Continue consuming upstream to prevent leak
                return;
            }

            // Emit limited buffer
            final long remaining = this.limit - currentEmitted;
            if (buffer.remaining() > remaining) {
                // Create limited view of buffer
                final ByteBuffer limited = buffer.duplicate();
                limited.limit((int) (limited.position() + remaining));
                this.emitted.addAndGet(limited.remaining());
                this.downstream.onNext(limited);
                
                // Mark completed
                if (!this.completed.getAndSet(true)) {
                    this.downstream.onComplete();
                }
            } else {
                // Emit full buffer
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
                    .eventCategory("http")
                    .eventAction("range_stream_error")
                    .eventOutcome("failure")
                    .field("error.message", error.getMessage())
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
