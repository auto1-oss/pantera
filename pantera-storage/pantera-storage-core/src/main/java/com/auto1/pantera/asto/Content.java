/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto;

import com.artipie.asto.log.EcsLogger;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Content that can be stored in {@link Storage}.
 *
 * <p>PERFORMANCE NOTE: For large content, prefer streaming methods like
 * {@link #asInputStream()} over buffering methods like {@link #asBytes()}.
 * Buffering methods load ALL content into memory and can cause OOM for large files.</p>
 *
 * <p>ENTERPRISE BEST PRACTICE: Always use async methods ({@link #asBytesFuture()},
 * {@link #asStringFuture()}, {@link #asJsonObjectFuture()}) in request handling paths.
 * The blocking methods are deprecated and will be removed in future versions.</p>
 */
public interface Content extends Publisher<ByteBuffer> {

    /**
     * Empty content.
     */
    Content EMPTY = new Empty();

    /**
     * Provides size of the content in bytes if known.
     *
     * @return Size of content in bytes if known.
     */
    Optional<Long> size();

    /**
     * Reads bytes from the content into memory asynchronously.
     *
     * <p>PERFORMANCE: This method uses optimized pre-allocation when content size
     * is known, avoiding exponential buffer growth.</p>
     *
     * @return Byte array as CompletableFuture
     */
    default CompletableFuture<byte[]> asBytesFuture() {
        // Use size-optimized path when size is known
        final long knownSize = this.size().orElse(-1L);
        return new Concatenation(this, knownSize)
            .single()
            .map(buf -> new Remaining(buf, true))
            .map(Remaining::bytes)
            .to(SingleInterop.get())
            .toCompletableFuture();
    }

    /**
     * Reads bytes from content into memory.
     *
     * @return Byte array
     * @deprecated Use {@link #asBytesFuture()} instead. This method blocks the calling
     *     thread and can cause performance issues in async contexts like Vert.x event loop.
     *     Will be removed in version 2.0.
     */
    @Deprecated
    default byte[] asBytes() {
        return this.asBytesFuture().join();
    }

    /**
     * Reads bytes from the content as a string in the {@code StandardCharsets.UTF_8} charset.
     *
     * @return String as CompletableFuture
     */
    default CompletableFuture<String> asStringFuture() {
        return this.asBytesFuture().thenApply(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Reads bytes from the content as a string in the {@code StandardCharsets.UTF_8} charset.
     *
     * @return String
     * @deprecated Use {@link #asStringFuture()} instead. This method blocks the calling
     *     thread and can cause performance issues in async contexts like Vert.x event loop.
     *     Will be removed in version 2.0.
     */
    @Deprecated
    default String asString() {
        return this.asStringFuture().join();
    }

    /**
     * Reads bytes from the content as a JSON object asynchronously.
     *
     * @return JsonObject as CompletableFuture
     */
    default CompletableFuture<JsonObject> asJsonObjectFuture() {
        return this.asStringFuture().thenApply(val -> {
            try (JsonReader reader = Json.createReader(new StringReader(val))) {
                return reader.readObject();
            }
        });
    }

    /**
     * Reads bytes from the content as a JSON object.
     *
     * @return JsonObject
     * @deprecated Use {@link #asJsonObjectFuture()} instead. This method blocks the calling
     *     thread and can cause performance issues in async contexts like Vert.x event loop.
     *     Will be removed in version 2.0.
     */
    @Deprecated
    default JsonObject asJsonObject() {
        return this.asJsonObjectFuture().join();
    }

    /**
     * Returns content as a streaming InputStream.
     *
     * <p>PERFORMANCE: This is the preferred method for large content as it does NOT
     * buffer all bytes in memory. Data flows through a pipe as it becomes available.</p>
     *
     * <p>The returned InputStream must be closed by the caller to release resources.</p>
     *
     * @return InputStream that streams content bytes
     * @throws java.io.IOException if pipe creation fails
     */
    default InputStream asInputStream() throws java.io.IOException {
        final PipedInputStream input = new PipedInputStream(64 * 1024); // 64KB buffer
        final PipedOutputStream output = new PipedOutputStream(input);
        final AtomicBoolean completed = new AtomicBoolean(false);

        // Subscribe to content and pipe bytes to output stream
        Flowable.fromPublisher(this)
            .subscribe(
                buffer -> {
                    try {
                        final byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        output.write(bytes);
                    } catch (final java.io.IOException ex) {
                        throw new RuntimeException("Failed to write to pipe", ex);
                    }
                },
                error -> {
                    try {
                        output.close();
                    } catch (final java.io.IOException ex) {
                        EcsLogger.debug("com.artipie.asto")
                            .message("Failed to close piped output stream on error")
                            .error(ex)
                            .log();
                    }
                },
                () -> {
                    try {
                        output.close();
                        completed.set(true);
                    } catch (final java.io.IOException ex) {
                        EcsLogger.debug("com.artipie.asto")
                            .message("Failed to close piped output stream on completion")
                            .error(ex)
                            .log();
                    }
                }
            );
        return input;
    }

    /**
     * Reads bytes from content with optimized pre-allocation when size is known.
     *
     * @return Byte array as CompletableFuture
     * @deprecated Use {@link #asBytesFuture()} instead, which now automatically
     *     uses size-optimized pre-allocation when content size is known.
     */
    @Deprecated
    default CompletableFuture<byte[]> asBytesOptimized() {
        return this.asBytesFuture();
    }

    /**
     * Empty content.
     */
    final class Empty implements Content {

        @Override
        public Optional<Long> size() {
            return Optional.of(0L);
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
            Flowable.<ByteBuffer>empty().subscribe(subscriber);
        }
    }

    /**
     * Key built from byte buffers publisher and total size if it is known.
     */
    final class From implements Content {

        /**
         * Total content size in bytes, if known.
         */
        private final Optional<Long> length;

        /**
         * Content bytes.
         */
        private final Publisher<ByteBuffer> publisher;

        /**
         * Ctor.
         *
         * @param array Content bytes.
         */
        public From(final byte[] array) {
            this(
                array.length,
                Flowable.fromArray(ByteBuffer.wrap(Arrays.copyOf(array, array.length)))
            );
        }

        /**
         * @param publisher Content bytes.
         */
        public From(final Publisher<ByteBuffer> publisher) {
            this(Optional.empty(), publisher);
        }

        /**
         * @param size Total content size in bytes.
         * @param publisher Content bytes.
         */
        public From(final long size, final Publisher<ByteBuffer> publisher) {
            this(Optional.of(size), publisher);
        }

        /**
         * @param size Total content size in bytes, if known.
         * @param publisher Content bytes.
         */
        public From(final Optional<Long> size, final Publisher<ByteBuffer> publisher) {
            this.length = size;
            this.publisher = publisher;
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
            this.publisher.subscribe(subscriber);
        }

        @Override
        public Optional<Long> size() {
            return this.length;
        }
    }

    /**
     * A content which can be consumed only once.
     *
     * @since 0.24
     */
    final class OneTime implements Content {

        /**
         * The wrapped content.
         */
        private final Content wrapped;

        /**
         * Ctor.
         *
         * @param original The original content
         */
        public OneTime(final Content original) {
            this.wrapped = new Content.From(original.size(), new OneTimePublisher<>(original));
        }

        @Override
        public Optional<Long> size() {
            return this.wrapped.size();
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> sub) {
            this.wrapped.subscribe(sub);
        }
    }
}
