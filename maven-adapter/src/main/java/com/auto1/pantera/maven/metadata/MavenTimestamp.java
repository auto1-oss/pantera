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
package com.auto1.pantera.maven.metadata;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Maven-standard timestamp formatting for maven-metadata.xml lastUpdated field.
 * Produces timestamps in {@code yyyyMMddHHmmss} format (UTC), as defined by the
 * Maven repository metadata specification.
 *
 * <p>Thread-safe: {@link DateTimeFormatter} is immutable and thread-safe.
 *
 * @since 1.20.13
 */
public final class MavenTimestamp {

    /**
     * Maven metadata timestamp format: yyyyMMddHHmmss in UTC.
     */
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /**
     * Private ctor.
     */
    private MavenTimestamp() {
    }

    /**
     * Current timestamp in Maven metadata format.
     * @return Timestamp string, e.g. "20260213120000"
     */
    @SuppressWarnings("PMD.ProhibitPublicStaticMethods")
    public static String now() {
        return FMT.format(Instant.now());
    }

    /**
     * Format an instant in Maven metadata format.
     * @param instant The instant to format
     * @return Timestamp string, e.g. "20260213120000"
     */
    @SuppressWarnings("PMD.ProhibitPublicStaticMethods")
    public static String format(final Instant instant) {
        return FMT.format(instant);
    }
}
