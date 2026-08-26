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

import com.auto1.pantera.http.Headers;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

/**
 * Client-facing base URL of the repository a request actually addressed.
 *
 * <p>Stamped once by {@code SliceByPath} as {@link #HEADER} and read by every
 * slice that writes an absolute URL into a response body (npm tarball links).
 * Stamping happens above the group resolver, so a group member sees the
 * <em>group's</em> base rather than its own — which is the whole point: a
 * client that configured the group as its registry must receive URLs under
 * the group, or strict clients (corepack) reject them.</p>
 *
 * <p><b>Forwarded headers are a trust boundary.</b> {@code X-Forwarded-Proto},
 * {@code X-Forwarded-Host}, and {@code X-Forwarded-Prefix} are client-supplied
 * unless something in front of Pantera overwrites them on every inbound
 * request. Honouring them unconditionally lets a client steer the tarball
 * URLs Pantera emits at an arbitrary host, and those responses are
 * cacheable. They are therefore only honoured when the operator has enabled
 * it via the DB-backed {@code trust_forwarded_headers} admin setting
 * (env fallback {@code PANTERA_TRUST_FORWARDED_HEADERS}, default {@code
 * false}); see {@link #ClientBaseUrl(Headers)}.</p>
 *
 * <p><b>{@code Host} is also client-supplied.</b> Even with forwarded
 * headers untrusted, the base is still derived from the raw {@code Host}
 * header by default — a request can carry any {@code Host} it likes. The
 * DB-backed {@code client_base_host_allowlist} admin setting (env fallback
 * {@code PANTERA_CLIENT_BASE_HOST_ALLOWLIST}) restricts which {@code Host}
 * values may be used for derivation; a non-matching {@code Host} is treated
 * exactly as an absent one rather than emitted verbatim. An empty/unset
 * allowlist is permissive (today's behaviour, honouring any {@code Host}).</p>
 *
 * <p><b>The canonical base URL setting supersedes both of the above.</b>
 * When the DB-backed {@code client_base_url} admin setting (env fallback
 * {@code PANTERA_CLIENT_BASE_URL}) is non-empty, it supplies the origin (and
 * optional path prefix) for every repository with no explicit {@code url:}
 * -- {@code Host} and {@code X-Forwarded-*} are not consulted at all for
 * those repositories, which makes Host-spoofing structurally impossible
 * rather than merely filtered by the allowlist. The repository-relative
 * path portion is still derived from the request in {@link #derive(String,
 * String)}, since a deployment may use a global path prefix and/or the
 * {@code /api/<type>/<name>} route style. Unset (the default) falls through
 * to tier 3 (request-derived, subject to the allowlist above) exactly as
 * before this setting existed.</p>
 *
 * <p>All three settings are read dynamically on every construction via {@link
 * ClientBaseUrlSettingsRegistry#active()} — a change made through the admin
 * API applies to the very next request, no restart required. The
 * two-argument constructor bypasses that lookup entirely, for tests that
 * need a fixed, deployment-independent {@code trustForwarded} value.</p>
 */
public final class ClientBaseUrl {

    /**
     * Internal header carrying the addressed repository's client-facing base URL.
     */
    public static final String HEADER = "X-Pantera-Client-Base";

    /**
     * Header stamped by {@code ApiRoutingSlice} with the pre-rewrite client
     * path; preferred over the live request path because it still carries the
     * {@code /api/<type>} segment the client configured as its registry.
     */
    public static final String ORIGINAL_PATH = "X-Original-Path";

    /**
     * Request headers.
     */
    private final Headers headers;

    /**
     * Whether {@code X-Forwarded-*} headers are honoured for this instance.
     */
    private final boolean trustForwarded;

    /**
     * {@code Host} values permitted to be used when deriving a base for this
     * instance. Empty means permissive (any {@code Host} is honoured).
     */
    private final List<String> hostAllowlist;

    /**
     * Scheme + authority of the canonical base URL setting (e.g. {@code
     * https://reg.example.com}), or empty when unset — in which case tier 3
     * (request-derived) is used instead. See {@link #absolute(String)}.
     */
    private final String canonicalOrigin;

