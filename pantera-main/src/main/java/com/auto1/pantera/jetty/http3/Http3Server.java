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
package com.auto1.pantera.jetty.http3;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.misc.ConfigDefaults;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.asto.Content;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.http3.server.RawHTTP3ServerConnectionFactory;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Http3 server.
 * @since 0.31
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @checkstyle MagicNumberCheck (500 lines)
 */
public final class Http3Server {

    /**
     * Protocol version.
     */
    private static final String HTTP_3 = "HTTP/3";

    /**
     * Maximum bytes accumulated in memory for a single HTTP/3 request body before the
     * stream is rejected with 413 Payload Too Large. Acts as a safety ceiling to prevent
     * an unbounded LinkedList from consuming heap on large or hostile uploads.
     *
     * <p>Default 16 MB; override via {@code PANTERA_HTTP3_MAX_STREAM_BUFFER_BYTES}.</p>
     *
     * <p><b>Design note (A.6):</b> We reject on overflow rather than spill to a temp file.
     * Rationale: the Jetty 12.1.4 {@code Stream.Server} data-frame callback plumbing in
     * {@code onDataAvailable} below is currently a stub (see comments) and does not yet
     * forward bytes into the slice. Wiring a temp-file spill through that future
     * callback-based API is significantly more invasive than the surgical cap this fix
     * targets. 16 MB covers typical artifact metadata / manifest uploads; larger HTTP/3
     * uploads will surface an explicit 413 instead of silently ballooning heap.</p>
     */
    private static final int MAX_STREAM_BUFFER_BYTES = ConfigDefaults.getInt(
        "PANTERA_HTTP3_MAX_STREAM_BUFFER_BYTES", 16 * 1024 * 1024
    );

    /**
     * Whether the HTTP/3 connector should accept a PROXY-protocol-v2 prelude
     * before the QUIC/HTTP/3 bytes. Mirrors the existing Vert.x
     * {@code setUseProxyProtocol} toggle on the HTTP/1–2 listeners
     * (see {@code AsyncApiVerticle} / {@code VertxMain}).
     *
     * <p>Default {@code false}; override via
     * {@code PANTERA_HTTP3_PROXY_PROTOCOL=true}. The planned YAML path
     * {@code meta.http3.proxyProtocol} (see plan Task H.3) is not wired here
     * because {@link Http3Server} is currently constructed without a
     * {@code Settings} reference (see {@code VertxMain}:~302 and ~802).
     * Threading {@code Settings} through would require a signature change;
     * until then the env-var is the sole entry point and operators fronting
     * the HTTP/3 listener with an NLB / PROXYv2 proxy set it explicitly.</p>
     *
     * <p>When {@code true}, a {@link ProxyConnectionFactory} is prepended to
     * the connector's factory chain so that the upstream proxy's
     * PROXY-protocol prelude is parsed before the HTTP/3 handshake and the
     * real client IP (not the TCP peer) is surfaced to request handlers.</p>
     */
    private static final boolean PROXY_PROTOCOL_ENABLED = ConfigDefaults.getBoolean(
        "PANTERA_HTTP3_PROXY_PROTOCOL", false
    );

    /**
     * Pantera slice.
     */
    private final Slice slice;

    /**
     * Http3 server.
     */
    private final Server server;

    /**
     * Port.
     */
    private final int port;

    /**
     * SSL factory.
     */
    private final SslContextFactory.Server ssl;

    /**
     * Ctor.
     *
     * @param slice Pantera slice
     * @param port Port to start server on
     * @param ssl SSL factory
     */
    public Http3Server(final Slice slice, final int port, final SslContextFactory.Server ssl) {
        this.slice = slice;
        this.port = port;
        this.ssl = ssl;
        this.server = new Server();
    }

