/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.importer;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.importer.api.DigestType;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.codec.binary.Hex;
import org.reactivestreams.Subscriber;

/**
 * Content wrapper that calculates message digests as bytes flow through.
 *
 * <p>The wrapper is single-use as it must observe the underlying stream once.</p>
 *
 * @since 1.0
 */
final class DigestingContent implements Content {

    /**
     * Origin content.
     */
    private final Content origin;

    /**
     * Digests to calculate.
     */
    private final EnumMap<DigestType, java.security.MessageDigest> digests;

    /**
     * Total streamed bytes.
     */
    private final AtomicLong total;

    /**
     * Computation result.
     */
    private final CompletableFuture<DigestResult> result;

    /**
     * Ctor.
     *
     * @param origin Origin content
     * @param types Digest algorithms to compute
     */
    DigestingContent(final Content origin, final Set<DigestType> types) {
        this.origin = origin;
        this.digests = new EnumMap<>(DigestType.class);
        types.forEach(type -> this.digests.put(type, type.newDigest()));
        this.total = new AtomicLong();
        this.result = new CompletableFuture<>();
    }

    @Override
    public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
        Flowable.fromPublisher(this.origin)
            .map(buffer -> {
                final byte[] chunk = new Remaining(buffer, true).bytes();
                this.digests.values().forEach(digest -> digest.update(chunk));
                this.total.addAndGet(chunk.length);
                return buffer;
            })
            .doOnError(err -> {
                this.result.completeExceptionally(err);
                // Ensure cleanup on error
                this.cleanup();
            })
            .doOnComplete(() -> {
                this.result.complete(new DigestResult(this.total.get(), this.digestHex()));
                // Ensure cleanup on completion
                this.cleanup();
            })
            .doOnCancel(this::cleanup)  // Cleanup on cancellation
            .subscribe(subscriber);
    }

    /**
     * Cleanup resources.
     */
    private void cleanup() {
        // Clear digest instances to free memory
        this.digests.clear();
    }

    @Override
    public java.util.Optional<Long> size() {
        return this.origin.size();
    }

    /**
     * Computation result future.
     *
     * @return Future with digest result
     */
    CompletableFuture<DigestResult> result() {
        return this.result;
    }

    /**
     * Produce immutable map of digest hex values.
     *
     * @return Map from digest enum to hex string
     */
    private Map<DigestType, String> digestHex() {
        final Map<DigestType, String> values = new ConcurrentHashMap<>(this.digests.size());
        this.digests.forEach((type, digest) -> values.put(type, Hex.encodeHexString(digest.digest())));
        return Collections.unmodifiableMap(values);
    }

    /**
     * Digest calculation result.
     *
     * @param size Streamed bytes
     * @param digests Map of computed digests
     */
    record DigestResult(long size, Map<DigestType, String> digests) {
    }
}
