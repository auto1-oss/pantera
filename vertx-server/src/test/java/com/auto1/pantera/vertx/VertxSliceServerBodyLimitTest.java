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
package com.auto1.pantera.vertx;

import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import io.reactivex.Flowable;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exploit-regression tests for the mandatory request-body byte limit
 * (resource-dos F31/F17).
 *
 * <p>Before 2.2.9 the server imposed NO request-body cap: the only limiter,
 * {@code ContentLengthRestriction}, was wired per repository only when an
 * operator set {@code content-length-max} (unset in every shipped config),
 * sat inside the anonymous gate, and trusted the declared header — so a
 * chunked body bypassed it entirely. The server must now (1) reject a
 * declared {@code Content-Length} above the limit with 413 before reading
 * a byte or dispatching to the slice, and (2) meter ACTUAL bytes of a
 * chunked body and reject with 413 once the limit is exceeded.</p>
 *
 * @since 2.2.9
 */
final class VertxSliceServerBodyLimitTest {

    private static final String HOST = "localhost";

    private static final long LIMIT = 1024L;

    private int port;

    private Vertx vertx;

    private WebClient client;

    private VertxSliceServer server;

    @BeforeEach
    void setUp() {
        this.vertx = Vertx.vertx();
        this.client = WebClient.create(this.vertx);
    }

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.close();
        }
        if (this.client != null) {
            this.client.close();
        }
        if (this.vertx != null) {
            this.vertx.close();
        }
    }

    @Test
    @Timeout(30)
    void declaredContentLengthAboveTheLimitIsRejectedBeforeDispatch() {
        final AtomicBoolean reached = new AtomicBoolean();
        this.start((line, headers, body) -> {
            reached.set(true);
            return body.discard().thenApply(
                ignored -> ResponseBuilder.ok().build()
            );
        });
        // A body twice the limit with a truthful Content-Length.
        final byte[] payload = new byte[(int) LIMIT * 2];
        final HttpResponse<Buffer> response = this.client
            .put(this.port, HOST, "/repo/artifact.bin")
            .rxSendBuffer(Buffer.buffer(payload))
            .blockingGet();
        MatcherAssert.assertThat(
            "a declared Content-Length above the limit must be rejected with 413",
            response.statusCode(), new IsEqual<>(413)
        );
        MatcherAssert.assertThat(
            "the slice must never be dispatched for an over-limit declared body",
            reached.get(), new IsEqual<>(false)
        );
    }

    @Test
    @Timeout(30)
    void chunkedBodyExceedingTheLimitIsRejected() {
        final AtomicLong consumed = new AtomicLong();
        this.start((line, headers, body) -> {
            final CompletableFuture<com.auto1.pantera.http.Response> future =
                new CompletableFuture<>();
            Flowable.fromPublisher(body)
                .doOnNext(buf -> consumed.addAndGet(buf.remaining()))
                .doOnComplete(() -> future.complete(ResponseBuilder.ok().build()))
                .doOnError(future::completeExceptionally)
                .subscribe(buf -> { }, err -> { });
            return future;
        });
        // Chunked (no Content-Length): eight 512-byte chunks = 4x the limit.
        final Flowable<Buffer> chunked = Flowable.range(0, 8)
            .map(idx -> Buffer.buffer(new byte[512]));
        final HttpResponse<Buffer> response = this.client
            .put(this.port, HOST, "/repo/artifact.bin")
            .rxSendStream(chunked)
            .blockingGet();
        MatcherAssert.assertThat(
            "a chunked body that exceeds the limit must be rejected with 413",
            response.statusCode(), new IsEqual<>(413)
        );
        MatcherAssert.assertThat(
            "the slice must not have consumed the whole over-limit body",
            consumed.get() < 8L * 512L, new IsEqual<>(true)
        );
    }

    /**
     * B81: Docker Registry API paths get the 413 as an OCI error body, which
     * docker/oras/crane print, instead of a text/plain line.
     */
    @Test
    @Timeout(30)
    void registryApiPathGetsOciErrorBody() {
        this.start((line, headers, body) -> body.discard().thenApply(
            ignored -> ResponseBuilder.ok().build()
        ));
        final HttpResponse<Buffer> response = this.client
            .patch(this.port, HOST, "/v2/docker-local/app/blobs/uploads/abc")
            .rxSendBuffer(Buffer.buffer(new byte[(int) LIMIT * 2]))
            .blockingGet();
        MatcherAssert.assertThat(
            "over-limit registry upload is 413",
            response.statusCode(), new IsEqual<>(413)
        );
        MatcherAssert.assertThat(
            "413 body is JSON",
            response.getHeader("Content-Type"), new IsEqual<>("application/json")
        );
        MatcherAssert.assertThat(
            "413 body carries the OCI SIZE_INVALID code",
            response.bodyAsString().contains("\"SIZE_INVALID\""), new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(30)
    void bodyWithinTheLimitIsServedNormally() {
        this.start((line, headers, body) -> body.asBytesFuture().thenApply(
            bytes -> ResponseBuilder.ok().textBody(Integer.toString(bytes.length)).build()
        ));
        final byte[] payload = new byte[(int) LIMIT / 2];
        final HttpResponse<Buffer> response = this.client
            .put(this.port, HOST, "/repo/artifact.bin")
            .rxSendBuffer(Buffer.buffer(payload))
            .blockingGet();
        MatcherAssert.assertThat(
            "a body within the limit must be served",
            response.statusCode(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the slice must receive the whole body",
            response.bodyAsString(), new IsEqual<>(Integer.toString(payload.length))
        );
    }

    @Test
    @Timeout(30)
    void capChangesApplyToTheNextRequestWithoutRestart() {
        final AtomicLong cap = new AtomicLong(LIMIT);
        this.start(
            (line, headers, body) -> body.asBytesFuture().thenApply(
                bytes -> ResponseBuilder.ok().textBody(Integer.toString(bytes.length)).build()
            ),
            cap::get
        );
        final byte[] payload = new byte[(int) LIMIT / 2];
        final HttpResponse<Buffer> before = this.client
            .put(this.port, HOST, "/repo/artifact.bin")
            .rxSendBuffer(Buffer.buffer(payload))
            .blockingGet();
        MatcherAssert.assertThat(
            "the body is within the initial cap",
            before.statusCode(), new IsEqual<>(200)
        );
        cap.set(LIMIT / 4);
        final HttpResponse<Buffer> after = this.client
            .put(this.port, HOST, "/repo/artifact.bin")
            .rxSendBuffer(Buffer.buffer(payload))
            .blockingGet();
        MatcherAssert.assertThat(
            "lowering the cap must reject the very next request, no restart",
            after.statusCode(), new IsEqual<>(413)
        );
    }

    @Test
    @Timeout(60)
    void rejectionOverHttp2KeepsTheConnectionUsable() throws Exception {
        this.start((line, headers, body) -> body.asBytesFuture().thenApply(
            bytes -> ResponseBuilder.ok().textBody(Integer.toString(bytes.length)).build()
        ));
        final io.vertx.core.http.HttpClient h2 = this.vertx.getDelegate().createHttpClient(
            new io.vertx.core.http.HttpClientOptions()
                .setProtocolVersion(io.vertx.core.http.HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false),
            new io.vertx.core.http.PoolOptions().setHttp2MaxSize(1)
        );
        final java.util.Set<io.vertx.core.http.HttpConnection> conns =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicBoolean closed = new AtomicBoolean();
        h2.connectionHandler(conn -> {
            conns.add(conn);
            conn.closeHandler(v -> closed.set(true));
        });
        try {
            final int rejected = this.h2Put(h2, (int) LIMIT * 2);
            final int accepted = this.h2Put(h2, (int) LIMIT / 2);
            MatcherAssert.assertThat(
                "an over-limit h2 body is rejected with 413",
                rejected, new IsEqual<>(413)
            );
            MatcherAssert.assertThat(
                "the next request on the same h2 client succeeds",
                accepted, new IsEqual<>(200)
            );
            MatcherAssert.assertThat(
                "the h2 connection is not torn down by the 413",
                closed.get() || conns.size() != 1, new IsEqual<>(false)
            );
        } finally {
            h2.close();
        }
    }

    private int h2Put(final io.vertx.core.http.HttpClient h2, final int size)
        throws Exception {
        return h2.request(io.vertx.core.http.HttpMethod.PUT, this.port, HOST, "/repo/a.bin")
            .compose(
                req -> req.putHeader("Content-Length", Integer.toString(size))
                    .send(io.vertx.core.buffer.Buffer.buffer(new byte[size]))
            )
            .compose(rsp -> rsp.body().map(ignored -> rsp.statusCode()))
            .toCompletionStage().toCompletableFuture()
            .get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void start(final Slice slice) {
        this.start(slice, () -> LIMIT);
    }

    private void start(final Slice slice, final LongSupplier cap) {
        this.server = new VertxSliceServer(
            this.vertx,
            slice,
            new HttpServerOptions().setPort(0).setHost(HOST),
            Duration.ZERO,
            Duration.ofSeconds(5),
            VertxSliceServer.DEFAULT_BODY_BUFFER_THRESHOLD,
            cap
        );
        // Ephemeral port, read back from start(): probing then binding races (-T8).
        this.port = this.server.start();
    }
}
