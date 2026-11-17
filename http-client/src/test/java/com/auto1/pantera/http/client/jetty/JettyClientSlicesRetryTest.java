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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.log.FailureCause;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An upstream that closes a connection before answering must not fail the
 * request: every server and load balancer closes kept-alive connections
 * (idle timeout, request limit, restart), and the close can cross a request
 * already on its way. A {@code GET} or {@code HEAD} is sent again on another
 * connection.
 *
 * <p>Observed on 2026-11-17: {@code docker.elastic.co} closed the connection
 * on one of three parallel layer downloads, the layer was answered 404 and
 * the {@code docker pull} failed.</p>
 *
 * @since 2.2.12
 */
final class JettyClientSlicesRetryTest {

    /**
     * Body the upstream serves.
     */
    private static final byte[] BODY = "hello".getBytes(StandardCharsets.US_ASCII);

    /**
     * Layer path, as in the observed failure.
     */
    private static final String BLOB = "/v2/beats/filebeat/blobs/sha256:0e8a0913c581";

    /**
     * Clients under test.
     */
    private JettyClientSlices clients;

    @BeforeEach
    void setUp() {
        this.clients = new JettyClientSlices(new HttpClientSettings());
        this.clients.start();
    }

    @AfterEach
    void tearDown() {
        this.clients.stop();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void sendsGetAgainWhenUpstreamClosesTheConnectionBeforeAnswering() throws Exception {
        try (FlakyUpstream upstream = new FlakyUpstream(1)) {
            final Response response = this.clients.http("localhost", upstream.port()).response(
                new RequestLine(RqMethod.GET, JettyClientSlicesRetryTest.BLOB),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "the request is answered", response.status(), new IsEqual<>(RsStatus.OK)
            );
            MatcherAssert.assertThat(
                "with the body of the second attempt",
                response.body().asBytesFuture().get(30, TimeUnit.SECONDS),
                new IsEqual<>(JettyClientSlicesRetryTest.BODY)
            );
            MatcherAssert.assertThat(
                "the upstream received the request twice",
                upstream.requests(),
                new IsEqual<>(
                    List.of(
                        "1 GET " + JettyClientSlicesRetryTest.BLOB,
                        "2 GET " + JettyClientSlicesRetryTest.BLOB
                    )
                )
            );
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void sendsGetAgainWhenUpstreamClosesTheKeptAliveConnectionItArrivesOn() throws Exception {
        // The shape of a Docker layer download: HEAD, then GET on the
        // connection the HEAD left in the pool.
        try (FlakyUpstream upstream = new FlakyUpstream(2)) {
            final Slice slice = this.clients.http("localhost", upstream.port());
            final Response head = slice.response(
                new RequestLine(RqMethod.HEAD, JettyClientSlicesRetryTest.BLOB),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS);
            head.body().asBytesFuture().get(30, TimeUnit.SECONDS);
            this.awaitIdleConnection();
            final Response get = slice.response(
                new RequestLine(RqMethod.GET, JettyClientSlicesRetryTest.BLOB),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "the HEAD is answered", head.status(), new IsEqual<>(RsStatus.OK)
            );
            MatcherAssert.assertThat(
                "the GET is answered", get.status(), new IsEqual<>(RsStatus.OK)
            );
            MatcherAssert.assertThat(
                "with the body of the second attempt",
                get.body().asBytesFuture().get(30, TimeUnit.SECONDS),
                new IsEqual<>(JettyClientSlicesRetryTest.BODY)
            );
            MatcherAssert.assertThat(
                "the GET went out on the kept-alive connection, then on a new one",
                upstream.requests(),
                new IsEqual<>(
                    List.of(
                        "1 HEAD " + JettyClientSlicesRetryTest.BLOB,
                        "1 GET " + JettyClientSlicesRetryTest.BLOB,
                        "2 GET " + JettyClientSlicesRetryTest.BLOB
                    )
                )
            );
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void givesUpWhenUpstreamKeepsClosingConnections() throws Exception {
        try (FlakyUpstream upstream = new FlakyUpstream(1, 2, 3, 4, 5, 6)) {
            final ExecutionException failure = Assertions.assertThrows(
                ExecutionException.class,
                () -> this.clients.http("localhost", upstream.port()).response(
                    new RequestLine(RqMethod.GET, JettyClientSlicesRetryTest.BLOB),
                    Headers.EMPTY, Content.EMPTY
                ).get(30, TimeUnit.SECONDS),
                "the request fails"
            );
            MatcherAssert.assertThat(
                "with the closed connection as the cause",
                new FailureCause(failure).reason(), new IsEqual<>("connection_closed")
            );
            MatcherAssert.assertThat(
                "after the first attempt and two more",
                upstream.requests().size(), new IsEqual<>(3)
            );
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void doesNotSendAPostAgain() throws Exception {
        // A request that changes state is not safe to repeat: the upstream
        // may have acted on it before the connection went away.
        try (FlakyUpstream upstream = new FlakyUpstream(1)) {
            Assertions.assertThrows(
                ExecutionException.class,
                () -> this.clients.http("localhost", upstream.port()).response(
                    new RequestLine(RqMethod.POST, "/v2/token"),
                    Headers.EMPTY,
                    new Content.From("grant".getBytes(StandardCharsets.US_ASCII))
                ).get(30, TimeUnit.SECONDS),
                "the request fails"
            );
            MatcherAssert.assertThat(
                "the upstream received it once",
                upstream.requests(), new IsEqual<>(List.of("1 POST /v2/token"))
            );
        }
    }

    /**
     * Waits until the client holds an idle connection: a response completes
     * a moment before its connection is back in the pool, and a request sent
     * in that moment opens a new connection instead of reusing it.
     * @throws InterruptedException If interrupted
     */
    private void awaitIdleConnection() throws InterruptedException {
        boolean idle = false;
        for (int attempt = 0; attempt < 2_000 && !idle; attempt += 1) {
            idle = this.clients.httpClient().getDestinations().stream()
                .map(destination -> ((HttpDestination) destination).getConnectionPool())
                .anyMatch(pool -> ((AbstractConnectionPool) pool).getIdleConnectionCount() > 0);
            if (!idle) {
                TimeUnit.MILLISECONDS.sleep(5L);
            }
        }
        MatcherAssert.assertThat(
            "the connection of the first request is back in the pool",
            idle, new IsEqual<>(true)
        );
    }

    /**
     * Upstream that answers every request with 200 and a small body, except
     * the ones it is told to drop: for those it reads the request and closes
     * the connection without a byte of response.
     */
    private static final class FlakyUpstream implements AutoCloseable {

        /**
         * Listening socket.
         */
        private final ServerSocket server;

        /**
         * Numbers (from 1) of the requests to drop.
         */
        private final Set<Integer> dropped;

        /**
         * Requests received: connection number, method and path.
         */
        private final List<String> seen;

        /**
         * Connections accepted.
         */
        private final AtomicInteger connections;

        /**
         * Threads serving connections.
         */
        private final ExecutorService threads;

        FlakyUpstream(final Integer... dropped) throws IOException {
            this.server = new ServerSocket(0, 50, InetAddress.getByName("localhost"));
            this.dropped = Set.of(dropped);
            this.seen = new CopyOnWriteArrayList<>();
            this.connections = new AtomicInteger();
            this.threads = Executors.newCachedThreadPool(
                task -> {
                    final Thread thread = new Thread(task, "flaky-upstream");
                    thread.setDaemon(true);
                    return thread;
                }
            );
            this.threads.submit(this::accept);
        }

        int port() {
            return this.server.getLocalPort();
        }

        List<String> requests() {
            return List.copyOf(this.seen);
        }

        @Override
        public void close() throws IOException {
            this.server.close();
            this.threads.shutdownNow();
        }

        private void accept() {
            while (!this.server.isClosed()) {
                try {
                    final Socket socket = this.server.accept();
                    final int connection = this.connections.incrementAndGet();
                    this.threads.submit(() -> this.serve(socket, connection));
                } catch (final IOException closed) {
                    return;
                }
            }
        }

        private void serve(final Socket socket, final int connection) {
            try (socket) {
                final BufferedReader input = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)
                );
                final OutputStream output = socket.getOutputStream();
                String line = input.readLine();
                while (line != null) {
                    String header = input.readLine();
                    while (header != null && !header.isEmpty()) {
                        header = input.readLine();
                    }
                    final String[] parts = line.split(" ");
                    this.seen.add(String.format("%d %s %s", connection, parts[0], parts[1]));
                    if (this.dropped.contains(this.seen.size())) {
                        return;
                    }
                    output.write(
                        String.format(
                            Locale.ROOT,
                            "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                                + "Content-Length: %d\r\n\r\n",
                            JettyClientSlicesRetryTest.BODY.length
                        ).getBytes(StandardCharsets.US_ASCII)
                    );
                    if (!"HEAD".equals(parts[0])) {
                        output.write(JettyClientSlicesRetryTest.BODY);
                    }
                    output.flush();
                    line = input.readLine();
                }
            } catch (final IOException gone) {
                // the client closed the connection
            }
        }
    }
}
