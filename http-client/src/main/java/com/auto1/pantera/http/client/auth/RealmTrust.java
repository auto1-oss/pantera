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
package com.auto1.pantera.http.client.auth;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Decides which secondary hosts may receive the credentials configured for
 * an upstream — Bearer token realms and upstream-supplied index/mirror
 * links alike.
 *
 * <p>A {@code WWW-Authenticate: Bearer realm=...} challenge is upstream-
 * controlled, so before 2.2.9 a malicious or compromised upstream could name
 * any host as its realm and Pantera would send the configured Basic
 * credentials there. Credentials are now released only to a realm that is:
 * the upstream host itself; a host under the upstream's parent domain when
 * that parent has at least two labels (Docker Hub answers for
 * {@code registry-1.docker.io} with realm {@code auth.docker.io}; a bare
 * {@code ghcr.io} does NOT thereby trust every {@code *.io}); or a host in
 * the {@code PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS} allowlist. Every other realm
 * gets an anonymous token request — most registries grant pull tokens
 * anonymously, and a denied anonymous request fails safely.</p>
 *
 * @since 2.2.9
 */
public final class RealmTrust {

    /**
     * Upstream host (lower-case) the credentials belong to; null when the
     * caller did not say — then only the allowlist can release them.
     */
    private final String upstream;

    /**
     * Explicitly trusted realm hosts (lower-case).
     */
    private final Supplier<Set<String>> allowed;

    /**
     * Ctor.
     *
     * @param upstream Upstream URI the credentials are configured for (nullable)
     * @param allowed Explicitly trusted realm hosts
     */
    public RealmTrust(final URI upstream, final Set<String> allowed) {
        this(upstream, RealmTrust.constant(allowed));
    }

    /**
     * Ctor reading the allowlist through a supplier on every decision, so
     * an admin edit applies to the next challenge without re-wiring.
     *
     * @param upstream Upstream registry URI, may be null
     * @param allowed Live allowlist source (host names, any case)
     */
    public RealmTrust(final URI upstream, final Supplier<Set<String>> allowed) {
        this.upstream = upstream == null || upstream.getHost() == null
            ? null : upstream.getHost().toLowerCase(Locale.ROOT);
        this.allowed = allowed;
    }

    /**
     * Trust for a configured upstream plus the environment allowlist
     * ({@code PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS}, comma-separated).
     *
     * @param upstream Upstream URI (nullable)
     * @return Realm trust
     */
    public static RealmTrust forUpstream(final URI upstream) {
        return new RealmTrust(
            upstream,
            com.auto1.pantera.http.client.egress.EgressSettingsRegistry.credentialAllowHosts()
        );
    }

    /**
     * Trust nothing but the environment allowlist — the safe default when
     * the upstream is unknown.
     *
     * @return Realm trust
     */
    public static RealmTrust none() {
        return RealmTrust.forUpstream(null);
    }

    /**
     * Whether the configured credentials may be sent to this realm.
     *
     * @param realm Realm URI from the challenge
     * @return {@code true} if credentials may be released
     */
    public boolean trusts(final URI realm) {
        final String host = realm.getHost();
        if (host == null) {
            return false;
        }
        final String lower = host.toLowerCase(Locale.ROOT);
        if (RealmTrust.listed(this.allowed.get(), lower)) {
            return true;
        }
        if (this.upstream == null) {
            return false;
        }
        if (this.upstream.equals(lower)) {
            return true;
        }
        final int dot = this.upstream.indexOf('.');
        if (dot < 0) {
            return false;
        }
        final String parent = this.upstream.substring(dot + 1);
        return parent.indexOf('.') >= 0 && lower.endsWith("." + parent);
    }

    /**
     * Fixed allowlist as a supplier.
     *
     * @param allowed Hosts, any case
     * @return Supplier of the normalised set
     */
    private static Supplier<Set<String>> constant(final Set<String> allowed) {
        final Set<String> lower = new HashSet<>();
        for (final String host : allowed) {
            if (host != null && !host.isBlank()) {
                lower.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        final Set<String> fixed = Collections.unmodifiableSet(lower);
        return () -> fixed;
    }

    /**
     * Case-insensitive membership.
     *
     * @param hosts Allowlist as supplied
     * @param lower Lower-cased realm host
     * @return True when listed
     */
    private static boolean listed(final Set<String> hosts, final String lower) {
        for (final String host : hosts) {
            if (host != null && host.trim().toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }
}
