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
package com.auto1.pantera.settings.policy;

import com.auto1.pantera.http.client.egress.EgressPolicy;
import com.auto1.pantera.http.client.egress.EgressSettingsRegistry;
import java.util.Set;

/**
 * Validated outbound egress policy: whether private/loopback/link-local
 * destinations are refused, the hosts exempt from that refusal, and the
 * hosts a bearer-token realm may live on before upstream credentials are
 * released to it. Cloud-metadata destinations stay denied regardless.
 *
 * @since 2.2.9
 */
public final class EgressConfig {

    /**
     * Refuse private destinations.
     */
    private final boolean blockPrivate;

    /**
     * Hosts exempt from the private-destination refusal (lower-cased).
     */
    private final Set<String> allowHosts;

    /**
     * Hosts trusted to receive upstream credentials (lower-cased).
     */
    private final Set<String> credentialAllowHosts;

    /**
     * Ctor; validates every host entry.
     * @param blockPrivate Refuse private destinations
     * @param allowHosts Comma-separated hosts, may be empty
     * @param credentialAllowHosts Comma-separated hosts, may be empty
     */
    public EgressConfig(
        final boolean blockPrivate, final String allowHosts, final String credentialAllowHosts
    ) {
        this.blockPrivate = blockPrivate;
        this.allowHosts = EgressConfig.validHosts("egress_allow_hosts", allowHosts);
        this.credentialAllowHosts = EgressConfig.validHosts(
            "upstream_credential_allow_hosts", credentialAllowHosts
        );
    }

    /**
     * Documented defaults: not strict, no exemptions.
     * @return Config
     */
    public static EgressConfig defaults() {
        return new EgressConfig(false, "", "");
    }

    /**
     * Refuse private destinations.
     * @return Flag
     */
    public boolean blockPrivate() {
        return this.blockPrivate;
    }

    /**
     * Hosts exempt from the refusal.
     * @return Lower-cased hosts
     */
    public Set<String> allowHosts() {
        return this.allowHosts;
    }

    /**
     * Hosts trusted with upstream credentials.
     * @return Lower-cased hosts
     */
    public Set<String> credentialAllowHosts() {
        return this.credentialAllowHosts;
    }

    /**
     * The address policy enforced on every outbound connect.
     * @return Policy
     */
    public EgressPolicy policy() {
        return new EgressPolicy(this.blockPrivate, this.allowHosts);
    }

    /**
     * Comma-joined form of a host set, for the admin API.
     * @param hosts Hosts
     * @return Comma-separated, sorted
     */
    public static String join(final Set<String> hosts) {
        return String.join(",", new java.util.TreeSet<>(hosts));
    }

    /**
     * Parse and validate a comma-separated host list.
     * @param key Settings key, for the error message
     * @param spec Raw list, null treated as empty
     * @return Lower-cased hosts
     */
    private static Set<String> validHosts(final String key, final String spec) {
        final Set<String> hosts = EgressSettingsRegistry.parseHosts(spec == null ? "" : spec);
        for (final String host : hosts) {
            if (!host.matches("[a-z0-9._\\-\\[\\]:]+") || host.startsWith(".") || host.endsWith(".")) {
                throw new IllegalArgumentException(key + " entry is not a host name: " + host);
            }
        }
        return hosts;
    }
}
