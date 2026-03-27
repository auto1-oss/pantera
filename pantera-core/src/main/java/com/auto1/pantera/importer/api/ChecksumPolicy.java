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
package com.auto1.pantera.importer.api;

import java.util.Locale;

/**
 * Checksum handling policies supported by the importer.
 *
 * <p>The policy determines whether checksums are calculated on the fly,
 * trusted from accompanying metadata, or fully skipped.</p>
 *
 * @since 1.0
 */
public enum ChecksumPolicy {

    /**
     * Compute checksums while streaming and verify expected values when present.
     */
    COMPUTE,

    /**
     * Do not calculate digests, rely on provided metadata values.
     */
    METADATA,

    /**
     * Skip checksum validation entirely.
     */
    SKIP;

    /**
     * Parse checksum policy from header value. Defaults to {@link #COMPUTE}.
     *
     * @param header Header value, may be {@code null}
     * @return Parsed policy
     */
    public static ChecksumPolicy fromHeader(final String header) {
        if (header == null || header.isBlank()) {
            return COMPUTE;
        }
        final String normalized = header.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "COMPUTE" -> COMPUTE;
            case "METADATA" -> METADATA;
            case "SKIP" -> SKIP;
            default -> throw new IllegalArgumentException(
                String.format("Unsupported checksum policy '%s'", header)
            );
        };
    }
}
