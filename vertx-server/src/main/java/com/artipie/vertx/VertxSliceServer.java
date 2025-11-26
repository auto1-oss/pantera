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
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
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
            .setHttp2ClearTextEnabled(true),  // Enable HTTP/2 over cleartext (h2c) for AWS NLB
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
                
                // CRITICAL FIX: For requests with body (PUT/POST), consume body FIRST
                // This ensures Vert.x sees body consumption even if slice returns error
                if (req.headers().contains("Content-Length") && !"0".equals(req.getHeader("Content-Length"))) {
                    // Buffer the entire body first, then serve
                    req.bodyHandler(body -> {
                        EcsLogger.debug("com.artipie.vertx")
                            .message("Request body buffered")
                            .eventCategory("http")
                            .eventAction("request_buffer")
                            .field("http.request.body.bytes", body.length())
                            .log();
                        this.serveWithBody(req, body).whenComplete((result, throwable) -> {
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
                                    if (!req.response().ended()) {
                                        VertxSliceServer.sendError(req.response(), throwable);
                                    }
                                } else {
                                    transaction.setResult("success");
                                }
                            } finally {
                                transaction.end();
                            }
                        });
                    });
                } else {
                    // No body, serve immediately
                    this.serve(req, null).whenComplete((result, throwable) -> {
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
                                if (!req.response().ended()) {
                                    VertxSliceServer.sendError(req.response(), throwable);
                                }
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
                if (!req.response().ended()) {
                    VertxSliceServer.sendError(req.response(), ex);
                }
            }
        };
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
     * Server HTTP request with buffered body.
     *
     * @param req HTTP request.
     * @param body Buffered request body (null if no body).
     * @return Completion of request serving.
     */
    private CompletionStage<Void> serveWithBody(final HttpServerRequest req, final Buffer body) {
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

                    return VertxSliceServer.accept(req.response(), resp.status(), resp.headers(), resp.body(), isHead, req.version());
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
     * @return Completion of request serving.
     */
    private CompletionStage<Void> serve(final HttpServerRequest req, final Buffer unused) {
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

                    return VertxSliceServer.accept(req.response(), resp.status(), resp.headers(), resp.body(), isHead, req.version());
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
        HttpServerResponse response, RsStatus status, Headers headers, Content body
    ) {
        return accept(response, status, headers, body, false, HttpVersion.HTTP_1_1);
    }

    private static CompletionStage<Void> accept(
        HttpServerResponse response, RsStatus status, Headers headers, Content body, boolean isHead, io.vertx.core.http.HttpVersion version
    ) {
        final CompletableFuture<Void> promise = new CompletableFuture<>();
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

        // TRACE level removed - not needed in production
        // if (isHead) {
        //     EcsLogger.debug("com.artipie.vertx")
        //         .message("HEAD request Content-Length check (present: " + response.headers().contains("Content-Length") + ", value: " + response.headers().get("Content-Length") + ")")
        //         .eventCategory("http")
        //         .eventAction("response_prepare")
        //         .log();
        // }

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
                // CRITICAL: Must execute on Vert.x event loop thread for thread safety
                // DO NOT use observeOn() - it breaks Vert.x threading model and causes corruption
                // This is especially critical for large file downloads (>200MB)
                vpb.subscribe(
                    buffer -> {
                        response.write(buffer);
                    },
                    error -> {
                        EcsLogger.error("com.artipie.vertx")
                            .message("Error writing response body")
                            .eventCategory("http")
                            .eventAction("response_write")
                            .eventOutcome("failure")
                            .error(error)
                            .log();
                        terminator.fail(error);
                    },
                    () -> {
                        EcsLogger.debug("com.artipie.vertx")
                            .message("Response body fully written")
                            .eventCategory("http")
                            .eventAction("response_write")
                            .eventOutcome("success")
                            .field("http.response.body.bytes", true)
                            .log();
                        terminator.end();
                    }
                );
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
        if (ctx.clientIp() != null && !ctx.clientIp().isEmpty()) {
            logger.field("client.ip", ctx.clientIp());
        }
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
            final io.vertx.reactivex.core.net.SocketAddress remote = req.remoteAddress();
            final String host = remote == null ? "unknown" : remote.host();
            final int port = remote == null ? -1 : remote.port();
            final String clientIp = EcsLogEvent.extractClientIp(headers, host);
            final Optional<String> username = EcsLogEvent.extractUsername(headers);
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

        ResponseTerminator(final HttpServerResponse response, final CompletableFuture<Void> promise) {
            this.response = response;
            this.promise = promise;
        }

        void end() {
            if (this.finished.compareAndSet(false, true)) {
                this.response.end();
                this.promise.complete(null);
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
                try {
                    // Only set error status if headers haven't been sent yet
                    if (!this.response.headWritten()) {
                        this.response.setStatusCode(500);
                        final String errorMsg = String.format(
                            "Internal Server Error: %s: %s",
                            error.getClass().getSimpleName(),
                            error.getMessage()
                        );
                        this.response.end(errorMsg);
                    } else {
                        // Headers already sent, just end the response
                        this.response.end();
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
