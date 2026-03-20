/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RqHeaders;

/**
 * Location header.
 */
public final class Location extends Header {

    /**
     * Header name.
     */
    public static final String NAME = "Location";

    /**
     * Ctor.
     *
     * @param value Header value.
     */
    public Location(final String value) {
        super(new Header(Location.NAME, value));
    }

    /**
     * Ctor.
     *
     * @param headers Headers to extract header from.
     */
    public Location(final Headers headers) {
        this(new RqHeaders.Single(headers, Location.NAME).asString());
    }
}
