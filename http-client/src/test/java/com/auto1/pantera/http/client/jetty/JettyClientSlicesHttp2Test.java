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
package com.auto1.pantera.http.client.jetty;

import com.auto1.pantera.http.client.HttpClientSettings;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests verifying that {@link JettyClientSlices} honours
 * {@link JettyClientSlices.HttpProtocol} when constructing the
 * underlying Jetty {@link HttpClient}'s transport.
 *
 * <ul>
 *   <li>{@code H1} → {@link HttpClientTransportOverHTTP} (pure HTTP/1.1)</li>
 *   <li>{@code H2}, {@code AUTO} → {@link HttpClientTransportDynamic}
 *       (ALPN-negotiated h2 with h1.1 fallback)</li>
 * </ul>
 *
 * <p>The {@code h2MaxPoolSize} primitive maps to Jetty's
 * {@code HttpClient.setMaxConnectionsPerDestination(...)} so a small
 * value (1) keeps a single multiplexed h2 connection per upstream
 * which is the recommended HTTP/2 deployment.</p>
 */
final class JettyClientSlicesHttp2Test {

    @Test
    void h1ProtocolUsesPureHttp1Transport() {
        final JettyClientSlices clients = new JettyClientSlices(
            new HttpClientSettings(),
            JettyClientSlices.HttpProtocol.H1,
            8,
            100
        );
        try {
            final HttpClient client = clients.httpClient();
            final HttpClientTransport transport = client.getTransport();
            Assertions.assertTrue(
                transport instanceof HttpClientTransportOverHTTP,
                "H1 protocol should produce HttpClientTransportOverHTTP, got "
                    + transport.getClass().getName()
            );
            Assertions.assertEquals(
                8,
                client.getMaxConnectionsPerDestination(),
                "h2MaxPoolSize must be honoured even for H1 transport (it maps to per-destination cap)"
            );
        } finally {
            clients.stop();
        }
    }

    @Test
    void h2ProtocolUsesDynamicTransportWithAlpn() {
        final JettyClientSlices clients = new JettyClientSlices(
            new HttpClientSettings(),
            JettyClientSlices.HttpProtocol.H2,
            1,
            128
        );
        try {
            final HttpClient client = clients.httpClient();
            final HttpClientTransport transport = client.getTransport();
            Assertions.assertTrue(
                transport instanceof HttpClientTransportDynamic,
                "H2 protocol should produce HttpClientTransportDynamic for ALPN h2/h1.1, got "
                    + transport.getClass().getName()
            );
            Assertions.assertEquals(
                1,
                client.getMaxConnectionsPerDestination(),
                "h2MaxPoolSize=1 means a single multiplexed h2 connection per upstream"
            );
        } finally {
            clients.stop();
        }
    }

    @Test
    void autoProtocolUsesDynamicTransport() {
        final JettyClientSlices clients = new JettyClientSlices(
            new HttpClientSettings(),
            JettyClientSlices.HttpProtocol.AUTO,
            2,
            64
        );
        try {
            final HttpClient client = clients.httpClient();
            Assertions.assertTrue(
                client.getTransport() instanceof HttpClientTransportDynamic,
                "AUTO protocol should use HttpClientTransportDynamic so ALPN can pick"
            );
        } finally {
            clients.stop();
        }
    }

    @Test
    void defaultConstructorPreservesLegacyBehaviour() {
        // Existing code paths (RepositorySlices today) call new JettyClientSlices(settings).
        // Until task 9 wires hot-reload, the legacy ctor must not change behaviour.
        // The legacy default is the equivalent of HttpTuning.defaults() = (H2, 1, 100),
        // i.e. the same dynamic transport with the small connection pool.
        final JettyClientSlices clients = new JettyClientSlices(new HttpClientSettings());
        try {
            final HttpClient client = clients.httpClient();
            Assertions.assertTrue(
                client.getTransport() instanceof HttpClientTransportDynamic,
                "Legacy ctor must default to ALPN-negotiated transport"
            );
        } finally {
            clients.stop();
        }
    }

    @Test
    void clientStartsAndStopsWithDynamicTransport() throws Exception {
        // Smoke test: the transport must actually be wirable into Jetty's
        // lifecycle. A misconfigured ClientConnectionFactory list will throw
        // on start().
        final JettyClientSlices clients = new JettyClientSlices(
            new HttpClientSettings(),
            JettyClientSlices.HttpProtocol.H2,
            1,
            100
        );
        clients.start();
        try {
            Assertions.assertTrue(clients.isOperational());
            Assertions.assertTrue(clients.httpClient().isStarted());
        } finally {
            clients.stop();
        }
    }
}