    /**
     * Path prefix of the canonical base URL setting (e.g. {@code
     * /artifactory}), or empty when unset or the setting is a bare origin.
     */
    private final String canonicalPrefix;

    /**
     * Ctor. Reads the CURRENT {@code trustForwardedHeaders}, {@code
     * hostAllowlist}, and {@code canonicalBaseUrl} settings from {@link
     * ClientBaseUrlSettingsRegistry} — dynamically, on every call, so a
     * runtime admin-API change is honoured on the next request without
     * restarting the process.
     * @param headers Request headers
     */
    public ClientBaseUrl(final Headers headers) {
        this(headers, ClientBaseUrlSettingsRegistry.active());
    }

    /**
     * Ctor delegating to the field-initializing constructor with the given
     * settings snapshot.
     * @param headers Request headers
     * @param settings Settings snapshot to derive from
     */
    private ClientBaseUrl(final Headers headers, final ClientBaseUrlSettings settings) {
        this(
            headers, settings.trustForwardedHeaders(), settings.hostAllowlist(),
            settings.canonicalBaseUrl()
        );
    }

    /**
     * Ctor for callers that need a fixed, deployment-independent trust
     * decision (tests). The host allowlist is always empty (permissive) and
     * the canonical base URL is always unset for this constructor — it
     * never consults {@link ClientBaseUrlSettingsRegistry}.
     * @param headers Request headers
     * @param trustForwarded Whether to honour {@code X-Forwarded-Proto},
     *  {@code X-Forwarded-Host}, and {@code X-Forwarded-Prefix}
     */
    public ClientBaseUrl(final Headers headers, final boolean trustForwarded) {
        this(headers, trustForwarded, List.of(), "");
    }

    /**
     * The single field-initializing constructor; every other constructor
     * delegates here via {@code this(...)}.
     * @param headers Request headers
     * @param trustForwarded Whether to honour {@code X-Forwarded-Proto},
     *  {@code X-Forwarded-Host}, and {@code X-Forwarded-Prefix}
     * @param hostAllowlist {@code Host} values permitted for derivation;
     *  empty is permissive
     * @param canonicalBaseUrl Canonical base URL setting (already validated
     *  and normalized by {@link ClientBaseUrlSettings}), or blank when unset
     */
    private ClientBaseUrl(
        final Headers headers, final boolean trustForwarded, final List<String> hostAllowlist,
        final String canonicalBaseUrl
    ) {
        this.headers = headers;
        this.trustForwarded = trustForwarded;
        this.hostAllowlist = hostAllowlist;
        this.canonicalOrigin = ClientBaseUrl.originOf(canonicalBaseUrl);
        this.canonicalPrefix = ClientBaseUrl.pathOf(canonicalBaseUrl);
    }

    /**
     * Scheme and authority the client used. Honours reverse-proxy forwarding
     * headers only when this instance trusts them; otherwise derives from
     * the {@code Host} header alone (subject to the host allowlist), scheme
     * {@code http}.
     * @return e.g. {@code https://reg.example.com}
     */
    public String origin() {
        final String result;
        if (this.trustForwarded) {
            result = String.format(
                "%s://%s",
                this.first("X-Forwarded-Proto").orElse("http"),
                this.first("X-Forwarded-Host").or(this::allowedHost).orElse("localhost")
            );
        } else {
            result = String.format("http://%s", this.allowedHost().orElse("localhost"));
        }
        return result;
    }

    /**
     * The {@code Host} header value, but only when it passes the configured
     * host allowlist. {@code Host} is client-supplied; an empty/unset
     * allowlist is permissive (any {@code Host} is honoured, matching the
     * behaviour before this allowlist existed). A non-empty allowlist that
     * the header's value does not match (case-insensitive, exact string
     * including any port) yields empty here — every caller of this method
     * already treats an empty result exactly like an absent {@code Host}
     * header, so a disallowed value is never emitted, only ever silently
     * falls back.
     * @return {@code Host} header value if present and allowed, else empty
     */
    private Optional<String> allowedHost() {
        final Optional<String> host = this.first("Host");
        final Optional<String> result;
        if (host.isEmpty() || this.hostAllowlist.isEmpty()) {
            result = host;
        } else {
            final String candidate = host.get();
            result = this.hostAllowlist.stream().anyMatch(candidate::equalsIgnoreCase)
                ? host : Optional.empty();
        }
        return result;
    }

