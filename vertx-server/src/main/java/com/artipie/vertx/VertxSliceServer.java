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
import com.artipie.http.log.EcsLogEvent;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.slice.EcsLoggingSlice;
import com.artipie.http.log.LogSanitizer;
import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Transaction;
import com.artipie.http.rq.RequestLine;
import io.reactivex.Flowable;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.SocketAddress;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.core.http.HttpServerRequest;
import io.vertx.reactivex.core.http.HttpServerResponse;

import java.io.Closeable;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Objects;

/**
 * Vert.x Slice.
 */
public final class VertxSliceServer implements Closeable {

    /**
     * Default maximum time to wait for slice response.
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(2);

    /**
     * HTTP/2 forbidden headers per RFC 7540 Section 8.1.2.
     * These connection-specific headers MUST NOT be included in HTTP/2 messages.
     */
    private static final Set<String> HTTP2_FORBIDDEN_HEADERS = Set.of(
        "connection",
        "keep-alive",
        "proxy-connection",
        "transfer-encoding",
        "upgrade"
    );

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
            .setTcpNoDelay(true)
            .setUseAlpn(true)  // Enable ALPN for HTTP/2 negotiation
            .setHttp2ClearTextEnabled(true)  // Enable HTTP/2 over cleartext (h2c) for AWS NLB
            // HTTP Compression - matches JFrog/Nexus performance characteristics
            .setCompressionSupported(true)  // Enable gzip/deflate compression
            .setCompressionLevel(6),        // Balanced compression (1=fast, 9=best)
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
            EcsLogger.warn("com.artipie.vertx")
                .message("stop() called but server was not started")
                .eventCategory("http")
                .eventAction("server_stop")
                .eventOutcome("skipped")
                .log();
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
            // Create APM transaction for this HTTP request
            final Transaction transaction = ElasticApm.startTransaction();

            // Create request ID for logging and debugging
            final String requestId = req.method().name() + " " + extractRouteName(req.uri())
                + " (" + Integer.toHexString(System.identityHashCode(req)) + ")";

            // CRITICAL: Create guarded response to prevent race conditions between
            // timeout handler, normal completion, and error handlers.
            // This ensures response.end() is called exactly once.
            final GuardedHttpServerResponse guardedResponse = new GuardedHttpServerResponse(
                req.response(), requestId
            );

