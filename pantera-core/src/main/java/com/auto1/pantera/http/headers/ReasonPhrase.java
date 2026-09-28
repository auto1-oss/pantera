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

/**
 * Internal response header asking the HTTP server to send a custom reason
 * phrase in the status line (for example {@code 400 File already exists},
 * which is what some clients, such as twine, print). The server strips the
 * header before the response is written and never forwards it; over HTTP/2,
 * which has no reason phrase, it is only stripped.
 *
 * @since 2.2.9
 */
public final class ReasonPhrase extends Header {

    /**
     * Header name.
     */
    public static final String NAME = "X-Pantera-Reason-Phrase";

    /**
     * Ctor.
     * @param phrase Reason phrase (printable ASCII)
     */
    public ReasonPhrase(final String phrase) {
        super(ReasonPhrase.NAME, phrase);
    }
}