    /**
     * The already-stamped base, if an outer slice set one.
     * @return Stamped base URL
     */
    public Optional<String> stamped() {
        return this.first(ClientBaseUrl.HEADER);
    }

    /**
     * {@code Vary} response header value for any response whose body embeds
     * a base URL this instance could derive.
     *
     * <p>When the canonical base URL setting is in effect ({@link
     * #canonicalOrigin} non-empty), {@code Host} and {@code X-Forwarded-*}
     * are not consulted at all — see {@link #absolute(String)} — so NEITHER
     * participates here either; an empty string is returned. Getting this
     * backwards (leaving a header in {@code Vary} that no longer influences
     * the body, or — the dangerous direction — dropping one that still
     * does) is exactly the kind of stale {@code Vary} that lets a shared
     * cache cross-serve a response built for a different request. Otherwise
     * {@code Host} always participates (see {@link #origin()}); the {@code
     * X-Forwarded-*} triplet only participates when this instance trusts
     * forwarded headers, since {@link #origin()} and
     * {@link #forwardedPrefix()} only read them in that case.</p>
     *
     * <p>Deliberately independent of the headers this instance was built
     * with: whether a header participates in the derivation is a property
     * of the CURRENT settings ({@link #trustForwarded} / {@link
     * #canonicalOrigin}, resolved dynamically at construction via {@link
     * ClientBaseUrlSettingsRegistry#active()} — never a boot-time snapshot),
     * not a per-request one, so callers may compute this from any {@link
     * ClientBaseUrl} instance, including one built from headers unrelated to
     * the response being built. A change applied through the admin API is
     * therefore reflected in the very next response's {@code Vary} header,
     * with no restart.</p>
     *
     * @return Vary header value, or {@code ""} when nothing varies
     */
    public String varyHeaderValue() {
        final String result;
        if (!this.canonicalOrigin.isEmpty()) {
            result = "";
        } else if (this.trustForwarded) {
            result = "Host, X-Forwarded-Proto, X-Forwarded-Host, X-Forwarded-Prefix";
        } else {
            result = "Host";
        }
        return result;
    }

