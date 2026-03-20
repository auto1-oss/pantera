/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
 * <p>OPTIMIZATION: When size is known, this class now pre-allocates exact buffer capacity,
 * avoiding exponential 2x memory growth. Always provide size when available.</p>
 *
 * @since 0.17
 */
public class Concatenation {

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
        // OPTIMIZATION: Pre-allocate exact size when known (avoids all resizes)
        if (this.expectedSize > 0 && this.expectedSize <= Integer.MAX_VALUE) {
            return this.singleOptimized((int) this.expectedSize);
        }
        // Original behavior for unknown size (maintains backward compatibility)
        return Flowable.fromPublisher(this.source).reduce(
            ByteBuffer.allocate(0),
            (left, right) -> {
                right.mark();
                final ByteBuffer result;
                if (left.capacity() - left.limit() >= right.limit()) {
                    left.position(left.limit());
                    left.limit(left.limit() + right.limit());
                    result = left.put(right);
                } else {
                    result = ByteBuffer.allocate(
                        2 * Math.max(left.capacity(), right.capacity())
                    ).put(left).put(right);
                }
                right.reset();
                result.flip();
                return result;
            }
        );
    }

    /**
     * Optimized single() when size is known - pre-allocates exact capacity.
     *
     * @param size Known total size in bytes.
     * @return Single buffer with exact capacity.
     */
    private Single<ByteBuffer> singleOptimized(final int size) {
        return Flowable.fromPublisher(this.source).reduce(
            ByteBuffer.allocate(size),
            (left, right) -> {
                right.mark();
                // With exact pre-allocation, we should never need to resize
                left.put(right);
                right.reset();
                return left;
            }
        ).map(buf -> {
            buf.flip();
            return buf;
        });
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
