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
    void allowlistedHostBypassesTheDenyList() throws Exception {
        final EgressPolicy policy = new EgressPolicy(true, Set.of("registry.internal"));
        final Optional<String> rejection = policy.rejection(
            "registry.internal", InetAddress.getByName("10.0.0.5")
        );
        MatcherAssert.assertThat(
            "an explicitly allowlisted host must pass even when its address is in a denied range",
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
}