    /**
     * Build the absolute repository base by removing the slice-relative
     * remainder from the client-facing path.
     *
     * <p>Returns empty when {@code remainder} is not a suffix of
     * {@code originalClientPath} — deliberately preferring "no value" over a
     * wrong URL, so consumers fall through to their existing fallback chain.</p>
     *
     * @param originalClientPath Path as the client sent it
     * @param remainder Path relative to the repository, e.g. {@code /pnpm}
     * @return Absolute base URL, or empty if it cannot be derived safely
     */
    public Optional<String> derive(final String originalClientPath, final String remainder) {
        final Optional<String> result;
        if (originalClientPath == null || originalClientPath.isEmpty()) {
            result = Optional.empty();
        } else if (remainder == null || remainder.isEmpty() || "/".equals(remainder)) {
            result = Optional.of(this.absolute(originalClientPath));
        } else if (originalClientPath.endsWith(remainder)) {
            result = Optional.of(
                this.absolute(
                    originalClientPath.substring(
                        0, originalClientPath.length() - remainder.length()
                    )
                )
            );
        } else {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * Prepend a base to a repository path: the canonical base URL setting
     * when one is configured (tier 2 — {@code Host}/{@code X-Forwarded-*}
     * are never consulted in this branch, see {@link #origin()}), otherwise
     * the request-derived origin and any forwarded prefix (tier 3, today's
     * behaviour).
     *
     * <p>Tier 2 avoids doubling the canonical prefix: when the derived
     * {@code repoPath} already starts with it — the deployment's global
     * path prefix happens to equal the canonical setting's own path prefix
     * — only the origin is prepended, since the prefix is already present.</p>
     *
     * @param repoPath Repository base path
     * @return Absolute URL without a trailing slash
     */
    private String absolute(final String repoPath) {
        final String path = ClientBaseUrl.withoutTrailingSlash(repoPath);
        final String result;
        if (this.canonicalOrigin.isEmpty()) {
            result = this.origin() + this.forwardedPrefix() + path;
        } else if (!this.canonicalPrefix.isEmpty() && path.startsWith(this.canonicalPrefix)) {
            result = this.canonicalOrigin + path;
        } else {
            result = this.canonicalOrigin + this.canonicalPrefix + path;
        }
        return result;
    }

    /**
     * Scheme + authority of a canonical base URL setting value, e.g. {@code
     * https://reg.example.com} out of {@code https://reg.example.com/artifactory}.
     * Defensive against a non-parseable value even though every value
     * reaching this method already passed {@link ClientBaseUrlSettings}'s
     * validation.
     * @param canonicalBaseUrl Canonical base URL setting, or blank when unset
     * @return {@code scheme://authority}, or {@code ""} when unset/unparseable
     */
    private static String originOf(final String canonicalBaseUrl) {
        String result = "";
        if (canonicalBaseUrl != null && !canonicalBaseUrl.isBlank()) {
            try {
                final URI uri = new URI(canonicalBaseUrl);
                result = uri.getScheme() + "://" + uri.getAuthority();
            } catch (final URISyntaxException ex) {
                result = "";
            }
        }
        return result;
    }

    /**
     * Path prefix of a canonical base URL setting value, e.g. {@code
     * /artifactory} out of {@code https://reg.example.com/artifactory}, or
     * {@code ""} for a bare origin. Defensive against a non-parseable value
     * for the same reason as {@link #originOf(String)}.
     * @param canonicalBaseUrl Canonical base URL setting, or blank when unset
     * @return Path prefix without a trailing slash, or {@code ""}
     */
    private static String pathOf(final String canonicalBaseUrl) {
        String result = "";
        if (canonicalBaseUrl != null && !canonicalBaseUrl.isBlank()) {
            try {
                final String path = new URI(canonicalBaseUrl).getRawPath();
                if (path != null) {
                    result = path;
                }
            } catch (final URISyntaxException ex) {
                result = "";
            }
        }
        return result;
    }

    /**
     * Reverse-proxy path prefix stripped before forwarding, if declared and
     * this instance trusts forwarded headers; otherwise treated as absent.
     * @return Prefix without a trailing slash, or empty string
     */
    private String forwardedPrefix() {
        final String result;
        if (this.trustForwarded) {
            result = this.first("X-Forwarded-Prefix")
                .map(ClientBaseUrl::withoutTrailingSlash)
                .orElse("");
        } else {
            result = "";
        }
        return result;
    }

    /**
     * First non-blank value of a header, taking the leftmost entry of a
     * comma-separated forwarded chain.
     * @param name Header name
     * @return Header value
     */
    private Optional<String> first(final String name) {
        final List<String> values = this.headers.values(name);
        Optional<String> result = Optional.empty();
        if (!values.isEmpty()) {
            final String raw = values.get(0);
            if (raw != null && !raw.isBlank()) {
                final int comma = raw.indexOf(',');
                final String single;
                if (comma >= 0) {
                    single = raw.substring(0, comma);
                } else {
                    single = raw;
                }
                result = Optional.of(single.trim());
            }
        }
        return result;
    }

    /**
     * Drop a single trailing slash so concatenation never doubles it.
     * @param value Path or prefix
     * @return Value without a trailing slash
     */
    private static String withoutTrailingSlash(final String value) {
        final String result;
        if (value.length() > 1 && value.endsWith("/")) {
            result = value.substring(0, value.length() - 1);
        } else if ("/".equals(value)) {
            result = "";
        } else {
            result = value;
        }
        return result;
    }
}
