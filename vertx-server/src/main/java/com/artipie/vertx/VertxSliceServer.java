/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.vertx;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.log.LogSanitizer;
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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x Slice.
 */
public final class VertxSliceServer implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VertxSliceServer.class);

    /**
     * Default maximum time to wait for slice response.
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(2);

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
     * Maximum time to process a single request.
     */
    private final Duration requestTimeout;

    /**
     * The Http server reference (lock-free).
     */
    private final AtomicReference<HttpServer> serverRef;

    /**
     * @param vertx The vertx.
     * @param served The slice to be served.
     */
    public VertxSliceServer(final Vertx vertx, final Slice served) {
        this(vertx, served, new HttpServerOptions().setPort(0), DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * @param served The slice to be served.
     * @param port The port.
     */
    public VertxSliceServer(final Slice served, final Integer port) {
        this(Vertx.vertx(), served, new HttpServerOptions().setPort(port), DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * @param vertx The vertx.
     * @param served The slice to be served.
     * @param port The port.
     */
    public VertxSliceServer(Vertx vertx, Slice served, Integer port) {
        this(vertx, served, new HttpServerOptions().setPort(port), DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * @param vertx The vertx.
     * @param served The slice to be served.
     * @param options The options to use.
     */
    public VertxSliceServer(Vertx vertx, Slice served, HttpServerOptions options) {
        this(vertx, served, options, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * @param vertx The vertx.
     * @param served The slice to be served.
     * @param port The port.
     * @param requestTimeout Maximum time to process a single request. Zero disables timeout enforcement.
     */
    public VertxSliceServer(
        final Vertx vertx,
        final Slice served,
        final Integer port,
        final Duration requestTimeout
    ) {
        this(vertx, served, new HttpServerOptions()
            .setPort(port)
            .setIdleTimeout(60)  // Close idle connections after 60 seconds
            .setTcpKeepAlive(true)
            .setTcpNoDelay(true), 
            requestTimeout);
    }

    /**
     * @param vertx The vertx.
     * @param served The slice to be served.
     * @param options The options to use.
     * @param requestTimeout Maximum time to process a single request. Zero disables timeout enforcement.
     */
    public VertxSliceServer(
        final Vertx vertx,
        final Slice served,
        final HttpServerOptions options,
        final Duration requestTimeout
    ) {
        this.vertx = Objects.requireNonNull(vertx, "vertx must not be null");
        this.served = Objects.requireNonNull(served, "served must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        if (requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be zero or positive");
        }
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
        final Headers requestHeaders = Headers.from(req.headers());
        
        // Extract or generate trace ID for request correlation
        final String traceId = com.artipie.http.trace.TraceContext.extractOrGenerate(requestHeaders);
        com.artipie.http.trace.TraceContext.set(traceId);
        
        LOGGER.info("Serving request: {} {}", req.method().name(), LogSanitizer.sanitizeUrl(req.uri()));
        
        final boolean isHead = "HEAD".equals(req.method().name());
        final CompletionStage<Response> response = withRequestTimeout(
            this.served.response(
                new RequestLine(req.method().name(), req.uri(), req.version().toString()),
                requestHeaders,
                new Content.From(
                    req.toFlowable().map(buffer -> ByteBuffer.wrap(buffer.getBytes()))
                )
            ),
            req
        );
        final CompletionStage<Void> continueFuture = continueResponseFut(requestHeaders, req.response());
        return response.thenCombine(continueFuture, (resp, ignored) -> resp)
            .thenCompose(
                resp -> {
                    // Ensure trace context is set for response handling
                    com.artipie.http.trace.TraceContext.set(traceId);
                    LOGGER.debug("Accepting response for: {} {}, status: {}", 
                        req.method().name(), LogSanitizer.sanitizeUrl(req.uri()), resp.status());
                    return VertxSliceServer.accept(req.response(), resp.status(), resp.headers(), resp.body(), isHead);
                }
            )
            .whenComplete((result, error) -> {
                // Ensure trace context is set for completion logging
                com.artipie.http.trace.TraceContext.set(traceId);
                if (error != null) {
                    LOGGER.error("Request failed: {} {}, error: {}", 
                        req.method().name(), LogSanitizer.sanitizeUrl(req.uri()), 
                        LogSanitizer.sanitizeMessage(error.getMessage()));
                } else {
                    LOGGER.debug("Request completed successfully: {} {}", 
                        req.method().name(), LogSanitizer.sanitizeUrl(req.uri()));
                }
                // Clean up trace context after request completes
                com.artipie.http.trace.TraceContext.clear();
            });
    }

    private CompletionStage<Response> withRequestTimeout(
        final CompletionStage<Response> original,
        final HttpServerRequest req
    ) {
        if (this.requestTimeout.isZero()) {
            return original;
        }
        final CompletableFuture<Response> delegate = toFuture(original);
        final CompletableFuture<Response> guarded = new CompletableFuture<>();
        final long timerId = this.vertx.setTimer(
            this.requestTimeout.toMillis(),
            ignored -> {
                final RequestTimeoutException timeout = new RequestTimeoutException(this.requestTimeout);
                if (guarded.completeExceptionally(timeout)) {
                    delegate.cancel(true);
                }
            }
        );
        delegate.whenComplete((resp, error) -> {
            this.vertx.cancelTimer(timerId);
            if (error != null) {
                guarded.completeExceptionally(error);
            } else {
                guarded.complete(resp);
            }
        });
        return guarded.handle((resp, error) -> {
            if (error == null) {
                return resp;
            }
            final Throwable cause = unwrapCompletionCause(error);
            if (cause instanceof RequestTimeoutException timeout) {
                LOGGER.warn(
                    "Upstream processing exceeded {} ms for {} {}",
                    timeout.timeout.toMillis(),
                    req.method(),
                    req.uri()
                );
                return ResponseBuilder.unavailable()
                    .textBody(
                        String.format(
                            "Processing timed out after %d ms",
                            timeout.timeout.toMillis()
                        )
                    )
                    .build();
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new CompletionException(cause);
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

        final ResponseTerminator terminator = new ResponseTerminator(response, promise);

        if (body == null || (body.size().isPresent() && body.size().get() == 0L)) {
            terminator.end();
            return promise;
        }

        final Flowable<Buffer> vpb = Flowable.fromPublisher(body)
            .map(VertxSliceServer::mapBuffer);
        if (response.headers().contains("Content-Length")) {
            response.setChunked(false);
            if (isHead) {
                vpb.subscribe(
                    buffer -> { },
                    terminator::fail,
                    terminator::end
                );
            } else {
                vpb.subscribe(
                    response::write,
                    terminator::fail,
                    terminator::end
                );
            }
        } else {
            response.setChunked(true);
            if (isHead) {
                terminator.end();
            } else {
                response.endHandler(ignored -> {
                    LOGGER.debug("Completing chunked response");
                    terminator.completeWithoutEnding();
                });
                vpb.doOnSubscribe(subscription -> LOGGER.debug("Subscribed to chunked response body"))
                    .doOnError(terminator::fail)
                    .subscribe(response.toSubscriber());
            }
        }
        return promise;
    }

    private static <T> CompletableFuture<T> toFuture(final CompletionStage<T> stage) {
        if (stage instanceof CompletableFuture<T> future) {
            return future;
        }
        final CompletableFuture<T> wrapper = new CompletableFuture<>();
        stage.whenComplete((value, error) -> {
            if (error != null) {
                wrapper.completeExceptionally(error);
            } else {
                wrapper.complete(value);
            }
        });
        return wrapper;
    }

    private static Throwable unwrapCompletionCause(final Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static final class ResponseTerminator {
        private final HttpServerResponse response;
        private final CompletableFuture<Void> promise;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        ResponseTerminator(final HttpServerResponse response, final CompletableFuture<Void> promise) {
            this.response = response;
            this.promise = promise;
        }

        void end() {
            if (this.finished.compareAndSet(false, true)) {
                this.response.end();
                this.promise.complete(null);
            } else {
                LOGGER.debug("Duplicate response completion suppressed (end)");
            }
        }

        void completeWithoutEnding() {
            if (this.finished.compareAndSet(false, true)) {
                this.promise.complete(null);
            } else {
                LOGGER.debug("Duplicate response completion suppressed (handler)");
            }
        }

        void fail(final Throwable error) {
            if (this.finished.compareAndSet(false, true)) {
                LOGGER.error("Error streaming response: {}", error.getMessage(), error);
                this.promise.completeExceptionally(error);
            } else {
                LOGGER.debug("Late failure after response completion: {}", error.getMessage());
            }
        }
    }

    private static final class RequestTimeoutException extends RuntimeException {
        private final Duration timeout;

        RequestTimeoutException(final Duration timeout) {
            super(String.format("Timed out after %d ms", timeout.toMillis()));
            this.timeout = timeout;
        }
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
