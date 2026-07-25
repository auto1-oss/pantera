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

import java.util.Locale;

/**
 * PEP 691 / PEP 700 content negotiation for the Simple Repository API.
 * Determines whether to serve HTML (PEP 503) or JSON (PEP 691) based on
 * the {@code Accept} header, honoring RFC 9110 q-values and the
 * {@code v1}/{@code latest} media-type aliases pip/uv send.
 */
public enum SimpleApiFormat {

    HTML("text/html"),
    JSON("application/vnd.pypi.simple.v1+json");

    private final String contentType;

    SimpleApiFormat(final String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return this.contentType;
    }

    /**
     * Determine format from request headers. Parses every {@code Accept}
     * media-range, ranks JSON-family vs. HTML-family candidates by their
     * highest acceptable q-value, and picks the winner. Ties (including
     * "nothing recognized") default to HTML for backward compatibility.
     *
     * @param headers Request headers
     * @return {@link #JSON} or {@link #HTML}
     */
    public static SimpleApiFormat fromHeaders(final Headers headers) {
        String accept = null;
        for (final var header : headers) {
            if ("accept".equalsIgnoreCase(header.getKey())) {
                accept = header.getValue();
                break;
            }
        }
        if (accept == null || accept.isBlank()) {
            return HTML;
        }
        return SimpleApiFormat.select(accept);
    }

    /**
     * Rank every comma-separated media-range in {@code accept} and pick
     * JSON only if its best q strictly exceeds HTML's best q.
     */
    private static SimpleApiFormat select(final String accept) {
        double bestJson = -1;
        double bestHtml = -1;
        for (final String spec : accept.split(",")) {
            final MediaRange range = parseRange(spec);
            if (range == null) {
                continue;
            }
            if (isJsonRange(range.mediaType())) {
                bestJson = Math.max(bestJson, range.q());
            } else if (isHtmlRange(range.mediaType())) {
                bestHtml = Math.max(bestHtml, range.q());
            }
        }
        return bestJson > bestHtml ? JSON : HTML;
    }

    private static boolean isJsonRange(final String mediaType) {
        return "application/vnd.pypi.simple.v1+json".equals(mediaType)
            || "application/vnd.pypi.simple.latest+json".equals(mediaType);
    }

    /**
     * HTML-family media ranges. {@code *}/{@code *} is treated as an
     * HTML-acceptable wildcard — matches the pre-existing backward-compat
     * default (a client with no PyPI-specific preference gets HTML).
     */
    private static boolean isHtmlRange(final String mediaType) {
        return "text/html".equals(mediaType)
            || "application/vnd.pypi.simple.v1+html".equals(mediaType)
            || "application/vnd.pypi.simple.latest+html".equals(mediaType)
            || "*/*".equals(mediaType)
            || "text/*".equals(mediaType);
    }

    /**
     * Parse a single {@code Accept} media-range entry ({@code type/subtype
     * [;q=value][;other=params]}) into its media type and q-value.
     *
     * @param spec Raw comma-delimited segment
     * @return Parsed range, or {@code null} for a blank/unparseable segment
     */
    private static MediaRange parseRange(final String spec) {
        final String[] parts = spec.trim().split(";");
        if (parts.length == 0 || parts[0].isBlank()) {
            return null;
        }
        final String mediaType = parts[0].trim().toLowerCase(Locale.ROOT);
        double quality = 1.0;
        for (int idx = 1; idx < parts.length; idx++) {
            final String param = parts[idx].trim().toLowerCase(Locale.ROOT);
            if (param.startsWith("q=")) {
                quality = parseQuality(param.substring(2).trim());
            }
        }
        return new MediaRange(mediaType, quality);
    }

    /**
     * Parse an RFC 9110 q-value; malformed or out-of-range values fall
     * back to {@code 1.0} (treat as fully acceptable) rather than
     * rejecting the whole header.
     */
    private static double parseQuality(final String value) {
        double result;
        try {
            result = Double.parseDouble(value);
            if (result < 0 || result > 1) {
                result = 1.0;
            }
        } catch (final NumberFormatException ex) {
            result = 1.0;
        }
        return result;
    }

    /**
     * A single parsed {@code Accept} media-range.
     *
     * @param mediaType Lower-cased media type (parameters stripped except q)
     * @param q Quality weight, {@code 0.0}-{@code 1.0}
     */
    private record MediaRange(String mediaType, double q) {
    }
}
