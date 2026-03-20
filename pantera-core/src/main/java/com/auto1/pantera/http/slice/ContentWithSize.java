/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
