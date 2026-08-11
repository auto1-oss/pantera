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

import java.net.URI;
import java.net.URISyntaxException;
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
 * @param canonicalBaseUrl Canonical origin (+ optional path prefix) used for
 *  EVERY repository that has no explicit {@code url:}, e.g. {@code
 *  http://localhost:9999} or {@code https://reg.example.com/artifactory}. An
 *  <b>empty</b> string (the default) means unset -- {@link ClientBaseUrl}
 *  then falls back to deriving from the request ({@code Host} / {@code
 *  X-Forwarded-*}, tier 3). When non-empty, it is ENFORCED: {@code Host} and
 *  {@code X-Forwarded-*} are not consulted at all for a repository without
 *  {@code url:} -- see {@link ClientBaseUrl#derive(String, String)}.
 *  Normalized to strip a single trailing slash so composing it with a
 *  derived repository path never doubles it.
 *
 * @since 2.3.0
 */
public record ClientBaseUrlSettings(
    boolean trustForwardedHeaders, List<String> hostAllowlist, String canonicalBaseUrl
) {

    /**
     * Compact constructor -- validates and defensively copies {@code
     * hostAllowlist}, and validates/normalizes {@code canonicalBaseUrl}.
     */
    public ClientBaseUrlSettings {
        Objects.requireNonNull(hostAllowlist, "hostAllowlist");
        if (hostAllowlist.stream().anyMatch(host -> host == null || host.isBlank())) {
            throw new IllegalArgumentException(
                "hostAllowlist entries must be non-blank: " + hostAllowlist
            );
        }
        hostAllowlist = List.copyOf(hostAllowlist);
        canonicalBaseUrl = ClientBaseUrlSettings.normalizeCanonicalBaseUrl(canonicalBaseUrl);
    }

    /**
     * Convenience constructor for callers that don't set a canonical base
     * URL (every pre-2.3.0-fixwave-h call site, including tests unrelated to
     * this setting) -- delegates to the canonical 3-arg constructor with
     * {@code canonicalBaseUrl} unset.
     *
     * @param trustForwardedHeaders See the canonical constructor
     * @param hostAllowlist See the canonical constructor
     */
    public ClientBaseUrlSettings(final boolean trustForwardedHeaders, final List<String> hostAllowlist) {
        this(trustForwardedHeaders, hostAllowlist, "");
    }

    /**
     * Defaults: forwarded headers not trusted, allowlist empty (permissive
     * -- any {@code Host} is honoured), canonical base URL unset. Matches
     * Pantera's behaviour before any of these settings existed.
     *
     * @return Default settings.
     */
    public static ClientBaseUrlSettings defaults() {
        return new ClientBaseUrlSettings(false, List.of(), "");
    }

    /**
     * Validate and normalize a canonical base URL setting: blank means
     * unset (returned as {@code ""}); otherwise it must parse as an
     * absolute {@code http}/{@code https} URL with a host, and a single
     * trailing slash is stripped so concatenating it with a derived
     * repository path (which always starts with {@code /}) never produces
     * a doubled slash.
     *
     * @param value Raw setting value
     * @return Normalized value, or {@code ""} when unset
     */
    private static String normalizeCanonicalBaseUrl(final String value) {
        final String result;
        if (value == null || value.isBlank()) {
            result = "";
        } else {
            final String trimmed = value.trim();
            final URI uri;
            try {
                uri = new URI(trimmed);
            } catch (final URISyntaxException ex) {
                throw new IllegalArgumentException(
                    "canonicalBaseUrl must be a valid absolute URL: " + trimmed, ex
                );
            }
            final String scheme = uri.getScheme();
            if (scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException(
                    "canonicalBaseUrl must use http or https: " + trimmed
                );
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException(
                    "canonicalBaseUrl must include a host: " + trimmed
                );
            }
            result = trimmed.length() > 1 && trimmed.endsWith("/")
                ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }
        return result;
    }
}
