/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.vertx;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import io.reactivex.Flowable;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.core.http.HttpServerRequest;
import io.vertx.reactivex.core.http.HttpServerResponse;

import java.io.Closeable;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x Slice.
 */
public final class VertxSliceServer implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VertxSliceServer.class);

    /**
     * The Vert.x.
     */
    private final Vertx vertx;

    /**
     * The slice to be served.
     */
    private final Slice served;

    /**
     * Represents options used by an HttpServer instance.
     */
    private final HttpServerOptions options;

    /**
     * The Http server reference (lock-free).
     */
    private final AtomicReference<HttpServer> serverRef;

    /**
     * @param vertx The vertx.
     * @param served The slice to be served.
     */
    public VertxSliceServer(final Vertx vertx, final Slice served) {
        this(vertx, served, new HttpServerOptions().setPort(0));
    }

    /**
     * @param served The slice to be served.
     * @param port The port.
     */
    public VertxSliceServer(final Slice served, final Integer port) {
        this(Vertx.vertx(), served, new HttpServerOptions().setPort(port));
    }

    /**
     * @param vertx The vertx.
     * @param served The slice to be served.
     * @param port The port.
     */
    public VertxSliceServer(Vertx vertx, Slice served, Integer port) {
        this(vertx, served, new HttpServerOptions().setPort(port));
    }

    /**
     * @param vertx The vertx.
     * @param served The slice to be served.
     * @param options The options to use.
     */
    public VertxSliceServer(Vertx vertx, Slice served, HttpServerOptions options) {
        this.vertx = vertx;
        this.served = served;
        this.options = options;
        this.serverRef = new AtomicReference<>();
    }

    /**
     * Get the configured port (may be 0 for random assignment).
     * Use actualPort() to get the port the server is actually listening on.
     * @return Configured port
     */
    public int configuredPort() {
        return options.getPort();
    }

    /**
     * Get the actual port the server is listening on.
     * @return Actual port, or -1 if server not started
     */
    public int actualPort() {
        HttpServer server = this.serverRef.get();
        return server != null ? server.actualPort() : -1;
    }

    /**
     * @deprecated Use configuredPort() or actualPort() instead
     * @return Configured port
     */
    @Deprecated
    public int port() {
        return configuredPort();
    }

    /**
     * Start the server.
     *
     * @return Port the server is listening on.
     */
    public int start() {
        HttpServer server = this.vertx.createHttpServer(this.options);
        server.requestHandler(this.proxyHandler());
        
        // Set atomically before blocking - fails if already started
        if (!this.serverRef.compareAndSet(null, server)) {
            throw new IllegalStateException("Server was already started");
        }
        
        // Block OUTSIDE of any lock
        server.rxListen().blockingGet();
        return server.actualPort();
    }

    /**
     * Stop the server.
     */
    public void stop() {
        HttpServer server = this.serverRef.getAndSet(null);
        if (server != null) {
            // Block OUTSIDE of any lock
            server.rxClose().blockingAwait();
        } else {
            LOGGER.warn("stop() called but server was not started");
        }
    }

    @Override
    public void close() {
        this.stop();
    }

    /**
     * A handler which proxy incoming requests to encapsulated slice.
     * @return The request handler.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private Handler<HttpServerRequest> proxyHandler() {
        return (HttpServerRequest req) -> {
            try {
                this.serve(req).exceptionally(
                    throwable -> {
                        VertxSliceServer.sendError(req.response(), throwable);
                        return null;
                    }
                );
            } catch (final Exception ex) {
                VertxSliceServer.sendError(req.response(), ex);
            }
        };
    }

    /**
     * Server HTTP request.
     *
     * @param req HTTP request.
     * @return Completion of request serving.
     */
    private CompletionStage<Void> serve(final HttpServerRequest req) {
        Headers requestHeaders = Headers.from(req.headers());
        AtomicReference<Response> artipieResponse = new AtomicReference<>();
        final boolean isHead = "HEAD".equals(req.method().name());
        return CompletableFuture.allOf(
            this.served.response(
                new RequestLine(req.method().name(), req.uri(), req.version().toString()),
                requestHeaders,
                new Content.From(
                    req.toFlowable().map(buffer -> ByteBuffer.wrap(buffer.getBytes()))
                )
            ).thenAccept(artipieResponse::set),
            continueResponseFut(requestHeaders, req.response())
        ).thenCompose(v -> {
            Response resp = artipieResponse.get();
            // For HEAD requests, pass full body - Vert.x will handle stripping it
            return VertxSliceServer.accept(req.response(), resp.status(), resp.headers(), resp.body(), isHead);
        });
    }


    private static CompletionStage<Void> accept(
        HttpServerResponse response, RsStatus status, Headers headers, Content body
    ) {
        return accept(response, status, headers, body, false);
    }

    private static CompletionStage<Void> accept(
        HttpServerResponse response, RsStatus status, Headers headers, Content body, boolean isHead
    ) {
        final CompletableFuture<Void> promise = new CompletableFuture<>();
        if (status == RsStatus.CONTINUE) {
            response.writeContinue();
            return CompletableFuture.completedFuture(null);
        }
        response.setStatusCode(status.code());
        headers.stream().forEach(h -> response.putHeader(h.getKey(), h.getValue()));
        
        if (isHead && LOGGER.isTraceEnabled()) {
            LOGGER.trace("HEAD request: Content-Length present={}, value={}", 
                response.headers().contains("Content-Length"),
                response.headers().get("Content-Length")
            );
        }
        
        final Flowable<Buffer> vpb = Flowable.fromPublisher(body)
            .map(VertxSliceServer::mapBuffer)
            .doOnError(promise::completeExceptionally);
        if (response.headers().contains("Content-Length")) {
            response.setChunked(false);
            if (isHead) {
                // For HEAD, consume body without writing to preserve Content-Length
                vpb.doOnComplete(
                    () -> {
                        response.end();
                        promise.complete(null);
                    }
                ).subscribe();
            } else {
                vpb.doOnComplete(
                    () -> {
                        response.end();
                        promise.complete(null);
                    }
                ).forEach(response::write);
            }
        } else {
            response.setChunked(true);
            if (isHead) {
                // For HEAD without Content-Length, just end immediately
                response.end();
                promise.complete(null);
            } else {
                vpb.doOnComplete(() -> promise.complete(null))
                    .subscribe(response.toSubscriber());
            }
        }
        return promise;
    }

    /**
     * Check if request expects 100-continue.
     *
     * @param headers Request headers
     * @return True if expects 100-continue
     */
    private static boolean expects100Continue(Headers headers) {
        for (Header h : headers.find("expect")) {
            if ("100-continue".equalsIgnoreCase(h.getValue())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Send continue response if expected.
     *
     * @param headers Request headers
     * @param response Response to write to
     * @return CompletableFuture
     */
    private static CompletableFuture<Void> continueResponseFut(Headers headers, HttpServerResponse response) {
        if (expects100Continue(headers)) {
            // Direct call - accept() already returns CompletionStage
            return VertxSliceServer.accept(
                response, RsStatus.CONTINUE, Headers.EMPTY, Content.EMPTY
            ).toCompletableFuture();
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Map {@link ByteBuffer} to {@link Buffer}.
     *
     * @param buffer Java byte buffer
     * @return Vertx buffer
     */
    private static Buffer mapBuffer(final ByteBuffer buffer) {
        final byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return Buffer.buffer(bytes);
    }

    /**
     * Sends response built from {@link Throwable}.
     *
     * @param response Response to write to.
     * @param throwable Exception to send.
     */
    private static void sendError(final HttpServerResponse response, final Throwable throwable) {
        response.setStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR);
        final StringWriter body = new StringWriter();
        body.append(throwable.toString()).append("\n");
        throwable.printStackTrace(new PrintWriter(body));
        response.end(body.toString());
    }
}
