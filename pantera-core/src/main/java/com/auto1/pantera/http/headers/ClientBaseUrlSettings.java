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
package com.auto1.pantera.http.headers;

import java.util.List;
import java.util.Objects;

/**
 * Runtime-tunable configuration for {@link ClientBaseUrl}'s derivation of the
 * client-facing base URL.
 *
 * @param trustForwardedHeaders Whether {@code X-Forwarded-Proto},
 *  {@code X-Forwarded-Host}, and {@code X-Forwarded-Prefix} are honoured.
 *  See {@link ClientBaseUrl#ClientBaseUrl(Headers)}.
 * @param hostAllowlist Host header values permitted to be used when deriving
 *  a base URL, matched case-insensitively against the raw {@code Host}
 *  header (including port, if the client sent one). An <b>empty</b> list is
 *  permissive: any {@code Host} value is honoured, matching Pantera's
 *  behaviour before this allowlist existed. A non-empty list rejects any
 *  {@code Host} not on it -- {@link ClientBaseUrl} then falls back exactly
 *  as it does when {@code Host} is absent, never emitting the rejected
 *  value.
 *
 * @since 2.3.0
 */
public record ClientBaseUrlSettings(boolean trustForwardedHeaders, List<String> hostAllowlist) {

    /**
     * Compact constructor -- validates and defensively copies {@code
     * hostAllowlist}.
     */
    public ClientBaseUrlSettings {
        Objects.requireNonNull(hostAllowlist, "hostAllowlist");
        if (hostAllowlist.stream().anyMatch(host -> host == null || host.isBlank())) {
            throw new IllegalArgumentException(
                "hostAllowlist entries must be non-blank: " + hostAllowlist
            );
        }
        hostAllowlist = List.copyOf(hostAllowlist);
    }

    /**
     * Defaults: forwarded headers not trusted, allowlist empty (permissive
     * -- any {@code Host} is honoured). Matches Pantera's behaviour before
     * either setting existed.
     *
     * @return Default settings.
     */
    public static ClientBaseUrlSettings defaults() {
        return new ClientBaseUrlSettings(false, List.of());
    }
}
