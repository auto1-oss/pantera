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
 * Strip client-supplied copies of internal-only header markers before an
 * outer slice decides whether to stamp its own.
 *
 * <p>{@link ClientBaseUrl#HEADER} and {@link ClientBaseUrl#ORIGINAL_PATH}
 * are trusted signals consumed downstream to build absolute URLs Pantera
 * emits (npm {@code dist.tarball}); if a client-supplied value survived, a
 * request could poison a cacheable response with an attacker-controlled
 * host. Extracted from what used to be duplicated {@code without(...)}
 * helpers in {@code SliceByPath} and {@code ApiRoutingSlice} so every call
 * site — including repositories bound to a dedicated port, which bypass
 * both of those slices entirely — scrubs the same way.</p>
 */
public final class InternalHeaderScrub {

    /**
     * Source headers.
     */
    private final Headers headers;

    /**
     * @param headers Source headers
     */
    public InternalHeaderScrub(final Headers headers) {
        this.headers = headers;
    }

    /**
     * Remove every header matching any of {@code names}, matched
     * case-insensitively, returning an independent copy.
     * {@link Headers#add(Header, boolean)}'s overwrite path compares names
     * case-<em>sensitively</em>, so it cannot be used to neutralise a
     * client-supplied header sent in a different case (e.g.
     * {@code x-pantera-client-base}) — this does the comparison the safe
     * way instead.
     *
     * @param names Header names to remove, any case
     * @return A new {@link Headers} without any entry matching a name in
     *  {@code names}
     */
    public Headers without(final String... names) {
        final Headers result = new Headers();
        for (final Header header : this.headers) {
            if (!InternalHeaderScrub.matches(header, names)) {
                result.add(header);
            }
        }
        return result;
    }

    /**
     * @param header Header to test
     * @param names Candidate names, any case
     * @return true if {@code header}'s name matches any of {@code names}
     */
    private static boolean matches(final Header header, final String... names) {
        boolean found = false;
        for (final String name : names) {
            if (header.getKey().equalsIgnoreCase(name)) {
                found = true;
                break;
            }
        }
        return found;
    }
}
