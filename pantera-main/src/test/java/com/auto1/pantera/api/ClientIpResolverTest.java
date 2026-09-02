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
package com.auto1.pantera.api;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression tests for the audit source-address spoof: the API
 * trace handler took {@code X-Forwarded-For} (first entry) unconditionally
 * and wrote it to the MDC, from where every audited mutation persisted it
 * as {@code client.ip} — so any client could falsify the recorded source
 * address of its own actions. Forwarding headers must only be honoured
 * when the deployment says a trusted proxy fronts Pantera
 * ({@code trust_forwarded_headers}, default off).
 *
 * @since 2.2.9
 */
final class ClientIpResolverTest {

    @Test
    void forwardedHeadersAreIgnoredUnlessProxyIsTrusted() {
        final String ip = new ClientIpResolver(false).resolve(
            "203.0.113.9", "10.10.10.10, 203.0.113.9", "10.10.10.11"
        );
        MatcherAssert.assertThat(
            "with no trusted proxy the TCP peer address must be recorded, not the client-supplied header",
            ip, new IsEqual<>("203.0.113.9")
        );
    }

    @Test
    void firstForwardedEntryIsUsedWhenProxyIsTrusted() {
        final String ip = new ClientIpResolver(true).resolve(
            "10.0.0.2", "198.51.100.7, 10.0.0.2", null
        );
        MatcherAssert.assertThat(
            "behind a trusted proxy the first X-Forwarded-For entry is the client",
            ip, new IsEqual<>("198.51.100.7")
        );
    }

    @Test
    void realIpIsTheTrustedFallbackAndPeerTheLast() {
        MatcherAssert.assertThat(
            "X-Real-IP is used when X-Forwarded-For is absent behind a trusted proxy",
            new ClientIpResolver(true).resolve("10.0.0.2", null, "198.51.100.8"),
            new IsEqual<>("198.51.100.8")
        );
        MatcherAssert.assertThat(
            "the peer address is the last resort",
            new ClientIpResolver(true).resolve("10.0.0.2", null, null),
            new IsEqual<>("10.0.0.2")
        );
    }
}
