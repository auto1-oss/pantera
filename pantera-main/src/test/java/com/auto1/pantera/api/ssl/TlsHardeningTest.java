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
package com.auto1.pantera.api.ssl;

import io.vertx.core.http.HttpServerOptions;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsIterableContaining;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * Tests for {@link TlsHardening}.
 *
 * @since 2.2.0
 */
class TlsHardeningTest {

    @Test
    void enablesTls12AndTls13() {
        final HttpServerOptions opts = TlsHardening.apply(new HttpServerOptions());
        final Set<String> protocols = opts.getEnabledSecureTransportProtocols();
        MatcherAssert.assertThat(
            "TLS 1.2 must be permitted",
            protocols,
            new IsIterableContaining<>(new IsEqual<>("TLSv1.2"))
        );
        MatcherAssert.assertThat(
            "TLS 1.3 must be permitted",
            protocols,
            new IsIterableContaining<>(new IsEqual<>("TLSv1.3"))
        );
    }

    @Test
    void rejectsLegacyProtocols() {
        final HttpServerOptions opts = TlsHardening.apply(new HttpServerOptions());
        final Set<String> protocols = opts.getEnabledSecureTransportProtocols();
        MatcherAssert.assertThat(
            "TLSv1 must be excluded (RFC 8996)",
            protocols.contains("TLSv1"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "TLSv1.1 must be excluded (RFC 8996)",
            protocols.contains("TLSv1.1"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "SSLv3 must be excluded (POODLE)",
            protocols.contains("SSLv3"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "SSLv2 must be excluded",
            protocols.contains("SSLv2"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "SSLv2Hello must be excluded",
            protocols.contains("SSLv2Hello"),
            new IsEqual<>(false)
        );
    }

    @Test
    void installsIntermediateCipherSuites() {
        final HttpServerOptions opts = TlsHardening.apply(new HttpServerOptions());
        final Set<String> ciphers = opts.getEnabledCipherSuites();
        MatcherAssert.assertThat(
            "AES-256-GCM-SHA384 (TLS 1.3) must be enabled",
            ciphers,
            new IsIterableContaining<>(new IsEqual<>("TLS_AES_256_GCM_SHA384"))
        );
        MatcherAssert.assertThat(
            "Forward-secrecy ECDHE+AES-256-GCM (TLS 1.2) must be enabled",
            ciphers,
            new IsIterableContaining<>(new IsEqual<>("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"))
        );
    }

    @Test
    void protocolListIsExactlyTwoEntries() {
        // The TlsHardening constants document the contract; T-S06
        // explicitly says "TLS 1.2 minimum" and the docs say "two
        // protocols". Catch any drift in a later refactor.
        MatcherAssert.assertThat(
            TlsHardening.ENABLED_PROTOCOLS.size(),
            new IsEqual<>(2)
        );
    }

    @Test
    void apply_isIdempotent() {
        // Calling apply twice must produce the same set, not duplicate
        // entries. HttpServerOptions exposes a Set so duplicates would
        // be collapsed, but the cipher list is an ordered structure —
        // verify the count is stable.
        final int single = TlsHardening.apply(new HttpServerOptions())
            .getEnabledCipherSuites().size();
        final HttpServerOptions doubled = new HttpServerOptions();
        TlsHardening.apply(doubled);
        TlsHardening.apply(doubled);
        MatcherAssert.assertThat(
            "apply() must not duplicate cipher entries when called twice",
            doubled.getEnabledCipherSuites().size(),
            new IsEqual<>(single)
        );
    }
}
