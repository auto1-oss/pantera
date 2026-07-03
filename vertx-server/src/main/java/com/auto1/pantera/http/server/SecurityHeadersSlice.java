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
package com.auto1.pantera.http.server;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Decorator that injects a hardened set of HTTP security response headers
 * onto every downstream response. T-S05 of
 * {@code analysis/plan/v2/IMPLEMENTATION.md}.
 *
 * <p>The injected headers match Mozilla's "intermediate" web security
 * baseline:</p>
 * <ul>
 *   <li>{@code Strict-Transport-Security} — force HTTPS at the client
 *     for a year on every subdomain.</li>
 *   <li>{@code X-Content-Type-Options: nosniff} — disable MIME sniffing
 *     so a tampered {@code Content-Type} can't be re-interpreted by the
 *     browser as a script.</li>
 *   <li>{@code X-Frame-Options: DENY} — refuse to be framed; mitigates
 *     UI-redress / clickjacking attacks against the admin UI.</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin} —
 *     trim the {@code Referer} on cross-origin navigations so internal
 *     repo paths don't leak.</li>
 *   <li>{@code Content-Security-Policy: default-src 'self'} — restrict
 *     all sub-resource loads to same-origin by default.</li>
 *   <li>{@code Permissions-Policy} — opt out of geolocation, microphone,
 *     and camera access for embedded content.</li>
 * </ul>
 *
 * <p>The decorator is idempotent: if the inner slice already emitted any
 * of these headers (e.g. an adapter that needs a custom CSP), the
 * existing value is preserved and the default is skipped. This is what
 * lets the UI relax {@code X-Frame-Options} to {@code SAMEORIGIN} per
 * route without the outer wrap overriding it.</p>
 *
 * <p>HSTS is suppressed by default for plain-HTTP deployments — a
 * cleartext server emitting an HSTS header is a configuration smell
 * (the next request will be coerced to HTTPS even though the server
 * isn't listening on TLS). The {@code includeHsts} flag opt-in lets
 * production builds turn it on once TLS is wired (see T-S06).</p>
 *
 * @since 2.2.0
 */
public final class SecurityHeadersSlice implements Slice {

    /**
     * Default value for {@code Strict-Transport-Security} — one year,
     * subdomains included. {@code preload} is intentionally omitted: the
     * HSTS preload list is a one-way submission and operators must opt
     * in explicitly via the configuration knob.
     */
    public static final String HSTS_DEFAULT =
        "max-age=31536000; includeSubDomains";

    /**
     * Default value for {@code Content-Security-Policy}. Keep it terse:
     * the UI bundle is same-origin, served by Pantera itself. Adapter
     * responses with HTML bodies (rare — directory listings, the Conan
     * search HTML page) can override per-route by setting the header
     * before this decorator runs.
     */
    public static final String CSP_DEFAULT = "default-src 'self'";

    /**
     * Default value for {@code Permissions-Policy}. Disables three
     * powerful permission features for any embedded content. The list
     * is intentionally short — the spec evolves rapidly and an over-
     * specified policy creates churn.
     */
    public static final String PERMISSIONS_DEFAULT =
        "geolocation=(), microphone=(), camera=()";

    /**
     * Default value for {@code Referrer-Policy}. The "strict-origin-when-
     * cross-origin" value is the modern default: same-origin requests get
     * the full referrer, cross-origin HTTPS-to-HTTPS gets only the origin,
     * HTTPS-to-HTTP gets nothing.
     */
    public static final String REFERRER_POLICY_DEFAULT =
        "strict-origin-when-cross-origin";

    /**
     * Default value for {@code X-Frame-Options}. The decorator emits
     * {@code DENY} (the strictest setting); UI routes that need to be
     * framed can override at the slice level.
     */
    public static final String FRAME_OPTIONS_DEFAULT = "DENY";

    /**
     * Default value for {@code X-Content-Type-Options}. The only
     * meaningful value the spec defines is {@code nosniff}.
     */
    public static final String CONTENT_TYPE_OPTIONS_DEFAULT = "nosniff";

    /**
     * Header names handled by this decorator, normalised to lowercase
     * for matching. We do not overwrite if a downstream slice already
     * emitted one of these — see {@link #merge(Headers)}.
     */
    private static final Set<String> MANAGED_HEADERS = Set.of(
        "strict-transport-security",
        "x-content-type-options",
        "x-frame-options",
        "referrer-policy",
        "content-security-policy",
        "permissions-policy"
    );

    /**
     * Wrapped slice.
     */
    private final Slice origin;

    /**
     * {@code true} to emit the HSTS header. Should be {@code false} for
     * cleartext-HTTP listeners (dev, behind plaintext sidecars).
     */
    private final boolean includeHsts;

    /**
     * {@code Strict-Transport-Security} value, only used when
     * {@link #includeHsts} is {@code true}.
     */
    private final String hstsValue;

    /**
     * {@code Content-Security-Policy} value.
     */
    private final String cspValue;

    /**
     * {@code X-Frame-Options} value.
     */
    private final String frameOptionsValue;

    /**
     * Construct a decorator with all defaults, HSTS enabled. This is
     * the production deployment configuration (TLS-terminated edge).
     *
     * @param origin Wrapped slice.
     */
    public SecurityHeadersSlice(final Slice origin) {
        this(origin, true, HSTS_DEFAULT, CSP_DEFAULT, FRAME_OPTIONS_DEFAULT);
    }

    /**
     * Construct a decorator with explicit HSTS toggle. Use this with
     * {@code includeHsts=false} for plain-HTTP listeners so the client
     * doesn't get a header it can't honour.
     *
     * @param origin Wrapped slice.
     * @param includeHsts {@code true} to emit HSTS, {@code false} otherwise.
     */
    public SecurityHeadersSlice(final Slice origin, final boolean includeHsts) {
        this(origin, includeHsts, HSTS_DEFAULT, CSP_DEFAULT, FRAME_OPTIONS_DEFAULT);
    }

    /**
     * Construct a decorator with full per-header overrides.
     *
     * @param origin           Wrapped slice.
     * @param includeHsts      {@code true} to emit HSTS.
     * @param hstsValue        Value for {@code Strict-Transport-Security}.
     * @param cspValue         Value for {@code Content-Security-Policy}.
     * @param frameOptionsValue Value for {@code X-Frame-Options}.
     */
    public SecurityHeadersSlice(
        final Slice origin,
        final boolean includeHsts,
        final String hstsValue,
        final String cspValue,
        final String frameOptionsValue
    ) {
        this.origin = origin;
        this.includeHsts = includeHsts;
        this.hstsValue = hstsValue;
        this.cspValue = cspValue;
        this.frameOptionsValue = frameOptionsValue;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return this.origin.response(line, headers, body)
            .thenApply(res -> new Response(res.status(), merge(res.headers()), res.body()));
    }

    /**
     * Return a {@link Headers} instance that contains every header from
     * {@code existing} plus any managed header not already present.
     * Downstream-supplied values win — this is the only way for an
     * adapter (e.g. the UI) to relax {@code X-Frame-Options} or tighten
     * a route-specific {@code Content-Security-Policy}.
     *
     * @param existing Response headers from the wrapped slice.
     * @return Merged header set.
     */
    private Headers merge(final Headers existing) {
        final Headers out = existing.copy();
        if (this.includeHsts && !hasHeader(existing, "strict-transport-security")) {
            out.add(new Header("Strict-Transport-Security", this.hstsValue));
        }
        if (!hasHeader(existing, "x-content-type-options")) {
            out.add(new Header("X-Content-Type-Options", CONTENT_TYPE_OPTIONS_DEFAULT));
        }
        if (!hasHeader(existing, "x-frame-options")) {
            out.add(new Header("X-Frame-Options", this.frameOptionsValue));
        }
        if (!hasHeader(existing, "referrer-policy")) {
            out.add(new Header("Referrer-Policy", REFERRER_POLICY_DEFAULT));
        }
        if (!hasHeader(existing, "content-security-policy")) {
            out.add(new Header("Content-Security-Policy", this.cspValue));
        }
        if (!hasHeader(existing, "permissions-policy")) {
            out.add(new Header("Permissions-Policy", PERMISSIONS_DEFAULT));
        }
        return out;
    }

    /**
     * Case-insensitive header presence check. {@link Headers#find(String)}
     * is already case-insensitive but allocates a list; iterate once.
     *
     * @param headers Header bag.
     * @param name    Header name to look up (case-insensitive).
     * @return {@code true} if {@code name} is already present.
     */
    private static boolean hasHeader(final Headers headers, final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        for (final Header h : headers) {
            if (h.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Set of header names this decorator manages, in lowercase.
     *     Exposed for tests and diagnostics; the set is unmodifiable.
     */
    public static Set<String> managedHeaders() {
        return MANAGED_HEADERS;
    }
}
