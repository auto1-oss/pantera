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
package com.auto1.pantera.http.rq.multipart;

import com.auto1.pantera.http.Headers;
import java.nio.ByteBuffer;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * Empty part.
 * @since 1.0
 */
final class EmptyPart implements RqMultipart.Part {

    /**
     * Origin publisher.
     */
    private final Publisher<ByteBuffer> origin;

    /**
     * New empty part.
     * @param origin Publisher
     */
    EmptyPart(final Publisher<ByteBuffer> origin) {
        this.origin = origin;
    }

    @Override
    public void subscribe(final Subscriber<? super ByteBuffer> sub) {
        this.origin.subscribe(sub);
    }

    @Override
    public Headers headers() {
        return Headers.EMPTY;
    }
}
