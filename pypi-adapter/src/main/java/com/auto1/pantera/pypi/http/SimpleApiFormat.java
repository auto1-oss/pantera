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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.http.Headers;

/**
 * PEP 691 content negotiation for the Simple Repository API.
 * Determines whether to serve HTML (PEP 503) or JSON (PEP 691) based on Accept header.
 */
public enum SimpleApiFormat {

    HTML("text/html"),
    JSON("application/vnd.pypi.simple.v1+json");

    private static final String JSON_MIME = "application/vnd.pypi.simple.v1+json";

    private final String contentType;

    SimpleApiFormat(final String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return this.contentType;
    }

    /**
     * Determine format from request headers.
     * Returns JSON if Accept header contains the PEP 691 JSON MIME type,
     * otherwise HTML (backward-compatible default).
     */
    public static SimpleApiFormat fromHeaders(final Headers headers) {
        for (final var header : headers) {
            if ("accept".equalsIgnoreCase(header.getKey())
                && header.getValue().contains(JSON_MIME)) {
                return JSON;
            }
        }
        return HTML;
    }
}
