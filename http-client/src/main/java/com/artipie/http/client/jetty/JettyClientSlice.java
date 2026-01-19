/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client.jetty;

import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.log.LogSanitizer;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.RsStatus;
import io.reactivex.Flowable;
import org.apache.hc.core5.net.URIBuilder;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ClientSlices implementation using Jetty HTTP client as back-end.
 * <a href="https://eclipse.dev/jetty/documentation/jetty-12/programming-guide/index.html#pg-client-http-non-blocking">Docs</a>
 */
final class JettyClientSlice implements Slice {

    /**
     * HTTP client.
     */
    private final HttpClient client;

    /**
     * Secure connection flag.
     */
    private final boolean secure;

    /**
     * Host name.
     */
    private final String host;

    /**
     * Port.
     */
    private final int port;

    /**
     * Max time in milliseconds to wait for connection acquisition.
     */
    private final long acquireTimeoutMillis;

    /**
     * @param client HTTP client.
     * @param secure Secure connection flag.
     * @param host Host name.
     * @param port Port.
     */
    JettyClientSlice(
        HttpClient client,
        boolean secure,
        String host,
        int port,
        long acquireTimeoutMillis
    ) {
        this.client = client;
        this.secure = secure;
        this.host = host;
        this.port = port;
        this.acquireTimeoutMillis = acquireTimeoutMillis;
    }

    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, com.artipie.asto.Content body
    ) {
        final Request request = this.buildRequest(headers, line);
        final CompletableFuture<Response> res = new CompletableFuture<>();
        final List<ByteBuffer> buffers = new ArrayList<>();  // Better cache locality than LinkedList
        if (line.method() != RqMethod.HEAD) {
            final AsyncRequestContent async = new AsyncRequestContent();
            Flowable.fromPublisher(body)
                .doOnError(async::fail)
                .doOnCancel(
                    () -> async.fail(new CancellationException("Request body cancelled"))
                )
                .doFinally(async::close)
                .subscribe(
                    buf -> async.write(buf, Callback.NOOP),
                    throwable -> EcsLogger.error("com.artipie.http.client")
                        .message("Failed to stream HTTP request body")
                        .eventCategory("http")
                        .eventAction("http_request_body")
                        .eventOutcome("failure")
                        .error(throwable)
                        .log()
                );
            request.body(async);
        }
        request.onResponseContentSource(
                (response, source) -> {
                    // The function (as a Runnable) that reads the response content.
                    final Runnable demander = new Demander(source, response, buffers);
                    // Initiate the reads.
                    demander.run();
                }
        );
        final Headers sanitizedHeaders = LogSanitizer.sanitizeHeaders(toHeaders(request.getHeaders()));
        EcsLogger.debug("com.artipie.http.client")
            .message("Sending HTTP request")
            .eventCategory("http")
            .eventAction("http_request_send")
            .field("http.request.method", request.getMethod())
            .field("url.domain", request.getHost())
            .field("url.port", request.getPort())
            .field("url.path", LogSanitizer.sanitizeUrl(request.getPath()))
            .field("http.version", request.getVersion().toString())
            .field("http.request.headers", sanitizedHeaders.toString())
            .log();
        request.send(
                result -> {
                    if (result.getFailure() == null) {
                        RsStatus status = RsStatus.byCode(result.getResponse().getStatus());
                        Flowable<ByteBuffer> content = Flowable.fromIterable(buffers)
                            .map(ByteBuffer::asReadOnlyBuffer);
                        final Headers sanitizedRespHeaders = LogSanitizer.sanitizeHeaders(
                            toHeaders(result.getResponse().getHeaders())
                        );
                        EcsLogger.debug("com.artipie.http.client")
                            .message("Received HTTP response")
                            .eventCategory("http")
                            .eventAction("http_response_receive")
                            .field("http.response.status_code", result.getResponse().getStatus())
                            .field("http.response.body.content", result.getResponse().getReason())
                            .field("http.response.headers", sanitizedRespHeaders.toString())
                            .log();
                        res.complete(
                            ResponseBuilder.from(status)
                                .headers(toHeaders(result.getResponse().getHeaders()))
                                .body(content)
                                .build()
                        );
                    } else {
                        EcsLogger.error("com.artipie.http.client")
                            .message("HTTP request failed")
                            .eventCategory("http")
                            .eventAction("http_request_send")
                            .eventOutcome("failure")
                            .error(result.getFailure())
                            .log();
                        res.completeExceptionally(result.getFailure());
                    }
                }
        );
        return res;
    }

    private Headers toHeaders(HttpFields fields) {
        return new Headers(
            fields.stream()
                .map(field -> new Header(field.getName(), field.getValue()))
                .toList()
        );
    }

    /**
     * Builds jetty basic request from artipie request line and headers.
     * @param headers Headers
     * @param req Artipie request line
     * @return Jetty request
     */
    private Request buildRequest(Headers headers, RequestLine req) {
        final String scheme = this.secure ? "https" : "http";
        final URI uri = req.uri();
        final Request request = this.client.newRequest(
            new URIBuilder()
                .setScheme(scheme)
                .setHost(this.host)
                .setPort(this.port)
                .setPath(uri.getPath())
                .setCustomQuery(uri.getQuery())
                .toString()
        ).method(req.method().value());
        if (this.acquireTimeoutMillis > 0) {
            request.timeout(this.acquireTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        for (Header header : headers) {
            request.headers(mutable -> mutable.add(header.getKey(), header.getValue()));
        }
        return request;
    }

    /**
     * Demander.This class reads response content from request asynchronously piece by piece.
     * See <a href="https://eclipse.dev/jetty/documentation/jetty-12/programming-guide/index.html#pg-client-http-content-response">jetty docs</a>
     * for more details.
     * @since 0.3
     */
    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.CognitiveComplexity"})
    private static final class Demander implements Runnable {

        /**
         * Content source.
         */
        private final Content.Source source;

        /**
         * Response.
         */
        private final org.eclipse.jetty.client.Response response;

        /**
         * Content chunks.
         */
        private final List<ByteBuffer> chunks;

        /**
         * Ctor.
         * @param source Content source
         * @param response Response
         * @param chunks Content chunks for further process
         */
        private Demander(
            final Content.Source source,
            final org.eclipse.jetty.client.Response response,
            final List<ByteBuffer> chunks
        ) {
            this.source = source;
            this.response = response;
            this.chunks = chunks;
        }

        @Override
        public void run() {
            final long startTime = System.nanoTime();
            final long timeoutNanos = TimeUnit.SECONDS.toNanos(30);  // 30 second timeout
            int iterations = 0;
            final int maxIterations = 10000;  // Safety limit
            
            while (iterations++ < maxIterations) {
                // Check timeout
                if (System.nanoTime() - startTime > timeoutNanos) {
                    EcsLogger.error("com.artipie.http.client")
                        .message("Response reading timeout (30 seconds)")
                        .eventCategory("http")
                        .eventAction("http_response_read")
                        .eventOutcome("timeout")
                        .field("url.full", this.response.getRequest().getURI().toString())
                        .log();
                    this.response.abort(new TimeoutException("Response reading timeout"));
                    return;
                }
                
                final Content.Chunk chunk = this.source.read();
                if (chunk == null) {
                    this.source.demand(this);
                    return;
                }
                if (Content.Chunk.isFailure(chunk)) {
                    final Throwable failure = chunk.getFailure();
                    if (chunk.isLast()) {
                        this.response.abort(failure);
                        EcsLogger.error("com.artipie.http.client")
                            .message("HTTP response read failed")
                            .eventCategory("http")
                            .eventAction("http_response_read")
                            .eventOutcome("failure")
                            .field("url.full", this.response.getRequest().getURI().toString())
                            .error(failure)
                            .log();
                        return;
                    } else {
                        // A transient failure such as a read timeout.
                        if (RsStatus.byCode(this.response.getStatus()).success()) {
                            // Release chunk before retry to prevent leak
                            if (chunk.canRetain()) {
                                chunk.release();
                            }
                            // Try to read again.
                            continue;
                        } else {
                            // The transient failure is treated as a terminal failure.
                            this.response.abort(failure);
                            EcsLogger.error("com.artipie.http.client")
                                .message("Transient failure treated as terminal")
                                .eventCategory("http")
                                .eventAction("http_response_read")
                                .eventOutcome("failure")
                                .field("url.full", this.response.getRequest().getURI().toString())
                                .error(failure)
                                .log();
                            return;
                        }
                    }
                }
                final ByteBuffer stored;
                try {
                    stored = JettyClientSlice.copyChunk(chunk);
                } finally {
                    chunk.release();
                }
                this.chunks.add(stored);
                if (chunk.isLast()) {
                    return;
                }
            }

            // Max iterations exceeded
            EcsLogger.error("com.artipie.http.client")
                .message("Max iterations exceeded while reading response (max: " + maxIterations + ")")
                .eventCategory("http")
                .eventAction("http_response_read")
                .eventOutcome("failure")
                .field("url.full", this.response.getRequest().getURI().toString())
                .log();
            this.response.abort(new IllegalStateException("Too many chunks - possible infinite loop"));
        }
    }

    private static ByteBuffer copyChunk(final Content.Chunk chunk) {
        final ByteBuffer original = chunk.getByteBuffer();
        if (original.hasArray() && original.arrayOffset() == 0 && original.position() == 0
            && original.remaining() == original.capacity()) {
            // Fast-path: reuse backing array when buffer fully represents it
            return ByteBuffer.wrap(original.array()).asReadOnlyBuffer();
        }
        final ByteBuffer slice = original.slice();
        final ByteBuffer copy = ByteBuffer.allocate(slice.remaining());
        copy.put(slice);
        copy.flip();
        return copy;
    }
}
