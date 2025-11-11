/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.misc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Calculates ETag for npm package metadata.
 * 
 * <p>ETags enable conditional requests (If-None-Match) and 304 Not Modified responses,
 * reducing bandwidth usage and improving client-side caching.</p>
 *
 * @since 1.19
 */
public final class MetadataETag {
    
    /**
     * Metadata content.
     */
    private final String content;
    
    /**
     * Ctor.
     * 
     * @param content Metadata JSON content
     */
    public MetadataETag(final String content) {
        this.content = content;
    }
    
    /**
     * Calculate ETag as SHA-256 hash of content.
     * 
     * @return ETag string (hex-encoded hash)
     */
    public String calculate() {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(this.content.getBytes(StandardCharsets.UTF_8));
            return this.toHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is always available in Java
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
    
    /**
     * Calculate weak ETag (prefixed with W/).
     * Weak ETags allow semantic equivalence rather than byte-for-byte equality.
     * 
     * @return Weak ETag string
     */
    public String calculateWeak() {
        return "W/" + this.calculate();
    }
    
    /**
     * Convert byte array to hex string.
     * 
     * @param bytes Byte array
     * @return Hex string
     */
    private String toHex(final byte[] bytes) {
        final StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
