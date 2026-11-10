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
package com.auto1.pantera.docker.http.manifest;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Accept;

import java.util.List;

/**
 * Negotiates whether a stored manifest's media type is acceptable to the
 * client per the inbound {@code Accept} header (WS4-docker.7).
 *
 * <p>An absent {@code Accept} header preserves pre-negotiation behaviour --
 * every stored manifest media type is served regardless, matching every
 * client (and tooling) that never sent the header. The universal wildcard
 * (any type, any subtype) is honored, as is a bare subtype wildcard on an
 * exact type (e.g. an {@code application} wildcard) per RFC 9110
 * &sect;12.5.1. Quality values ({@code q=}) are parsed by {@link Accept}
 * (which orders by weight) but not consulted here -- weight only matters
 * when picking among several acceptable representations, not for this
 * binary accept/reject gate.
 */
final class ManifestAccept {

    /**
     * The universal wildcard media-range: matches any stored media type.
     */
    private static final String WILDCARD = "*/*";

    /**
     * Media types (or wildcards) the client declared acceptable, ordered
     * by weight. Empty when the {@code Accept} header was absent.
     */
    private final List<String> acceptable;

    /**
     * @param headers Inbound request headers.
     */
    ManifestAccept(final Headers headers) {
        this.acceptable = new Accept(headers).values();
    }

    /**
     * @param mediaType Stored/served manifest media type.
     * @return {@code true} when the client's {@code Accept} header allows
     *  {@code mediaType} to be served, or the header was absent entirely.
     */
    boolean accepts(final String mediaType) {
        return this.acceptable.isEmpty()
            || this.acceptable.stream().anyMatch(candidate -> ManifestAccept.matches(candidate, mediaType));
    }

    /**
     * @param candidate One value from the client's {@code Accept} header.
     * @param mediaType Stored/served manifest media type.
     * @return {@code true} when {@code candidate} matches {@code mediaType}
     *  exactly, is the universal wildcard, or is a {@code type} + bare
     *  subtype wildcard covering {@code mediaType}'s type.
     */
    private static boolean matches(final String candidate, final String mediaType) {
        final boolean matched;
        if (WILDCARD.equals(candidate) || candidate.equals(mediaType)) {
            matched = true;
        } else {
            final int slash = candidate.indexOf('/');
            matched = slash >= 0 && candidate.length() == slash + 2 && candidate.charAt(slash + 1) == '*'
                && mediaType.regionMatches(0, candidate, 0, slash + 1);
        }
        return matched;
    }
}
