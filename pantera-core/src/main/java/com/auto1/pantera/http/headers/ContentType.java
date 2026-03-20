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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Content-Type header.
 */
public final class ContentType {
    /**
     * Header name.
     */
    public static final String NAME = "Content-Type";

    public static Header mime(String mime) {
        return new Header(NAME, mime);
    }

    public static Header mime(String mime, Charset charset) {
        return new Header(NAME, mime + "; charset=" + charset.displayName().toLowerCase());
    }

    public static Header json() {
        return json(StandardCharsets.UTF_8);
    }

    public static Header json(Charset charset) {
        return mime("application/json", charset);
    }

    public static Header text() {
        return text(StandardCharsets.UTF_8);
    }

    public static Header text(Charset charset) {
        return mime("text/plain", charset);
    }

    public static Header html() {
        return html(StandardCharsets.UTF_8);
    }

    public static Header html(Charset charset) {
        return mime("text/html", charset);
    }

    public static Header yaml() {
        return yaml(StandardCharsets.UTF_8);
    }

    public static Header yaml(Charset charset) {
        return mime("text/x-yaml", charset);
    }

    public static Header single(Headers headers) {
        List<Header> res = headers.find(NAME);
        if (res.isEmpty()) {
            throw new IllegalStateException("No headers were found");
        }
        if (res.size() > 1) {
            throw new IllegalStateException("Too many headers were found");
        }
        return res.getFirst();
    }

    private ContentType() {
        //no-op
    }
}
