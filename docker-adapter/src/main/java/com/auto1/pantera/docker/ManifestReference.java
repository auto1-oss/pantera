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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.docker.misc.ImageTag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manifest reference.
 * <p>Can be resolved by image tag or digest.
 *
 * @param link      The key for manifest blob link.
 * @param digest String representation.
 */
public record ManifestReference(Key link, String digest) {

    /**
     * Scopes this reference to a negotiated {@code Accept}-variant for
     * proxy-cache keying (WS4-docker.7).
     *
     * <p>A tag reference resolves to different manifest media types depending
     * on the client's {@code Accept} header, so its cache link gets an extra
     * variant-token segment ({@code .../tags/<tag>/current/<token>/link}),
     * storing each variant independently. Tag enumeration is unaffected: the
     * token sits below the tag node, which stays the direct child of the tags
     * root. A digest reference is content-addressed — one immutable
     * representation — so it is returned unchanged, keeping the shared
     * by-digest revision link across variants. A non-negotiated variant
     * (absent/wildcard {@code Accept}) also returns {@code this}, preserving
     * the legacy single-key layout.</p>
     *
     * @param variant Negotiated variant.
     * @return A variant-scoped reference, or {@code this} when no scoping applies.
     */
    public ManifestReference withVariant(final ManifestVariant variant) {
        final ManifestReference result;
        if (!variant.negotiated() || new Digest.FromString(this.digest).valid()) {
            result = this;
        } else {
            final List<String> parts = new ArrayList<>(this.link.parts());
            parts.add(parts.size() - 1, variant.cacheToken());
            result = new ManifestReference(new Key.From(parts), this.digest);
        }
        return result;
    }

    /**
     * Creates a manifest reference from a Content Digest.
     *
     * @param digest Content Digest
     * @return Manifest reference record
     */
    public static ManifestReference from(Digest digest) {
        return new ManifestReference(
            new Key.From(Arrays.asList("revisions", digest.alg(), digest.hex(), "link")),
            digest.string()
        );
    }

    /**
     * Creates a manifest reference from a string representation of Content Digest or Image Tag.
     *
     * @param val String representation of Content Digest or Image Tag
     * @return Manifest reference record
     */
    public static ManifestReference from(String val) {
        final Digest.FromString digest = new Digest.FromString(val);
        return digest.valid() ? from(digest) : fromTag(val);
    }

    /**
     * Creates a manifest reference from a Docker image tag.
     *
     * @param tag Image tag
     * @return Manifest reference record
     */
    public static ManifestReference fromTag(String tag) {
        String validated = ImageTag.validate(tag);
        return new ManifestReference(
            new Key.From(Arrays.asList("tags", validated, "current", "link")),
            validated
        );
    }
}
