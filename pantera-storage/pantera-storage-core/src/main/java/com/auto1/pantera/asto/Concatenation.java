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
package com.auto1.pantera.asto;

import io.reactivex.Flowable;
import io.reactivex.Single;
import java.nio.ByteBuffer;
import org.reactivestreams.Publisher;

/**
 * Concatenation of {@link ByteBuffer} instances.
 *
 * <p><strong>WARNING - MEMORY INTENSIVE:</strong> This class loads ALL content into memory.
 * For large files (>1MB), prefer streaming patterns that process chunks without full buffering:</p>
 *
 * <ul>
 *   <li>For reading: Use {@link Content#asInputStream()} for streaming</li>
 *   <li>For storage: Stream directly to storage without buffering</li>
 *   <li>For JSON: Use streaming JSON parsers for large documents</li>
 * </ul>
 *
 * <p><strong>ENTERPRISE RECOMMENDATION:</strong> Limit use of this class to small metadata
 * files (&lt;1MB). For artifact storage, use direct streaming to avoid heap pressure.</p>
 *
 * <p>OPTIMIZATION: When size is known, this class pre-allocates buffer capacity from
 * it — but only up to {@link #MAX_PREALLOCATION}. The size hint is frequently the
 * request's attacker-declared {@code Content-Length}; before 2.2.9 the exact
 * declared size was used as the eagerly-allocated reduce seed, so an
 * unauthenticated request declaring 2 GB and sending nothing reserved 2 GB of
 * heap before a single body byte arrived (resource-dos F31). The seed is now a
 * bounded hint and the buffer grows only as real bytes are received.</p>
 *
 * @since 0.17
 */
public class Concatenation {

    /**
     * Upper bound on the capacity pre-allocated from a declared size hint
     * (1 MiB). Beyond it the buffer grows on demand from real bytes, so an
     * untrusted declared size can never drive the allocation.
     */
    public static final int MAX_PREALLOCATION = 1024 * 1024;

    /**
     * Source of byte buffers.
     */
    private final Publisher<ByteBuffer> source;

    /**
     * Optional hint for expected total size (enables pre-allocation).
     */
    private final long expectedSize;

    /**
     * Ctor.
     *
     * @param source Source of byte buffers.
     */
    public Concatenation(final Publisher<ByteBuffer> source) {
        this(source, -1L);
    }

    /**
     * Ctor with size hint for optimized pre-allocation.
     *
     * <p>PERFORMANCE: When size is known, pre-allocates exact buffer capacity,
     * avoiding all resize operations and exponential memory growth.</p>
     *
     * @param source Source of byte buffers.
     * @param expectedSize Expected total size in bytes, or -1 if unknown.
     */
    public Concatenation(final Publisher<ByteBuffer> source, final long expectedSize) {
        this.source = source;
        this.expectedSize = expectedSize;
    }

    /**
     * Concatenates all buffers into single one.
     *
     * <p>PERFORMANCE: If expectedSize was provided via constructor or {@link #withSize},
     * pre-allocates exact buffer size to avoid resize operations. Otherwise uses
     * standard 2x growth for amortized O(1) appends.</p>
     *
     * @return Single buffer.
     */
    public Single<ByteBuffer> single() {
        // The declared size is a HINT bounded by MAX_PREALLOCATION: it is
        // typically the attacker-controlled Content-Length, so it must never
        // drive an eager allocation. Real bytes grow the buffer on demand.
        final int seed = this.expectedSize > 0
            ? (int) Math.min(this.expectedSize, MAX_PREALLOCATION)
            : 0;
        return Flowable.fromPublisher(this.source).reduce(
            ByteBuffer.allocate(seed),
            Concatenation::append
        ).map(buf -> {
            buf.flip();
            return buf;
        });
    }

    /**
     * Append {@code right} to the write-mode accumulator {@code left},
     * growing it geometrically when it lacks room. The accumulator is kept
     * in write mode (position = bytes written) and flipped once at the end.
     *
     * @param left Accumulator in write mode
     * @param right Next chunk (its position is restored after the copy)
     * @return The accumulator holding both, possibly a new larger buffer
     */
    private static ByteBuffer append(final ByteBuffer left, final ByteBuffer right) {
        right.mark();
        final ByteBuffer result;
        if (left.remaining() >= right.remaining()) {
            result = left.put(right);
        } else {
            final int needed = left.position() + right.remaining();
            result = ByteBuffer.allocate(Math.max(2 * left.capacity(), needed));
            left.flip();
            result.put(left).put(right);
        }
        right.reset();
        return result;
    }

    /**
     * Creates a Concatenation with known size for optimal pre-allocation.
     *
     * <p>PERFORMANCE: This is the preferred factory method when content size is known.
     * It enables exact buffer pre-allocation, completely avoiding the exponential
     * 2x growth pattern that can waste up to 50% memory.</p>
     *
     * @param source Source of byte buffers.
     * @param size Known total size in bytes.
     * @return Concatenation optimized for the given size.
     */
    public static Concatenation withSize(final Publisher<ByteBuffer> source, final long size) {
        return new Concatenation(source, size);
    }
}
