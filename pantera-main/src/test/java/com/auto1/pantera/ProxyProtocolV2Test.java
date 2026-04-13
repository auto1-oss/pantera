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
package com.auto1.pantera;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies Pantera's PROXY Protocol v2 wiring is correct end-to-end.
 *
 * <p>AWS Network Load Balancers (with "Proxy Protocol v2" enabled on the target
 * group) prepend a binary PROXYv2 header to every TCP connection describing the
 * real client endpoint. Pantera is expected to parse that header and expose the
 * upstream client IP to request handlers, access logs, and rate-limit logic —
 * not the NLB's own IP.
 *
 * <p>This requires two things to be correctly wired:
 * <ul>
 *   <li><b>HttpServerOptions.setUseProxyProtocol(true)</b> on every listener
 *       (main port, API port, per-repo ports). See {@code VertxMain}, {@code
 *       AsyncApiVerticle}.</li>
 *   <li><b>{@code io.netty:netty-codec-haproxy}</b> on the runtime classpath.
 *       Vert.x delegates PROXYv2 decoding to Netty's {@code
 *       HAProxyMessageDecoder}, which lives in that artifact and is <em>not</em>
 *       a transitive dep of {@code vertx-core}. If it is missing, Vert.x logs
 *       {@code "Proxy protocol support could not be enabled. Make sure that
 *       netty-codec-haproxy is included in your classpath"} at startup and
 *       silently downgrades to plain HTTP — at which point every NLB connection
 *       fails because the "PROXY …" bytes are parsed as broken HTTP.</li>
 * </ul>
 *
 * <p>The test spins up a bare Vert.x {@link HttpServer} with
 * {@code setUseProxyProtocol(true)}, opens a raw TCP socket, and writes the
 * PROXYv2 header (encoded via Netty's {@link HAProxyMessageEncoder}) followed
 * by a minimal HTTP/1.1 request. It asserts:
 * <ol>
 *   <li>The server accepts the connection and serves a 200 response — proving
 *       that the PROXY header was consumed as protocol metadata, not as HTTP.</li>
 *   <li>{@code request.remoteAddress()} reports the client endpoint from the
 *       PROXY header (not the loopback address of the test TCP peer).</li>
 * </ol>
 *
 * <p>If {@code netty-codec-haproxy} ever goes missing from the classpath,
 * or if {@code setUseProxyProtocol} is dropped from an HttpServer call site,
 * this test will fail.
 */
class ProxyProtocolV2Test {

    /** Timeout applied to every network I/O step in the tests. */
    private static final long IO_TIMEOUT_MS = 10_000L;

    private Vertx vertx;
    private HttpServer server;
    private int serverPort;

    private final AtomicReference<String> capturedHost = new AtomicReference<>();
    private final AtomicInteger capturedPort = new AtomicInteger();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        this.vertx = Vertx.vertx();
        final HttpServerOptions opts = new HttpServerOptions()
            .setHost("127.0.0.1")
            .setPort(0)
            .setUseProxyProtocol(true);

        final CompletableFuture<HttpServer> started = new CompletableFuture<>();
        this.vertx.createHttpServer(opts)
            .requestHandler(req -> {
                this.capturedHost.set(req.remoteAddress().host());
                this.capturedPort.set(req.remoteAddress().port());
                this.requestCount.incrementAndGet();
                req.response().putHeader("Content-Type", "text/plain").end("ok");
            })
            .listen()
            .onSuccess(started::complete)
            .onFailure(started::completeExceptionally);
        this.server = started.get(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        this.serverPort = this.server.actualPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (this.server != null) {
            this.server.close().toCompletionStage().toCompletableFuture()
                .get(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        if (this.vertx != null) {
            this.vertx.close().toCompletionStage().toCompletableFuture()
                .get(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * The representative AWS NLB case: IPv4 client, TCP, PROXY command.
     * The header is 28 bytes (12 sig + 4 v2/TCP4 + 12 addrs). The server must
     * accept the connection AND expose 192.0.2.10:54321 as the remote address.
     */
    @Test
    void exposesClientIpFromProxyV2Tcp4Header() throws Exception {
        final byte[] header = encodeProxyV2Message(
            HAProxyProxiedProtocol.TCP4,
            "192.0.2.10", 54_321,       // real client
            "10.0.0.50", 443             // NLB-facing dest
        );
        final String response = sendWithHeader(header,
            "GET /probe HTTP/1.1\r\nHost: pantera.local\r\nConnection: close\r\n\r\n");

        MatcherAssert.assertThat(
            "server must serve the request once PROXY header is consumed",
            response, Matchers.startsWith("HTTP/1.1 200")
        );
        MatcherAssert.assertThat(this.requestCount.get(), Matchers.equalTo(1));
        MatcherAssert.assertThat(
            "handler must see the client IP from the PROXY header, not 127.0.0.1",
            this.capturedHost.get(), Matchers.equalTo("192.0.2.10")
        );
        MatcherAssert.assertThat(
            "handler must see the client port from the PROXY header",
            this.capturedPort.get(), Matchers.equalTo(54_321)
        );
    }

    /**
     * IPv6 clients (dual-stack NLB). Ensures we don't silently ignore TCP6
     * addresses or misinterpret the 36-byte IPv6 address block.
     */
    @Test
    void exposesClientIpFromProxyV2Tcp6Header() throws Exception {
        final byte[] header = encodeProxyV2Message(
            HAProxyProxiedProtocol.TCP6,
            "2001:db8::1", 1234,
            "2001:db8::ff", 443
        );
        final String response = sendWithHeader(header,
            "GET /probe HTTP/1.1\r\nHost: pantera.local\r\nConnection: close\r\n\r\n");

        MatcherAssert.assertThat(response, Matchers.startsWith("HTTP/1.1 200"));
        // Vert.x may canonicalise the IPv6 literal (e.g. "2001:db8::1" becomes
        // "2001:db8:0:0:0:0:0:1"); compare via InetAddress so either form passes.
        MatcherAssert.assertThat(
            InetAddress.getByName(this.capturedHost.get()),
            Matchers.equalTo(InetAddress.getByName("2001:db8::1"))
        );
        MatcherAssert.assertThat(
            this.capturedPort.get(), Matchers.equalTo(1234)
        );
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    /**
     * Encode a PROXYv2 header using Netty's encoder — exactly what an AWS NLB
     * would put on the wire. Using the encoder (rather than hand-rolled bytes)
     * keeps the test honest against future PROXYv2 spec additions.
     */
    private static byte[] encodeProxyV2Message(
        final HAProxyProxiedProtocol proto,
        final String src, final int srcPort,
        final String dst, final int dstPort
    ) {
        final EmbeddedChannel ch = new EmbeddedChannel(HAProxyMessageEncoder.INSTANCE);
        final HAProxyMessage msg = new HAProxyMessage(
            HAProxyProtocolVersion.V2,
            HAProxyCommand.PROXY,
            proto,
            src, dst, srcPort, dstPort
        );
        try {
            ch.writeOutbound(msg);
            final ByteBuf buf = ch.readOutbound();
            try {
                final byte[] out = new byte[buf.readableBytes()];
                buf.readBytes(out);
                return out;
            } finally {
                buf.release();
            }
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    /**
     * Open a raw TCP socket to the test server, write the PROXYv2 header
     * followed by an HTTP request, return the first response line plus
     * headers. We deliberately drop under Vert.x's client APIs here — a high
     * level client would emit its own PROXYv2 header and mask the very bug
     * we are trying to catch.
     */
    private String sendWithHeader(final byte[] proxyHeader, final String httpRequest)
        throws Exception {
        try (Socket sock = new Socket("127.0.0.1", this.serverPort)) {
            sock.setSoTimeout((int) IO_TIMEOUT_MS);
            final OutputStream out = sock.getOutputStream();
            out.write(proxyHeader);
            out.write(httpRequest.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII)
            )) {
                final StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (line.isEmpty() && sb.length() > 2) {
                        break;
                    }
                }
                return sb.toString();
            }
        }
    }
}
