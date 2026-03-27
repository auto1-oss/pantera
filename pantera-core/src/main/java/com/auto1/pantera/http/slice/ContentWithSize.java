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
import com.auto1.pantera.http.rq.RqHeaders;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Content with size from headers.
 * @since 0.6
 */
public final class ContentWithSize implements Content {

    /**
     * Request body.
     */
    private final Publisher<ByteBuffer> body;

    /**
     * Request headers.
     */
    private final Headers headers;

    /**
     * Content with size from body and headers.
     * @param body Body
     * @param headers Headers
     */
    public ContentWithSize(Publisher<ByteBuffer> body, Headers headers) {
        this.body = body;
        this.headers = headers;
    }

    @Override
    public Optional<Long> size() {
        return new RqHeaders(this.headers, "content-length")
            .stream().findFirst()
            .map(Long::parseLong);
    }

    @Override
    public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
        this.body.subscribe(subscriber);
    }
}
