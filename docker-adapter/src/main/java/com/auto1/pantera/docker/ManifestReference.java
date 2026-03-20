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

import java.util.Arrays;

/**
 * Manifest reference.
 * <p>Can be resolved by image tag or digest.
 *
 * @param link      The key for manifest blob link.
 * @param digest String representation.
 */
public record ManifestReference(Key link, String digest) {

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
