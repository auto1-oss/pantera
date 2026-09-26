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
import java.util.Optional;
import java.util.Set;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression tests for {@link EgressPolicy}: Pantera builds
 * outbound clients to destinations derived from lower-trust inputs (proxy
 * upstream index links, composer {@code dist.url}, Bearer challenge realms,
 * repository {@code remotes[].url}). Before 2.2.9 no destination was ever
 * checked, so any of those inputs could steer a server-side request at the
 * cloud metadata service or a link-local address.
 *
 * @since 2.2.9
 */
final class EgressPolicyTest {

    @Test
    void cloudMetadataAddressIsDeniedByDefault() throws Exception {
        final EgressPolicy policy = EgressPolicy.defaults();
        MatcherAssert.assertThat(
            "169.254.169.254 (cloud metadata) must be denied by the default policy",
            policy.rejection(InetAddress.getByName("169.254.169.254")).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void linkLocalAnyLocalAndMulticastAreDeniedByDefault() throws Exception {
        final EgressPolicy policy = EgressPolicy.defaults();
        MatcherAssert.assertThat(
            "IPv6 link-local must be denied",
            policy.rejection(InetAddress.getByName("fe80::1")).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "any-local 0.0.0.0 must be denied",
            policy.rejection(InetAddress.getByName("0.0.0.0")).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "multicast must be denied",
            policy.rejection(InetAddress.getByName("224.0.0.1")).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void loopbackAndPrivateRangesAreAllowedByDefaultButDeniedInStrictMode() throws Exception {
        final EgressPolicy lenient = EgressPolicy.defaults();
        final EgressPolicy strict = new EgressPolicy(true, Set.of());
        MatcherAssert.assertThat(
            "loopback is allowed by default (local dev stack / test upstreams)",
            lenient.rejection(InetAddress.getByName("127.0.0.1")).isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "RFC1918 is allowed by default (private registries live there)",
            lenient.rejection(InetAddress.getByName("10.1.2.3")).isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "strict mode denies loopback",
            strict.rejection(InetAddress.getByName("127.0.0.1")).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "strict mode denies RFC1918",
            strict.rejection(InetAddress.getByName("10.1.2.3")).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void publicAddressIsAllowed() throws Exception {
        MatcherAssert.assertThat(
            "a public address must pass",
            EgressPolicy.defaults().rejection(InetAddress.getByName("93.184.216.34")).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void allowlistedHostBypassesTheStrictPrivateRefusal() throws Exception {
        final EgressPolicy policy = new EgressPolicy(true, Set.of("registry.internal"));
        final Optional<String> rejection = policy.rejection(
            "registry.internal", InetAddress.getByName("10.0.0.5")
        );
        MatcherAssert.assertThat(
            "an explicitly allowlisted host must pass even when its address is in a strict-mode private range",
            rejection.isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void metadataHostnamesAreDeniedWithoutResolution() {
        final EgressPolicy policy = EgressPolicy.defaults();
        MatcherAssert.assertThat(
            "the GCE metadata hostname must be denied by name",
            policy.hostRejection("metadata.google.internal").isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "an ordinary hostname passes the name check (address check happens on resolve)",
            policy.hostRejection("registry.npmjs.org").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void allowlistedHostResolvingToMetadataIsStillDenied() throws Exception {
        final EgressPolicy policy = new EgressPolicy(false, Set.of("mirror.corp"));
        MatcherAssert.assertThat(
            "an allowlisted host rebound to 169.254.169.254 must be denied",
            policy.rejection("mirror.corp", InetAddress.getByName("169.254.169.254")),
            new IsEqual<>(Optional.of("cloud metadata service"))
        );
        MatcherAssert.assertThat(
            "an allowlisted host rebound to fd00:ec2::254 must be denied",
            policy.rejection("mirror.corp", InetAddress.getByName("fd00:ec2::254")),
            new IsEqual<>(Optional.of("cloud metadata service"))
        );
        MatcherAssert.assertThat(
            "an allowlisted host rebound to link-local must be denied",
            policy.rejection("mirror.corp", InetAddress.getByName("169.254.10.1")),
            new IsEqual<>(Optional.of("link-local address"))
        );
        MatcherAssert.assertThat(
            "an allowlisted host rebound to any-local must be denied",
            policy.rejection("mirror.corp", InetAddress.getByName("0.0.0.0")),
            new IsEqual<>(Optional.of("any-local address"))
        );
    }

    @Test
    void allowlistingTheMetadataServiceDoesNotOpenIt() throws Exception {
        final EgressPolicy policy = new EgressPolicy(
            true, Set.of("metadata.google.internal", "169.254.169.254", "[fd00:ec2::254]")
        );
        MatcherAssert.assertThat(
            "allowlisting metadata.google.internal must not exempt it from the name check",
            policy.hostRejection("metadata.google.internal"),
            new IsEqual<>(Optional.of("cloud metadata service"))
        );
        MatcherAssert.assertThat(
            "allowlisting 169.254.169.254 must not exempt it from the name check",
            policy.hostRejection("169.254.169.254"),
            new IsEqual<>(Optional.of("cloud metadata service"))
        );
        MatcherAssert.assertThat(
            "allowlisting 169.254.169.254 must not exempt it from the address check",
            policy.rejection("169.254.169.254", InetAddress.getByName("169.254.169.254")),
            new IsEqual<>(Optional.of("cloud metadata service"))
        );
        MatcherAssert.assertThat(
            "allowlisting [fd00:ec2::254] must not exempt it from the literal check",
            policy.literalRejection("[fd00:ec2::254]"),
            new IsEqual<>(Optional.of("cloud metadata service"))
        );
    }

    @Test
    void allowlistStillExemptsLoopbackInStrictMode() throws Exception {
        final EgressPolicy policy = new EgressPolicy(true, Set.of("localhost"));
        MatcherAssert.assertThat(
            "an allowlisted host on loopback passes in strict mode",
            policy.rejection("localhost", InetAddress.getByName("127.0.0.1")),
            new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "a host that is not allowlisted on loopback is refused in strict mode",
            policy.rejection("other.local", InetAddress.getByName("127.0.0.1")),
            new IsEqual<>(Optional.of("loopback address (strict egress policy)"))
        );
    }

    @Test
    void ipv6AndAlibabaMetadataAddressesAreDeniedByDefault() throws Exception {
        final EgressPolicy policy = EgressPolicy.defaults();
        MatcherAssert.assertThat(
            "the AWS IMDS IPv6 address must be denied",
            policy.rejection(InetAddress.getByName("fd00:ec2::254")).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the GCP metadata IPv6 address must be denied",
            policy.rejection(InetAddress.getByName("fd20:ce::254")).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the Alibaba Cloud metadata address must be denied",
            policy.rejection(InetAddress.getByName("100.100.100.200")).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void metadataLiteralsAreDeniedByNameInEverySpelling() {
        final EgressPolicy policy = EgressPolicy.defaults();
        MatcherAssert.assertThat(
            "a bracketed IPv6 metadata literal must be denied by name",
            policy.hostRejection("[fd00:ec2::254]").isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the GCP IPv6 metadata literal must be denied by name",
            policy.hostRejection("fd20:ce::254").isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the Alibaba metadata literal must be denied by name",
            policy.hostRejection("100.100.100.200").isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void trailingDotAndCaseDoNotBypassTheMetadataHostnameCheck() {
        final EgressPolicy policy = EgressPolicy.defaults();
        MatcherAssert.assertThat(
            "a trailing-dot FQDN names the same metadata host",
            policy.hostRejection("metadata.google.internal.").isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "upper case and a trailing dot together must still be denied",
            policy.hostRejection("Metadata.Google.Internal.").isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void trailingDotAllowlistEntryMatchesTheBareHost() throws Exception {
        final EgressPolicy policy = new EgressPolicy(true, Set.of("registry.internal."));
        MatcherAssert.assertThat(
            "an allowlist entry and a host differing only by the root dot are the same host",
            policy.rejection("registry.internal", InetAddress.getByName("10.0.0.5")).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void uniqueLocalAndSharedAddressSpaceAreDeniedOnlyInStrictMode() throws Exception {
        final EgressPolicy lenient = EgressPolicy.defaults();
        final EgressPolicy strict = new EgressPolicy(true, Set.of());
        MatcherAssert.assertThat(
            "fc00::/7 is allowed by default",
            lenient.rejection(InetAddress.getByName("fd12:3456::1")).isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "100.64.0.0/10 is allowed by default",
            lenient.rejection(InetAddress.getByName("100.64.1.1")).isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "strict mode must deny the fc00::/7 unique-local range",
            strict.rejection(InetAddress.getByName("fd12:3456::1")).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "strict mode must deny the fc00::/7 range in its fc half too",
            strict.rejection(InetAddress.getByName("fc00::1")).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "strict mode must deny the 100.64.0.0/10 shared address space",
            strict.rejection(InetAddress.getByName("100.127.255.254")).isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "100.128.0.1 is outside 100.64.0.0/10 and stays allowed in strict mode",
            strict.rejection(InetAddress.getByName("100.128.0.1")).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void malformedNumericHostsNeverReachTheSystemResolver() throws Exception {
        MatcherAssert.assertThat(
            "the recording resolver provider must be installed for this test to mean anything",
            InetAddress.getByName(RecordingResolverProvider.PROBE).isLoopbackAddress(),
            new IsEqual<>(true)
        );
        final EgressPolicy policy = new EgressPolicy(false, Set.of());
        final String[] hosts = {
            "1.2.3.4.5", "999.1.1.1", "1.2.3", "1..2.3", "1.2.3.4.", "01.2.3.4",
            "\u0661\u0666\u0669.\u0662\u0665\u0664.\u0661\u0666\u0669.\u0662\u0665\u0664",
            "fe80::zz", "::1%eth0", "1:2:3:4:5:6:7:8:9",
        };
        for (final String host : hosts) {
            MatcherAssert.assertThat(
                "hostRejection must not deny " + host,
                policy.hostRejection(host), new IsEqual<>(Optional.empty())
            );
            MatcherAssert.assertThat(
                "literalRejection must not deny " + host,
                policy.literalRejection(host), new IsEqual<>(Optional.empty())
            );
        }
        for (final String host : hosts) {
            MatcherAssert.assertThat(
                "no DNS lookup may be made for " + host,
                RecordingResolverProvider.LOOKUPS.contains(EgressPolicyTest.lower(host))
                    || RecordingResolverProvider.LOOKUPS.contains(host),
                new IsEqual<>(false)
            );
        }
    }

    @Test
    void literalRejectionJudgesStrictLiteralsOnly() {
        final EgressPolicy policy = new EgressPolicy(true, Set.of());
        MatcherAssert.assertThat(
            "an IPv4 metadata literal is denied",
            policy.literalRejection("169.254.169.254"),
            new IsEqual<>(Optional.of("cloud metadata service"))
        );
        MatcherAssert.assertThat(
            "a bracketed link-local IPv6 literal is denied",
            policy.literalRejection("[fe80::1]"),
            new IsEqual<>(Optional.of("link-local address"))
        );
        MatcherAssert.assertThat(
            "a private IPv4 literal is denied in strict mode",
            policy.literalRejection("10.0.0.1"),
            new IsEqual<>(Optional.of("private address (strict egress policy)"))
        );
        MatcherAssert.assertThat(
            "a hostname is not a literal and is left to the resolver",
            policy.literalRejection("repo.example.com"),
            new IsEqual<>(Optional.empty())
        );
    }

    private static String lower(final String host) {
        return host.toLowerCase(java.util.Locale.ROOT);
    }
}
