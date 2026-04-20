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
package com.auto1.pantera.http.cooldown;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parsed Go {@code /@latest} JSON response.
 *
 * <p>Shape per the Go module proxy spec
 * (<a href="https://go.dev/ref/mod#module-proxy">go.dev/ref/mod</a>):</p>
 * <pre>
 * {
 *   "Version": "v1.2.3",
 *   "Time":    "2024-05-12T00:00:00Z",
 *   "Origin":  { ...optional... }
 * }
 * </pre>
 *
 * <p>{@code Time} and {@code Origin} are optional per the spec; the Go
 * client tolerates their absence. {@code origin} is retained as a raw
 * {@link JsonNode} so rewriting preserves all fields the Go toolchain
 * might introduce.</p>
 *
 * @param version Version string (e.g. {@code v1.2.3}); never null
 * @param time    ISO-8601 timestamp string; may be null when absent
 * @param origin  Raw {@code Origin} sub-object; null when absent
 * @since 2.2.0
 */
public record GoLatestInfo(String version, String time, JsonNode origin) {
    /**
     * Canonical constructor. {@code version} must not be null.
     */
    public GoLatestInfo {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
    }

    /**
     * Return a copy with the given version replacing {@link #version()}.
     * {@code time} is cleared (nulled) because a rewritten version has a
     * different release timestamp than the upstream {@code latest}; rather
     * than emit stale {@code Time}, we drop the field and rely on the Go
     * client's tolerance for its absence. {@code origin} is preserved
     * because it describes the module, not the specific version.
     *
     * @param newVersion New {@code Version} value
     * @return New record with swapped version and null time
     */
    public GoLatestInfo withVersion(final String newVersion) {
        return new GoLatestInfo(newVersion, null, this.origin);
    }
}
