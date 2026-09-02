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
package com.auto1.pantera.http.client.egress;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Outbound-destination policy for every HTTP request Pantera makes on its
 * own behalf: proxy upstream fetches, upstream index/metadata links, Bearer
 * token realms, repository {@code remotes[].url}, storage-alias endpoints.
 *
 * <p>Several of those destinations are derived from lower-trust input (an
 * upstream index can link anywhere; a {@code WWW-Authenticate} challenge
 * names its own realm; a repository-config writer types the URL). Before
 * 2.2.9 no destination was ever checked, so any of them could steer a
 * server-side request at the cloud metadata service ({@code
 * 169.254.169.254}) or a link-local address. This policy denies those
 * ranges unconditionally; loopback and RFC1918 stay ALLOWED by default
 * because the local dev stack, unit-test upstreams and private registries
 * legitimately live there — a deployment can deny them too with
 * {@code strict} ({@code PANTERA_EGRESS_BLOCK_PRIVATE=true}) and whitelist
 * specific hosts with {@code PANTERA_EGRESS_ALLOW_HOSTS}.</p>
 *
 * <p>Enforced in two places: at repository-config write time (full
 * resolution on a worker thread) and inside the Jetty client's socket
 * address resolver on every connect — including redirect hops, and after
 * DNS resolution, so a hostname that resolves into a denied range is
 * refused as well (see {@link EgressFilteringResolver}).</p>
 *
 * @since 2.2.9
 */
public final class EgressPolicy {

    /**
     * Hostnames that name the cloud metadata service on the platforms
     * Pantera runs on; denied by name so a request never even resolves.
     */
    private static final Set<String> METADATA_HOSTS = Set.of(
        "metadata.google.internal",
        "metadata",
        "instance-data",
        "instance-data.ec2.internal"
    );

    /**
     * The AWS/GCP/Azure metadata address; denied even if a deployment
     * somehow whitelists link-local ranges.
     */
    private static final String METADATA_V4 = "169.254.169.254"; // NOPMD AvoidUsingHardCodedIP - the cloud metadata address is exactly the literal this policy exists to deny

    /**
     * Also deny loopback and site-local (RFC1918 / fc00::/7) addresses.
     */
    private final boolean strict;

    /**
     * Hosts (lower-case) exempt from the deny list.
     */
    private final Set<String> allowed;

    /**
     * Ctor.
     *
     * @param strict Deny loopback + site-local too
     * @param allowed Hostnames exempt from the deny list
     */
    public EgressPolicy(final boolean strict, final Set<String> allowed) {
        this.strict = strict;
        final Set<String> lower = new HashSet<>();
        for (final String host : allowed) {
            if (host != null && !host.isBlank()) {
                lower.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.allowed = Collections.unmodifiableSet(lower);
    }

    /**
     * Default policy: link-local, any-local, multicast and the metadata
     * service denied; loopback and private ranges allowed.
     *
     * @return Default policy
     */
    public static EgressPolicy defaults() {
        return new EgressPolicy(false, Set.of());
    }

    /**
     * Policy from the runtime environment: {@code PANTERA_EGRESS_BLOCK_PRIVATE}
     * (default {@code false}) and {@code PANTERA_EGRESS_ALLOW_HOSTS}
     * (comma-separated hostnames, default empty).
     *
     * @return Policy configured from env
     */
    public static EgressPolicy fromEnvironment() {
        final boolean strict = Boolean.parseBoolean(
            System.getenv().getOrDefault("PANTERA_EGRESS_BLOCK_PRIVATE", "false")
        );
        final String hosts = System.getenv().getOrDefault("PANTERA_EGRESS_ALLOW_HOSTS", "");
        return new EgressPolicy(strict, new HashSet<>(Arrays.asList(hosts.split(","))));
    }

    /**
     * Whether this policy denies loopback and site-local addresses too.
     *
     * @return {@code true} in strict mode
     */
    public boolean strict() {
        return this.strict;
    }

    /**
     * Name-level check that needs no DNS: metadata-service hostnames are
     * refused outright. Everything else passes here and is judged by
     * {@link #rejection(String, InetAddress)} once resolved.
     *
     * @param host Hostname or IP literal from the URI
     * @return Reason the host is denied, or empty
     */
    public Optional<String> hostRejection(final String host) {
        if (host == null) {
            return Optional.of("missing host");
        }
        final String lower = host.toLowerCase(Locale.ROOT);
        if (this.allowed.contains(lower)) {
            return Optional.empty();
        }
        if (METADATA_HOSTS.contains(lower) || METADATA_V4.equals(lower)) {
            return Optional.of("cloud metadata service");
        }
        return Optional.empty();
    }

    /**
     * Address-level check without a host name (literal or already
     * resolved).
     *
     * @param address Resolved address
     * @return Reason the address is denied, or empty
     */
    public Optional<String> rejection(final InetAddress address) {
        return this.rejection(null, address);
    }

    /**
     * Address-level check. An allowlisted host passes regardless of where
     * it resolves; otherwise the address must not fall in a denied range.
     *
     * @param host Hostname the address was resolved from (nullable)
     * @param address Resolved address
     * @return Reason the address is denied, or empty
     */
    public Optional<String> rejection(final String host, final InetAddress address) {
        if (host != null && this.allowed.contains(host.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        if (METADATA_V4.equals(address.getHostAddress())) {
            return Optional.of("cloud metadata service");
        }
        if (address.isLinkLocalAddress()) {
            return Optional.of("link-local address");
        }
        if (address.isAnyLocalAddress()) {
            return Optional.of("any-local address");
        }
        if (address.isMulticastAddress()) {
            return Optional.of("multicast address");
        }
        if (this.strict && address.isLoopbackAddress()) {
            return Optional.of("loopback address (strict egress policy)");
        }
        if (this.strict && address.isSiteLocalAddress()) {
            return Optional.of("private address (strict egress policy)");
        }
        return Optional.empty();
    }
}
