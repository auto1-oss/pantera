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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.http.headers.Header;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Builds the {@code Last-Modified} response header from storage metadata, so
 * local artifact/metadata GET and HEAD responses can advertise the same
 * value (WS4-maven.9).
 */
final class LastModifiedHeader {

    private LastModifiedHeader() {
    }

    /**
     * Build the {@code Last-Modified} header for the given storage metadata.
     * Prefers the storage-reported update time, falls back to creation time,
     * and — for storage backends that report neither (e.g. the in-memory
     * test double) — falls back to the current instant so the header is
     * always present, mirroring {@code CachedProxySlice#buildMetadataResponse}.
     *
     * @param meta Storage metadata
     * @return {@code Last-Modified} header, RFC 1123 formatted
     */
    static Header from(final Meta meta) {
        return new Header("Last-Modified", httpDate(instant(meta)));
    }

    /**
     * Resolve the timestamp to advertise.
     * @param meta Storage metadata
     * @return Best-known timestamp
     */
    private static Instant instant(final Meta meta) {
        final Optional<? extends Instant> updated = meta.read(Meta.OP_UPDATED_AT);
        if (updated.isPresent()) {
            return updated.get();
        }
        final Optional<? extends Instant> created = meta.read(Meta.OP_CREATED_AT);
        if (created.isPresent()) {
            return created.get();
        }
        return Instant.now();
    }

    /**
     * Format an instant as an RFC 1123 HTTP date.
     * @param when Instant to format
     * @return RFC 1123 formatted date string
     */
    private static String httpDate(final Instant when) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(when, ZoneOffset.UTC)
        );
    }
}
