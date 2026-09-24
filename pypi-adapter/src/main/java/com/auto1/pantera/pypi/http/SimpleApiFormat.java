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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.Header;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PEP 691 content negotiation for the Simple Repository API.
 * Determines whether to serve HTML (PEP 503) or JSON (PEP 691) based on the
 * Accept header, honouring quality values and the {@code latest} aliases.
 * Responses negotiated this way must carry {@code Vary: Accept}.
 */
public enum SimpleApiFormat {

    HTML("text/html"),
    JSON("application/vnd.pypi.simple.v1+json");

    /**
     * {@code Vary} header value for every negotiated index response.
     */
    public static final String VARY = "Accept";

    /**
     * Media types that select the PEP 691 JSON serialization.
     */
    private static final Set<String> JSON_TYPES = Set.of(
        "application/vnd.pypi.simple.v1+json",
        "application/vnd.pypi.simple.latest+json"
    );

    /**
     * Media types (including wildcards) that select the HTML serialization
     * under its legacy {@code text/html} type.
     */
    private static final Set<String> HTML_TYPES = Set.of(
        "text/html",
        "text/*",
        "*/*"
    );

    /**
     * Media types that select the HTML serialization under its versioned
     * PEP 691 type.
     */
    private static final Set<String> VERSIONED_HTML_TYPES = Set.of(
        "application/vnd.pypi.simple.v1+html",
        "application/vnd.pypi.simple.latest+html"
    );

    /**
     * The versioned PEP 691 HTML media type.
     */
    private static final String VERSIONED_HTML = "application/vnd.pypi.simple.v1+html";

    private final String contentType;

    SimpleApiFormat(final String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return this.contentType;
    }

    /**
     * Determine format from request headers.
     *
     * <p>Every {@code Accept} media range is weighed by its {@code q} value;
     * the serialization with the highest acceptable quality wins, JSON on a
     * tie (the explicit PEP 691 type is the more specific request). Anything
     * without an acceptable match falls back to HTML, the PEP 503 default.</p>
     *
     * @param headers Request headers
     * @return Negotiated format
     */
    public static SimpleApiFormat fromHeaders(final Headers headers) {
        final double[] quality = SimpleApiFormat.qualities(headers);
        final double json = quality[0];
        final double html = Math.max(quality[1], quality[2]);
        final SimpleApiFormat result;
        if (json > 0.0 && json >= html) {
            result = JSON;
        } else {
            result = HTML;
        }
        return result;
    }

    /**
     * Finish a negotiated index response: mark it as varying on
     * {@code Accept} and, when it is HTML and the client asked for the
     * versioned HTML serialization ({@code v1+html} or the {@code latest}
     * alias) at least as much as for {@code text/html}, announce it as
     * {@code application/vnd.pypi.simple.v1+html} like PyPI does (PEP 691).
     *
     * @param response Index response
     * @param request Request headers
     * @return Finished response
     */
    static Response negotiated(final Response response, final Headers request) {
        final Response varied = SimpleApiFormat.varyOnAccept(response);
        final double[] quality = SimpleApiFormat.qualities(request);
        final Response result;
        if (quality[2] > 0.0 && quality[2] >= quality[1] && quality[2] > quality[0]) {
            result = SimpleApiFormat.versionedHtml(varied);
        } else {
            result = varied;
        }
        return result;
    }

    /**
     * Retype a {@code text/html} response as the versioned HTML type,
     * keeping its parameters (charset). Other responses are unchanged.
     * @param response Response
     * @return Retyped response
     */
    private static Response versionedHtml(final Response response) {
        final List<Header> headers = new ArrayList<>();
        boolean html = false;
        for (final Header header : response.headers()) {
            final String value = header.getValue();
            if ("content-type".equalsIgnoreCase(header.getKey())
                && value.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                headers.add(
                    new Header(
                        "Content-Type",
                        SimpleApiFormat.VERSIONED_HTML + value.substring("text/html".length())
                    )
                );
                html = true;
            } else {
                headers.add(header);
            }
        }
        final Response result;
        if (html) {
            result = new Response(response.status(), new Headers(headers), response.body());
        } else {
            result = response;
        }
        return result;
    }

    /**
     * Highest acceptable quality of each serialization in the request's
     * {@code Accept} ranges.
     * @param headers Request headers
     * @return Qualities: [JSON, text/html incl. wildcards, versioned HTML]
     */
    private static double[] qualities(final Headers headers) {
        final double[] result = new double[3];
        for (final var header : headers) {
            if ("accept".equalsIgnoreCase(header.getKey())) {
                for (final String range : header.getValue().split(",")) {
                    final String[] parts = range.split(";");
                    final String type = parts[0].trim().toLowerCase(Locale.ROOT);
                    final int slot = SimpleApiFormat.slot(type);
                    if (slot >= 0) {
                        result[slot] = Math.max(result[slot], SimpleApiFormat.quality(parts));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Quality slot of a media type.
     * @param type Lower-cased media type
     * @return Slot index, -1 for types that select no serialization
     */
    private static int slot(final String type) {
        final int result;
        if (JSON_TYPES.contains(type)) {
            result = 0;
        } else if (HTML_TYPES.contains(type)) {
            result = 1;
        } else if (VERSIONED_HTML_TYPES.contains(type)) {
            result = 2;
        } else {
            result = -1;
        }
        return result;
    }

    /**
     * Mark a negotiated index response as varying on {@code Accept}: the same
     * URL answers HTML or PEP 691 JSON, so shared caches must key on it.
     *
     * @param response Index response
     * @return Response with {@code Vary: Accept}
     */
    private static Response varyOnAccept(final Response response) {
        return new Response(
            response.status(),
            response.headers().copy().add(new Header("Vary", SimpleApiFormat.VARY)),
            response.body()
        );
    }

    /**
     * Quality value of one media range ({@code q} parameter, default 1).
     * @param parts Media range split on {@code ;}
     * @return Quality in [0, 1]; malformed values count as 1
     */
    private static double quality(final String... parts) {
        double result = 1.0;
        for (int idx = 1; idx < parts.length; idx += 1) {
            final String param = parts[idx].trim();
            if (param.length() > 1 && Character.toLowerCase(param.charAt(0)) == 'q'
                && param.substring(1).trim().startsWith("=")) {
                try {
                    final double parsed = Double.parseDouble(
                        param.substring(param.indexOf('=') + 1).trim()
                    );
                    result = Math.max(0.0, Math.min(1.0, parsed));
                } catch (final NumberFormatException ex) {
                    result = 1.0;
                }
            }
        }
        return result;
    }
}
