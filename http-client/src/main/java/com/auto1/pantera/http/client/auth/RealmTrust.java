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
 * controlled, so a malicious or compromised upstream could name any host as
 * its realm and try to make Pantera send the configured Basic credentials
 * there. Credentials are released only to a realm that is the upstream host
 * itself, or a host in the {@code PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS}
 * allowlist. There is deliberately no parent-domain heuristic: on multi-tenant
 * suffix domains ({@code azurecr.io}, {@code s3.amazonaws.com},
 * {@code dkr.ecr.*.amazonaws.com}, {@code storage.googleapis.com},
 * {@code blob.core.windows.net}) a sibling subdomain belongs to a different
 * tenant, so trusting by shared parent domain would leak the credentials to an
 * attacker-controlled sibling host. A cross-subdomain realm that is genuinely
 * required (e.g. Docker Hub's {@code auth.docker.io} for
 * {@code registry-1.docker.io}) must be added to the allowlist explicitly.
 * Every other realm gets an anonymous token request — most registries grant
 * pull tokens anonymously, and a denied anonymous request fails safely.</p>
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
     * <p>Credentials are released only to a realm host that exactly equals the
     * configured upstream host, or that is present in the explicit
     * credential-host allowlist. There is no parent-domain matching: a sibling
     * subdomain on a multi-tenant suffix domain belongs to a different tenant,
     * so releasing credentials to it would leak them. An operator who needs a
     * redirect/realm host trusted adds it to
     * {@code PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS}.</p>
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
        return RealmTrust.listed(this.allowed.get(), lower)
            || (this.upstream != null && this.upstream.equals(lower));
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
