/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client.vertx;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.log.LogSanitizer;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.RsStatus;
import io.reactivex.Flowable;
import io.reactivex.processors.UnicastProcessor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Vert.x-based HTTP client slice using raw HttpClient for true streaming.
 * <p>
 * Uses HttpClient instead of WebClient to enable streaming responses -
 * the response is returned immediately when headers arrive, with body
 * streaming concurrently. This is critical for large downloads (blobs)
 * where buffering the entire response would cause timeouts and memory issues.
 */
public final class VertxClientSlice implements Slice {

    /**
     * Raw Vert.x HTTP client for streaming.
     */
    private final HttpClient client;

    /**
     * Target host.
     */
    private final String host;

    /**
     * Target port.
     */
    private final int port;

    /**
     * Use SSL/TLS.
     */
    private final boolean ssl;

    /**
     * Retry policy.
     */
    private final RetryPolicy retryPolicy;

    /**
     * Circuit breaker for this destination.
     */
    private final CircuitBreaker circuitBreaker;

    /**
     * Connect timeout in milliseconds.
     */
    private final long connectTimeout;

    /**
     * Idle timeout in milliseconds.
     */
    private final long idleTimeout;

    /**
     * Constructor.
     *
     * @param client Vert.x HTTP client
     * @param host Target host
     * @param port Target port
     * @param ssl Use SSL/TLS
     * @param retryPolicy Retry policy
     * @param circuitBreaker Circuit breaker
     * @param connectTimeout Connect timeout ms
     * @param idleTimeout Idle timeout ms
     */
    public VertxClientSlice(
        final HttpClient client,
        final String host,
        final int port,
        final boolean ssl,
        final RetryPolicy retryPolicy,
        final CircuitBreaker circuitBreaker,
        final long connectTimeout,
        final long idleTimeout
    ) {
        this.client = client;
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
        this.connectTimeout = connectTimeout;
        this.idleTimeout = idleTimeout;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final com.artipie.asto.Content body
    ) {
        // Check circuit breaker first
        if (!this.circuitBreaker.allowRequest()) {
            EcsLogger.warn("com.artipie.http.client")
                .message("Circuit breaker OPEN, rejecting request")
                .eventCategory("http")
                .eventAction("circuit_breaker")
                .eventOutcome("rejected")
                .field("url.domain", this.host)
                .field("url.port", this.port)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.serviceUnavailable("Circuit breaker open")
                    .textBody("Circuit breaker open for " + this.host + ":" + this.port)
                    .build()
            );
        }

