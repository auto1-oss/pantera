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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exploit-regression tests for the egress policy when an outbound HTTP
 * proxy is configured. Jetty then resolves only the PROXY's address, so
 * before the fix the request target — and every redirect hop — was never
 * checked. Uses a real Jetty {@link HttpClient} with an {@link HttpProxy}
 * on a local port and a stub DNS; no external network.
 *
 * @since 2.2.9
 */
@Timeout(30)
final class EgressThroughOutboundProxyTest {

    /**
     * Hosts handed to the stub DNS, in order.
     */
    private final List<String> lookups = Collections.synchronizedList(new ArrayList<>());

    /**
     * Client under test.
     */
    private HttpClient client;

    /**
     * Fake proxy (redirect test only).
     */
    private ServerSocket proxy;

    @AfterEach
    void tearDown() throws Exception {
        if (this.client != null) {
            this.client.stop();
        }
        if (this.proxy != null) {
            this.proxy.close();
        }
    }

    @Test
    void metadataTargetIsRefusedEvenThroughTheProxy() throws Exception {
        this.start(EgressThroughOutboundProxyTest.closedPort());
        MatcherAssert.assertThat(
            this.failure("http://169.254.169.254/latest/meta-data/"),
            new IsInstanceOf(EgressDeniedException.class)
        );
    }

    @Test
    void tunnelledHttpsTargetResolvingToMetadataIsRefused() throws Exception {
        this.start(EgressThroughOutboundProxyTest.closedPort());
        MatcherAssert.assertThat(
            "a CONNECT-tunnelled target that resolves into a denied range must be refused",
            this.failure("https://rebind.example/"),
            new IsInstanceOf(EgressDeniedException.class)
        );
        MatcherAssert.assertThat(
            "the target must have been resolved and checked, not only the proxy",
            this.lookups.contains("rebind.example"), new IsEqual<>(true)
        );
    }

    @Test
    void allowedTargetStillGoesToTheProxy() throws Exception {
        this.start(EgressThroughOutboundProxyTest.closedPort());
        MatcherAssert.assertThat(
            "a public target passes the policy and the connect goes to the (closed) proxy",
            this.failure("http://public.example/"),
            new IsNot<>(new IsInstanceOf(EgressDeniedException.class))
        );
        MatcherAssert.assertThat(
            "the proxy address must be resolved after the target check",
            this.lookups, new IsEqual<>(List.of("public.example", "127.0.0.1"))
        );
    }

    @Test
    void targetOnlyTheProxyCanResolveIsLeftToTheProxy() throws Exception {
        this.start(EgressThroughOutboundProxyTest.closedPort());
        MatcherAssert.assertThat(
            "a target Pantera's own DNS cannot resolve is not refused by Pantera",
            this.failure("http://pkg.unresolvable/"),
            new IsNot<>(new IsInstanceOf(EgressDeniedException.class))
        );
        MatcherAssert.assertThat(
            "the connect must still go to the proxy after the failed target lookup",
            this.lookups, new IsEqual<>(List.of("pkg.unresolvable", "127.0.0.1"))
        );
    }

    @Test
    void redirectHopThroughTheProxyIsChecked() throws Exception {
        this.proxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final Thread server = new Thread(this::redirectForever, "fake-proxy");
        server.setDaemon(true);
        server.start();
        this.start(this.proxy.getLocalPort());
        MatcherAssert.assertThat(
            "a redirect hop into a denied range must be refused behind a proxy too",
            this.failure("http://public.example/start"),
            new IsInstanceOf(EgressDeniedException.class)
        );
        MatcherAssert.assertThat(
            "the redirect target must have been resolved and checked",
            this.lookups.contains("rebind.example"), new IsEqual<>(true)
        );
    }

    /**
     * Start the client with a proxy on the given loopback port.
     *
     * @param port Proxy port
     * @throws Exception On start failure
     */
    private void start(final int port) throws Exception {
        this.client = new HttpClient();
        this.client.getProxyConfiguration().addProxy(
            new HttpProxy(new Origin.Address("127.0.0.1", port), false)
        );
        this.client.setFollowRedirects(true);
        this.client.setSocketAddressResolver(
            new EgressFilteringResolver(EgressPolicy.defaults(), this.dns())
        );
        this.client.start();
    }

    /**
     * Fake forward proxy: answers every request with a redirect to a host
     * that resolves to the metadata address.
     */
    private void redirectForever() {
        while (!this.proxy.isClosed()) {
            try (Socket sock = this.proxy.accept()) {
                final BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII)
                );
                String line = in.readLine();
                while (line != null && !line.isEmpty()) {
                    line = in.readLine();
                }
                final OutputStream out = sock.getOutputStream();
                out.write(
                    ("HTTP/1.1 302 Found\r\nLocation: http://rebind.example/latest\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII)
                );
                out.flush();
            } catch (final IOException ex) {
                return;
            }
        }
    }

    /**
     * Send a GET and return the egress denial in its failure chain, else
     * the root cause.
     *
     * @param url Target URL
     * @return Cause
     */
    private Throwable failure(final String url) {
        final CompletableFuture<Throwable> out = new CompletableFuture<>();
        this.client.newRequest(url).timeout(20, TimeUnit.SECONDS).send(
            result -> out.complete(result.getFailure())
        );
        Throwable cause;
        try {
            cause = out.get(25, TimeUnit.SECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
        if (cause == null) {
            throw new IllegalStateException("request unexpectedly succeeded: " + url);
        }
        while (cause.getCause() != null && !(cause instanceof EgressDeniedException)) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Stub DNS: {@code rebind.example} resolves to the metadata address,
     * any other {@code .example} name to a public address, literals to
     * themselves.
     *
     * @return Resolver
     */
    private SocketAddressResolver dns() {
        return (host, port, ctx, promise) -> {
            this.lookups.add(host);
            try {
                final InetAddress addr;
                if (host.endsWith(".unresolvable")) {
                    throw new UnknownHostException(host);
                }
                if ("rebind.example".equals(host)) {
                    addr = InetAddress.getByName("169.254.169.254");
                } else if (host.endsWith(".example")) {
                    addr = InetAddress.getByName("93.184.216.34");
                } else {
                    addr = InetAddress.getByName(host);
                }
                promise.succeeded(List.of(new InetSocketAddress(addr, port)));
            } catch (final UnknownHostException ex) {
                promise.failed(ex);
            }
        };
    }

    /**
     * A loopback port nothing listens on.
     *
     * @return Port
     * @throws IOException On socket failure
     */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