            try {
                // Set transaction metadata
                transaction.setType(Transaction.TYPE_REQUEST);
                transaction.setName(req.method().name() + " " + extractRouteName(req.uri()));
                transaction.addLabel("http.method", req.method().name());
                transaction.addLabel("http.url", req.uri());
                transaction.addLabel("http.version", req.version().toString());

                // Add remote address if available
                if (req.remoteAddress() != null) {
                    transaction.addLabel("client.ip", req.remoteAddress().host());
                }

                // ENTERPRISE STREAMING: Stream request body directly to slice without buffering
                // For large uploads (artifacts), this avoids loading entire body into heap
                final boolean hasBody = req.headers().contains("Content-Length")
                    && !"0".equals(req.getHeader("Content-Length"));
                if (hasBody) {
                    // STREAMING PATH: Convert Vert.x request to reactive stream
                    // This allows slice to process body as it arrives without buffering
                    final long contentLength = Long.parseLong(req.getHeader("Content-Length"));

                    // For small bodies (<1MB), buffer for simplicity and error handling
                    // For large bodies (>=1MB), stream directly to avoid OOM
                    if (contentLength < 1_048_576) {
                        // Small body - buffer for simpler error handling
                        req.bodyHandler(body -> {
                            EcsLogger.debug("com.artipie.vertx")
                                .message("Small request body buffered")
                                .eventCategory("http")
                                .eventAction("request_buffer")
                                .field("http.request.body.bytes", body.length())
                                .log();
                            this.serveWithBody(req, body, guardedResponse).whenComplete((result, throwable) -> {
                                try {
                                    if (throwable != null) {
                                        EcsLogger.error("com.artipie.vertx")
                                            .message("Request serving failed")
                                            .eventCategory("http")
                                            .eventAction("request_serve")
                                            .eventOutcome("failure")
                                            .error(throwable)
                                            .log();
                                        transaction.captureException(throwable);
                                        transaction.setResult("error");
                                        guardedResponse.safeSendError(
                                            "proxyHandler.serveWithBody.error",
                                            HttpURLConnection.HTTP_INTERNAL_ERROR,
                                            formatError(throwable)
                                        );
                                    } else {
                                        transaction.setResult("success");
                                    }
                                } finally {
                                    transaction.end();
                                }
                            });
                        });
                    } else {
                        // Large body - stream directly to slice
                        EcsLogger.debug("com.artipie.vertx")
                            .message("Streaming large request body")
                            .eventCategory("http")
                            .eventAction("request_stream")
                            .field("http.request.body.bytes", contentLength)
                            .log();
                        this.serveWithStream(req, contentLength, guardedResponse).whenComplete((result, throwable) -> {
                            try {
                                if (throwable != null) {
                                    EcsLogger.error("com.artipie.vertx")
                                        .message("Request serving failed (streaming)")
                                        .eventCategory("http")
                                        .eventAction("request_serve")
                                        .eventOutcome("failure")
                                        .error(throwable)
                                        .log();
                                    transaction.captureException(throwable);
                                    transaction.setResult("error");
                                    guardedResponse.safeSendError(
                                        "proxyHandler.serveWithStream.error",
                                        HttpURLConnection.HTTP_INTERNAL_ERROR,
                                        formatError(throwable)
                                    );
                                } else {
                                    transaction.setResult("success");
                                }
                            } finally {
                                transaction.end();
                            }
                        });
                    }
                } else {
                    // No body, serve immediately
                    this.serve(req, null, guardedResponse).whenComplete((result, throwable) -> {
                        try {
                            if (throwable != null) {
                                EcsLogger.error("com.artipie.vertx")
                                    .message("Request serving failed")
                                    .eventCategory("http")
                                    .eventAction("request_serve")
                                    .eventOutcome("failure")
                                    .error(throwable)
                                    .log();
                                transaction.captureException(throwable);
                                transaction.setResult("error");
                                guardedResponse.safeSendError(
                                    "proxyHandler.serve.error",
                                    HttpURLConnection.HTTP_INTERNAL_ERROR,
                                    formatError(throwable)
                                );
                            } else {
                                transaction.setResult("success");
                            }
                        } finally {
                            transaction.end();
                        }
                    });
                }
            } catch (final Exception ex) {
                EcsLogger.error("com.artipie.vertx")
                    .message("Exception in proxy handler")
                    .eventCategory("http")
                    .eventAction("request_handle")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
                transaction.captureException(ex);
                transaction.setResult("error");
                transaction.end();
                guardedResponse.safeSendError(
                    "proxyHandler.exception",
                    HttpURLConnection.HTTP_INTERNAL_ERROR,
                    formatError(ex)
                );
            }
        };
    }

    /**
     * Format exception for error response.
     */
    private static String formatError(final Throwable throwable) {
        final StringWriter body = new StringWriter();
        body.append(throwable.toString()).append("\n");
        throwable.printStackTrace(new PrintWriter(body));
        return body.toString();
    }

    /**
     * Extract route name from URI for transaction naming.
     * Simplifies paths like /maven-central/com/foo/bar/1.0/artifact.jar to /maven-central/*
     */
    private static String extractRouteName(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        // Split by '/' and keep first 2 segments (e.g., /maven-central/com/... -> /maven-central/*)
        String[] parts = uri.split("/");
        if (parts.length <= 2) {
            return uri;
        }
        // Return repo name pattern: /repo-name/*
        return "/" + parts[1] + "/*";
    }

    /**
     * Serve HTTP request with streaming body (for large uploads).
     *
     * <p>ENTERPRISE STREAMING: This method converts Vert.x request stream directly to
     * reactive Content without buffering, enabling memory-efficient large file uploads.</p>
     *
     * @param req HTTP request.
     * @param contentLength Known content length for pre-allocation optimization.
     * @param guardedResponse Thread-safe response guard.
     * @return Completion of request serving.
     */
    private CompletionStage<Void> serveWithStream(
        final HttpServerRequest req,
        final long contentLength,
        final GuardedHttpServerResponse guardedResponse
    ) {
        final Headers requestHeaders = Headers.from(req.headers());
        final RequestLogContext ctx = RequestLogContext.from(req, requestHeaders);
        final Slice loggedSlice = new EcsLoggingSlice(this.served, ctx.remoteHost());

        // Extract or generate trace ID for request correlation
        final String traceId = com.artipie.http.trace.TraceContext.extractOrGenerate(requestHeaders);
        com.artipie.http.trace.TraceContext.set(traceId);

        addRequestContext(
            EcsLogger.info("com.artipie.vertx")
                .message("Serving request with streaming body")
                .eventCategory("http")
                .eventAction("request_serve_stream")
                .field("http.request.method", req.method().name())
                .field("url.path", LogSanitizer.sanitizeUrl(req.uri()))
                .field("http.request.body.bytes", contentLength),
            ctx
        ).log();

        final boolean isHead = "HEAD".equals(req.method().name());

        // STREAMING: Convert Vert.x request stream to reactive Publisher without buffering
        // req.toFlowable() returns Flowable<Buffer> which we map to ByteBuffer
        final Flowable<ByteBuffer> bodyStream = req.toFlowable()
            .map(buffer -> {
                final byte[] bytes = buffer.getBytes();
                return ByteBuffer.wrap(bytes);
            });

        // Create Content with known size for optimal downstream processing
        final Content requestBody = new Content.From(contentLength, bodyStream);

        final CompletionStage<Response> response = withRequestTimeout(
            loggedSlice.response(
                new RequestLine(req.method().name(), req.uri(), req.version().toString()),
                requestHeaders,
                requestBody
            ),
            req
        );
        final CompletionStage<Void> continueFuture = continueResponseFut(requestHeaders, req.response());
        return response.thenCombine(continueFuture, (resp, ignored) -> resp)
            .thenCompose(
                resp -> {
                    com.artipie.http.trace.TraceContext.set(traceId);
                    addRequestContext(
                        EcsLogger.debug("com.artipie.vertx")
                            .message("Accepting response (streaming)")
                            .eventCategory("http")
                            .eventAction("response_accept")
                            .field("http.request.method", req.method().name())
                            .field("url.path", LogSanitizer.sanitizeUrl(req.uri()))
                            .field("http.response.status_code", resp.status().code()),
                        ctx
                    ).log();

                    final Transaction transaction = ElasticApm.currentTransaction();
                    if (transaction != null) {
                        transaction.addLabel("http.status_code", String.valueOf(resp.status().code()));
                    }

                    return VertxSliceServer.accept(req.response(), resp.status(), resp.headers(), resp.body(), isHead, req.version(), guardedResponse);
                }
            )
            .whenComplete((result, error) -> {
                com.artipie.http.trace.TraceContext.set(traceId);
                if (error != null) {
                    addRequestContext(
                        EcsLogger.error("com.artipie.vertx")
                            .message("Request failed (streaming)")
                            .eventCategory("http")
                            .eventAction("request_serve")
                            .eventOutcome("failure")
                            .field("http.request.method", req.method().name())
                            .field("url.path", LogSanitizer.sanitizeUrl(req.uri()))
                            .error(error),
                        ctx
                    ).log();
                } else {
                    addRequestContext(
                        EcsLogger.debug("com.artipie.vertx")
                            .message("Request completed successfully (streaming)")
                            .eventCategory("http")
                            .eventAction("request_serve")
                            .eventOutcome("success")
                            .field("http.request.method", req.method().name())
                            .field("url.path", LogSanitizer.sanitizeUrl(req.uri())),
                        ctx
                    ).log();
                }
                com.artipie.http.trace.TraceContext.clear();
            });
    }

    /**
     * Server HTTP request with buffered body (for small uploads <1MB).
     *
     * @param req HTTP request.
     * @param body Buffered request body (null if no body).
     * @param guardedResponse Thread-safe response guard.
     * @return Completion of request serving.
     */
    private CompletionStage<Void> serveWithBody(
        final HttpServerRequest req,
        final Buffer body,
        final GuardedHttpServerResponse guardedResponse
    ) {
        final Headers requestHeaders = Headers.from(req.headers());
        final RequestLogContext ctx = RequestLogContext.from(req, requestHeaders);
        final Slice loggedSlice = new EcsLoggingSlice(this.served, ctx.remoteHost());

        // Extract or generate trace ID for request correlation
        final String traceId = com.artipie.http.trace.TraceContext.extractOrGenerate(requestHeaders);
        com.artipie.http.trace.TraceContext.set(traceId);

        addRequestContext(
            EcsLogger.info("com.artipie.vertx")
                .message("Serving request with body")
                .eventCategory("http")
                .eventAction("request_serve")
                .field("http.request.method", req.method().name())
                .field("url.path", LogSanitizer.sanitizeUrl(req.uri())),
            ctx
        ).log();

        final boolean isHead = "HEAD".equals(req.method().name());

        // Body already buffered, convert to Content
        final Content requestBody = new Content.From(
            Flowable.just(ByteBuffer.wrap(body.getBytes()))
        );

        final CompletionStage<Response> response = withRequestTimeout(
            loggedSlice.response(
                new RequestLine(req.method().name(), req.uri(), req.version().toString()),
                requestHeaders,
                requestBody
            ),
            req
        );
        final CompletionStage<Void> continueFuture = continueResponseFut(requestHeaders, req.response());
        return response.thenCombine(continueFuture, (resp, ignored) -> resp)
            .thenCompose(
                resp -> {
                    // Ensure trace context is set for response handling
                    com.artipie.http.trace.TraceContext.set(traceId);
                    addRequestContext(
                        EcsLogger.debug("com.artipie.vertx")
                            .message("Accepting response")
                            .eventCategory("http")
                            .eventAction("response_accept")
                            .field("http.request.method", req.method().name())
                            .field("url.path", LogSanitizer.sanitizeUrl(req.uri()))
                            .field("http.response.status_code", resp.status().code()),
                        ctx
                    ).log();

                    // Add HTTP status to current APM transaction
                    final Transaction transaction = ElasticApm.currentTransaction();
                    if (transaction != null) {
                        transaction.addLabel("http.status_code", String.valueOf(resp.status().code()));
                    }

                    return VertxSliceServer.accept(req.response(), resp.status(), resp.headers(), resp.body(), isHead, req.version(), guardedResponse);
                }
            )
            .whenComplete((result, error) -> {
                // Ensure trace context is set for completion logging
                com.artipie.http.trace.TraceContext.set(traceId);
                if (error != null) {
                    addRequestContext(
                        EcsLogger.error("com.artipie.vertx")
                            .message("Request failed")
                            .eventCategory("http")
                            .eventAction("request_serve")
                            .eventOutcome("failure")
                            .field("http.request.method", req.method().name())
                            .field("url.path", LogSanitizer.sanitizeUrl(req.uri()))
                            .error(error),
                        ctx
                    ).log();
                } else {
                    addRequestContext(
                        EcsLogger.debug("com.artipie.vertx")
                            .message("Request completed successfully")
                            .eventCategory("http")
                            .eventAction("request_serve")
                            .eventOutcome("success")
                            .field("http.request.method", req.method().name())
                            .field("url.path", LogSanitizer.sanitizeUrl(req.uri())),
                        ctx
                    ).log();
                }
                // Clean up trace context after request completes
                com.artipie.http.trace.TraceContext.clear();
            });
    }

    /**
     * Server HTTP request without body.
     *
     * @param req HTTP request.
     * @param unused Unused parameter for signature compatibility.
     * @param guardedResponse Thread-safe response guard.
     * @return Completion of request serving.
     */
    private CompletionStage<Void> serve(
        final HttpServerRequest req,
        final Buffer unused,
        final GuardedHttpServerResponse guardedResponse
    ) {
        final Headers requestHeaders = Headers.from(req.headers());
        final RequestLogContext ctx = RequestLogContext.from(req, requestHeaders);
        final Slice loggedSlice = new EcsLoggingSlice(this.served, ctx.remoteHost());

        // Extract or generate trace ID for request correlation
        final String traceId = com.artipie.http.trace.TraceContext.extractOrGenerate(requestHeaders);
        com.artipie.http.trace.TraceContext.set(traceId);

        addRequestContext(
            EcsLogger.info("com.artipie.vertx")
                .message("Serving request without body")
                .eventCategory("http")
                .eventAction("request_serve")
                .field("http.request.method", req.method().name())
                .field("url.path", LogSanitizer.sanitizeUrl(req.uri())),
            ctx
        ).log();

        final boolean isHead = "HEAD".equals(req.method().name());

        // No body for this request
        final Content requestBody = Content.EMPTY;

        final CompletionStage<Response> response = withRequestTimeout(
            loggedSlice.response(
                new RequestLine(req.method().name(), req.uri(), req.version().toString()),
                requestHeaders,
                requestBody
            ),
            req
        );
        final CompletionStage<Void> continueFuture = continueResponseFut(requestHeaders, req.response());
        return response.thenCombine(continueFuture, (resp, ignored) -> resp)
            .thenCompose(
                resp -> {
                    // Ensure trace context is set for response handling
                    com.artipie.http.trace.TraceContext.set(traceId);
                    addRequestContext(
                        EcsLogger.debug("com.artipie.vertx")
                            .message("Accepting response")
                            .eventCategory("http")
                            .eventAction("response_accept")
                            .field("http.request.method", req.method().name())
                            .field("url.path", LogSanitizer.sanitizeUrl(req.uri()))
                            .field("http.response.status_code", resp.status().code()),
                        ctx
                    ).log();

                    // Add HTTP status to current APM transaction
                    final Transaction transaction = ElasticApm.currentTransaction();
                    if (transaction != null) {
                        transaction.addLabel("http.status_code", String.valueOf(resp.status().code()));
                    }

                    return VertxSliceServer.accept(req.response(), resp.status(), resp.headers(), resp.body(), isHead, req.version(), guardedResponse);
                }
            )
            .whenComplete((result, error) -> {
                // Ensure trace context is set for completion logging
                com.artipie.http.trace.TraceContext.set(traceId);
                if (error != null) {
                    addRequestContext(
                        EcsLogger.error("com.artipie.vertx")
                            .message("Request failed")
                            .eventCategory("http")
                            .eventAction("request_serve")
                            .eventOutcome("failure")
                            .field("http.request.method", req.method().name())
                            .field("url.path", LogSanitizer.sanitizeUrl(req.uri()))
                            .error(error),
                        ctx
                    ).log();
                } else {
                    addRequestContext(
                        EcsLogger.debug("com.artipie.vertx")
                            .message("Request completed successfully")
                            .eventCategory("http")
                            .eventAction("request_serve")
                            .eventOutcome("success")
                            .field("http.request.method", req.method().name())
                            .field("url.path", LogSanitizer.sanitizeUrl(req.uri())),
                        ctx
                    ).log();
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
                final RequestLogContext timeoutCtx = RequestLogContext.from(req, Headers.from(req.headers()));
                addRequestContext(
                    EcsLogger.warn("com.artipie.vertx")
                        .message("Upstream processing timeout exceeded (" + timeout.timeout.toMillis() + "ms)")
                        .eventCategory("http")
                        .eventAction("request_timeout")
                        .eventOutcome("timeout")
                        .field("http.request.method", req.method().name())
                        .field("url.path", LogSanitizer.sanitizeUrl(req.uri())),
                    timeoutCtx
                ).log();
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
        HttpServerResponse response, RsStatus status, Headers headers, Content body,
        boolean isHead, io.vertx.core.http.HttpVersion version,
        GuardedHttpServerResponse guardedResponse
    ) {
        final CompletableFuture<Void> promise = new CompletableFuture<>();

        // Check if response already terminated by another code path (e.g., timeout handler)
        if (guardedResponse.isTerminated()) {
            EcsLogger.debug("com.artipie.vertx")
                .message("Response already terminated, skipping accept")
                .eventCategory("http")
                .eventAction("response_accept")
                .field("http.response.status_code", status.code())
                .log();
            promise.complete(null);
            return promise;
        }

        if (status == RsStatus.CONTINUE) {
            response.writeContinue();
            return CompletableFuture.completedFuture(null);
        }
        response.setStatusCode(status.code());

        // Filter HTTP/2 forbidden headers per RFC 7540 Section 8.1.2
        // Connection-specific headers MUST NOT be included in HTTP/2 messages
        final Headers filteredHeaders = version == HttpVersion.HTTP_2
            ? filterHttp2ForbiddenHeaders(headers)
            : headers;

        filteredHeaders.stream().forEach(h -> response.putHeader(h.getKey(), h.getValue()));

        final ResponseTerminator terminator = new ResponseTerminator(response, promise, guardedResponse);

        if (body == null || (body.size().isPresent() && body.size().get() == 0L)) {
            terminator.end();
            return promise;
        }

        final Flowable<Buffer> vpb = Flowable.fromPublisher(body)
            .map(VertxSliceServer::mapBuffer);
        if (response.headers().contains("Content-Length")) {
            response.setChunked(false);
            if (isHead) {
                // CRITICAL: Must execute on Vert.x event loop thread for thread safety
                // DO NOT use observeOn() - it breaks Vert.x threading model and causes corruption
                vpb.subscribe(
                    buffer -> { },
                    error -> {
                        EcsLogger.error("com.artipie.vertx")
                            .message("Error in HEAD response body")
                            .eventCategory("http")
                            .eventAction("response_write")
                            .eventOutcome("failure")
                            .error(error)
                            .log();
                        terminator.fail(error);
                    },
                    () -> {
                        EcsLogger.debug("com.artipie.vertx")
                            .message("HEAD response body fully written")
                            .eventCategory("http")
                            .eventAction("response_write")
                            .eventOutcome("success")
                            .field("http.response.body.bytes", true)
                            .log();
                        terminator.end();
                    }
                );
            } else {
                // PRODUCTION-GRADE BACKPRESSURE STREAMING
                // Uses response.toSubscriber() which provides built-in backpressure via
                // WriteStreamSubscriber - the same mechanism used for chunked responses.
                // This handles writeQueueFull()/drainHandler() automatically.
                //
                // IMPORTANT: response.toSubscriber() automatically calls response.end() when
                // the Flowable completes. We must NOT call terminator.end() ourselves, as that
                // would cause "Response has already been written" errors.
                // Instead, use endHandler to detect when Vert.x finishes writing.
                final java.util.concurrent.atomic.AtomicLong bytesWritten = new java.util.concurrent.atomic.AtomicLong(0);
                final long expectedBytes = response.headers().contains("Content-Length")
                    ? Long.parseLong(response.headers().get("Content-Length"))
                    : -1L;

                // Register end handler BEFORE subscribing - Vert.x will call this when
                // response.end() is invoked by toSubscriber()
                response.endHandler(ignored -> {
                    final long bytes = bytesWritten.get();
                    if (expectedBytes > 0 && bytes != expectedBytes) {
                        EcsLogger.error("com.artipie.vertx")
                            .message("Incomplete transfer detected")
                            .eventCategory("http")
                            .eventAction("response_write")
                            .eventOutcome("failure")
                            .field("http.response.body.bytes", bytes)
                            .field("http.response.body.expected", expectedBytes)
                            .log();
                        terminator.fail(new java.io.IOException(
                            String.format("Incomplete transfer: expected %d bytes, wrote %d bytes",
                                expectedBytes, bytes)));
                    } else {
                        terminator.completeWithoutEnding();
                    }
                });

                // Use toSubscriber() for backpressure - it will call response.end() on completion
                vpb.doOnNext(buffer -> bytesWritten.addAndGet(buffer.length()))
                   .doOnError(error -> {
                       EcsLogger.error("com.artipie.vertx")
                           .message("Error streaming response body")
                           .eventCategory("http")
                           .eventAction("response_write")
                           .eventOutcome("failure")
                           .field("http.response.body.bytes", bytesWritten.get())
                           .error(error)
                           .log();
                       terminator.fail(error);
                   })
                   .subscribe(response.toSubscriber());
            }
        } else {
            response.setChunked(true);
            if (isHead) {
                terminator.end();
            } else {
                response.endHandler(ignored -> {
                    EcsLogger.debug("com.artipie.vertx")
                        .message("Completing chunked response")
                        .eventCategory("http")
                        .eventAction("response_write")
                        .eventOutcome("success")
                        .log();
                    terminator.completeWithoutEnding();
                });
                // CRITICAL: Must execute on Vert.x event loop thread for thread safety
                // DO NOT use observeOn() - it breaks Vert.x threading model and causes corruption
                vpb.doOnSubscribe(subscription ->
                    EcsLogger.debug("com.artipie.vertx")
                        .message("Subscribed to chunked response body")
                        .eventCategory("http")
                        .eventAction("response_subscribe")
                        .log()
                )
                    .doOnError(terminator::fail)
                    .subscribe(response.toSubscriber());
            }
        }
        return promise;
    }

    /**
     * Attach common request metadata to ECS logs.
     * @param logger Logger builder
     * @param ctx Request context
     * @return Enriched logger
     */
    private static EcsLogger addRequestContext(final EcsLogger logger, final RequestLogContext ctx) {
        // Note: client.ip is added automatically by EcsLogger from MDC
        if (ctx.remotePort() >= 0) {
            logger.field("client.port", ctx.remotePort());
        }
        if (ctx.userAgent() != null && !ctx.userAgent().isEmpty()) {
            logger.field("user_agent.original", ctx.userAgent());
        }
        ctx.username().ifPresent(logger::userName);
        if (!ctx.headers().isEmpty()) {
            logger.field("http.request.headers", ctx.headers());
        }
        return logger;
    }

    /**
     * Request metadata snapshot for logging.
     */
    private static final class RequestLogContext {

        private final String remoteHost;
        private final int remotePort;
        private final String clientIp;
        private final String userAgent;
        private final Optional<String> username;
        private final Map<String, String> headers;

        private RequestLogContext(
            final String remoteHost,
            final int remotePort,
            final String clientIp,
            final String userAgent,
            final Optional<String> username,
            final Map<String, String> headers
        ) {
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.clientIp = clientIp;
            this.userAgent = userAgent;
            this.username = username;
            this.headers = headers;
        }

        static RequestLogContext from(final HttpServerRequest req, final Headers headers) {
            final SocketAddress remote = req.remoteAddress();
            final String host = remote == null ? "unknown" : remote.host();
            final int port = remote == null ? -1 : remote.port();
            final String clientIp = EcsLogEvent.extractClientIp(headers, host);
            // Try MDC first (set by auth middleware), then fall back to header extraction
            final String mdcUser = org.slf4j.MDC.get("user.name");
            final Optional<String> username = (mdcUser != null && !mdcUser.isEmpty())
                ? Optional.of(mdcUser)
                : EcsLogEvent.extractUsername(headers);
            final String userAgent = headers.values("user-agent").stream().findFirst().orElse(null);
            final Map<String, String> snapshot = captureHeaders(headers);
            return new RequestLogContext(host, port, clientIp, userAgent, username, snapshot);
        }

        String remoteHost() {
            return this.remoteHost;
        }

        int remotePort() {
            return this.remotePort;
        }

        String clientIp() {
            return this.clientIp;
        }

        String userAgent() {
            return this.userAgent;
        }

        Optional<String> username() {
            return this.username;
        }

        Map<String, String> headers() {
            return this.headers;
        }
    }

    /**
     * Capture a sanitized snapshot of incoming headers for auditing.
     * @param headers Request headers
     * @return Map of header names to values
     */
    private static Map<String, String> captureHeaders(final Headers headers) {
        final Map<String, String> snapshot = new LinkedHashMap<>();
        for (Header header : headers) {
            final String key = header.getKey();
            if (key == null) {
                continue;
            }
            String value = header.getValue();
            if (value == null) {
                continue;
            }
            if ("authorization".equalsIgnoreCase(key) || "proxy-authorization".equalsIgnoreCase(key)) {
                value = "<redacted>";
            } else if (value.length() > 256) {
                value = value.substring(0, 256) + "...";
            }
            final String normalized = key.toLowerCase(Locale.ROOT);
            snapshot.merge(normalized, value, (existing, update) -> existing + ", " + update);
            if (snapshot.size() >= 25) {
                break;
            }
        }
        return snapshot;
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
        private final GuardedHttpServerResponse guardedResponse;

        ResponseTerminator(
            final HttpServerResponse response,
            final CompletableFuture<Void> promise,
            final GuardedHttpServerResponse guardedResponse
        ) {
            this.response = response;
            this.promise = promise;
            this.guardedResponse = guardedResponse;
        }

        void end() {
            if (this.finished.compareAndSet(false, true)) {
                // Use guarded response to safely end - this prevents double-end race conditions
                if (this.guardedResponse.safeEnd("ResponseTerminator.end")) {
                    this.promise.complete(null);
                } else {
                    // Response already ended by another path (e.g., timeout handler)
                    this.promise.complete(null);
                }
            } else {
                EcsLogger.debug("com.artipie.vertx")
                    .message("Duplicate response completion suppressed (method: end)")
                    .eventCategory("http")
                    .eventAction("response_complete")
                    .log();
            }
        }

        void completeWithoutEnding() {
            if (this.finished.compareAndSet(false, true)) {
                // Mark as terminated so other paths know response is done
                this.guardedResponse.markTerminated("ResponseTerminator.completeWithoutEnding");
                this.promise.complete(null);
            } else {
                EcsLogger.debug("com.artipie.vertx")
                    .message("Duplicate response completion suppressed (method: handler)")
                    .eventCategory("http")
                    .eventAction("response_complete")
                    .log();
            }
        }

        void fail(final Throwable error) {
            if (this.finished.compareAndSet(false, true)) {
                EcsLogger.error("com.artipie.vertx")
                    .message("Error streaming response")
                    .eventCategory("http")
                    .eventAction("response_stream")
                    .eventOutcome("failure")
                    .error(error)
                    .log();
                // CRITICAL: Must end response even on error to decrement counter
                // Use guarded response to prevent double-end race conditions
                if (!this.guardedResponse.isTerminated()) {
                    try {
                        // Only set error status if headers haven't been sent yet
                        if (!this.response.headWritten()) {
                            final String errorMsg = String.format(
                                "Internal Server Error: %s: %s",
                                error.getClass().getSimpleName(),
                                error.getMessage()
                            );
                            this.guardedResponse.safeSendError(
                                "ResponseTerminator.fail",
                                HttpURLConnection.HTTP_INTERNAL_ERROR,
                                errorMsg
                            );
                        } else {
                            // Headers already sent, just end the response
                            this.guardedResponse.safeEnd("ResponseTerminator.fail");
                        }
                    } catch (Exception e) {
                        EcsLogger.warn("com.artipie.vertx")
                            .message("Failed to end error response")
                            .eventCategory("http")
                            .eventAction("response_end")
                            .eventOutcome("failure")
                            .error(e)
                            .log();
                    }
                }
                this.promise.completeExceptionally(error);
            } else {
                EcsLogger.debug("com.artipie.vertx")
                    .message("Late failure after response completion")
                    .eventCategory("http")
                    .eventAction("response_fail")
                    .error(error)
                    .log();
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
     * Filter out HTTP/2 forbidden headers per RFC 7540 Section 8.1.2.
     * Connection-specific headers MUST NOT be included in HTTP/2 messages.
     *
     * @param headers Original headers
     * @return Filtered headers safe for HTTP/2
     */
    private static Headers filterHttp2ForbiddenHeaders(final Headers headers) {
        final List<Header> filtered = new ArrayList<>();
        for (final Header header : headers) {
            final String name = header.getKey().toLowerCase(Locale.US);
            if (!HTTP2_FORBIDDEN_HEADERS.contains(name)) {
                filtered.add(header);
            } else {
                EcsLogger.debug("com.artipie.vertx")
                    .message("Filtering HTTP/2 forbidden header: " + header.getKey())
                    .eventCategory("http")
                    .eventAction("header_filter")
                    .field("http.version", "2.0")
                    .log();
            }
        }
        return new Headers(filtered);
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
            // For 100-continue, just write the continue response directly
            // No need for the full guarded accept flow
            response.writeContinue();
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
}
