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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.ResponseBuilder;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.reactivex.core.Vertx;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * HTTP/2 streamed uploads into a rio-backed {@link FileStorage}: every
 * upload must complete. Before the fix a rio writer thread could call
 * {@code request(n)} into the Vert.x HTTP/2 request after its body had
 * ended; {@code Http2ServerRequest.fetch} throws "Request has already been
 * read", the exception escapes rio's write task with its {@code running}
 * flag still set, the queued completion is never processed, the save never
 * completes and the client hangs until the idle timeout.
 *
 * @since 2.2.11
 */
final class VertxSliceServerHttp2UploadTest {

    /**
     * Number of uploads; each body end is one chance to hit the race.
     */
    private static final int UPLOADS = 400;

    /**
     * Uploads in flight at once (streams multiplexed on one connection).
     */
    private static final int PARALLEL = 8;

    /**
     * Frames written per body, spaced by a timer so the rio writer drains
     * its queue (and idles in its request loop) between frames.
     */
    private static final int FRAMES = 4;

    /**
     * Bytes per frame.
     */
    private static final int FRAME_BYTES = 1024;

    private Vertx vertx;

    private VertxSliceServer server;

    private HttpClient client;

    private int port;

    @BeforeEach
    void setUp(@TempDir final Path dir) {
        this.vertx = Vertx.vertx();
        final FileStorage storage = new FileStorage(dir);
        final AtomicInteger seq = new AtomicInteger();
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> storage.save(
                new Key.From("up", Integer.toString(seq.incrementAndGet())), body
            ).thenApply(nothing -> ResponseBuilder.created().build()),
            new HttpServerOptions().setPort(0).setHost("localhost")
                .setHttp2ClearTextEnabled(true),
            Duration.ZERO,
            Duration.ofSeconds(1),
            1L
        );
        this.port = this.server.start();
        this.client = this.vertx.getDelegate().createHttpClient(
            new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false)
                .setHttp2MaxPoolSize(1)
                .setHttp2MultiplexingLimit(PARALLEL)
        );
    }

    @AfterEach
    void tearDown() {
        this.client.close();
        this.server.close();
        this.vertx.close();
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void everyStreamedHttp2UploadCompletes() throws Exception {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger next = new AtomicInteger();
        final List<CompletableFuture<Void>> lanes = new ArrayList<>(PARALLEL);
        for (int lane = 0; lane < PARALLEL; lane += 1) {
            lanes.add(this.lane(next, created));
        }
        CompletableFuture.allOf(lanes.toArray(new CompletableFuture[0]))
            .get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(created.get(), new IsEqual<>(UPLOADS));
    }

    /**
     * One lane uploads sequentially until the shared counter is exhausted.
     * @param next Shared upload counter
     * @param created Count of 201 responses
     * @return Completes when the lane has no uploads left
     */
    private CompletableFuture<Void> lane(final AtomicInteger next, final AtomicInteger created) {
        if (next.getAndIncrement() >= UPLOADS) {
            return CompletableFuture.completedFuture(null);
        }
        return this.upload().thenCompose(
            status -> {
                if (status == 201) {
                    created.incrementAndGet();
                }
                return this.lane(next, created);
            }
        );
    }

    /**
     * Upload one body in {@link #FRAMES} timer-spaced frames.
     * @return Response status
     */
    private CompletableFuture<Integer> upload() {
        final CompletableFuture<Integer> status = new CompletableFuture<>();
        this.client.request(
            new RequestOptions().setMethod(HttpMethod.PUT).setHost("localhost")
                .setPort(this.port).setURI("/repo/file.bin")
        ).onFailure(status::completeExceptionally).onSuccess(
            req -> {
                req.putHeader("Content-Length", Integer.toString(FRAMES * FRAME_BYTES));
                req.response()
                    .onFailure(status::completeExceptionally)
                    .onSuccess(
                        (HttpClientResponse rsp) -> rsp.body().onComplete(
                            ignored -> status.complete(rsp.statusCode())
                        )
                    );
                this.writeFrames(req, 0);
            }
        );
        return status;
    }

    /**
     * Write frame {@code idx}, then schedule the next one on a timer.
     * @param req Client request
     * @param idx Frame index
     */
    private void writeFrames(final HttpClientRequest req, final int idx) {
        if (idx == FRAMES) {
            req.end();
            return;
        }
        req.write(Buffer.buffer(new byte[FRAME_BYTES]));
        this.vertx.getDelegate().setTimer(1L, id -> this.writeFrames(req, idx + 1));
    }
}
