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
package com.auto1.pantera.hex.http.headers;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Accept;
import com.auto1.pantera.http.headers.ContentType;

import java.util.Map;

/**
 * ContentType header for HexPm.
 */
public class HexContentType {
    /**
     * Default ContentType.
     */
    static final String DEFAULT_TYPE = "application/vnd.hex+erlang";

    /**
     * Request headers.
     */
    private final Headers headers;

    /**
     * @param headers Request headers.
     */
    public HexContentType(Headers headers) {
        this.headers = headers;
    }

    /**
     * Fill ContentType header for response.
     *
     * @return Filled headers.
     */
    public Headers fill() {
        String type = HexContentType.DEFAULT_TYPE;
        for (final Map.Entry<String, String> header : this.headers) {
            if (Accept.NAME.equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
                type = header.getValue();
            }
        }
        return this.headers.copy().add(ContentType.mime(type));
    }
}
