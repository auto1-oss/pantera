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
import com.auto1.pantera.http.headers.LastModified;

import java.time.Instant;

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
     * @return {@code Last-Modified} header, a fixed-length HTTP date
     */
    static Header from(final Meta meta) {
        return new LastModified(meta, Instant.now());
    }
}
