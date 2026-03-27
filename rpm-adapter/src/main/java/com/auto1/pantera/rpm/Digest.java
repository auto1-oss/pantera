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
package com.auto1.pantera.rpm;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Supported algorithms for hashing.
 *
 * @since 0.3.3
 */
public enum Digest {
    /**
     * Supported algorithm enumeration: SHA-1, SHA-256.
     */
    SHA1("SHA-1", "sha"), SHA256("SHA-256", "sha256");

    /**
     * Algorithm used to instantiate MessageDigest instance.
     */
    private final String hashalg;

    /**
     * Algorithm name used in RPM metadata as checksum type.
     */
    private final String type;

    /**
     * Ctor.
     * @param alg Hashing algorithm
     * @param type Short name of the algorithm used in RPM metadata.
     */
    Digest(final String alg, final String type) {
        this.hashalg = alg;
        this.type = type;
    }

    /**
     * Instantiate MessageDigest instance.
     * @return MessageDigest instance
     */
    public MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance(this.hashalg);
        } catch (final NoSuchAlgorithmException err) {
            throw new IllegalStateException(
                String.format(
                    "%s is unavailable on this environment",
                    this.hashalg
                ),
                err
            );
        }
    }

    /**
     * Returns short algorithm name for using in RPM metadata.
     * @return Digest type
     */
    public String type() {
        return this.type;
    }
}
