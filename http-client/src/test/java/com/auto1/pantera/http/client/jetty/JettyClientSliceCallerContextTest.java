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
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.ThreadContext;
import org.eclipse.jetty.client.HttpClient;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An upstream answer arrives on a Jetty client thread, and the request code
 * chained on it runs there. It must see the caller's {@code trace.id}, and
 * what it binds must not stay on the Jetty thread: a Go-proxy 404 logged on
 * a {@code HttpClient@...} thread carried the trace id of an unrelated Maven
 * request.
 *
 * @since 2.2.14
 */
@Timeout(60)
final class JettyClientSliceCallerContextTest {

    private static final String CALLER = "11111111aaaaaaaa11111111aaaaaaaa";

    private static final String BOUND = "22222222bbbbbbbb22222222bbbbbbbb";

    private static final String TRACE = "trace.id";

    private HttpClient client;

    private ExecutorService pool;

    private ServerSocket server;

    /**
     * Trace ids Jetty tasks still had in their context when they ended.
     */
    private Set<String> leftovers;

    @BeforeEach
    void setUp() throws Exception {
        ThreadContext.clearAll();
        this.leftovers = Collections.synchronizedSet(new HashSet<>());
        this.pool = Executors.newCachedThreadPool();
        this.client = new HttpClient();
        this.client.setExecutor(
            task -> this.pool.execute(
                () -> {
                    ThreadContext.clearAll();
                    try {
                        task.run();
                    } finally {
                        this.leftovers.add(String.valueOf(ThreadContext.get(TRACE)));
                    }
                }
            )
        );
        this.client.start();
        this.server = new ServerSocket(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.server.close();
        if (this.client.isRunning()) {
            this.client.stop();
        }
        this.pool.shutdownNow();
        ThreadContext.clearAll();
    }

    @Test
    void continuationSeesCallersTraceAndJettyThreadKeepsNothing() throws Exception {
        final CountDownLatch chained = new CountDownLatch(1);
        final Thread upstream = new Thread(() -> this.answerOnce(chained));
        upstream.start();
        final Headers request = Headers.from(EcsLoggingSlice.CTX_TRACE_ID_HEADER, BOUND);
        final Thread caller = Thread.currentThread();
        ThreadContext.put(TRACE, CALLER);
        final CompletableFuture<String[]> seen = new JettyClientSlice(
            this.client, false, "localhost", this.server.getLocalPort(), 0L
        ).response(
            new RequestLine(RqMethod.GET, "/go/mod/@v/list"), Headers.EMPTY, Content.EMPTY
        ).thenCompose(
            resp -> resp.body().asBytesFuture().thenApply(
                ignored -> {
                    final String trace = ThreadContext.get(TRACE);
                    // What request code does on the answering thread.
                    RequestContextHeaders.bindToMdc(request);
                    return new String[] {
                        trace, String.valueOf(Thread.currentThread() == caller),
                    };
                }
            )
        );
        ThreadContext.clearAll();
        chained.countDown();
        final String[] result = seen.get(30, TimeUnit.SECONDS);
        upstream.join(TimeUnit.SECONDS.toMillis(30));
        this.client.stop();
        this.pool.shutdown();
        this.pool.awaitTermination(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the continuation ran on a Jetty thread, not on the caller",
            result[1], new IsEqual<>("false")
        );
        MatcherAssert.assertThat(
            "the continuation sees the caller's trace id",
            result[0], new IsEqual<>(CALLER)
        );
        MatcherAssert.assertThat(
            "no Jetty thread keeps the trace id the continuation bound",
            this.leftovers.contains(BOUND), new IsNot<>(new IsEqual<>(true))
        );
    }

    /**
     * Accept one connection and answer 200 once the client code has chained
     * its continuation.
     * @param chained Released when the continuation is attached
     */
    private void answerOnce(final CountDownLatch chained) {
        try (Socket socket = this.server.accept()) {
            chained.await(30, TimeUnit.SECONDS);
            final OutputStream out = socket.getOutputStream();
            out.write(
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    .getBytes(StandardCharsets.US_ASCII)
            );
            out.flush();
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
