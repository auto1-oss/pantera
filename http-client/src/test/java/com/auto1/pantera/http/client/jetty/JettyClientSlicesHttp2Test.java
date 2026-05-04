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
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
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

    @Test
    void h2MultiplexingLimitReachesUnderlyingHttp2Client() throws Exception {
        // Regression guard: the 4-arg ctor's h2MultiplexingLimit MUST plumb
        // through to the underlying HTTP2Client.setMaxLocalStreams(...) — the
        // existing tests only checked the transport class, which would happily
        // pass even if the multiplexing knob was silently dropped on the floor.
        final JettyClientSlices clients = new JettyClientSlices(
            new HttpClientSettings(),
            JettyClientSlices.HttpProtocol.H2,
            /* h2MaxPoolSize */ 1,
            /* h2MultiplexingLimit */ 73
        );
        try {
            final HttpClient client = clients.httpClient();
            final HttpClientTransport transport = client.getTransport();
            Assertions.assertTrue(
                transport instanceof HttpClientTransportDynamic,
                "expected HttpClientTransportDynamic for H2 protocol, got "
                    + transport.getClass()
            );
            // Walk the bean tree of the dynamic transport to find the
            // HTTP2Client. Jetty 12 registers the HTTP/2 ClientConnectionFactory
            // (and its HTTP2Client) as managed beans of the transport.
            final HTTP2Client found = findFirstBean(
                (ContainerLifeCycle) transport, HTTP2Client.class
            );
            Assertions.assertNotNull(
                found, "HTTP2Client bean not found under transport"
            );
            Assertions.assertEquals(
                73, found.getMaxLocalStreams(),
                "max-local-streams should match the multiplexing limit passed to ctor"
            );
            Assertions.assertEquals(
                0, found.getMaxConcurrentPushedStreams(),
                "server push should be disabled"
            );
        } finally {
            clients.stop();
        }
    }

    /**
     * Recursively search a Jetty {@link ContainerLifeCycle} bean tree for the
     * first bean assignable to {@code type}. Used to assert that the
     * HTTP/2 multiplexing knob actually reaches the underlying
     * {@link HTTP2Client} — direct field reflection would be more brittle.
     */
    private static <T> T findFirstBean(
        final ContainerLifeCycle root, final Class<T> type
    ) {
        for (final Object bean : root.getBeans()) {
            if (type.isInstance(bean)) {
                return type.cast(bean);
            }
            if (bean instanceof ContainerLifeCycle child) {
                final T nested = findFirstBean(child, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
