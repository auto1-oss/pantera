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
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.sun.net.httpserver.HttpServer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests for {@link JettyClientSlice} against upstream statuses that {@link
 * com.auto1.pantera.http.RsStatus} does not recognise. An upstream status
 * absent from {@code RsStatus} must still settle the outbound {@link
 * CompletableFuture} returned by {@link JettyClientSlice#response}, not
 * orphan it inside a Jetty callback (root cause: {@code RsStatus.byCode}
 * throws for an unmapped code, and that throw used to happen inside Jetty's
 * own completion callbacks with nothing to catch it).
 */
final class JettyClientSliceStatusTest {

    /**
     * Raw, threading HTTP server used to serve arbitrary wire-level status
     * codes. Deliberately not Pantera's own {@code Response}/{@code
     * RsStatus}-backed test server ({@link com.auto1.pantera.http.client.HttpServer})
     * -- the whole point of these tests is to serve a status code {@code
     * RsStatus} may not map, which that helper cannot produce since it can
     * only build responses from {@code RsStatus} values in the first place.
     */
    private HttpServer server;

    /**
     * Executor backing {@link #server}. A single-threaded responder cannot
     * accept a new connection while a regression stalls one exchange for
     * the full idle timeout, which would silently prevent the request from
     * ever reaching the responder at all; a threading executor keeps the
     * server accepting regardless.
     */
    private ExecutorService serverExecutor;

    /**
     * Jetty client slices backing the slice under test, started per test in
     * {@link #sliceFor(int)}.
     */
    private JettyClientSlices clients;

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.stop(0);
        }
        if (this.serverExecutor != null) {
            this.serverExecutor.shutdownNow();
        }
        if (this.clients != null) {
            this.clients.stop();
        }
    }

    @Test
    @Timeout(10)
    void completesTheFutureForAnUnmappedStatus() throws IOException {
        final CompletableFuture<Response> future = this.sliceFor(451).response(
            new RequestLine(RqMethod.GET, "/thing"), Headers.EMPTY, Content.EMPTY
        );
        MatcherAssert.assertThat(
            "an unmapped upstream status must settle the future, not orphan it",
            this.settles(future), new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(10)
    void completesTheFutureForAMappedStatus() throws IOException {
        final CompletableFuture<Response> future = this.sliceFor(409).response(
            new RequestLine(RqMethod.GET, "/thing"), Headers.EMPTY, Content.EMPTY
        );
        MatcherAssert.assertThat(
            "control: a mapped status settles", this.settles(future), new IsEqual<>(true)
        );
    }

    /**
     * Coverage for a HEAD request specifically. {@code JettyClientSlice
     * #response} has a second {@code RsStatus.byCode} call site in the
     * {@code request.send} completion callback, documented as the fallback
     * for "responses where onResponseContentSource never fired (empty
     * body, HEAD, etc.)" -- guarded the same way as {@code
     * onResponseContentSource} for whichever response shape actually takes
     * that fallback. Real HEAD traffic on this path includes
     * cooldown/freshness probes (see {@code BaseCachedProxySlice}), so an
     * upstream returning a status {@code RsStatus} does not recognise must
     * settle the future here too, regardless of which of the two call
     * sites ends up resolving it for a given response. Uses 599 rather
     * than a real status so this guard stays meaningful even as more
     * codes get mapped.
     */
    @Test
    @Timeout(10)
    void completesTheFutureForAnUnmappedStatusOnHeadRequest() throws IOException {
        final CompletableFuture<Response> future = this.sliceFor(599).response(
            new RequestLine(RqMethod.HEAD, "/thing"), Headers.EMPTY, Content.EMPTY
        );
        MatcherAssert.assertThat(
            "an unmapped upstream status on a HEAD response must settle the future too",
            this.settles(future), new IsEqual<>(true)
        );
    }

    /**
     * Start a raw, threading HTTP server that answers every request with
     * {@code code} and no body, and build a {@link JettyClientSlice}
     * pointed at it.
     *
     * @param code Raw HTTP status code to serve for every request
     * @return A client slice pointed at the server
     * @throws IOException If the loopback listener cannot be created
     */
    private JettyClientSlice sliceFor(final int code) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.serverExecutor = Executors.newCachedThreadPool();
        this.server.setExecutor(this.serverExecutor);
        this.server.createContext(
            "/", exchange -> {
                exchange.sendResponseHeaders(code, -1);
                exchange.close();
            }
        );
        this.server.start();
        this.clients = new JettyClientSlices(new HttpClientSettings());
        this.clients.start();
        return new JettyClientSlice(
            this.clients.httpClient(), false, "localhost",
            this.server.getAddress().getPort(), 0L
        );
    }

    /**
     * Wait for the future to settle either way, without asserting latency.
     * @param future Future under test
     * @return True when it settled, false when it timed out
     */
    private boolean settles(final CompletableFuture<Response> future) {
        boolean settled;
        try {
            future.handle((res, err) -> res).get(5, TimeUnit.SECONDS);
            settled = true;
        } catch (final TimeoutException ignored) {
            settled = false;
        } catch (final InterruptedException | ExecutionException ignored) {
            settled = true;
        }
        return settled;
    }
}
