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

import java.util.List;
import java.util.Set;

/**
 * TLS hardening helpers used by every keystore-bound listener. T-S06 of
 * {@code analysis/plan/v2/IMPLEMENTATION.md}: restrict the inbound TLS
 * surface to TLS 1.2 / 1.3 with Mozilla "intermediate" ciphers.
 *
 * <p>See {@code docs/security/tls.md} for the configuration rationale.</p>
 *
 * @since 2.2.0
 */
public final class TlsHardening {

    /**
     * TLS protocols permitted on the inbound surface. TLS 1.0 and 1.1
     * have been deprecated by the IETF (RFC 8996) since March 2021 and
     * are removed from every modern compliance baseline (PCI DSS 4.0,
     * SOC2 Type II, ISO 27001 Annex A).
     */
    public static final List<String> ENABLED_PROTOCOLS =
        List.of("TLSv1.2", "TLSv1.3");

    /**
     * Mozilla "intermediate" cipher suites for TLS 1.2. The TLS 1.3 set
     * is fixed by the spec (AEAD only) so we don't list those here —
     * Vert.x / OpenSSL pick from the in-spec set automatically.
     *
     * <p>The list is ordered by client preference: ECDHE first (forward
     * secrecy), AES-256 before AES-128 (defence-in-depth against future
     * cryptanalysis), GCM before SHA384 hash-based MAC ordering.</p>
     */
    public static final List<String> INTERMEDIATE_CIPHER_SUITES = List.of(
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
    );

    /**
     * Legacy / weak protocols that must NEVER be enabled. We make the
     * removal explicit because Vert.x defaults pull from the JVM, which
     * has historically shipped older protocols enabled on older Java
     * point releases.
     */
    private static final Set<String> WEAK_PROTOCOLS = Set.of(
        "SSLv2",
        "SSLv2Hello",
        "SSLv3",
        "TLSv1",
        "TLSv1.1"
    );

    private TlsHardening() {
        // utility class
    }

    /**
     * Apply TLS 1.2+ protocol restrictions and Mozilla "intermediate"
     * cipher suites to the supplied {@link HttpServerOptions}. Mutates
     * the argument and returns it for chaining.
     *
     * <p>Behaviour:</p>
     * <ol>
     *   <li>Strip every protocol Vert.x might have picked from the JVM
     *     default that isn't in {@link #ENABLED_PROTOCOLS}.</li>
     *   <li>Add TLS 1.2 and TLS 1.3 explicitly.</li>
     *   <li>Replace the cipher-suite list with
     *     {@link #INTERMEDIATE_CIPHER_SUITES} (TLS 1.2). TLS 1.3 cipher
     *     suites are fixed by the spec and selected automatically.</li>
     * </ol>
     *
     * @param options Vert.x server options to harden.
     * @return The same {@code options} for chaining.
     */
    public static HttpServerOptions apply(final HttpServerOptions options) {
        for (final String weak : WEAK_PROTOCOLS) {
            options.removeEnabledSecureTransportProtocol(weak);
        }
        for (final String allowed : ENABLED_PROTOCOLS) {
            options.addEnabledSecureTransportProtocol(allowed);
        }
        // The cipher list is replaced wholesale: addEnabledCipherSuite
        // is additive against the JVM default, so simply adding the
        // intermediate set would leave any weak default suite enabled.
        options.getEnabledCipherSuites().clear();
        for (final String suite : INTERMEDIATE_CIPHER_SUITES) {
            options.addEnabledCipherSuite(suite);
        }
        return options;
    }
}
