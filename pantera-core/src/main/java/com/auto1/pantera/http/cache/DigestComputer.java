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
package com.auto1.pantera.http.cache;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes cryptographic digests for artifact content.
 * Thread-safe utility — each call allocates fresh MessageDigest instances.
 *
 * <p>Supported algorithms: SHA-256, SHA-1, MD5, SHA-512.
 *
 * <p>Provides both batch ({@link #compute(byte[], Set)}) and streaming
 * ({@link #createDigests}, {@link #updateDigests}, {@link #finalizeDigests})
 * APIs. The streaming API enables incremental digest computation without
 * buffering the entire content in memory.
 *
 * @since 1.20.13
 */
public final class DigestComputer {

    /**
     * SHA-256 algorithm name.
     */
    public static final String SHA256 = "SHA-256";

    /**
     * SHA-1 algorithm name.
     */
    public static final String SHA1 = "SHA-1";

    /**
     * MD5 algorithm name.
     */
    public static final String MD5 = "MD5";

    /**
     * SHA-512 algorithm name.
     */
    public static final String SHA512 = "SHA-512";

    /**
     * Maven digest set: SHA-256 + SHA-1 + MD5.
     */
    public static final Set<String> MAVEN_DIGESTS = Set.of(SHA256, SHA1, MD5);


    /**
     * Hex formatter for digest output.
     */
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Private ctor — static utility class.
     */
    private DigestComputer() {
    }

    /**
     * Compute digests for the given content using specified algorithms.
     *
     * @param content Raw artifact bytes
     * @param algorithms Set of algorithm names (e.g., "SHA-256", "MD5")
     * @return Map of algorithm name to lowercase hex-encoded digest string
     * @throws IllegalArgumentException If an unsupported algorithm is requested
     */
    public static Map<String, String> compute(
        final byte[] content, final Set<String> algorithms
    ) {
        Objects.requireNonNull(content, "content");
        if (algorithms == null || algorithms.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, MessageDigest> digests = createDigests(algorithms);
        for (final MessageDigest digest : digests.values()) {
            digest.update(content);
        }
        return finalizeDigests(digests);
    }

    /**
     * Create fresh MessageDigest instances for the specified algorithms.
     * Use with {@link #updateDigests} and {@link #finalizeDigests} for
     * streaming digest computation.
     *
     * @param algorithms Set of algorithm names (e.g., "SHA-256", "MD5")
     * @return Map of algorithm name to MessageDigest instance
     * @throws IllegalArgumentException If an unsupported algorithm is requested
     */
    public static Map<String, MessageDigest> createDigests(
        final Set<String> algorithms
    ) {
        if (algorithms == null || algorithms.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, MessageDigest> digests = new HashMap<>(algorithms.size());
        for (final String algo : algorithms) {
            try {
                digests.put(algo, MessageDigest.getInstance(algo));
            } catch (final NoSuchAlgorithmException ex) {
                throw new IllegalArgumentException(
                    String.format("Unsupported digest algorithm: %s", algo), ex
                );
            }
        }
        return digests;
    }

    /**
     * Update all digests with the given chunk of data.
     * The ByteBuffer position is advanced to its limit after this call.
     *
     * @param digests Map of algorithm name to MessageDigest (from {@link #createDigests})
     * @param chunk Data chunk to feed into digests
     */
    public static void updateDigests(
        final Map<String, MessageDigest> digests, final ByteBuffer chunk
    ) {
        if (digests.isEmpty() || !chunk.hasRemaining()) {
            return;
        }
        for (final MessageDigest digest : digests.values()) {
            final ByteBuffer view = chunk.asReadOnlyBuffer();
            digest.update(view);
        }
    }

    /**
     * Finalize all digests and return hex-encoded results.
     * After this call the MessageDigest instances are reset.
     *
     * @param digests Map of algorithm name to MessageDigest
     * @return Map of algorithm name to lowercase hex-encoded digest string
     */
    public static Map<String, String> finalizeDigests(
        final Map<String, MessageDigest> digests
    ) {
        if (digests.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, String> result = new HashMap<>(digests.size());
        for (final Map.Entry<String, MessageDigest> entry : digests.entrySet()) {
            result.put(entry.getKey(), HEX.formatHex(entry.getValue().digest()));
        }
        return Collections.unmodifiableMap(result);
    }
}
