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
