/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RqHeaders;
import wtf.g4s8.mime.MimeType;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Accept header, check
 * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Accept">documentation</a>
 * for more details.
 *
 * @since 0.19
 */
public final class Accept {

    /**
     * Header name.
     */
    public static final String NAME = "Accept";

    /**
     * Headers.
     */
    private final Headers headers;

    /**
     * Ctor.
     * @param headers Headers to extract `accept` header from
     */
    public Accept(Headers headers) {
        this.headers = headers;
    }

    /**
     * Parses `Accept` header values, sorts them according to weight and returns in
     * corresponding order.
     * @return Set or the values
     */
    public List<String> values() {
        final RqHeaders rqh = new RqHeaders(this.headers, Accept.NAME);
        if (rqh.size() == 0) {
            return Collections.emptyList();
        }
        return MimeType.parse(
            rqh.stream().collect(Collectors.joining(","))
        ).stream()
            .map(mime -> String.format("%s/%s", mime.type(), mime.subtype()))
            .collect(Collectors.toList());
    }
}
