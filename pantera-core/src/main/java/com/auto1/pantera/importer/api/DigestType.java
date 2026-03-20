/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.importer.api;

import java.security.MessageDigest;

/**
 * Supported digest algorithms for import verification.
 *
 * @since 1.0
 */
public enum DigestType {

    /**
     * SHA-1 digest.
     */
    SHA1("SHA-1"),

    /**
     * SHA-256 digest.
     */
    SHA256("SHA-256"),

    /**
     * MD5 digest.
     */
    MD5("MD5"),

    /**
     * SHA-512 digest.
     */
    SHA512("SHA-512");

    /**
     * JCA algorithm name.
     */
    private final String algorithm;

    DigestType(final String algorithm) {
        this.algorithm = algorithm;
    }

    /**
     * Create an initialized {@link MessageDigest}.
     *
     * @return MessageDigest instance
     */
    public MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(this.algorithm);
        } catch (final Exception err) {
            throw new IllegalStateException(
                String.format("Failed to initialize digest %s", this.algorithm),
                err
            );
        }
    }

    /**
     * Header alias for digest.
     *
     * @return Header name suffix
     */
    public String headerSuffix() {
        return switch (this) {
            case SHA1 -> "sha1";
            case SHA256 -> "sha256";
            case MD5 -> "md5";
            case SHA512 -> "sha512";
        };
    }
}
