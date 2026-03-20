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
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;

/**
 * Content-Length header.
 */
public final class ContentLength extends Header {

    public static Header with(long size) {
        return new ContentLength(String.valueOf(size));
    }

    /**
     * Header name.
     */
    public static final String NAME = "Content-Length";

    /**
     * @param length Length number
     */
    public ContentLength(final Number length) {
        this(length.toString());
    }

    /**
     * @param value Header value.
     */
    public ContentLength(final String value) {
        super(new Header(ContentLength.NAME, value));
    }

    /**
     * @param headers Headers to extract header from.
     */
    public ContentLength(final Headers headers) {
        this(headers.single(ContentLength.NAME).getValue());
    }

    /**
     * Read header as long value.
     *
     * @return Header value.
     */
    public long longValue() {
        return Long.parseLong(this.getValue());
    }
}
