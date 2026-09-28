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
package com.auto1.pantera.http.body;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.RequestBodyTooLargeException;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.reactivestreams.Subscriber;

/**
 * A {@link Content} that meters the ACTUAL bytes it delivers and fails
 * with {@link RequestBodyTooLargeException} the moment they exceed a
 * limit.
 *
 * <p>This is the primitive behind every request-body cap since 2.2.9.
 * Metering real bytes (rather than trusting the declared
 * {@code Content-Length}) is what makes a cap hold for chunked framing —
 * the pre-2.2.9 {@code ContentLengthRestriction} only inspected the
 * header, so a chunked body of any size bypassed the operator's limit
 * (resource-dos F17). On overflow the error propagates downstream and the
 * upstream subscription is cancelled, so no further bytes are pulled.</p>
 *
 * <p>{@link #size()} is passed through unchanged: a declared size that is
 * itself above the limit should be rejected up front by the caller, before
 * the body is ever subscribed.</p>
 *
 * @since 2.2.9
 */
public final class BoundedContent implements Content {

    /**
     * Wrapped body.
     */
    private final Content origin;

    /**
     * Maximum number of bytes that may be delivered.
     */
    private final long limit;

    /**
     * Ctor.
     *
     * @param origin Wrapped body
     * @param limit Maximum number of bytes that may be delivered (must be positive)
     */
    public BoundedContent(final Content origin, final long limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        this.origin = origin;
        this.limit = limit;
    }

    @Override
    public Optional<Long> size() {
        return this.origin.size();
    }

    @Override
    public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
        final AtomicLong delivered = new AtomicLong();
        Flowable.fromPublisher(this.origin)
            .map(buffer -> {
                final long total = delivered.addAndGet(buffer.remaining());
                if (total > this.limit) {
                    // Throwing from map() errors the downstream and cancels
                    // the upstream subscription — the metering stops here.
                    throw new RequestBodyTooLargeException(this.limit, total);
                }
                return buffer;
            })
            .subscribe(subscriber);
    }
}