        final CompletableFuture<Response> result = new CompletableFuture<>();
        this.executeWithRetry(line, headers, body, 0, result);
        return result;
    }

    /**
     * Execute request with retry logic using raw HttpClient for streaming.
     *
     * @param line Request line
     * @param headers Headers
     * @param body Request body
     * @param attempt Current attempt (0-based)
     * @param result CompletableFuture to complete
     */
    private void executeWithRetry(
        final RequestLine line,
        final Headers headers,
        final com.artipie.asto.Content body,
        final int attempt,
        final CompletableFuture<Response> result
    ) {
        final HttpMethod method = HttpMethod.valueOf(line.method().value());
        final String path = line.uri().toString();

        final RequestOptions options = new RequestOptions()
            .setHost(this.host)
            .setPort(this.port)
            .setSsl(this.ssl)
            .setURI(path)
            .setTimeout(this.connectTimeout);

        final Headers sanitizedHeaders = LogSanitizer.sanitizeHeaders(headers);
        EcsLogger.debug("com.artipie.http.client")
            .message(String.format("Sending HTTP request (attempt=%d)", attempt))
            .eventCategory("http")
            .eventAction("http_request_send")
            .field("http.request.method", method.name())
            .field("url.domain", this.host)
            .field("url.port", this.port)
            .field("url.path", LogSanitizer.sanitizeUrl(path))
            .field("http.request.headers", sanitizedHeaders.toString())
            .log();

        // Collect body bytes for retry capability
        this.collectBody(body).thenAccept(bodyBuffer -> {
            this.executeStreamingRequest(method, options, headers, bodyBuffer, attempt, result, line);
        }).exceptionally(err -> {
            this.circuitBreaker.recordFailure();
            result.completeExceptionally(err);
            return null;
        });
    }

    /**
     * Execute request with true streaming response handling.
     * Uses raw HttpClient to return response immediately when headers arrive,
     * streaming body concurrently while downstream consumer processes it.
     */
    private void executeStreamingRequest(
        final HttpMethod method,
        final RequestOptions options,
        final Headers headers,
        final Buffer bodyBuffer,
        final int attempt,
        final CompletableFuture<Response> result,
        final RequestLine line
    ) {
        // UnicastProcessor for single-subscriber streaming (simpler than ReplayProcessor)
        final UnicastProcessor<ByteBuffer> bodyProcessor = UnicastProcessor.create();

        // Use raw HttpClient - returns response when headers arrive
        this.client.request(options.setMethod(method))
            .onSuccess(request -> {
                // Add headers
                final io.vertx.core.MultiMap vertxHeaders = toVertxHeaders(headers);
                request.headers().addAll(vertxHeaders);

                // Set idle timeout
                request.idleTimeout(this.idleTimeout);

                // Send request body and handle streaming response
                request.send(bodyBuffer)
                    .onSuccess(response -> {
                        this.handleStreamingResponse(response, bodyProcessor, attempt, result, line, headers, bodyBuffer);
                    })
                    .onFailure(err -> {
                        bodyProcessor.onError(err);
                        this.handleFailure(err, line, headers, bodyBuffer, attempt, result);
                    });
            })
            .onFailure(err -> {
                bodyProcessor.onError(err);
                this.handleFailure(err, line, headers, bodyBuffer, attempt, result);
            });
    }

    /**
     * Handle streaming response - returns immediately when headers arrive.
     * Body streams in background via UnicastProcessor.
     */
    private void handleStreamingResponse(
        final HttpClientResponse response,
        final UnicastProcessor<ByteBuffer> bodyProcessor,
        final int attempt,
        final CompletableFuture<Response> result,
        final RequestLine line,
        final Headers headers,
        final Buffer bodyBuffer
    ) {
        final int statusCode = response.statusCode();
        final RsStatus status = RsStatus.byCode(statusCode);

        // Record circuit breaker metrics
        if (statusCode >= 500) {
            this.circuitBreaker.recordFailure();
        } else {
            this.circuitBreaker.recordSuccess();
        }

        final Headers respHeaders = fromVertxHeaders(response.headers());

        EcsLogger.debug("com.artipie.http.client")
            .message(String.format("Received streaming HTTP response (attempt=%d, status=%d)", attempt, statusCode))
            .eventCategory("http")
            .eventAction("http_response_receive")
            .field("http.response.status_code", statusCode)
            .log();

        // Check if this is a retryable status
        if (this.retryPolicy.shouldRetry(attempt, null, statusCode)) {
            // Consume body before retry
            response.body().onSuccess(body -> {
                final long delay = this.retryPolicy.nextDelay(attempt);
                EcsLogger.info("com.artipie.http.client")
                    .message(String.format("Retrying request due to status %d (attempt=%d, delay_ms=%d)",
                        statusCode, attempt + 1, delay))
                    .eventCategory("http")
                    .eventAction("http_retry")
                    .field("url.domain", this.host)
                    .log();

                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        final com.artipie.asto.Content retryBody =
                            new com.artipie.asto.Content.From(bodyBuffer.getBytes());
                        this.executeWithRetry(line, headers, retryBody, attempt + 1, result);
                    });
            }).onFailure(err -> {
                bodyProcessor.onError(err);
                result.completeExceptionally(err);
            });
            return;
        }

        // For non-2xx responses, buffer the body instead of streaming
        // Error responses typically have small bodies (error messages) and
        // streaming them can cause race conditions with timeout handling
        if (statusCode >= 400) {
            response.body()
                .onSuccess(body -> {
                    bodyProcessor.onNext(ByteBuffer.wrap(body.getBytes()));
                    bodyProcessor.onComplete();
                    result.complete(
                        ResponseBuilder.from(status)
                            .headers(respHeaders)
                            .body(bodyProcessor.onBackpressureBuffer())
                            .build()
                    );
                })
                .onFailure(err -> {
                    bodyProcessor.onError(err);
                    result.completeExceptionally(err);
                });
            return;
        }

        // For 2xx responses, use streaming for large bodies
        // CRITICAL: Pause the response to prevent data loss before handlers are set up
        response.pause();

        // Set up body streaming handlers
        response.handler(chunk -> {
            bodyProcessor.onNext(ByteBuffer.wrap(chunk.getBytes()));
        });

        response.endHandler(v -> {
            bodyProcessor.onComplete();
        });

        response.exceptionHandler(err -> {
            bodyProcessor.onError(err);
        });

        // Return response immediately - body streams in background
        result.complete(
            ResponseBuilder.from(status)
                .headers(respHeaders)
                .body(bodyProcessor.onBackpressureBuffer())
                .build()
        );

        // CRITICAL: Resume the response AFTER handlers are set up AND result is completed
        // This ensures the downstream consumer is ready to receive data
        response.resume();
    }

    /**
     * Handle request failure with retry logic.
     */
    private void handleFailure(
        final Throwable err,
        final RequestLine line,
        final Headers headers,
        final Buffer bodyBuffer,
        final int attempt,
        final CompletableFuture<Response> result
    ) {
        EcsLogger.warn("com.artipie.http.client")
            .message(String.format("HTTP request failed (attempt=%d)", attempt))
            .eventCategory("http")
            .eventAction("http_request_send")
            .eventOutcome("failure")
            .field("url.domain", this.host)
            .field("url.port", this.port)
            .error(err)
            .log();

        // Check if we should retry
        if (this.retryPolicy.shouldRetry(attempt, err, -1)) {
            final long delay = this.retryPolicy.nextDelay(attempt);
            EcsLogger.info("com.artipie.http.client")
                .message(String.format("Retrying request after delay (attempt=%d, delay_ms=%d)", attempt + 1, delay))
                .eventCategory("http")
                .eventAction("http_retry")
                .field("url.domain", this.host)
                .log();

            // Schedule retry with delay
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    // Re-create body Content from buffer for retry
                    final com.artipie.asto.Content retryBody =
                        new com.artipie.asto.Content.From(bodyBuffer.getBytes());
                    this.executeWithRetry(line, headers, retryBody, attempt + 1, result);
                });
        } else {
            this.circuitBreaker.recordFailure();
            result.completeExceptionally(err);
        }
    }

    /**
     * Collect body into a Buffer for potential retry.
     */
    private CompletableFuture<Buffer> collectBody(final com.artipie.asto.Content body) {
        return body.asBytesFuture()
            .thenApply(Buffer::buffer)
            .exceptionally(err -> Buffer.buffer());
    }

    /**
     * Convert Artipie Headers to Vert.x MultiMap.
     */
    private static io.vertx.core.MultiMap toVertxHeaders(final Headers headers) {
        final io.vertx.core.MultiMap map = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
        for (final Header header : headers) {
            map.add(header.getKey(), header.getValue());
        }
        return map;
    }

    /**
     * Convert Vert.x MultiMap to Artipie Headers.
     */
    private static Headers fromVertxHeaders(final io.vertx.core.MultiMap map) {
        final List<Header> list = new ArrayList<>();
        map.forEach(entry -> list.add(new Header(entry.getKey(), entry.getValue())));
        return new Headers(list);
    }
}