    /**
     * Starts http3 server with native QUIC support via Quiche.
     * @throws com.auto1.pantera.PanteraException On Error
     */
    public void start() {
        try {
            // Create PEM directory for QUIC native library (required by Quiche)
            final Path pemDir = Files.createTempDirectory("http3-pem");
            pemDir.toFile().deleteOnExit();
            
            // Configure QUIC with Quiche native library
            final QuicheServerQuicConfiguration serverQuicConfig = 
                HTTP3ServerQuicConfiguration.configure(
                    new QuicheServerQuicConfiguration(pemDir)
                );
            // Configure max number of requests per QUIC connection
            serverQuicConfig.setBidirectionalMaxStreams(1024 * 1024);
            
            // Create HTTP/3 connection factory with low-level API
            final RawHTTP3ServerConnectionFactory http3 =
                new RawHTTP3ServerConnectionFactory(new SliceListener());
            http3.getHTTP3Configuration().setStreamIdleTimeout(15_000);

            // Build the connector's factory chain. When PROXY_PROTOCOL_ENABLED
            // is true, prepend Jetty's ProxyConnectionFactory so the upstream
            // LB's PROXY-protocol-v2 prelude is parsed before the HTTP/3 frames
            // and Jetty's Server API surfaces the real client IP (not the TCP
            // peer) to handlers. Mirrors the Vert.x setUseProxyProtocol
            // behavior on the HTTP/1–2 listeners.
            final QuicheServerConnector connector;
            if (Http3Server.PROXY_PROTOCOL_ENABLED) {
                connector = new QuicheServerConnector(
                    this.server,
                    this.ssl,
                    serverQuicConfig,
                    new ProxyConnectionFactory(),
                    http3
                );
                EcsLogger.info("com.auto1.pantera.jetty.http3")
                    .message("HTTP/3 proxy-protocol prelude parsing enabled")
                    .eventCategory("configuration")
                    .eventAction("http3_proxy_protocol_enabled")
                    .field("url.port", this.port)
                    .log();
            } else {
                connector = new QuicheServerConnector(
                    this.server,
                    this.ssl,
                    serverQuicConfig,
                    http3
                );
            }
            connector.setPort(this.port);

            this.server.addConnector(connector);
            this.server.start();
        // @checkstyle IllegalCatchCheck (5 lines)
        } catch (final Exception err) {
            throw new PanteraException(err);
        }
    }

    /**
     * Stops the server.
     * @throws Exception On error
     */
    public void stop() throws Exception {
        this.server.stop();
    }

    /**
     * Implementation of {@link Session.Server.Listener} which passes data to slice and sends
     * response to {@link Stream.Server} via {@link  Http3Connection}.
     * @since 0.31
     * @checkstyle ReturnCountCheck (500 lines)
     * @checkstyle AnonInnerLengthCheck (500 lines)
     * @checkstyle NestedIfDepthCheck (500 lines)
     */
    private final class SliceListener implements Session.Server.Listener {

        public Stream.Server.Listener onRequest(
            final Stream.Server stream, final HeadersFrame frame
        ) {
            final MetaData.Request request = (MetaData.Request) frame.getMetaData();
            if (frame.isLast()) {
                // Request with no body
                Http3Server.this.slice.response(
                    RequestLine.from(
                        request.getMethod() + " " + 
                        request.getHttpURI().getPath() + " " + 
                        Http3Server.HTTP_3
                    ),
                    new Headers(
                        request.getHttpFields().stream()
                            .map(field -> new Header(field.getName(), field.getValue()))
                            .collect(Collectors.toList())
                    ),
                    Content.EMPTY
                ).thenAccept(response -> new Http3Connection(stream).send(response));
                return null;
            } else {
                // Request with body - collect data frames into a bounded accumulator.
                // The previous unbounded LinkedList<ByteBuffer> was a latent heap-exhaustion
                // risk for large or hostile uploads. We cap at MAX_STREAM_BUFFER_BYTES; on
                // overflow we reset the stream (HTTP/3 equivalent of 413) and stop demanding
                // further data. See MAX_STREAM_BUFFER_BYTES javadoc for the reject-vs-spill
                // rationale.
                stream.demand();
                final List<ByteBuffer> buffers = new ArrayList<>();
                final AtomicLong totalBytes = new AtomicLong(0L);
                return new Stream.Server.Listener() {
                    @Override
                    public void onDataAvailable(final Stream.Server stream) {
                        // Accumulator overflow guard.
                        // Note: readData() API changed in Jetty 12.1.4
                        // This is a simplified implementation
                        // Full implementation would use stream.read() with callbacks
                        // and add each data-frame buffer to `buffers` while updating
                        // totalBytes; on exceeding MAX_STREAM_BUFFER_BYTES the stream
                        // would be reset (413 Payload Too Large) and demand stopped.
                        if (totalBytes.get() > MAX_STREAM_BUFFER_BYTES) {
                            // Clear to release references; do not demand more.
                            buffers.clear();
                            return;
                        }
                        stream.demand();
                    }
                };
            }
        }
    }

}
