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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
     * Literal spellings of the cloud metadata service addresses, denied by
     * name so they never even reach the resolver: AWS/GCP/Azure/OCI IPv4
     * ({@code 169.254.169.254}), AWS IMDS over IPv6 ({@code fd00:ec2::254}),
     * GCP metadata over IPv6 ({@code fd20:ce::254}) and Alibaba Cloud
     * ({@code 100.100.100.200}). The IPv6 ones are unique-local, not
     * link-local, and the Alibaba one is in the shared address space, so
     * none of them is caught by the link-local rule.
     */
    private static final List<String> METADATA_LITERALS = List.of(
        "169.254.169.254", // NOPMD AvoidUsingHardCodedIP - the cloud metadata address is exactly the literal this policy exists to deny
        "fd00:ec2::254", // NOPMD AvoidUsingHardCodedIP - AWS IMDS IPv6 address, denied by this policy
        "fd20:ce::254", // NOPMD AvoidUsingHardCodedIP - GCP metadata IPv6 address, denied by this policy
        "100.100.100.200" // NOPMD AvoidUsingHardCodedIP - Alibaba Cloud metadata address, denied by this policy
    );

    /**
     * The metadata addresses parsed, so any spelling of them (expanded
     * IPv6, IPv4-mapped IPv6) is recognised once resolved; denied even if a
     * deployment somehow whitelists link-local ranges.
     */
    private static final Set<InetAddress> METADATA_ADDRESSES = EgressPolicy.parse(METADATA_LITERALS);

    /**
     * Also deny loopback and private ranges: RFC1918 and fec0::/10
     * site-local, fc00::/7 unique-local and 100.64.0.0/10 shared address
     * space.
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
                lower.add(EgressPolicy.normalize(host.trim()));
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
        final String name = EgressPolicy.normalize(host);
        if (this.allowed.contains(name)) {
            return Optional.empty();
        }
        if (METADATA_HOSTS.contains(name) || METADATA_LITERALS.contains(name)
            || EgressPolicy.literal(name).map(METADATA_ADDRESSES::contains).orElse(false)) {
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
        if (host != null && this.allowed.contains(EgressPolicy.normalize(host))) {
            return Optional.empty();
        }
        if (METADATA_ADDRESSES.contains(address)) {
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
        if (this.strict && EgressPolicy.isPrivate(address)) {
            return Optional.of("private address (strict egress policy)");
        }
        return Optional.empty();
    }

    /**
     * Canonical host form for comparisons: lower-case, IPv6 brackets
     * removed, and the DNS root dot dropped ({@code metadata.google.internal.}
     * is the same host as {@code metadata.google.internal}).
     *
     * @param host Host from a URI or a config entry
     * @return Normalised host
     */
    private static String normalize(final String host) {
        String name = host.toLowerCase(Locale.ROOT);
        if (name.length() > 1 && name.charAt(0) == '[' && name.charAt(name.length() - 1) == ']') {
            name = name.substring(1, name.length() - 1);
        }
        while (name.length() > 1 && name.charAt(name.length() - 1) == '.') {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    /**
     * Parse an IP literal WITHOUT any DNS lookup: only strings made of hex
     * characters, dots and colons are handed to {@link InetAddress#getByName},
     * which parses such literals locally.
     *
     * @param host Normalised host
     * @return Parsed address, or empty for a hostname / unparsable literal
     */
    private static Optional<InetAddress> literal(final String host) {
        final boolean ipv6 = host.indexOf(':') >= 0
            && host.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.');
        final boolean ipv4 = !host.isEmpty()
            && host.chars().allMatch(c -> Character.isDigit(c) || c == '.');
        Optional<InetAddress> result = Optional.empty();
        if (ipv6 || ipv4) {
            try {
                result = Optional.of(InetAddress.getByName(host));
            } catch (final UnknownHostException ex) {
                result = Optional.empty();
            }
        }
        return result;
    }

    /**
     * Private (non-internet) unicast ranges denied in strict mode:
     * RFC1918 / fec0::/10 site-local, fc00::/7 unique-local (RFC 4193) and
     * 100.64.0.0/10 shared address space (RFC 6598). Java's
     * {@link InetAddress#isSiteLocalAddress()} covers only the first.
     *
     * @param address Address
     * @return True when the address is in a private range
     */
    private static boolean isPrivate(final InetAddress address) {
        final byte[] raw = address.getAddress();
        final boolean ula = address instanceof Inet6Address && (raw[0] & 0xFE) == 0xFC;
        final boolean shared = address instanceof Inet4Address
            && (raw[0] & 0xFF) == 100 && (raw[1] & 0xC0) == 64;
        return address.isSiteLocalAddress() || ula || shared;
    }

    /**
     * Parse the built-in literals.
     *
     * @param literals IP literals
     * @return Parsed addresses
     */
    private static Set<InetAddress> parse(final List<String> literals) {
        final Set<InetAddress> out = new HashSet<>();
        for (final String lit : literals) {
            out.add(
                EgressPolicy.literal(lit).orElseThrow(
                    () -> new IllegalStateException("Bad built-in IP literal: " + lit)
                )
            );
        }
        return Collections.unmodifiableSet(out);
    }
}
