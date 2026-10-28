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
package com.auto1.pantera.docker;

import org.apache.commons.codec.digest.DigestUtils;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The negotiated {@code Accept}-variant of a Docker/OCI manifest request
 * (WS4-docker.7).
 *
 * <p>A manifest GET for the same {@code image:tag} can resolve to different
 * media types depending on what the client's {@code Accept} header allows —
 * an {@code application/vnd.docker.distribution.manifest.v2+json} single-arch
 * manifest, an {@code application/vnd.oci.image.index.v1+json} multi-arch
 * index, and so on. On the proxy path these representations must be fetched
 * from and cached in the upstream <em>per variant</em>: forwarding the wrong
 * {@code Accept} upstream, or caching one variant under a key another variant
 * reads, cross-serves a client a media type it cannot parse.</p>
 *
 * <p>This value object carries the client's acceptable media ranges (used to
 * forward the correct {@code Accept} upstream) and derives a stable,
 * storage-safe {@link #cacheToken() cache token} that keys distinct variants
 * apart. An absent {@code Accept} header, or one that accepts the universal
 * wildcard, is {@link #any() not negotiated}: it forwards the upstream
 * superset and uses the legacy single-key cache layout, preserving
 * pre-negotiation behaviour for every client that never sent the header.</p>
 */
public final class ManifestVariant {

    /**
     * The universal media range: a client sending it accepts any
     * representation, so there is no variant to distinguish.
     */
    private static final String WILDCARD = "*/*";

    /**
     * Number of leading hex characters of the token digest to keep. 64 bits
     * is collision-safe for the handful of manifest variants a single tag can
     * have, while keeping the cache key short.
     */
    private static final int TOKEN_LENGTH = 16;

    /**
     * The non-negotiated variant: no {@code Accept} constraint.
     */
    private static final ManifestVariant ANY = new ManifestVariant(List.of());

    /**
     * Client-acceptable media ranges, normalized (trimmed, lower-cased). Empty
     * when the request imposes no variant (absent or wildcard {@code Accept}).
     */
    private final List<String> mediaTypes;

    /**
     * @param mediaTypes Normalized acceptable media ranges.
     */
    private ManifestVariant(final List<String> mediaTypes) {
        this.mediaTypes = List.copyOf(mediaTypes);
    }

    /**
     * @return The non-negotiated variant (no {@code Accept} constraint).
     */
    public static ManifestVariant any() {
        return ManifestVariant.ANY;
    }

    /**
     * Builds a variant from the media ranges parsed out of a client's
     * {@code Accept} header (e.g. via {@code new Accept(headers).values()}).
     *
     * @param accept Acceptable media ranges, weight-ordered, possibly empty.
     * @return {@link #any()} when the client accepts anything (empty or
     *  wildcard), otherwise a negotiated variant over the given ranges.
     */
    public static ManifestVariant fromAccept(final List<String> accept) {
        final List<String> normalized = accept.stream()
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toList());
        final ManifestVariant result;
        if (normalized.isEmpty() || normalized.contains(ManifestVariant.WILDCARD)) {
            result = ManifestVariant.ANY;
        } else {
            result = new ManifestVariant(normalized);
        }
        return result;
    }

    /**
     * @return {@code true} when the request constrains the media type, so the
     *  proxy must forward this {@code Accept} upstream and key the cache by
     *  {@link #cacheToken()}; {@code false} for the wildcard/absent case that
     *  keeps the legacy superset-upstream, single-key-cache behaviour.
     */
    public boolean negotiated() {
        return !this.mediaTypes.isEmpty();
    }

    /**
     * @return The client-acceptable media ranges to forward upstream. Empty
     *  for a non-negotiated variant.
     */
    public List<String> mediaTypes() {
        return this.mediaTypes;
    }

    /**
     * A deterministic, order-independent, storage-safe token identifying this
     * variant, used as a cache-key path segment. Two requests with the same
     * set of acceptable media ranges share a token (and a cache entry); any
     * difference yields a different token, so a v2-manifest variant and an
     * OCI-index variant of one tag never collide.
     *
     * @return Short hex token. Undefined (and unused) for {@link #any()}.
     */
    public String cacheToken() {
        final String canonical = this.mediaTypes.stream()
            .sorted()
            .collect(Collectors.joining(","));
        return DigestUtils.sha256Hex(canonical).substring(0, ManifestVariant.TOKEN_LENGTH);
    }
}
