/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client.jetty;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.LogSanitizer;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import io.reactivex.Flowable;
import io.reactivex.processors.UnicastProcessor;
import org.apache.hc.core5.net.URIBuilder;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Locale;
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
        RequestLine line, Headers headers, com.auto1.pantera.asto.Content body
    ) {
        final Request request = this.buildRequest(headers, line);
        final CompletableFuture<Response> res = new CompletableFuture<>();
        // Streaming: emit chunks as they arrive instead of buffering everything.
        // UnicastProcessor supports backpressure and single-subscriber semantics.
        final UnicastProcessor<ByteBuffer> processor = UnicastProcessor.create();
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
                    throwable -> EcsLogger.error("com.auto1.pantera.http.client")
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
                    // Complete the response future NOW with headers + streaming body.
                    // Client receives the Response as soon as headers arrive,
                    // body bytes stream through the processor as Demander reads them.
                    final RsStatus status = RsStatus.byCode(response.getStatus());
                    final Headers respHeaders = toHeaders(response.getHeaders());
                    final Headers sanitizedRespHeaders = LogSanitizer.sanitizeHeaders(respHeaders);
                    EcsLogger.debug("com.auto1.pantera.http.client")
                        .message("Received HTTP response headers (streaming body)")
                        .eventCategory("http")
                        .eventAction("http_response_receive")
                        .field("http.response.status_code", response.getStatus())
                        .field("http.response.headers", sanitizedRespHeaders.toString())
                        .log();
                    res.complete(
                        ResponseBuilder.from(status)
                            .headers(respHeaders)
                            .body(processor)
                            .build()
                    );
                    // Start streaming body chunks through the processor.
                    final Runnable demander = new StreamingDemander(source, response, processor);
                    demander.run();
                }
        );
        final Headers sanitizedHeaders = LogSanitizer.sanitizeHeaders(toHeaders(request.getHeaders()));
        EcsLogger.debug("com.auto1.pantera.http.client")
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
                        // For responses where onResponseContentSource never fired
                        // (empty body, HEAD, etc.), complete here with empty body.
                        // If already completed by onResponseContentSource, this is a no-op.
                        if (res.complete(
                            ResponseBuilder.from(
                                RsStatus.byCode(result.getResponse().getStatus())
                            )
                            .headers(toHeaders(result.getResponse().getHeaders()))
                            .body(Flowable.empty())
                            .build()
                        )) {
                            EcsLogger.debug("com.auto1.pantera.http.client")
                                .message("Received HTTP response (no body)")
                                .eventCategory("http")
                                .eventAction("http_response_receive")
                                .field("http.response.status_code",
                                    result.getResponse().getStatus())
                                .log();
                        }
                        // Complete the processor in case it was created but never used
                        // (edge case: content source callback fired but no chunks)
                        processor.onComplete();
                    } else {
                        EcsLogger.error("com.auto1.pantera.http.client")
                            .message("HTTP request failed")
                            .eventCategory("http")
                            .eventAction("http_request_send")
                            .eventOutcome("failure")
                            .error(result.getFailure())
                            .log();
                        // Complete processor with error so subscribers don't hang
                        processor.onError(result.getFailure());
                        res.completeExceptionally(result.getFailure());
                    }
                }
        );
        return res;
    }

    /**
     * Convert Jetty HttpFields to Pantera Headers.
     *
     * <p>When Jetty auto-decodes a gzip/deflate/br response body via its registered
     * {@code ContentDecoder.Factory} (default behaviour), the decoded (plain) bytes are
     * streamed through the processor while the original {@code Content-Encoding} header
     * is still present in {@code response.getHeaders()}. This creates a header/body
     * mismatch: the body is plain bytes but the header claims it is compressed.
     * Clients that trust the header will attempt to inflate the plain bytes and fail
     * with {@code Z_DATA_ERROR: zlib: incorrect header check}.
     *
     * <p>Fix: detect the presence of a decoded transfer encoding and strip both
     * {@code Content-Encoding} and {@code Content-Length} (which refers to the
     * compressed size, no longer valid for the decoded body) from the returned headers.
     */
    private static Headers toHeaders(final HttpFields fields) {
        final boolean decoded = fields.stream()
            .anyMatch(f -> f.is("Content-Encoding")
                && isDecodedEncoding(f.getValue()));
        if (!decoded) {
            return new Headers(
                fields.stream()
                    .map(f -> new Header(f.getName(), f.getValue()))
                    .toList()
            );
        }
        return new Headers(
            fields.stream()
                .filter(f -> !f.is("Content-Encoding") && !f.is("Content-Length"))
                .map(f -> new Header(f.getName(), f.getValue()))
                .toList()
        );
    }

    /**
     * Returns true if the encoding value is one that Jetty auto-decodes by default.
     * @param value Content-Encoding header value
     * @return True for gzip, deflate, br, x-gzip
     */
    private static boolean isDecodedEncoding(final String value) {
        final String lower = value.toLowerCase(Locale.ROOT).trim();
        return lower.contains("gzip") || lower.contains("deflate") || lower.contains("br");
    }

    /**
     * Builds jetty basic request from artipie request line and headers.
     * @param headers Headers
     * @param req Pantera request line
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
     * Streaming demander that emits response content chunks through a UnicastProcessor
     * as they arrive, instead of buffering everything in memory. This allows callers to
     * start processing bytes immediately without waiting for the full response.
     *
     * <p>See <a href="https://eclipse.dev/jetty/documentation/jetty-12/programming-guide/index.html#pg-client-http-content-response">jetty docs</a>
     * for more details on the Content.Source demand model.</p>
     *
     * @since 0.3
     */
    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.CognitiveComplexity"})
    private static final class StreamingDemander implements Runnable {

        /**
         * Content source.
         */
        private final Content.Source source;

        /**
         * Response.
         */
        private final org.eclipse.jetty.client.Response response;

        /**
         * Processor that streams chunks to subscribers.
         */
        private final UnicastProcessor<ByteBuffer> processor;

        /**
         * Ctor.
         * @param source Content source
         * @param response Response
         * @param processor Processor to emit chunks through
         */
        private StreamingDemander(
            final Content.Source source,
            final org.eclipse.jetty.client.Response response,
            final UnicastProcessor<ByteBuffer> processor
        ) {
            this.source = source;
            this.response = response;
            this.processor = processor;
        }

        @Override
        public void run() {
            long lastDataTime = System.nanoTime();
            final long idleTimeoutNanos = TimeUnit.SECONDS.toNanos(120);
            int iterations = 0;
            final int maxIterations = 1_000_000;

            while (iterations++ < maxIterations) {
                if (System.nanoTime() - lastDataTime > idleTimeoutNanos) {
                    EcsLogger.error("com.auto1.pantera.http.client")
                        .message(String.format("Response reading idle timeout (120s without data) after %d iterations", iterations))
                        .eventCategory("http")
                        .eventAction("http_response_read")
                        .eventOutcome("timeout")
                        .field("url.full", this.response.getRequest().getURI().toString())
                        .log();
                    final TimeoutException timeout =
                        new TimeoutException("Response reading idle timeout (120s without data)");
                    this.processor.onError(timeout);
                    this.response.abort(timeout);
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
                        this.processor.onError(failure);
                        this.response.abort(failure);
                        EcsLogger.error("com.auto1.pantera.http.client")
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
                            this.processor.onError(failure);
                            this.response.abort(failure);
                            EcsLogger.error("com.auto1.pantera.http.client")
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
                // Stream chunk to subscriber immediately instead of buffering
                this.processor.onNext(stored);
                lastDataTime = System.nanoTime();
                if (chunk.isLast()) {
                    this.processor.onComplete();
                    return;
                }
            }

            // Max iterations exceeded
            EcsLogger.error("com.auto1.pantera.http.client")
                .message("Max iterations exceeded while reading response (max: " + maxIterations + ")")
                .eventCategory("http")
                .eventAction("http_response_read")
                .eventOutcome("failure")
                .field("url.full", this.response.getRequest().getURI().toString())
                .log();
            final IllegalStateException error =
                new IllegalStateException("Too many chunks - possible infinite loop");
            this.processor.onError(error);
            this.response.abort(error);
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
